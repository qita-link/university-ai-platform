<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { schedulingApi } from '@/api/endpoints'
import type {
  ScheduleEntry,
  ScheduleEvaluation,
  ScheduleVersion,
  SchedulingClass,
  SchedulingRoom,
  SchedulingTeacher,
  SchedulingTerm,
  TimetableView,
} from '@/api/types'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiEmpty from '@/components/ui/UiEmpty.vue'
import UiPageHeader from '@/components/ui/UiPageHeader.vue'
import UiSelect from '@/components/ui/UiSelect.vue'
import UiTag from '@/components/ui/UiTag.vue'
import ScheduleGrid from '@/components/scheduling/ScheduleGrid.vue'
import JobPanel from '@/components/scheduling/JobPanel.vue'
import BaseDataPanel from '@/components/scheduling/BaseDataPanel.vue'
import SettingsPanel from '@/components/scheduling/SettingsPanel.vue'
import AdjustDialog from '@/components/scheduling/AdjustDialog.vue'

/**
 * 排课工作台：选学期 → 选版本 → 看课表/评分 → 调整 → 发布。
 * 排版遵循「课表在左、判断在右」：右边始终给出这份课表能不能发。
 */
const auth = useAuthStore()
const toast = useToastStore()

const canReadSchedule = computed(() => auth.can('schedule:read'))
const canManage = computed(() => auth.can('schedule:manage'))
const canAdjust = computed(() => auth.can('schedule:adjust'))
const canPublish = computed(() => auth.can('schedule:publish'))

const tab = ref<'timetable' | 'jobs' | 'base' | 'settings'>('timetable')

const terms = ref<SchedulingTerm[]>([])
const termId = ref<number | null>(null)
const versions = ref<ScheduleVersion[]>([])
const versionId = ref<number | null>(null)
const timetable = ref<TimetableView | null>(null)
const evaluation = ref<ScheduleEvaluation | null>(null)
const teachers = ref<SchedulingTeacher[]>([])
const classes = ref<SchedulingClass[]>([])
const rooms = ref<SchedulingRoom[]>([])

const scope = ref<'ALL' | 'TEACHER' | 'CLASS' | 'ROOM'>('ALL')

/** 课表显示偏好：本地记忆，只影响呈现，不影响数据 */
interface DisplayPrefs {
  density: 'comfy' | 'compact'
  showClass: boolean
  showTeacher: boolean
  showRoom: boolean
  showWeeks: boolean
  hideEmptyPeriods: boolean
  weekdaysOnly: boolean
}
const PREFS_KEY = 'campus.scheduling.display'
/** 偏好结构版本：v2 起周次默认打开，旧数据一次性迁移 */
const PREFS_VERSION = 2
const DEFAULT_PREFS: DisplayPrefs = {
  density: 'comfy',
  showClass: true,
  showTeacher: true,
  showRoom: true,
  // 周次默认打开：同一门课在不同周次上不同内容，看不到周次等于看不到课表的关键信息
  showWeeks: true,
  hideEmptyPeriods: false,
  weekdaysOnly: false,
}
function loadPrefs(): DisplayPrefs {
  try {
    const raw = localStorage.getItem(PREFS_KEY)
    if (!raw) return { ...DEFAULT_PREFS }
    const stored = JSON.parse(raw) as Partial<DisplayPrefs> & { version?: number }
    const merged = { ...DEFAULT_PREFS, ...stored }
    // 旧版本存的是「周次关」，升级后先打开一次；之后以用户自己的选择为准
    if (stored.version !== PREFS_VERSION) {
      merged.showWeeks = true
      try {
        localStorage.setItem(PREFS_KEY, JSON.stringify({ ...merged, version: PREFS_VERSION }))
      } catch {
        // 隐私模式写不进去也无所谓，本次仍然按迁移后的值显示
      }
    }
    return merged
  } catch {
    return { ...DEFAULT_PREFS }
  }
}
const prefs = ref<DisplayPrefs>(loadPrefs())
const prefsOpen = ref(false)
watch(
  prefs,
  (v) => localStorage.setItem(PREFS_KEY, JSON.stringify({ ...v, version: PREFS_VERSION })),
  { deep: true },
)
function resetPrefs() {
  prefs.value = { ...DEFAULT_PREFS }
}

