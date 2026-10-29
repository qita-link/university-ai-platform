<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { schedulingApi } from '@/api/endpoints'
import type { CalendarDate, CalendarWeek, ConstraintSetting, SchedulingTeacher, SchedulingTerm, TermPeriod } from '@/api/types'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { useToastStore } from '@/stores/toast'
import UiButton from '@/components/ui/UiButton.vue'
import UiEmpty from '@/components/ui/UiEmpty.vue'
import UiField from '@/components/ui/UiField.vue'
import UiSelect from '@/components/ui/UiSelect.vue'
import UiTag from '@/components/ui/UiTag.vue'
import RulePanel from './RulePanel.vue'

/**
 * 排课设置：学期时间语义、作息、教学日历、软约束权重、时间规则。
 * 这些参数直接改变求解模型，改完必须重新排课才会生效；已发布课表的学期改前三项会被后端拒绝。
 */
const props = defineProps<{ semesterId: number; canManage: boolean }>()

const toast = useToastStore()

type Section = 'term' | 'periods' | 'calendar' | 'weights' | 'teacher' | 'rules'
const section = ref<Section>('term')

const sections: { key: Section; label: string; latin: string }[] = [
  { key: 'term', label: '学期参数', latin: 'Term' },
  { key: 'periods', label: '节次作息', latin: 'Periods' },
  { key: 'calendar', label: '教学日历', latin: 'Calendar' },
  { key: 'weights', label: '约束权重', latin: 'Weights' },
  { key: 'teacher', label: '教师时间', latin: 'Faculty' },
  { key: 'rules', label: '时间规则', latin: 'Rules' },
]

const loading = ref(false)
const term = ref<SchedulingTerm | null>(null)
const periods = ref<TermPeriod[]>([])
const weeks = ref<CalendarWeek[]>([])
const dates = ref<CalendarDate[]>([])
const constraints = ref<ConstraintSetting[]>([])

const DAY_OPTIONS = [
  { value: 1, label: '周一' },
  { value: 2, label: '周二' },
  { value: 3, label: '周三' },
  { value: 4, label: '周四' },
  { value: 5, label: '周五' },
  { value: 6, label: '周六' },
  { value: 7, label: '周日' },
]

const SECTIONS = [
  { value: 'MORNING', label: '上午' },
  { value: 'AFTERNOON', label: '下午' },
  { value: 'EVENING', label: '晚上' },
]

const WEEK_TYPES = [
  { value: 'EXAM', label: '考试周' },
  { value: 'PRACTICE', label: '实践周' },
  { value: 'HOLIDAY', label: '假期周' },
  { value: 'OTHER', label: '其他' },
]

const DATE_TYPES = [
  { value: 'HOLIDAY', label: '节假日' },
  { value: 'SUSPENDED', label: '停课' },
  { value: 'WORKDAY', label: '调休上课' },
  { value: 'MAKEUP', label: '补课' },
]

