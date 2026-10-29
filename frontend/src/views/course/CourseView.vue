<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { courseApi, scheduleApi, selectionApi, semesterApi } from '@/api/endpoints'
import type {
  AvailableCourse,
  CourseSummary,
  MyScheduleItem,
  SelectionView,
  SemesterView,
  TermPeriod,
} from '@/api/types'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { BLOCK_REASON_LABEL, COURSE_STATUS_LABEL, SEMESTER_STATUS_LABEL, courseTone } from '@/lib/labels'
import { currentWeek, formatSchedule, matchesWeek, semesterWeeks, weekRange } from '@/lib/schedule'
import Timetable from '@/components/course/Timetable.vue'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiEmpty from '@/components/ui/UiEmpty.vue'
import UiPageHeader from '@/components/ui/UiPageHeader.vue'
import UiSelect from '@/components/ui/UiSelect.vue'
import UiTag from '@/components/ui/UiTag.vue'
import UiTextarea from '@/components/ui/UiTextarea.vue'

/**
 * 课程页：一张周课表 + 一份选课目录，共用一个学期选择器。
 * 学期决定「看哪一学期的课」，选课窗口决定「能不能动手」——两者互不替代。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const toast = useToastStore()

const semesters = ref<SemesterView[]>([])
const semesterId = ref<number | null>(null)
const semester = computed(() => semesters.value.find((s) => s.id === semesterId.value) ?? null)

const tab = computed<'timetable' | 'catalog' | 'teach'>(() => {
  const v = String(route.query.view ?? '')
  if (v === 'catalog') return 'catalog'
  if (v === 'teach') return 'teach'
  return 'timetable'
})

const schedule = ref<MyScheduleItem[]>([])
const selections = ref<SelectionView[]>([])
const available = ref<AvailableCourse[]>([])
/** 本学期节次作息：让课表左侧显示「第 1 节 08:00–08:45」而不只是一个数字 */
const periods = ref<TermPeriod[]>([])
const loading = ref(true)
const loadError = ref<string | null>(null)

const week = ref(1)
const canSelect = computed(() => auth.can('selection:select'))

/**
 * 默认学期可能「没有我的课」——例如库里的课都排在已归档的学期，而「进行中」的是另一学期。
 * 这种情况下整页会是空课表，看起来就像功能坏了。这里记一次兜底切换，并在页头提示。
 */
const autoSwitchNote = ref<string | null>(null)
let autoSwitched = false

/** 按开始日期倒序找一个「我有课或有选课」的学期；找不到返回 null */
async function findSemesterWithData(excludeId: number): Promise<number | null> {
  const ordered = [...semesters.value].sort((a, b) => (b.startDate ?? '').localeCompare(a.startDate ?? ''))
  for (const s of ordered) {
    if (s.id === excludeId) continue
    try {
      const [sch, sel] = await Promise.all([
        scheduleApi.mine(s.id).catch(() => [] as MyScheduleItem[]),
        selectionApi.mine(s.id).catch(() => [] as SelectionView[]),
      ])
      if (sch.length || sel.length) return s.id
    } catch {
      // 单个学期取失败不影响继续找
    }
  }
  return null
}

const semesterOptions = computed(() =>
  semesters.value.map((s) => ({
    value: String(s.id),
    label: `${s.name}${s.status === 'ACTIVE' ? '（进行中）' : s.status === 'CLOSED' ? '（已归档）' : ''}`,
  })),
)

/** UiSelect 只吃 string，这里做一次往返转换 */
const semesterSelect = computed({
  get: () => (semesterId.value ? String(semesterId.value) : ''),
  set: (v: string) => {
    semesterId.value = v ? Number(v) : null
  },
})

const weekItems = computed(() => schedule.value.filter((it) => matchesWeek(it, week.value)))
const range = computed(() => weekRange(semester.value?.startDate, week.value))

/**
 * 学期总周数按 start/end 日期算，不再写死 30。
 * 学期跨到 30 周以上时，写死的上限会把「今天第几周」压到 30，
 * 而课程通常只排 1–16 周，结果就是课表整页空白。
 */