const gridDays = computed(() => {
  const days = timetable.value?.days ?? []
  return prefs.value.weekdaysOnly ? days.filter((d) => d <= 5) : days
})

const gridRows = computed(() => {
  const days = gridDays.value
  const rows = (timetable.value?.rows ?? []).map((r) => ({
    ...r,
    cells: r.cells.filter((c) => days.includes(c.dayOfWeek)),
  }))
  return prefs.value.hideEmptyPeriods ? rows.filter((r) => r.cells.some((c) => c.entries.length)) : rows
})
const scopeId = ref<number | null>(null)
const loading = ref(false)
const busy = ref(false)

const adjustOpen = ref(false)
const picked = ref<ScheduleEntry | null>(null)

/** 只有 timetable:read 的教师/学生：不进入排课管理，只看本人已发布课表 */
const mine = ref<TimetableView | null>(null)
/** 本人课表：教师看自己的课（显示班级），学生看自己班的课（隐藏班级） */
const mineScope = computed<'TEACHER' | 'CLASS'>(() => (mine.value?.scope === 'CLASS' ? 'CLASS' : 'TEACHER'))
const mineError = ref<string | null>(null)

/**
 * 教师侧的「按教室查课」。
 * 教师没有 schedule:read（排课工作台权限），所以走 /timetables/room —— 只看已发布课表，
 * 但能看到某一间教室一周里被哪些课上占用。学生两个权限点都没有，因此看不到这个入口。
 */
const teacherView = ref<'mine' | 'room'>('mine')
const roomId = ref<number | null>(null)
const roomTable = ref<TimetableView | null>(null)
const roomLoading = ref(false)
const roomError = ref<string | null>(null)

async function loadRooms() {
  if (rooms.value.length) return
  try {
    // 后端 PageQuery 单页上限 100，传更大的值会直接 400
    const page = await schedulingApi.rooms({ size: 100 })
    rooms.value = page.items
    if (roomId.value == null && rooms.value.length) roomId.value = rooms.value[0]?.id ?? null
  } catch (e) {
    roomError.value = errorMessage(e)
  }
}

async function loadRoomTable() {
  if (roomId.value == null) return
  roomLoading.value = true
  roomError.value = null
  try {
    roomTable.value = await schedulingApi.roomTimetable(roomId.value)
  } catch (e) {
    roomTable.value = null
    roomError.value = errorMessage(e)
  } finally {
    roomLoading.value = false
  }
}

function switchTeacherView(next: 'mine' | 'room') {
  teacherView.value = next
  if (next === 'room') {
    void loadRooms().then(loadRoomTable)
  }
}

watch(roomId, () => {
  if (teacherView.value === 'room') void loadRoomTable()
})

const termOptions = computed(() =>
  terms.value.map((t) => ({
    value: String(t.id),
    label: `${t.name}${t.config?.configured ? '' : '（未配置）'}`,
  })),
)

const versionOptions = computed(() =>
  versions.value.map((v) => ({
    value: String(v.id),
    label: `#${v.versionNo} ${v.name} · ${v.status === 'PUBLISHED' ? '已发布' : v.status === 'DRAFT' ? '草稿' : '归档'}`,
  })),
)

const roomOptions = computed(() =>
  rooms.value.map((r) => ({
    value: String(r.id),
    label: `${r.code}${r.name && r.name !== r.code ? ' · ' + r.name : ''}${
      r.campusName ? '（' + r.campusName + '）' : ''
    }`,
  })),
)

const scopeOptions = computed(() => {
  if (scope.value === 'TEACHER') {
    return teachers.value.map((t) => ({ value: String(t.id), label: t.displayName }))
  }
  if (scope.value === 'ROOM') {
    return roomOptions.value
  }
  return classes.value.map((c) => ({ value: String(c.id), label: c.name }))
})