async function load() {
  if (!props.semesterId) return
  loading.value = true
  try {
    const [t, p, c, k] = await Promise.all([
      schedulingApi.term(props.semesterId),
      schedulingApi.periods(props.semesterId),
      schedulingApi.calendar(props.semesterId),
      schedulingApi.constraints(props.semesterId),
    ])
    term.value = t
    periods.value = p.map((x) => ({ ...x }))
    weeks.value = (c.weeks ?? []).map((x) => ({ ...x }))
    dates.value = (c.dates ?? []).map((x) => ({ ...x }))
    constraints.value = k
    resetTermForm()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(() => props.semesterId, load)

/* ---------------- 学期参数 ---------------- */
const termForm = ref({
  teachingWeeks: '16',
  firstWeekStart: '',
  teachingDays: [1, 2, 3, 4, 5] as number[],
  periodsPerDay: '10',
  defaultTravelMinutes: '30',
  maxRoomCandidates: '12',
})
const termBusy = ref(false)

function resetTermForm() {
  const c = term.value?.config
  termForm.value = {
    teachingWeeks: String(c?.teachingWeeks ?? 16),
    firstWeekStart: c?.firstWeekStart ?? '',
    teachingDays: [...(c?.teachingDays ?? [1, 2, 3, 4, 5])],
    periodsPerDay: String(c?.periodsPerDay ?? 10),
    defaultTravelMinutes: c?.defaultTravelMinutes == null ? '30' : String(c.defaultTravelMinutes),
    maxRoomCandidates: c?.maxRoomCandidates == null ? '12' : String(c.maxRoomCandidates),
  }
}

function toggleDay(day: number) {
  const set = new Set(termForm.value.teachingDays)
  if (set.has(day)) set.delete(day)
  else set.add(day)
  termForm.value.teachingDays = [...set].sort()
}

async function saveTerm() {
  if (!props.semesterId) return
  if (!termForm.value.teachingDays.length) {
    toast.push('至少选择一个教学日', 'danger')
    return
  }
  termBusy.value = true
  try {
    await schedulingApi.putTermConfig(props.semesterId, {
      teachingWeeks: Number(termForm.value.teachingWeeks),
      firstWeekStart: termForm.value.firstWeekStart,
      teachingDays: termForm.value.teachingDays,
      periodsPerDay: Number(termForm.value.periodsPerDay),
      defaultTravelMinutes: Number(termForm.value.defaultTravelMinutes) || 0,
      maxRoomCandidates: Number(termForm.value.maxRoomCandidates) || 12,
    })
    toast.push('学期参数已保存，需要重新排课才会生效', 'success')
    await load()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    termBusy.value = false
  }
}

/* ---------------- 节次作息 ---------------- */
const periodsBusy = ref(false)

function addPeriod() {
  const next = periods.value.length ? Math.max(...periods.value.map((p) => p.periodNo)) + 1 : 1
  periods.value = [...periods.value, { periodNo: next, startTime: '08:00', endTime: '08:50', section: 'MORNING' }]
}

function removePeriod(index: number) {
  periods.value = periods.value.filter((_, i) => i !== index)
}

async function savePeriods() {
  const sorted = [...periods.value].sort((a, b) => a.periodNo - b.periodNo)
  if (!sorted.length) {
    toast.push('至少保留一个节次', 'danger')
    return
  }
  const bad = sorted.find((p) => !p.startTime || !p.endTime || p.endTime <= p.startTime)
  if (bad) {
    toast.push(`第 ${bad.periodNo} 节的结束时间必须晚于开始时间`, 'danger')
    return
  }
  periodsBusy.value = true
  try {
    periods.value = await schedulingApi.putPeriods(props.semesterId, sorted.map((p) => ({
      periodNo: p.periodNo,
      startTime: p.startTime.length === 5 ? `${p.startTime}:00` : p.startTime,
      endTime: p.endTime.length === 5 ? `${p.endTime}:00` : p.endTime,
      section: p.section,
    })))
    toast.push('节次作息已保存', 'success')
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    periodsBusy.value = false
  }
}

/* ---------------- 教学日历 ---------------- */
const calendarBusy = ref(false)

function addWeek() {
  const total = Number(termForm.value.teachingWeeks) || 16
  const next = Math.min(total, weeks.value.length ? Math.max(...weeks.value.map((w) => w.weekNo)) + 1 : 1)
  weeks.value = [...weeks.value, { weekNo: next, weekType: 'EXAM', teachingAllowed: false, note: '' }]
}

function addDate() {
  dates.value = [...dates.value, {
    eventDate: termForm.value.firstWeekStart || '',
    dateType: 'HOLIDAY',
    followDayOfWeek: null,
    followDate: null,
    name: '',
  }]
}

async function saveCalendar() {
  const badDate = dates.value.find((d) => !d.eventDate)
  if (badDate) {
    toast.push('特殊日期必须填写日期', 'danger')
    return
  }
  const weekNos = weeks.value.map((w) => Number(w.weekNo))
  if (new Set(weekNos).size !== weekNos.length) {
    toast.push('特殊教学周的周次不能重复', 'danger')
    return
  }
  const eventDates = dates.value.map((d) => d.eventDate)
  if (new Set(eventDates).size !== eventDates.length) {
    toast.push('同一天只能有一条特殊日期记录', 'danger')
    return
  }
  const badWorkday = dates.value.find((d) => d.dateType === 'WORKDAY' && d.followDayOfWeek == null)
  if (badWorkday) {
    toast.push(`调休上课日 ${badWorkday.eventDate} 必须选择按星期几上课`, 'danger')
    return
  }
  const badMakeup = dates.value.find((d) => d.dateType === 'MAKEUP' && !d.followDate)
  if (badMakeup) {
    toast.push(`补课日 ${badMakeup.eventDate} 必须选择补哪一天的课`, 'danger')
    return
  }
  calendarBusy.value = true
  try {
    const result = await schedulingApi.putCalendar(props.semesterId, {
      weeks: weeks.value.map((w) => ({
        weekNo: Number(w.weekNo),
        weekType: w.weekType,
        teachingAllowed: w.teachingAllowed,
        note: w.note || null,
      })),
      dates: dates.value.map((d) => ({
        eventDate: d.eventDate,
        dateType: d.dateType,
        followDayOfWeek: d.followDayOfWeek == null ? null : Number(d.followDayOfWeek),
        followDate: d.followDate || null,
        name: d.name || '未命名',
      })),
    })
    weeks.value = result.weeks ?? []
    dates.value = result.dates ?? []
    toast.push('教学日历已保存', 'success')
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    calendarBusy.value = false
  }
}

/* ---------------- 约束权重 ---------------- */
const weightsBusy = ref(false)
const softConstraints = computed(() => constraints.value.filter((c) => !c.hard))
const hardConstraints = computed(() => constraints.value.filter((c) => c.hard))

function resetWeights() {
  constraints.value = constraints.value.map((c) => (c.hard ? c : { ...c, enabled: true, weight: c.defaultWeight }))
}

async function saveWeights() {
  weightsBusy.value = true
  try {
    constraints.value = await schedulingApi.putConstraints(
      props.semesterId,
      softConstraints.value.map((c) => ({ code: c.code, enabled: c.enabled, weight: Number(c.weight) })),
    )
    toast.push('权重已保存，需要重新排课才会生效', 'success')
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    weightsBusy.value = false
  }
}

/** 教师集中排课的快捷预设：同一天 / 同一个半天，外聘教师常用 */
const COMPACT_CODES = { sameDay: 'TEACHER_COMPACT', halfDay: 'TEACHER_HALF_DAY' }

function applyCompactPreset(target: 'spread' | 'sameDay' | 'halfDay') {
  const preset =
    target === 'spread'
      ? { sameDay: 2, halfDay: 0 }
      : target === 'sameDay'
        ? { sameDay: 8, halfDay: 2 }
        : { sameDay: 6, halfDay: 9 }
  constraints.value = constraints.value.map((c) => {
    if (c.code === COMPACT_CODES.sameDay) return { ...c, enabled: preset.sameDay > 0, weight: preset.sameDay }
    if (c.code === COMPACT_CODES.halfDay) return { ...c, enabled: preset.halfDay > 0, weight: preset.halfDay }
    return c
  })
  toast.push('已套用预设，点「保存权重」后生效', 'info')
}

/* ---------------- 教师时间要求（仅管理员） ---------------- */
const teachers = ref<SchedulingTeacher[]>([])
const availTeacherId = ref<number | null>(null)
const avail = ref<{ days: number[]; sections: string[] }>({ days: [], sections: [] })
const availBusy = ref(false)
const availLoading = ref(false)

const teacherOptions = computed(() =>
  teachers.value.map((t) => ({ value: String(t.id), label: t.displayName })),
)

const availTeacherSelect = computed({
  get: () => (availTeacherId.value ? String(availTeacherId.value) : ''),
  set: (v: string) => {
    availTeacherId.value = v ? Number(v) : null
  },
})

/** 「有限制」时至少勾了星期或半天；都不勾表示该教师不限时间 */
const availRestricted = computed(() => avail.value.days.length > 0 || avail.value.sections.length > 0)

async function loadTeachers() {
  if (teachers.value.length) return
  try {
    teachers.value = await schedulingApi.teachers()
    if (availTeacherId.value == null && teachers.value.length) {
      availTeacherId.value = teachers.value[0]?.id ?? null
    }
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  }
}

async function loadAvailability() {
  if (!props.semesterId || availTeacherId.value == null) return
  availLoading.value = true
  try {
    const v = await schedulingApi.teacherAvailability(props.semesterId, availTeacherId.value)
    avail.value = { days: [...(v.days ?? [])], sections: [...(v.sections ?? [])] }
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    availLoading.value = false
  }
}

function toggleAvailDay(day: number) {
  const set = new Set(avail.value.days)
  if (set.has(day)) set.delete(day)
  else set.add(day)
  avail.value.days = [...set].sort((a, b) => a - b)
}

function toggleAvailSection(value: string) {
  const set = new Set(avail.value.sections)
  if (set.has(value)) set.delete(value)
  else set.add(value)
  avail.value.sections = SECTIONS.filter((s) => set.has(s.value)).map((s) => s.value)
}

async function saveAvailability() {
  if (!props.semesterId || availTeacherId.value == null) return
  availBusy.value = true
  try {
    const v = await schedulingApi.putTeacherAvailability(props.semesterId, availTeacherId.value, {
      days: avail.value.days,
      sections: avail.value.sections,
    })
    avail.value = { days: [...(v.days ?? [])], sections: [...(v.sections ?? [])] }
    toast.push(
      availRestricted.value
        ? '教师时间要求已保存：该教师的课只会排在其可来时间内，需重新排课生效'
        : '已清除限制，该教师恢复为不限时间',
      'success',
    )
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    availBusy.value = false
  }
}

async function clearAvailability() {
  avail.value = { days: [], sections: [] }
  await saveAvailability()
}

watch(section, (s) => {
  if (s === 'teacher') {
    void loadTeachers().then(loadAvailability)
  }
})
watch(availTeacherId, () => {
  if (section.value === 'teacher') void loadAvailability()
})
watch(() => props.semesterId, () => {
  if (section.value === 'teacher') void loadAvailability()
})
</script>

<template>
  <section class="sp">
    <nav class="sp__tabs" aria-label="排课设置分类">
      <button
        v-for="s in sections"
        :key="s.key"
        type="button"
        :class="{ 'is-on': section === s.key }"
        @click="section = s.key"
      >
        <span class="sp__tab-label">{{ s.label }}</span>
        <span class="sp__tab-latin">{{ s.latin }}</span>
      </button>
    </nav>

    <UiEmpty v-if="loading && !term" title="正在读取设置" />

    <!-- 学期参数 -->
    <template v-else-if="section === 'term'">
      <p class="sp__hint">
        教学周数、开学日、教学日、每日节数决定「有哪些可用的时间格」，改动后必须重新求解；已发布课表的学期需先撤销发布。
      </p>
      <div class="sp__grid">
        <UiField v-model="termForm.teachingWeeks" label="教学周数" inputmode="numeric" />
        <UiField v-model="termForm.firstWeekStart" label="第 1 周周一" type="date" />
        <UiField v-model="termForm.periodsPerDay" label="每日节数" inputmode="numeric" hint="与节次表一致" />
        <UiField v-model="termForm.defaultTravelMinutes" label="跨校区通行（分钟）" inputmode="numeric" />
        <UiField v-model="termForm.maxRoomCandidates" label="每个课次最多候选教室" inputmode="numeric" hint="越大越慢" />
      </div>
      <div class="sp__days">
        <span class="sp__label">教学日</span>
        <button
          v-for="d in DAY_OPTIONS"
          :key="d.value"
          type="button"
          class="sp__day"
          :class="{ 'is-on': termForm.teachingDays.includes(d.value) }"
          @click="toggleDay(d.value)"
        >
          {{ d.label }}
        </button>
      </div>
      <div class="sp__foot">
        <UiButton v-if="props.canManage" @click="resetTermForm">重置</UiButton>
        <UiButton v-if="props.canManage" variant="primary" :loading="termBusy" @click="saveTerm">保存学期参数</UiButton>
      </div>
    </template>

    <!-- 节次作息 -->
    <template v-else-if="section === 'periods'">
      <p class="sp__hint">
        同一「时段」内且编号连续的节次才允许连堂；跨上午/下午的连堂会被判为非法。
      </p>
      <table class="sp__table">
        <thead>
          <tr>
            <th>节次</th>
            <th>开始</th>
            <th>结束</th>
            <th>时段</th>
            <th />
          </tr>
        </thead>
        <tbody>
          <tr v-for="(p, i) in periods" :key="i">
            <td><input v-model.number="p.periodNo" class="sp__mini mono" type="number" min="1" max="16" /></td>
            <td><input v-model="p.startTime" class="sp__mini mono" type="time" /></td>
            <td><input v-model="p.endTime" class="sp__mini mono" type="time" /></td>
            <td>
              <select v-model="p.section" class="sp__mini">
                <option v-for="s in SECTIONS" :key="s.value" :value="s.value">{{ s.label }}</option>
              </select>
            </td>
            <td class="sp__ops">
              <UiButton v-if="props.canManage" size="sm" variant="link" @click="removePeriod(i)">删除</UiButton>
            </td>
          </tr>
        </tbody>
      </table>
      <div class="sp__foot">
        <UiButton v-if="props.canManage" @click="addPeriod">添加一节</UiButton>
        <UiButton v-if="props.canManage" variant="primary" :loading="periodsBusy" @click="savePeriods">保存作息</UiButton>
      </div>
    </template>

    <!-- 教学日历 -->
    <template v-else-if="section === 'calendar'">
      <p class="sp__hint">
        特殊教学周（考试周/实践周）不排理论课；节假日、停课、调休上课、补课按日期影响具体周次。
      </p>

      <div class="sp__sub-head">
        <p class="sp__label">特殊教学周</p>
        <UiButton v-if="props.canManage" size="sm" @click="addWeek">添加</UiButton>
      </div>
      <p v-if="!weeks.length" class="sp__empty">没有特殊周，全部按正常教学周处理。</p>
      <table v-else class="sp__table">
        <thead>
          <tr>
            <th>周次</th>
            <th>类型</th>
            <th>是否排课</th>
            <th>备注</th>
            <th />
          </tr>
        </thead>
        <tbody>
          <tr v-for="(w, i) in weeks" :key="i">
            <td><input v-model.number="w.weekNo" class="sp__mini mono" type="number" min="1" max="30" /></td>
            <td>
              <select v-model="w.weekType" class="sp__mini">
                <option v-for="t in WEEK_TYPES" :key="t.value" :value="t.value">{{ t.label }}</option>
              </select>
            </td>
            <td>
              <label class="sp__check">
                <input v-model="w.teachingAllowed" type="checkbox" />
                <span>{{ w.teachingAllowed ? '排课' : '不排课' }}</span>
              </label>
            </td>
            <td><input v-model="w.note" class="sp__mini sp__mini--wide" type="text" maxlength="60" /></td>
            <td class="sp__ops">
              <UiButton v-if="props.canManage" size="sm" variant="link" @click="weeks = weeks.filter((_, k) => k !== i)">删除</UiButton>
            </td>
          </tr>
        </tbody>
      </table>

      <div class="sp__sub-head">
        <p class="sp__label">特殊日期</p>
        <UiButton v-if="props.canManage" size="sm" @click="addDate">添加</UiButton>
      </div>
      <p v-if="!dates.length" class="sp__empty">没有特殊日期，节假日请在学期参数里一次性排除或在此逐条添加。</p>
      <table v-else class="sp__table">
        <thead>
          <tr>
            <th>日期</th>
            <th>类型</th>
            <th>调休 / 补课</th>
            <th>名称</th>
            <th />
          </tr>
        </thead>
        <tbody>
          <tr v-for="(d, i) in dates" :key="i">
            <td><input v-model="d.eventDate" class="sp__mini mono" type="date" /></td>
            <td>
              <select v-model="d.dateType" class="sp__mini">
                <option v-for="t in DATE_TYPES" :key="t.value" :value="t.value">{{ t.label }}</option>
              </select>
            </td>
            <td>
              <select
                v-if="d.dateType === 'WORKDAY'"
                v-model="d.followDayOfWeek"
                class="sp__mini"
              >
                <option :value="null">按周几</option>
                <option v-for="d2 in DAY_OPTIONS" :key="d2.value" :value="d2.value">{{ d2.label }}</option>
              </select>
              <input
                v-else-if="d.dateType === 'MAKEUP'"
                v-model="d.followDate"
                class="sp__mini mono"
                type="date"
                title="补哪一天的课"
              />
              <span v-else class="sp__sub">—</span>
            </td>
            <td><input v-model="d.name" class="sp__mini sp__mini--wide" type="text" maxlength="40" /></td>
            <td class="sp__ops">
              <UiButton v-if="props.canManage" size="sm" variant="link" @click="dates = dates.filter((_, k) => k !== i)">删除</UiButton>
            </td>
          </tr>
        </tbody>
      </table>

      <div class="sp__foot">
        <UiButton v-if="props.canManage" variant="primary" :loading="calendarBusy" @click="saveCalendar">保存教学日历</UiButton>
      </div>
    </template>

    <!-- 约束权重 -->
    <template v-else-if="section === 'weights'">
      <p class="sp__hint">
        硬约束不可关闭，只能靠修正数据解决；软约束权重越大越优先满足，权重为 0 且关闭后该项不参与目标函数。
      </p>
      <div class="sp__preset">
        <span class="sp__label">教师集中排课</span>
        <button type="button" class="sp__day" :disabled="!props.canManage" @click="applyCompactPreset('spread')">
          分散（默认）
        </button>
        <button type="button" class="sp__day" :disabled="!props.canManage" @click="applyCompactPreset('sameDay')">
          集中到同一天
        </button>
        <button type="button" class="sp__day" :disabled="!props.canManage" @click="applyCompactPreset('halfDay')">
          集中到同一个半天
        </button>
        <span class="sp__preset-hint">
          「同一天」调 TEACHER_COMPACT，「同一个半天」调 TEACHER_HALF_DAY；外聘教师只能来一两天时用后者
        </span>
      </div>
      <div class="sp__tags">
        <UiTag tone="ochre">硬约束 {{ hardConstraints.length }} 项</UiTag>
        <UiTag tone="muted">可配置软约束 {{ softConstraints.length }} 项</UiTag>
      </div>
      <table class="sp__table">
        <thead>
          <tr>
            <th>软约束</th>
            <th>启用</th>
            <th>权重</th>
            <th>默认</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in softConstraints" :key="c.code">
            <td>
              {{ c.label }}
              <p class="sp__code mono">{{ c.code }}</p>
            </td>
            <td>
              <label class="sp__check">
                <input v-model="c.enabled" type="checkbox" :disabled="!props.canManage" />
                <span>{{ c.enabled ? '参与' : '关闭' }}</span>
              </label>
            </td>
            <td>
              <input v-model.number="c.weight" class="sp__mini mono" type="number" min="0" max="1000" :disabled="!props.canManage" />
            </td>
            <td class="mono sp__sub">{{ c.defaultWeight }}</td>
          </tr>
        </tbody>
      </table>
      <details class="sp__hard">
        <summary>查看硬约束清单（{{ hardConstraints.length }} 项，不可配置）</summary>
        <ul>
          <li v-for="c in hardConstraints" :key="c.code">
            <span class="mono">{{ c.code }}</span> · {{ c.label }}
          </li>
        </ul>
      </details>
      <div class="sp__foot">
        <UiButton v-if="props.canManage" @click="resetWeights">恢复默认</UiButton>
        <UiButton v-if="props.canManage" variant="primary" :loading="weightsBusy" @click="saveWeights">保存权重</UiButton>
      </div>
    </template>

    <!-- 教师时间要求（仅管理员） -->
    <template v-else-if="section === 'teacher'">
      <p class="sp__hint">
        外聘教师往往只能某一天来，或只能来半天。这里设的是<b>硬性可来时间窗</b>：求解时该教师的课必须整体落在窗口内，
        排不进去会在复核里报「不在教师可来时间」。只有管理员能改，教师本人改不了。
      </p>

      <div class="sp__grid">
        <UiSelect v-model="availTeacherSelect" label="教师" :options="teacherOptions" placeholder="选择教师" />
      </div>

      <div class="sp__days">
        <span class="sp__label">可来的星期</span>
        <button
          v-for="d in DAY_OPTIONS"
          :key="`avail-${d.value}`"
          type="button"
          class="sp__day"
          :class="{ 'is-on': avail.days.includes(d.value) }"
          :disabled="!props.canManage"
          @click="toggleAvailDay(d.value)"
        >
          {{ d.label }}
        </button>
      </div>

      <div class="sp__days">
        <span class="sp__label">可来的半天</span>
        <button
          v-for="s in SECTIONS"
          :key="`avail-${s.value}`"
          type="button"
          class="sp__day"
          :class="{ 'is-on': avail.sections.includes(s.value) }"
          :disabled="!props.canManage"
          @click="toggleAvailSection(s.value)"
        >
          {{ s.label }}
        </button>
      </div>

      <p class="sp__hint">
        <template v-if="availLoading">正在读取该教师的时间要求…</template>
        <template v-else-if="!availRestricted">
          当前不作限制（任何教学日、任何节次都可以排）。
        </template>
        <template v-else>
          当前要求：{{ avail.days.length ? avail.days.map((d) => DAY_OPTIONS.find((o) => o.value === d)?.label).join('、') : '每个教学日' }}
          ·
          {{ avail.sections.length ? SECTIONS.filter((s) => avail.sections.includes(s.value)).map((s) => s.label).join('、') : '全天' }}
        </template>
      </p>

      <div class="sp__foot">
        <UiButton v-if="props.canManage" :disabled="!availTeacherId || availBusy" @click="clearAvailability">
          清除限制
        </UiButton>
        <UiButton
          v-if="props.canManage"
          variant="primary"
          :loading="availBusy"
          :disabled="!availTeacherId"
          @click="saveAvailability"
        >
          保存教师时间
        </UiButton>
      </div>
    </template>

    <!-- 时间规则 -->
    <RulePanel v-else-if="section === 'rules'" :semester-id="semesterId" :can-manage="canManage" />
  </section>
</template>

<style scoped>
.sp {
  display: grid;
  gap: var(--s-4);
}

.sp__tabs {
  display: flex;
  flex-wrap: wrap;
  border-bottom: 1px solid var(--ink);
}

.sp__tabs button {
  display: grid;
  gap: 1px;
  padding: 7px var(--s-4) 8px;
  border: 0;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  background: none;
  color: var(--ink-2);
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.sp__tabs button.is-on {
  border-bottom-color: var(--teal);
  color: var(--ink);
}

.sp__tab-label {
  font-size: 14px;
  font-weight: 600;
}

.sp__tab-latin {
  font-size: 10px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--ink-2);
}

.sp__hint {
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.7;
}

.sp__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: var(--s-3) var(--s-4);
}

.sp__days {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

/* 教师集中排课预设 */
.sp__preset {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  padding: 10px 12px;
  border-left: 3px solid var(--teal);
  background: var(--paper-lift);
}

.sp__preset-hint {
  flex: 1 1 100%;
  color: var(--ink-2);
  font-size: 12px;
  line-height: 1.6;
}

.sp__label {
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
  font-weight: 600;
  letter-spacing: 0.08em;
}

.sp__day {
  padding: 5px 12px;
  border: var(--hairline);
  background: var(--paper);
  color: var(--ink-2);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}

.sp__day.is-on {
  border-color: var(--teal);
  color: var(--teal);
  font-weight: 600;
}

.sp__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.sp__table th {
  padding: 8px 8px;
  border-bottom: 1px solid var(--ink);
  color: var(--ink-2);
  font-size: var(--fs-small);
  font-weight: 600;
  text-align: left;
  white-space: nowrap;
}

.sp__table td {
  padding: 6px 8px;
  border-bottom: var(--hairline);
}

.sp__mini {
  width: 92px;
  height: 32px;
  padding: 0 6px;
  border: var(--hairline);
  background: var(--paper);
  color: var(--ink);
  font: inherit;
  font-size: 13px;
}

.sp__mini--wide {
  width: 100%;
  min-width: 160px;
}

.sp__ops {
  text-align: right;
  white-space: nowrap;
}

.sp__foot {
  display: flex;
  justify-content: flex-end;
  gap: var(--s-2);
  flex-wrap: wrap;
}

.sp__sub-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  margin-top: var(--s-2);
}

.sp__empty {
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.sp__check {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  cursor: pointer;
}

.sp__tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.sp__code {
  margin: 2px 0 0;
  color: var(--ink-2);
  font-size: 11px;
}

.sp__sub {
  color: var(--ink-2);
}

.sp__hard {
  border-top: var(--hairline);
  padding-top: var(--s-2);
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.sp__hard summary {
  cursor: pointer;
}

.sp__hard ul {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 4px 16px;
  margin: var(--s-2) 0 0;
  padding-left: var(--s-4);
  font-size: 12px;
}

@media (max-width: 760px) {
  .sp__tabs {
    flex-wrap: wrap;
  }

  .sp__tabs button {
    flex: 1 1 auto;
    min-width: 30%;
    padding: 6px 10px 7px;
  }

  .sp__table thead {
    display: none;
  }

  .sp__table tr {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 4px;
    padding: var(--s-2) 0;
    border-bottom: var(--hairline);
  }

  .sp__table td {
    padding: 2px 0;
    border: 0;
  }

  .sp__ops {
    grid-column: 1 / -1;
    text-align: left;
  }

  .sp__mini {
    width: 100%;
  }
}
</style>