const totalWeeks = computed(() => semesterWeeks(semester.value?.startDate, semester.value?.endDate))

/** 本学期实际有课的周次：用来给翻周提供锚点，避免落在空周上 */
const weeksWithClass = computed(() => {
  const limit = totalWeeks.value
  const set = new Set<number>()
  for (const it of schedule.value) {
    const from = Math.max(1, it.startWeek)
    const to = Math.min(it.endWeek, limit)
    for (let w = from; w <= to; w += 1) {
      if (matchesWeek(it, w)) set.add(w)
    }
  }
  return [...set].sort((a, b) => a - b)
})

const minWeek = computed(() => 1)
const maxWeek = computed(() => Math.max(totalWeeks.value, weeksWithClass.value.at(-1) ?? 1))
const selectedCredits = computed(() =>
  selections.value.filter((s) => s.status === 'SELECTED').reduce((sum, s) => sum + Number(s.credit || 0), 0),
)
const selectedCount = computed(() => selections.value.filter((s) => s.status === 'SELECTED').length)

async function loadSemesters() {
  semesters.value = await semesterApi.list()
  if (semesters.value.length === 0) return

  const fromQuery = Number(route.query.semester)
  const picked =
    semesters.value.find((s) => s.id === fromQuery) ??
    semesters.value.find((s) => s.status === 'ACTIVE') ??
    semesters.value[0]
  if (!picked) return
  semesterId.value = picked.id
  week.value = currentWeek(picked.startDate, Date.now(), semesterWeeks(picked.startDate, picked.endDate))
}

/**
 * 让当前周落在「有课」的位置上。
 * 学期很长时今天可能早已越过课程周次（例如今天第 35 周、课只排到第 16 周），
 * 此时默认显示空课表会让人以为功能坏了，这里自动跳到最近的有课周。
 */
function ensureVisibleWeek() {
  if (!schedule.value.length) return
  if (schedule.value.some((it) => matchesWeek(it, week.value))) return
  const weeks = weeksWithClass.value
  if (!weeks.length) return
  week.value = weeks.reduce(
    (best, w) => (Math.abs(w - week.value) < Math.abs(best - week.value) ? w : best),
    weeks[0] as number,
  )
}

async function loadSemesterData() {
  const id = semesterId.value
  if (!id) {
    loading.value = false
    return
  }
  loading.value = true
  loadError.value = null
  try {
    const [sch, sel, avail, per] = await Promise.all([
      scheduleApi.mine(id).catch(() => [] as MyScheduleItem[]),
      selectionApi.mine(id).catch(() => [] as SelectionView[]),
      canSelect.value ? courseApi.available(id).catch(() => [] as AvailableCourse[]) : Promise.resolve([]),
      // 节次作息只影响左侧时间列，取不到就退化成只显示节次号，不影响课表本身
      scheduleApi.periods(id).catch(() => [] as TermPeriod[]),
    ])
    schedule.value = sch
    selections.value = sel
    available.value = avail
    periods.value = per
    // 当前学期两手空空，但有别的学期有课：自动切过去（只尝试一次）
    if (!autoSwitched && sch.length === 0 && sel.length === 0) {
      const alt = await findSemesterWithData(id)
      if (alt != null) {
        autoSwitched = true
        autoSwitchNote.value = semesters.value.find((s) => s.id === alt)?.name ?? null
        semesterId.value = alt
        return
      }
    }
    ensureVisibleWeek()
  } catch (e) {
    loadError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

watch(semesterId, async (id) => {
  if (!id) return
  const s = semesters.value.find((x) => x.id === id)
  week.value = currentWeek(s?.startDate, Date.now(), semesterWeeks(s?.startDate, s?.endDate))
  await router.replace({ query: { ...route.query, semester: String(id) } })
  await loadSemesterData()
  if (tab.value === 'teach' && canTeach.value) await loadTeaching()
})

onMounted(async () => {
  try {
    await loadSemesters()
    await loadSemesterData()
    // 直接落在「我教的课」时，watch 不会触发，需要主动取一次
    if (tab.value === 'teach' && canTeach.value) await loadTeaching()
  } catch (e) {
    loadError.value = errorMessage(e)
    loading.value = false
  }
})

function switchTab(next: 'timetable' | 'catalog' | 'teach') {
  const query = { ...route.query, view: next === 'timetable' ? undefined : next }
  void router.replace({ query })
}

/* ---------------- 选课 / 退课 ---------------- */
const busyCourse = ref<number | null>(null)

async function selectCourse(course: AvailableCourse) {
  if (!course.selectable) return
  busyCourse.value = course.courseId
  try {
    await selectionApi.create(course.courseId)
    toast.push(`已选上「${course.name}」`, 'success')
    await loadSemesterData()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
    await loadSemesterData()
  } finally {
    busyCourse.value = null
  }
}

const busySelection = ref<number | null>(null)

async function dropSelection(sel: SelectionView) {
  busySelection.value = sel.id
  try {
    await selectionApi.drop(sel.id)
    toast.push(`已退掉「${sel.courseName}」`, 'success')
    await loadSemesterData()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    busySelection.value = null
  }
}

/** 目录里已选的课，给一个退课入口 */
function selectionOf(courseId: number): SelectionView | undefined {
  return selections.value.find((s) => s.courseId === courseId && s.status === 'SELECTED')
}

/* ---------------- 目录检索（后端 available 不支持关键词，本地过滤） ---------------- */
const keyword = ref('')

const filteredAvailable = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return available.value
  return available.value.filter(
    (c) =>
      c.name.toLowerCase().includes(k) ||
      c.code.toLowerCase().includes(k) ||
      c.teacherNames.some((t) => t.toLowerCase().includes(k)),
  )
})