const currentTerm = computed(() => terms.value.find((t) => t.id === termId.value) ?? null)
const termConfigured = computed(() => !!currentTerm.value?.config?.configured)
const current = computed(() => versions.value.find((v) => v.id === versionId.value) ?? null)
const published = computed(() => versions.value.find((v) => v.status === 'PUBLISHED') ?? null)
const editable = computed(() => current.value?.status === 'DRAFT')

const topScores = computed(() =>
  [...(evaluation.value?.details ?? [])]
    .filter((d) => d.applicable > 0)
    .sort((a, b) => b.penalty - a.penalty)
    .slice(0, 6),
)

/**
 * 默认学期优先选「已配置排课参数」的：课程模块里的学期没有排课配置，
 * 直接落到 ACTIVE 学期会看到一片空白，用户会以为功能坏了。
 */
function defaultTermId(): number | null {
  const list = terms.value
  const configured = (t: SchedulingTerm) => !!t.config?.configured
  const picked =
    list.find((t) => t.status === 'ACTIVE' && configured(t)) ??
    list.find(configured) ??
    list.find((t) => t.status === 'ACTIVE') ??
    list[0]
  return picked ? picked.id : null
}

async function loadTerms() {
  try {
    terms.value = await schedulingApi.terms()
    if (!termId.value && terms.value.length) termId.value = defaultTermId()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  }
}

async function loadVersions() {
  if (!termId.value) return
  loading.value = true
  try {
    versions.value = await schedulingApi.versions(termId.value)
    if (!versions.value.some((v) => v.id === versionId.value)) {
      const preferred =
        versions.value.find((v) => v.status === 'PUBLISHED') ?? versions.value[0] ?? null
      versionId.value = preferred?.id ?? null
    }
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    loading.value = false
  }
}

async function loadTimetable() {
  if (!versionId.value) {
    timetable.value = null
    return
  }
  const query: { teacherId?: number; classId?: number; roomId?: number } = {}
  if (scope.value === 'TEACHER' && scopeId.value) query.teacherId = scopeId.value
  if (scope.value === 'CLASS' && scopeId.value) query.classId = scopeId.value
  if (scope.value === 'ROOM' && scopeId.value) query.roomId = scopeId.value
  try {
    timetable.value = await schedulingApi.timetable(versionId.value, query)
  } catch (e) {
    timetable.value = null
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  }
}

async function loadEvaluation() {
  if (!versionId.value) {
    evaluation.value = null
    return
  }
  try {
    evaluation.value = await schedulingApi.evaluation(versionId.value)
  } catch (e) {
    evaluation.value = null
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  }
}

async function loadScopeOptions() {
  try {
    const [t, c, r] = await Promise.all([
      schedulingApi.teachers(),
      schedulingApi.classes(),
      schedulingApi.rooms({ size: 100 }),
    ])
    teachers.value = t
    classes.value = c
    rooms.value = r.items
  } catch {
    // 没有权限时忽略：维度仍可用「全部」
  }
}

async function refreshAll() {
  await loadVersions()
  await Promise.all([loadTimetable(), loadEvaluation()])
}

async function check() {
  if (!versionId.value) return
  busy.value = true
  try {
    evaluation.value = await schedulingApi.check(versionId.value)
    toast.push(
      evaluation.value.hardViolations
        ? `复核发现 ${evaluation.value.hardViolations} 条硬约束冲突，不能发布`
        : '复核通过：没有硬约束冲突',
      evaluation.value.hardViolations ? 'danger' : 'success',
    )
    await loadVersions()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    busy.value = false
  }
}

async function publish() {
  if (!versionId.value) return
  busy.value = true
  try {
    await schedulingApi.publish(versionId.value, '排课工作台发布')
    toast.push('课表已发布，教师与学生现在可以看到', 'success')
    await refreshAll()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    busy.value = false
  }
}

async function unpublish() {
  if (!versionId.value) return
  busy.value = true
  try {
    await schedulingApi.unpublish(versionId.value, '排课工作台撤回')
    toast.push('已撤销发布', 'info')
    await refreshAll()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    busy.value = false
  }
}

async function copyVersion() {
  if (!versionId.value) return
  busy.value = true
  try {
    const copy = await schedulingApi.copy(versionId.value)
    toast.push(`已复制为草稿 #${copy.versionNo}`, 'success')
    await loadVersions()
    versionId.value = copy.id
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    busy.value = false
  }
}

function pick(entry: ScheduleEntry) {
  picked.value = entry
  adjustOpen.value = true
}

function onScopeChanged() {
  scopeId.value = null
  if (scope.value !== 'ALL') {
    const first =
      scope.value === 'TEACHER'
        ? teachers.value[0]
        : scope.value === 'ROOM'
          ? rooms.value[0]
          : classes.value[0]
    scopeId.value = first?.id ?? null
  }
  loadTimetable()
}

watch(termId, async (value) => {
  if (!value) return
  versionId.value = null
  await loadVersions()
  await Promise.all([loadTimetable(), loadEvaluation()])
})

watch([versionId, scopeId], () => {
  loadTimetable()
})

watch(versionId, () => {
  loadEvaluation()
})

async function loadMine() {
  try {
    mine.value = await schedulingApi.myTimetable()
  } catch (e) {
    mineError.value = errorMessage(e)
  }
}

onMounted(async () => {
  if (!canReadSchedule.value) {
    await loadMine()
    return
  }
  await loadTerms()
  await Promise.all([loadScopeOptions(), loadVersions()])
  await Promise.all([loadTimetable(), loadEvaluation()])
})
</script>