/** 「已选」不是障碍而是一种状态，用深青；真正不可选才用赭红 */
function tagTone(c: AvailableCourse) {
  if (selectionOf(c.courseId)) return 'teal' as const
  return c.blockedReason ? ('ochre' as const) : ('success' as const)
}

function tagText(c: AvailableCourse) {
  if (selectionOf(c.courseId)) return '已选'
  return c.blockedReason ? (BLOCK_REASON_LABEL[c.blockedReason] ?? c.blockedReason) : '可选'
}

/* ---------------- 我教的课 ---------------- */
const canTeach = computed(() => auth.can('course:manage_own', 'course:manage_all'))
const teaching = ref<CourseSummary[]>([])
const teachingLoading = ref(false)
const teachingError = ref<string | null>(null)

async function loadTeaching() {
  if (!semesterId.value) return
  teachingLoading.value = true
  teachingError.value = null
  try {
    const res = await courseApi.list({
      semesterId: semesterId.value,
      teacherId: auth.user?.userId,
      size: 50,
    })
    teaching.value = res.items
  } catch (e) {
    teaching.value = []
    teachingError.value = errorMessage(e)
  } finally {
    teachingLoading.value = false
  }
}

watch(tab, (t) => {
  if (t === 'teach' && canTeach.value) void loadTeaching()
})

/* ---------------- 新建课程 ---------------- */
const createOpen = ref(false)
const createBusy = ref(false)
const createForm = ref({ code: '', name: '', credit: '3', capacity: '60', description: '' })
const createError = ref<Record<string, string | null>>({})

function openCreateCourse() {
  createForm.value = { code: '', name: '', credit: '3', capacity: '60', description: '' }
  createError.value = {}
  createOpen.value = true
}