<template>
  <div v-if="!canReadSchedule" class="sch">
    <UiPageHeader
      label="课表"
      latin="Timetable"
      title="我的课表"
      lede="只显示已发布的课表。教师看到自己任教的课，也可以按教室查某一间教室的占用情况。"
    />

    <nav class="sch__tabs" aria-label="课表视图">
      <button type="button" :class="{ 'is-on': teacherView === 'mine' }" @click="switchTeacherView('mine')">
        我的课表
      </button>
      <button type="button" :class="{ 'is-on': teacherView === 'room' }" @click="switchTeacherView('room')">
        按教室查课
      </button>
    </nav>

    <template v-if="teacherView === 'mine'">
      <UiEmpty v-if="mineError" :title="mineError" hint="课表发布后才会出现在这里" />
      <template v-else-if="mine">
        <div class="sch__main-head">
          <p class="sch__vmeta">{{ mine.ownerName }} · {{ mine.scope === 'TEACHER' ? '教师课表' : '班级课表' }}</p>
        </div>
        <ScheduleGrid :rows="mine.rows" :days="mine.days" :scope="mineScope" show-weeks />
      </template>
      <UiEmpty v-else title="正在读取课表" />
    </template>

    <template v-else>
      <div class="sch__toolbar">
        <UiSelect
          v-if="roomOptions.length"
          :model-value="roomId ? String(roomId) : ''"
          label="教室"
          :options="roomOptions"
          placeholder="选择教室"
          @update:model-value="(v: string) => (roomId = v ? Number(v) : null)"
        />
        <p v-else class="sch__vmeta">还没有教室数据，需要先在排课工作台维护教室。</p>
      </div>

      <p v-if="roomLoading" class="sch__vmeta">正在读取教室课表…</p>
      <UiEmpty v-else-if="roomError" :title="roomError" hint="确认该学期课表已经发布" />
      <template v-else-if="roomTable">
        <div class="sch__main-head">
          <p class="sch__vmeta">{{ roomTable.ownerName }} · 已发布课表</p>
        </div>
        <ScheduleGrid :rows="roomTable.rows" :days="roomTable.days" scope="ALL" show-weeks />
      </template>
      <UiEmpty v-else title="选一间教室看看" hint="左侧选择教室后显示这一间教室一周的课表。" />
    </template>
  </div>

  <div v-else class="sch">
    <UiPageHeader
      label="排课"
      latin="Timetable"
      title="自动排课"
      lede="选学期、看版本、核一遍硬约束，再决定发布。课表与评分都来自同一次求解。"
    >
      <template #actions>
        <UiButton v-if="canAdjust && current" size="sm" :loading="busy" @click="check">复核</UiButton>
        <UiButton
          v-if="canPublish && editable"
          size="sm"
          variant="primary"
          :loading="busy"
          :disabled="!!evaluation?.hardViolations"
          @click="publish"
        >
          发布课表
        </UiButton>
        <UiButton
          v-if="canPublish && current?.status === 'PUBLISHED'"
          size="sm"
          variant="danger"
          :loading="busy"
          @click="unpublish"
        >
          撤销发布
        </UiButton>
        <UiButton v-if="canManage && current" size="sm" :loading="busy" @click="copyVersion">复制为草稿</UiButton>
      </template>
      <template #meta>
        <div class="sch__toolbar">
          <UiSelect
            :model-value="termId ? String(termId) : ''"
            label="学期"
            :options="termOptions"
            placeholder="选择学期"
            @update:model-value="(v: string) => (termId = v ? Number(v) : null)"
          />
          <UiSelect
            v-if="versions.length"
            :model-value="versionId ? String(versionId) : ''"
            label="课表版本"
            :options="versionOptions"
            placeholder="选择版本"
            @update:model-value="(v: string) => (versionId = v ? Number(v) : null)"
          />
          <label class="sch__scope">
            <span>视角</span>
            <select v-model="scope" @change="onScopeChanged">
              <option value="ALL">全部课表</option>
              <option value="TEACHER">按教师</option>
              <option value="CLASS">按班级</option>
              <option value="ROOM">按教室</option>
            </select>
          </label>
          <UiSelect
            v-if="scope !== 'ALL' && scopeOptions.length"
            :model-value="scopeId ? String(scopeId) : ''"
            :label="scope === 'TEACHER' ? '教师' : scope === 'ROOM' ? '教室' : '班级'"
            :options="scopeOptions"
            @update:model-value="(v: string) => (scopeId = v ? Number(v) : null)"
          />
          <UiButton size="sm" variant="link" @click="prefsOpen = true">显示设置</UiButton>
        </div>
      </template>
    </UiPageHeader>

    <p v-if="termId && !termConfigured" class="sch__notice">
      这个学期还没有排课参数（教学周数、第 1 周周一、教学日、节次作息）。配置后才会出现课表与排课入口。
    </p>

    <nav class="sch__tabs" aria-label="排课视图">
      <button type="button" :class="{ 'is-on': tab === 'timetable' }" @click="tab = 'timetable'">课表</button>
      <button type="button" :class="{ 'is-on': tab === 'jobs' }" @click="tab = 'jobs'">排课任务</button>
      <button type="button" :class="{ 'is-on': tab === 'base' }" @click="tab = 'base'">基础数据</button>
      <button type="button" :class="{ 'is-on': tab === 'settings' }" @click="tab = 'settings'">排课设置</button>
    </nav>

    <template v-if="tab === 'timetable'">
      <UiEmpty
        v-if="!termId"
        title="先建立一个学期"
        hint="学期与排课配置可通过接口或管理端创建，之后才能排课"
      >
        <UiButton v-if="canManage" size="sm" @click="tab = 'base'">先维护基础数据</UiButton>
      </UiEmpty>
      <UiEmpty
        v-else-if="!versions.length && !loading"
        title="这个学期还没有课表版本"
        hint="先确认课程/教室/教学班/教学任务齐全，再到「排课任务」里发起求解"
      >
        <UiButton size="sm" variant="primary" @click="tab = 'jobs'">去排课</UiButton>
      </UiEmpty>

      <div v-else class="sch__body">
        <section class="sch__main">
          <div class="sch__main-head">
            <div>
              <p class="sch__vname">{{ current?.name ?? '—' }}</p>
              <p class="sch__vmeta">
                {{ timetable?.ownerName ?? '全部' }} ·
                {{ current?.placedSessions ?? 0 }}/{{ current?.totalSessions ?? 0 }} 次课
                <span v-if="current?.status === 'PUBLISHED'" class="sch__pub">已发布</span>
              </p>
            </div>
            <UiTag v-if="current" :tone="editable ? 'muted' : current.status === 'PUBLISHED' ? 'success' : 'neutral'">
              {{ current.status === 'DRAFT' ? '草稿可调整' : current.status === 'PUBLISHED' ? '已发布' : '已归档' }}
            </UiTag>
          </div>

          <ScheduleGrid
            v-if="timetable"
            :rows="gridRows"
            :days="gridDays"
            :scope="scope"
            :density="prefs.density"
            :show-class="prefs.showClass"
            :show-teacher="prefs.showTeacher"
            :show-room="prefs.showRoom"
            :show-weeks="prefs.showWeeks"
            :editable="editable && canAdjust"
            @pick="pick"
          />
        </section>

        <aside class="sch__side">
          <div v-if="evaluation" class="sch__score">
            <p class="sch__side-title">这份课表</p>
            <div class="sch__score-row">
              <span class="sch__score-num">{{ evaluation.score.toFixed(1) }}</span>
              <span class="sch__score-unit">分</span>
            </div>
            <dl class="sch__facts">
              <div>
                <dt>已排</dt>
                <dd>{{ evaluation.placedSessions }} / {{ evaluation.totalSessions }}</dd>
              </div>
              <div>
                <dt>硬约束冲突</dt>
                <dd :class="{ 'is-bad': evaluation.hardViolations > 0 }">{{ evaluation.hardViolations }}</dd>
              </div>
              <div>
                <dt>软约束扣分</dt>
                <dd>{{ evaluation.softPenalty }}</dd>
              </div>
            </dl>
            <p v-if="evaluation.hardViolations" class="sch__warn">
              存在硬约束冲突，发布已被阻止。先看下方冲突说明逐条调整。
            </p>
          </div>

          <div v-if="evaluation?.conflicts.length" class="sch__conflicts">
            <p class="sch__side-title">冲突（{{ evaluation.conflicts.length }}）</p>
            <ul>
              <li v-for="(c, i) in evaluation.conflicts.slice(0, 12)" :key="i">
                <UiTag tone="ochre">{{ c.label }}</UiTag>
                <p class="sch__conflict-msg">{{ c.message }}</p>
                <p class="sch__conflict-fix">{{ c.suggestion }}</p>
              </li>
            </ul>
          </div>

          <div v-if="topScores.length" class="sch__soft">
            <p class="sch__side-title">扣分项</p>
            <ul>
              <li v-for="d in topScores" :key="d.code">
                <span class="sch__soft-label">{{ d.label }}</span>
                <span class="sch__soft-val mono">
                  {{ d.violated }}/{{ d.applicable }} · −{{ d.penalty }}
                </span>
              </li>
            </ul>
            <p class="sch__soft-hint">满足率 = 1 − 违反量 / 适用量；权重在学期约束配置里调整。</p>
          </div>

          <p v-if="!evaluation" class="sch__side-empty">选择版本后显示评分与冲突。</p>
        </aside>
      </div>
    </template>

    <JobPanel
      v-else-if="tab === 'jobs'"
      :semester-id="termId ?? 0"
      :can-manage="canManage"
      :published-version-id="published?.id ?? null"
      :versions="versions"
      @changed="refreshAll"
      @open-version="
        (id) => {
          versionId = id
          tab = 'timetable'
        }
      "
    />

    <BaseDataPanel v-else-if="tab === 'base'" :semester-id="termId ?? 0" :can-manage="canManage" />
    <SettingsPanel v-else :semester-id="termId ?? 0" :can-manage="canManage" />

    <UiDialog :open="prefsOpen" title="课表显示设置" eyebrow="个性化" width="sm" @close="prefsOpen = false">
      <div class="pref">
        <div class="pref__row">
          <span class="pref__label">显示密度</span>
          <div class="pref__seg">
            <button type="button" :class="{ 'is-on': prefs.density === 'comfy' }" @click="prefs.density = 'comfy'">舒适</button>
            <button type="button" :class="{ 'is-on': prefs.density === 'compact' }" @click="prefs.density = 'compact'">紧凑</button>
          </div>
        </div>
        <label class="pref__check">
          <input v-model="prefs.showClass" type="checkbox" />
          <span>显示班级名</span>
        </label>
        <label class="pref__check">
          <input v-model="prefs.showTeacher" type="checkbox" />
          <span>显示教师名<span class="pref__dim">（教师视角自动隐藏）</span></span>
        </label>
        <label class="pref__check">
          <input v-model="prefs.showRoom" type="checkbox" />
          <span>显示教室</span>
        </label>
        <label class="pref__check">
          <input v-model="prefs.showWeeks" type="checkbox" />
          <span>显示周次角标<span class="pref__dim">（1-8 周 / 9-16 周）</span></span>
        </label>
        <label class="pref__check">
          <input v-model="prefs.hideEmptyPeriods" type="checkbox" />
          <span>隐藏没有课的节次</span>
        </label>
        <label class="pref__check">
          <input v-model="prefs.weekdaysOnly" type="checkbox" />
          <span>只显示工作日（周一至周五）</span>
        </label>
        <p class="pref__hint">设置保存在本机浏览器，只影响显示，不改动课表数据。</p>
      </div>
      <template #footer>
        <UiButton @click="resetPrefs">恢复默认</UiButton>
        <UiButton variant="primary" @click="prefsOpen = false">完成</UiButton>
      </template>
    </UiDialog>

    <AdjustDialog
      :open="adjustOpen"
      :version-id="versionId ?? 0"
      :entry="picked"
      :editable="editable"
      :can-adjust="canAdjust"
      @close="adjustOpen = false"
      @changed="refreshAll"
    />
  </div>