async function submitCreateCourse() {
  createError.value = {}
  if (!createForm.value.code.trim()) createError.value.code = '请填写课程编码'
  if (!createForm.value.name.trim()) createError.value.name = '请填写课程名称'
  const credit = Number(createForm.value.credit)
  const capacity = Number(createForm.value.capacity)
  if (!Number.isFinite(credit) || credit < 0 || credit > 20) createError.value.credit = '学分需在 0–20 之间'
  if (!Number.isInteger(capacity) || capacity < 1) createError.value.capacity = '容量至少为 1'
  if (!semesterId.value) createError.value.general = '请先选择学期'
  if (Object.keys(createError.value).length) return

  createBusy.value = true
  try {
    const created = await courseApi.create({
      semesterId: semesterId.value as number,
      code: createForm.value.code.trim(),
      name: createForm.value.name.trim(),
      credit,
      description: createForm.value.description.trim() || null,
      orgUnitId: null,
      capacity,
    })
    createOpen.value = false
    toast.push('课程已创建，你已成为主讲；排课后再开放选课', 'success')
    await router.push({ name: 'course', params: { id: created.id } })
  } catch (e) {
    createError.value = {
      code: e instanceof ApiError ? (e.fieldError('code') ?? null) : null,
      general: errorMessage(e),
    }
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    createBusy.value = false
  }
}

/* ---------------- 周次 ---------------- */
function stepWeek(delta: number) {
  week.value = Math.max(minWeek.value, Math.min(maxWeek.value, week.value + delta))
}

function thisWeek() {
  week.value = currentWeek(semester.value?.startDate, Date.now(), totalWeeks.value)
  ensureVisibleWeek()
}
</script>

<template>
  <div class="courses">
    <UiPageHeader
      label="课程"
      latin="Ledger"
      title="课表"
      lede="按周查看上课时间与地点。选课目录里的名额由后端条件更新保证不超卖，冲突与重复选课会在提交时拦下。"
    >
      <template #meta>
        <span v-if="semester">{{ SEMESTER_STATUS_LABEL[semester.status] ?? semester.status }}</span>
        <span v-if="semester" class="mono">{{ semester.startDate }} — {{ semester.endDate }}</span>
        <span :class="{ 'is-open': semester?.selectionOpen }">
          {{ semester?.selectionOpen ? '选课窗口开放中' : '选课窗口未开放' }}
        </span>
        <span class="mono">已选 {{ selectedCount }} 门 · {{ selectedCredits }} 学分</span>
        <span v-if="autoSwitchNote" class="is-note">已切到有课的学期：{{ autoSwitchNote }}</span>
      </template>
    </UiPageHeader>

    <UiEmpty
      v-if="!loading && semesters.length === 0"
      title="还没有学期"
      hint="学期由管理员在「管理」中创建并设为进行中。学期建立后课程与选课功能才能使用。"
    >
      <RouterLink to="/courses" class="link-quiet">刷新</RouterLink>
    </UiEmpty>

    <template v-else>
      <div class="bar">
        <div class="tabs" role="tablist" aria-label="视图">
          <button
            type="button"
            class="tabs__btn"
            role="tab"
            :aria-selected="tab === 'timetable'"
            :class="{ 'is-active': tab === 'timetable' }"
            @click="switchTab('timetable')"
          >
            课表
          </button>
          <button
            type="button"
            class="tabs__btn"
            role="tab"
            :aria-selected="tab === 'catalog'"
            :class="{ 'is-active': tab === 'catalog' }"
            @click="switchTab('catalog')"
          >
            选课目录
          </button>
          <button
            v-if="canTeach"
            type="button"
            class="tabs__btn"
            role="tab"
            :aria-selected="tab === 'teach'"
            :class="{ 'is-active': tab === 'teach' }"
            @click="switchTab('teach')"
          >
            我教的课
          </button>
        </div>

        <div class="bar__side">
          <UiSelect
            v-if="semesters.length > 1"
            v-model="semesterSelect"
            label="学期"
            class="bar__semester"
            :options="semesterOptions"
          />
          <p v-else-if="semester" class="bar__semester-name">{{ semester.name }}</p>
        </div>
      </div>

      <p v-if="loading" class="state">正在排课…</p>
      <div v-else-if="loadError" class="state state--err" role="alert">
        <p>{{ loadError }}</p>
        <UiButton size="sm" @click="loadSemesterData">重试</UiButton>
      </div>

      <!-- 课表 -->
      <template v-else-if="tab === 'timetable'">
        <div class="week">
          <div class="week__nav">
            <UiButton size="sm" :disabled="week <= minWeek" @click="stepWeek(-1)">上一周</UiButton>
            <p class="week__label">
              第 <span class="mono">{{ week }}</span> 周
              <span v-if="range" class="week__range mono">{{ range.from }} – {{ range.to }}</span>
            </p>
            <UiButton size="sm" :disabled="week >= maxWeek" @click="stepWeek(1)">下一周</UiButton>
            <UiButton size="sm" variant="link" @click="thisWeek">回到本周</UiButton>
          </div>
          <p class="week__note">
            单双周课程只在对应周次出现；左侧为节次与上下课时间。
            <span v-if="weeksWithClass.length" class="week__note-dim">
              课程排在第 {{ weeksWithClass[0] }}–{{ weeksWithClass[weeksWithClass.length - 1] }} 周，本学期共
              {{ totalWeeks }} 周。
            </span>
            <span class="week__mobile-only">手机上可左右滑动课表。</span>
          </p>
          <nav v-if="weeksWithClass.length > 1" class="weeks" aria-label="有课周次">
            <span class="weeks__label">有课周次</span>
            <button
              v-for="w in weeksWithClass"
              :key="w"
              type="button"
              class="weeks__chip"
              :class="{ 'is-on': w === week }"
              :aria-current="w === week ? 'true' : undefined"
              @click="week = w"
            >
              {{ w }}
            </button>
          </nav>
        </div>

        <Timetable v-if="weekItems.length" :items="weekItems" :periods="periods" />
        <UiEmpty
          v-else
          :title="`第 ${week} 周没有课`"
          :hint="
            schedule.length
              ? '这一周的课可能都在别的周次，试试上一周或下一周。'
              : '还没有课程。学生可以在「选课目录」选课，教师任教课程由管理员或本人建课后出现在这里。'
          "
        >
          <UiButton v-if="!schedule.length && canSelect" size="sm" variant="primary" @click="switchTab('catalog')">
            去选课
          </UiButton>
        </UiEmpty>

        <section v-if="selectedCount" class="picked" aria-label="已选课程">
          <div class="picked__head">
            <p class="eyebrow">已选</p>
            <p class="picked__sum mono">{{ selectedCount }} 门 · {{ selectedCredits }} 学分</p>
          </div>
          <ul class="picked__list">
            <li v-for="s in selections.filter((x) => x.status === 'SELECTED')" :key="s.id" class="picked__item">
              <RouterLink :to="{ name: 'course', params: { id: s.courseId } }" class="picked__name">
                {{ s.courseName }}
              </RouterLink>
              <span class="picked__meta">{{ s.teacherNames.join('、') || '未安排教师' }}</span>
              <span class="picked__meta mono">{{ s.credit }} 学分</span>
              <UiButton
                v-if="semester?.selectionOpen"
                size="sm"
                variant="link"
                :loading="busySelection === s.id"
                @click="dropSelection(s)"
              >
                退课
              </UiButton>
            </li>
          </ul>
        </section>
      </template>

      <!-- 选课目录 -->
      <template v-else-if="tab === 'catalog'">
        <p v-if="!canSelect" class="state">
          当前账号没有选课权限（教师与管理员代学生选课请使用管理工作台）。可以直接浏览课程与课表。
        </p>

        <form v-if="available.length" class="search" role="search" @submit.prevent>
          <label class="search__label" for="course-search">检索</label>
          <input
            id="course-search"
            v-model="keyword"
            class="search__input"
            type="search"
            placeholder="按课程名、课程号或教师查找"
            autocomplete="off"
          />
          <UiButton v-if="keyword" size="sm" variant="link" @click="keyword = ''">清除</UiButton>
        </form>

        <UiEmpty
          v-if="!available.length"
          title="本学期没有可选课程"
          hint="课程需要先由教师或管理员创建并「开放」，且状态为开放选课才会出现在这里。"
        />

        <UiEmpty
          v-else-if="filteredAvailable.length === 0"
          title="没有匹配的课程"
          :hint="`没有找到包含「${keyword}」的课程，换个词试试。`"
        >
          <UiButton size="sm" @click="keyword = ''">看全部</UiButton>
        </UiEmpty>

        <ul v-else class="catalog">
          <li v-for="c in filteredAvailable" :key="c.courseId" class="cc">
            <div class="cc__main">
              <p class="cc__code mono">{{ c.code }}</p>
              <h2 class="cc__name">
                <RouterLink :to="{ name: 'course', params: { id: c.courseId } }">{{ c.name }}</RouterLink>
              </h2>
              <p class="cc__meta">
                <span class="mono">{{ c.credit }} 学分</span>
                <span>{{ c.teacherNames.join('、') || '待安排教师' }}</span>
              </p>
              <p v-if="c.schedules.length" class="cc__sched">
                <span v-for="s in c.schedules" :key="s.id">{{ formatSchedule(s) }}</span>
              </p>
            </div>

            <div class="cc__side">
              <p class="cc__cap mono">{{ c.selectedCount }}/{{ c.capacity }}</p>
              <UiTag :tone="tagTone(c)">{{ tagText(c) }}</UiTag>
              <p v-if="c.conflictCourseName" class="cc__conflict">与「{{ c.conflictCourseName }}」冲突</p>

              <UiButton
                v-if="selectionOf(c.courseId)"
                class="cc__action"
                size="sm"
                :loading="busySelection === selectionOf(c.courseId)?.id"
                :disabled="!semester?.selectionOpen"
                @click="dropSelection(selectionOf(c.courseId)!)"
              >
                退课
              </UiButton>
              <UiButton
                v-else
                class="cc__action"
                size="sm"
                variant="primary"
                :disabled="!c.selectable || !canSelect"
                :loading="busyCourse === c.courseId"
                @click="selectCourse(c)"
              >
                选课
              </UiButton>
            </div>
          </li>
        </ul>
      </template>

      <!-- 我教的课 -->
      <template v-else-if="tab === 'teach'">
        <div class="teach-bar">
          <p class="teach-bar__note">{{ semester?.name }} · 你任教的课程。助教可以看名单，但只有主讲能改课与排课。</p>
          <UiButton variant="primary" @click="openCreateCourse">新建课程</UiButton>
        </div>

        <p v-if="teachingLoading" class="state">正在取课程…</p>
        <div v-else-if="teachingError" class="state state--err" role="alert">
          <p>{{ teachingError }}</p>
          <UiButton size="sm" @click="loadTeaching">重试</UiButton>
        </div>

        <UiEmpty
          v-else-if="teaching.length === 0"
          title="这个学期你还没有开课"
          hint="新建课程后你就是主讲，排好课才能设为「开放选课」。"
        >
          <UiButton size="sm" variant="primary" @click="openCreateCourse">新建课程</UiButton>
        </UiEmpty>

        <ul v-else class="teaching">
          <li v-for="c in teaching" :key="c.id" class="tc">
            <div class="tc__main">
              <p class="tc__code mono">{{ c.code }}</p>
              <h2 class="tc__name">
                <RouterLink :to="{ name: 'course', params: { id: c.id } }">{{ c.name }}</RouterLink>
              </h2>
              <p class="tc__meta">
                <UiTag :tone="courseTone(c.status)">{{ COURSE_STATUS_LABEL[c.status] ?? c.status }}</UiTag>
                <span class="mono">{{ c.credit }} 学分</span>
                <span class="mono">{{ c.selectedCount }}/{{ c.capacity }} 人</span>
                <span>{{ c.teacherNames.join('、') }}</span>
              </p>
            </div>
            <UiButton size="sm" @click="router.push({ name: 'course', params: { id: c.id } })">管理</UiButton>
          </li>
        </ul>
      </template>
    </template>

    <!-- 新建课程 -->
    <UiDialog
      :open="createOpen"
      title="新建课程"
      eyebrow="你将成为主讲"
      width="md"
      :dismissible="!createBusy"
      @close="createOpen = false"
    >
      <div class="form">
        <UiField v-model="createForm.code" label="课程编码" :error="createError.code" hint="学期内唯一，如 CS101。" />
        <UiField v-model="createForm.name" label="课程名称" :error="createError.name" />
        <div class="form-row">
          <UiField v-model="createForm.credit" label="学分" inputmode="numeric" :error="createError.credit" />
          <UiField v-model="createForm.capacity" label="容量" inputmode="numeric" :error="createError.capacity" />
        </div>
        <UiTextarea
          v-model="createForm.description"
          label="课程简介"
          :rows="4"
          :maxlength="1000"
          hint="会显示在课程详情页，可留空后再补。"
        />
        <p v-if="createError.general" class="form__err" role="alert">{{ createError.general }}</p>
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="createBusy" @click="createOpen = false">取消</UiButton>
        <UiButton variant="primary" :loading="createBusy" @click="submitCreateCourse">创建</UiButton>
      </template>
    </UiDialog>
  </div>