</template>

<style scoped>
.sch {
  width: 100%;
  max-width: 1240px;
  margin: 0 auto;
  padding: var(--s-5) clamp(var(--s-4), 4vw, var(--s-6)) var(--s-7);
  display: grid;
  gap: var(--s-4);
}

.sch__toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: var(--s-3);
}

.sch__toolbar :deep(.ui-select) {
  min-width: 200px;
}

.sch__scope {
  display: grid;
  gap: 4px;
  font-size: var(--fs-small);
  color: var(--ink-2);
}

.sch__scope {
  font-size: var(--fs-small);
  font-weight: 600;
  letter-spacing: 0.08em;
}

.sch__scope select {
  height: 44px;
  padding: 0 24px 0 2px;
  border: 0;
  border-bottom: 1px solid var(--rule-strong);
  background: transparent;
  color: var(--ink);
  font: inherit;
  font-size: 16px;
  font-weight: 400;
  letter-spacing: normal;
  appearance: none;
  cursor: pointer;
}

.pref {
  display: grid;
  gap: var(--s-2);
}

.pref__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  padding-bottom: var(--s-2);
  border-bottom: var(--hairline);
}

.pref__label {
  font-size: var(--fs-small);
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--ink-2);
}

.pref__seg {
  display: flex;
  border: var(--hairline);
}