</template>

<style scoped>
.courses {
  width: 100%;
  max-width: 1280px;
  margin: 0 auto;
  padding: var(--s-5) clamp(var(--s-4), 4vw, var(--s-6)) var(--s-7);
}

.link-quiet {
  font-size: var(--fs-small);
}

/* ---------- 工具条 ---------- */
.bar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--s-4);
  padding-bottom: var(--s-2);
  margin-bottom: var(--s-4);
  border-bottom: 1px solid var(--ink);
}

.tabs {
  display: flex;
  gap: var(--s-4);
}

.tabs__btn {
  position: relative;
  padding: 0 0 var(--s-2);
  border: 0;
  background: none;
  color: var(--ink-2);
  font-family: var(--font-serif-cjk);
  font-size: 17px;
  cursor: pointer;
  transition: color var(--t-fast) var(--ease);
}

.tabs__btn:hover {
  color: var(--ink);
}

.tabs__btn.is-active {
  color: var(--ink);
  font-weight: 600;
}

.tabs__btn.is-active::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  background: var(--teal);
}

.bar__side {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  padding-bottom: var(--s-1);
}

.bar__semester {
  min-width: 240px;
}

.bar__semester-name {
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

/* ---------- 周次 ---------- */
.week {
  display: grid;
  gap: var(--s-2);
  margin-bottom: var(--s-3);
}

.week__nav {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--s-2);
}

/* 按钮文案不折行（手机上「上一周」曾被挤成两行） */
.week__nav :deep(button) {
  white-space: nowrap;
}

.week__label {
  margin: 0 var(--s-1);
  font-family: var(--font-serif-cjk);
  font-size: 15px;
}

.week__range {
  margin-left: var(--s-2);
  color: var(--ink-2);
  font-size: 11px;
}

.week__note {
  margin: 0;
  color: var(--ink-2);
  font-size: 11px;
}

.week__mobile-only {
  display: none;
}

.week__note-dim {
  margin-left: var(--s-2);
  opacity: 0.8;
}

/* 有课周次：学期很长时一眼看出「哪几周有课」，也避免落在空周上翻来翻去 */
.weeks {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
}

.weeks__label {
  margin-right: var(--s-1);
  color: var(--ink-2);
  font-size: 11px;
  letter-spacing: 0.08em;
}

.weeks__chip {
  min-width: 26px;
  padding: 2px 6px;
  border: var(--hairline);
  background: transparent;
  color: var(--ink-2);
  font-family: var(--font-mono, monospace);
  font-size: 11px;
  line-height: 1.6;
  cursor: pointer;
  transition:
    background-color var(--t-fast) var(--ease),
    color var(--t-fast) var(--ease);
}

.weeks__chip:hover {
  background: var(--paper-deep);
  color: var(--ink);
}

.weeks__chip.is-on {
  border-color: var(--teal);
  background: var(--teal);
  color: var(--paper);
  font-weight: 600;
}

/* ---------- 已选 ---------- */
.picked {
  margin-top: var(--s-5);
  padding-top: var(--s-4);
  border-top: 1px solid var(--ink);
}

.picked__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: var(--s-2);
}

.picked__head p {
  margin: 0;
}