.pref__seg button {
  padding: 4px 12px;
  border: 0;
  background: var(--paper);
  color: var(--ink-2);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}

.pref__seg button.is-on {
  background: var(--teal);
  color: var(--paper);
}

.pref__check {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  font-size: 14px;
  cursor: pointer;
}

.pref__dim {
  color: var(--ink-2);
  font-size: 12px;
}

.pref__hint {
  margin: var(--s-2) 0 0;
  color: var(--ink-2);
  font-size: 12px;
  line-height: 1.6;
}

.sch__notice {
  margin: 0;
  padding: 10px 12px;
  border-left: 3px solid var(--ochre);
  background: var(--paper-lift);
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.6;
}

.sch__tabs {
  display: flex;
  gap: var(--s-4);
  border-bottom: 1px solid var(--ink);
}

.sch__tabs button {
  padding: 8px 2px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: none;
  color: var(--ink-2);
  font: inherit;
  font-size: 14px;
  letter-spacing: 0.06em;
  cursor: pointer;
}

.sch__tabs button.is-on {
  border-bottom-color: var(--teal);
  color: var(--ink);
  font-weight: 600;
}

.sch__body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: var(--s-5);
  align-items: start;
}

.sch__main {
  min-width: 0;
}

.sch__main-head {
  display: flex;
  align-items: start;
  justify-content: space-between;
  gap: var(--s-3);
  margin-bottom: var(--s-2);
}

.sch__vname {
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-size: 18px;
  font-weight: 600;
}

.sch__vmeta {
  margin: 2px 0 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.sch__pub {
  margin-left: 6px;
  color: var(--teal);
  font-weight: 600;
}

.sch__side {
  display: grid;
  gap: var(--s-4);
  padding-left: var(--s-4);
  border-left: var(--hairline);
}

.sch__side-title {
  margin: 0 0 var(--s-2);
  color: var(--ink-2);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.sch__score-row {
  display: flex;
  align-items: baseline;
  gap: 4px;
}

.sch__score-num {
  font-family: var(--font-serif-cjk);
  font-size: 40px;
  line-height: 1;
}

.sch__score-unit {
  color: var(--ink-2);
  font-size: 13px;
}

.sch__facts {
  display: grid;
  gap: 4px;
  margin: var(--s-3) 0 0;
}

.sch__facts div {
  display: flex;
  justify-content: space-between;
  gap: var(--s-2);
  font-size: 13px;
}

.sch__facts dt {
  color: var(--ink-2);
}

.sch__facts dd {
  margin: 0;
  font-variant-numeric: tabular-nums;
}

.sch__facts dd.is-bad {
  color: var(--danger);
  font-weight: 600;
}

.sch__warn {
  margin: var(--s-3) 0 0;
  color: var(--danger);
  font-size: 12px;
  line-height: 1.55;
}

.sch__conflicts ul,
.sch__soft ul {
  display: grid;
  gap: var(--s-2);
  margin: 0;
  padding: 0;
  list-style: none;
  max-height: 40vh;
  overflow: auto;
}

.sch__conflicts li {
  padding-bottom: var(--s-2);
  border-bottom: var(--hairline);
}

.sch__conflict-msg {
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 1.55;
}

.sch__conflict-fix {
  margin: 2px 0 0;
  color: var(--ink-2);
  font-size: 12px;
  line-height: 1.5;
}

.sch__soft li {
  display: flex;
  justify-content: space-between;
  gap: var(--s-2);
  font-size: 13px;
}

.sch__soft-label {
  color: var(--ink-2);
}

.sch__soft-val {
  font-variant-numeric: tabular-nums;
}

.sch__soft-hint,
.sch__side-empty {
  margin: var(--s-2) 0 0;
  color: var(--ink-2);
  font-size: 12px;
  line-height: 1.55;
}

@media (max-width: 760px) {
  .sch :deep(.ph__row) {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--s-2);
  }

  .sch :deep(.ph__actions) {
    width: 100%;
    justify-content: flex-start;
  }

  .sch :deep(.ph) {
    padding-bottom: var(--s-3);
    margin-bottom: var(--s-3);
  }

  .sch__toolbar :deep(.ui-select) {
    min-width: 100%;
  }

  .sch__scope {
    width: 100%;
  }

  .sch__scope select {
    width: 100%;
  }
}

@media (max-width: 1080px) {
  .sch__body {
    grid-template-columns: minmax(0, 1fr);
  }

  .sch__side {
    padding-left: 0;
    padding-top: var(--s-4);
    border-left: 0;
    border-top: var(--hairline);
    grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
    display: grid;
  }
}
</style>