.picked__sum {
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.picked__list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.picked__item {
  display: flex;
  align-items: baseline;
  gap: var(--s-3);
  padding: var(--s-2) 0;
  border-bottom: var(--hairline);
}

.picked__name {
  min-width: 10em;
  color: var(--ink);
  font-family: var(--font-serif-cjk);
  font-size: 15px;
  text-decoration: none;
}

.picked__name:hover {
  color: var(--teal);
  text-decoration: underline;
  text-underline-offset: 3px;
}

.picked__meta {
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.picked__item :deep(button) {
  margin-left: auto;
}

/* ---------- 目录 ---------- */
.search {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  padding-bottom: var(--s-3);
  margin-bottom: var(--s-2);
  border-bottom: var(--hairline);
}

.search__label {
  font-size: var(--fs-small);
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--ink-2);
}

.search__input {
  flex: 1;
  min-width: 0;
  height: 32px;
  padding: 0 2px;
  border: 0;
  background: transparent;
  font-size: 15px;
}

.search__input:focus {
  outline: none;
  box-shadow: 0 1px 0 0 var(--teal);
}

.catalog {
  margin: 0;
  padding: 0;
  list-style: none;
}

.cc {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 184px;
  gap: var(--s-4);
  padding: var(--s-4) 0;
  border-bottom: var(--hairline);
}

.cc__code {
  margin: 0;
  color: var(--ink-2);
  font-size: 11px;
  letter-spacing: 0.08em;
}

.cc__name {
  margin: var(--s-1) 0 var(--s-1);
  font-family: var(--font-serif-cjk);
  font-size: 19px;
  line-height: 1.3;
}

.cc__name a {
  color: inherit;
  text-decoration: none;
}

.cc__name a:hover {
  color: var(--teal);
  text-decoration: underline;
  text-underline-offset: 3px;
}

.cc__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-3);
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.cc__sched {
  display: grid;
  gap: 1px;
  margin: var(--s-1) 0 0;
  color: var(--ink-2);
  font-size: 11px;
}

.cc__side {
  display: grid;
  justify-items: end;
  align-content: start;
  gap: var(--s-2);
}

.cc__cap {
  margin: 0;
  color: var(--ink-2);
  font-size: 12px;
}

.cc__action {
  min-width: 88px;
}

.cc__conflict {
  margin: 0;
  color: var(--ochre);
  font-size: 11px;
  text-align: right;
}


.state {
  margin: 0;
  padding: var(--s-5) 0;
  color: var(--ink-2);
  font-family: var(--font-serif);
  font-size: var(--fs-read);
}

.state--err {
  color: var(--ochre);
}

.is-open {
  color: var(--success);
}

.is-note {
  color: var(--teal);
}

/* ---------- 我教的课 ---------- */
.teach-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  padding-bottom: var(--s-3);
  margin-bottom: var(--s-2);
  border-bottom: var(--hairline);
}

.teach-bar__note {
  max-width: 44em;
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.7;
}

.teaching {
  margin: 0;
  padding: 0;
  list-style: none;
}

.tc {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--s-4);
  padding: var(--s-3) 0;
  border-bottom: var(--hairline);
}

.tc__code {
  margin: 0;
  color: var(--ink-2);
  font-size: 11px;
  letter-spacing: 0.08em;
}

.tc__name {
  margin: var(--s-1) 0 var(--s-1);
  font-family: var(--font-serif-cjk);
  font-size: 19px;
  line-height: 1.3;
}

.tc__name a {
  color: inherit;
  text-decoration: none;
}

.tc__name a:hover {
  color: var(--teal);
  text-decoration: underline;
  text-underline-offset: 3px;
}

.tc__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--s-3);
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.form {
  display: grid;
  gap: var(--s-4);
}

.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--s-3);
}

.form__err {
  margin: 0;
  color: var(--ochre);
  font-size: var(--fs-small);
}

@media (max-width: 720px) {
  .bar {
    flex-direction: column;
    align-items: stretch;
  }

  .bar__side {
    justify-content: flex-end;
  }

  .week__mobile-only {
    display: inline;
  }

  .cc {
    grid-template-columns: minmax(0, 1fr);
  }

  .cc__side {
    justify-items: start;
    grid-auto-flow: column;
    align-items: center;
    gap: var(--s-3);
  }
}
</style>
