<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import {
  attendanceApi,
  leaveApi,
  selectionApi,
  semesterApi,
  settingApi,
} from '@/api/endpoints'
import type {
  AttendanceSessionDetailView,
  AttendanceSessionView,
  AttendanceStatus,
  ClassAttendanceStatsView,
  ClassOptionView,
  ClassTimetableCourse,
  ClassTimetableView,
  AppealView,
  LeaveFlowView,
  LeaveView,
  MyAttendanceView,
  SelectionView,
  SemesterView,
} from '@/api/types'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { formatDateTime } from '@/lib/time'
import {
  APPEAL_STATUS_LABEL,
  ATTENDANCE_SESSION_LABEL,
  ATTENDANCE_STATUS_LABEL,
  LEAVE_STAGE_LABEL,
  LEAVE_STATUS_LABEL,
  WEEKDAY_LABEL,
  appealTone,
  attendanceTone,
  leaveTone,
} from '@/lib/labels'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiEmpty from '@/components/ui/UiEmpty.vue'
import UiField from '@/components/ui/UiField.vue'
import UiPageHeader from '@/components/ui/UiPageHeader.vue'
import UiPager from '@/components/ui/UiPager.vue'
import UiSelect from '@/components/ui/UiSelect.vue'
import UiTag from '@/components/ui/UiTag.vue'
import UiTextarea from '@/components/ui/UiTextarea.vue'

/**
 * 请假与考勤：
 * 学生提交/撤销/销假；教师与导员按设置的两级流程审批；学生会、教师、管理员可查询；
 * 任课教师、导员与管理员可按班级课表登记每节课的考勤（旷课）。
 */
const auth = useAuthStore()
const toast = useToastStore()
const route = useRoute()
const router = useRouter()

type TabKey = 'mine' | 'review' | 'query' | 'attendance' | 'mine-attendance' | 'appeals'

const canSubmit = computed(() => auth.can('leave:submit'))
const canReview = computed(() => auth.can('leave:review', 'leave:manage_all'))
const canReadAll = computed(() => auth.can('leave:read_all'))
const canAttendance = computed(() => auth.can('attendance:read', 'attendance:manage'))
const canManageAttendance = computed(() => auth.can('attendance:manage'))
const canAppeal = computed(() => auth.can('attendance:appeal'))

const tabs = computed(() => {
  const list: { key: TabKey; label: string }[] = []
  if (canSubmit.value) list.push({ key: 'mine', label: '我的请假' })
  if (canReview.value) list.push({ key: 'review', label: '待我审批' })
  if (canReadAll.value) list.push({ key: 'query', label: '请假查询' })
  if (canAttendance.value) list.push({ key: 'attendance', label: '班级考勤' })
  if (canAppeal.value) list.push({ key: 'mine-attendance', label: '我的考勤' })
  if (canManageAttendance.value) list.push({ key: 'appeals', label: '考勤申诉' })
  return list
})

const active = ref<TabKey>('mine')
const flow = ref<LeaveFlowView | null>(null)

function pickTab() {
  const q = String(route.query.tab ?? '') as TabKey
  active.value = tabs.value.some((t) => t.key === q) ? q : (tabs.value[0]?.key ?? 'mine')
}

function go(key: TabKey) {
  active.value = key
  void router.replace({ query: { ...route.query, tab: key } })
}

const flowText = computed(() => {
  if (!flow.value) return '任课教师 → 导员'
  return flow.value.stages.map((s) => LEAVE_STAGE_LABEL[s] ?? s).join(' → ')
})

/* ---------------- 我的请假 ---------------- */

const mine = ref<LeaveView[]>([])
const mineStatus = ref('')
const mineBusy = ref(false)
const statusOptions = [
  { value: '', label: '全部状态' },
  { value: 'PENDING', label: '审批中' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'CANCELLED', label: '已撤销' },
]

async function loadMine() {
  mineBusy.value = true
  try {
    const res = await leaveApi.mine({ size: 50, ...(mineStatus.value ? { status: mineStatus.value } : {}) })
    mine.value = res.items
  } finally {
    mineBusy.value = false
  }
}

/* ---------------- 提交 ---------------- */

const selections = ref<SelectionView[]>([])
const createOpen = ref(false)
const createBusy = ref(false)
const form = ref({ courseId: '', startAt: '', endAt: '', reason: '' })
const formError = ref<{ courseId?: string | null; startAt?: string | null; endAt?: string | null; reason?: string | null }>({})

const courseOptions = computed(() =>
  selections.value
    .filter((s) => s.status === 'SELECTED')
    .map((s) => ({ value: String(s.courseId), label: `${s.courseName}（${s.courseCode}）` })),
)

async function openCreate() {
  form.value = { courseId: '', startAt: '', endAt: '', reason: '' }
  formError.value = {}
  createOpen.value = true
  try {
    selections.value = await selectionApi.mine()
    const first = courseOptions.value[0]
    if (first) form.value.courseId = first.value
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  }
}

async function submitCreate() {
  formError.value = {}
  if (!form.value.courseId) {
    formError.value = { courseId: '请选择请假课程' }
    return
  }
  if (!form.value.startAt || !form.value.endAt) {
    formError.value = { startAt: '请填写开始与结束时间' }
    return
  }
  if (form.value.endAt <= form.value.startAt) {
    formError.value = { endAt: '结束时间必须晚于开始时间' }
    return
  }
  if (!form.value.reason.trim()) {
    formError.value = { reason: '请写明请假原因' }
    return
  }
  createBusy.value = true
  try {
    await leaveApi.create({
      courseId: Number(form.value.courseId),
      startAt: form.value.startAt,
      endAt: form.value.endAt,
      reason: form.value.reason.trim(),
    })
    createOpen.value = false
    toast.push(`已提交，审批流程：${flowText.value}`, 'success')
    active.value = 'mine'
    await loadMine()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    createBusy.value = false
  }
}

/* ---------------- 撤销 / 销假 ---------------- */

const revokeTarget = ref<LeaveView | null>(null)
const revokeNote = ref('')
const revokeBusy = ref(false)

async function cancel(item: LeaveView) {
  try {
    await leaveApi.cancel(item.id)
    toast.push('已撤销', 'success')
    await refreshCurrent()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  }
}

async function submitRevoke() {
  if (!revokeTarget.value) return
  revokeBusy.value = true
  try {
    await leaveApi.revoke(revokeTarget.value.id, revokeNote.value.trim() || undefined)
    revokeTarget.value = null
    revokeNote.value = ''
    toast.push('已销假', 'success')
    await refreshCurrent()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    revokeBusy.value = false
  }
}

/* ---------------- 待审批 ---------------- */

const pending = ref<LeaveView[]>([])
const pendingBusy = ref(false)
const decide = ref<{ item: LeaveView; mode: 'approve' | 'reject' } | null>(null)
const comment = ref('')
const decideBusy = ref(false)

async function loadPending() {
  pendingBusy.value = true
  try {
    const res = await leaveApi.toReview({ status: 'PENDING', size: 100 })
    pending.value = res.items
  } finally {
    pendingBusy.value = false
  }
}

function openDecide(item: LeaveView, mode: 'approve' | 'reject') {
  decide.value = { item, mode }
  comment.value = ''
}

async function submitDecide() {
  if (!decide.value) return
  const { item, mode } = decide.value
  if (mode === 'reject' && !comment.value.trim()) {
    toast.push('驳回时请写明理由，便于学生理解', 'danger')
    return
  }
  decideBusy.value = true
  try {
    if (mode === 'approve') await leaveApi.approve(item.id, comment.value.trim() || undefined)
    else await leaveApi.reject(item.id, comment.value.trim())
    decide.value = null
    toast.push(mode === 'approve' ? '已通过' : '已驳回', 'success')
    await Promise.all([loadPending(), canSubmit.value ? loadMine() : Promise.resolve()])
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    decideBusy.value = false
  }
}

/* ---------------- 请假查询 ---------------- */

const queryList = ref<LeaveView[]>([])
const queryStatus = ref('')
const queryClassId = ref('')
const queryPage = ref(1)
const queryTotal = ref(0)
const queryPages = ref(0)
const queryBusy = ref(false)
const classes = ref<ClassOptionView[]>([])

const classOptions = computed(() => classes.value.map((c) => ({ value: String(c.id), label: c.name })))

function nextStageLabel(leave: LeaveView): string {
  const role = leave.approvalOrder[leave.currentStage]
  return role ? (LEAVE_STAGE_LABEL[role] ?? role) : '下一级'
}

async function loadQuery() {
  queryBusy.value = true
  try {
    const res = await leaveApi.all({
      page: queryPage.value,
      size: 20,
      ...(queryStatus.value ? { status: queryStatus.value } : {}),
      ...(queryClassId.value ? { classId: Number(queryClassId.value) } : {}),
    })
    queryList.value = res.items
    queryTotal.value = res.total
    queryPages.value = res.pages
  } finally {
    queryBusy.value = false
  }
}

function onQueryPage(p: number) {
  queryPage.value = p
  void loadQuery()
}

async function loadClasses() {
  if (classes.value.length) return
  try {
    classes.value = await attendanceApi.classes()
  } catch {
    classes.value = []
  }
}

/* ---------------- 班级考勤 ---------------- */

const semesters = ref<SemesterView[]>([])
const attClassId = ref('')
const attSemesterId = ref('')
const timetable = ref<ClassTimetableView | null>(null)
const timetableBusy = ref(false)
const timetableError = ref<string | null>(null)

const semesterOptions = computed(() =>
  semesters.value.map((s) => ({ value: String(s.id), label: s.name })),
)

const selectedCourse = ref<ClassTimetableCourse | null>(null)
const selectedSlot = ref<ClassTimetableCourse['slots'][number] | null>(null)
const sessions = ref<AttendanceSessionView[]>([])
const sessionsBusy = ref(false)

const detail = ref<AttendanceSessionDetailView | null>(null)
const drafts = ref<{ studentId: number; displayName: string; status: AttendanceStatus; remark: string }[]>([])
const editorBusy = ref(false)

const attCreateOpen = ref(false)
const attCreateBusy = ref(false)
const createForm = ref({ sessionDate: '', startPeriod: '1', endPeriod: '1', note: '' })
const statusList: AttendanceStatus[] = ['PRESENT', 'ABSENT', 'LATE', 'LEAVE']

const stats = ref<ClassAttendanceStatsView | null>(null)
const statsBusy = ref(false)

const numberPeriods = Array.from({ length: 12 }, (_, i) => ({ value: String(i + 1), label: `第 ${i + 1} 节` }))

function isoDate(d: Date): string {
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

async function loadTimetable() {
  if (!attClassId.value) {
    timetable.value = null
    return
  }
  timetableBusy.value = true
  timetableError.value = null
  try {
    timetable.value = await attendanceApi.timetable(
      Number(attClassId.value),
      attSemesterId.value ? Number(attSemesterId.value) : null,
    )
  } catch (e) {
    timetableError.value = errorMessage(e)
  } finally {
    timetableBusy.value = false
  }
}

async function pickSlot(course: ClassTimetableCourse, slot: ClassTimetableCourse['slots'][number]) {
  selectedCourse.value = course
  selectedSlot.value = slot
  sessionsBusy.value = true
  try {
    sessions.value = await attendanceApi.sessions(Number(attClassId.value), { courseId: course.courseId })
  } finally {
    sessionsBusy.value = false
  }
}

function nextDateForDow(dow: number): string {
  const now = new Date()
  const todayDow = ((now.getDay() + 6) % 7) + 1
  const diff = (dow - todayDow + 7) % 7
  now.setDate(now.getDate() + diff)
  return isoDate(now)
}

function openCreateSession() {
  if (!selectedCourse.value || !selectedSlot.value) return
  createForm.value = {
    sessionDate: nextDateForDow(selectedSlot.value.dayOfWeek),
    startPeriod: String(selectedSlot.value.startPeriod),
    endPeriod: String(selectedSlot.value.endPeriod),
    note: '',
  }
  attCreateOpen.value = true
}

function createFormDialogClose() {
  attCreateOpen.value = false
}

async function submitCreateSession() {
  if (!selectedCourse.value) return
  attCreateBusy.value = true
  try {
    const res = await attendanceApi.createSession({
      courseId: selectedCourse.value.courseId,
      scheduleId: selectedSlot.value?.scheduleId ?? null,
      classId: Number(attClassId.value),
      sessionDate: createForm.value.sessionDate,
      startPeriod: Number(createForm.value.startPeriod),
      endPeriod: Number(createForm.value.endPeriod),
      note: createForm.value.note.trim() || null,
    })
    attCreateOpen.value = false
    toast.push('已建立考勤，已通过的请假自动标为「请假」', 'success')
    await openEditor(res.session.id)
    await pickSlot(selectedCourse.value, selectedSlot.value!)
    await loadTimetable()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    attCreateBusy.value = false
  }
}

async function openEditor(id: number) {
  editorBusy.value = true
  try {
    const res = await attendanceApi.session(id)
    detail.value = res
    drafts.value = res.records.map((r) => ({
      studentId: r.studentId,
      displayName: r.displayName ?? `#${r.studentId}`,
      status: r.status,
      remark: r.remark ?? '',
    }))
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    editorBusy.value = false
  }
}

function setStatus(studentId: number, status: AttendanceStatus) {
  const row = drafts.value.find((d) => d.studentId === studentId)
  if (row) row.status = status
}

async function saveRecords(finalize = false) {
  if (!detail.value) return
  editorBusy.value = true
  try {
    await attendanceApi.save(
      detail.value.session.id,
      drafts.value.map((d) => ({
        studentId: d.studentId,
        status: d.status,
        remark: d.remark.trim() || null,
      })),
    )
    if (finalize) {
      await attendanceApi.finalize(detail.value.session.id)
      toast.push('考勤已提交', 'success')
    } else {
      toast.push('已保存', 'success')
    }
    const id = detail.value.session.id
    await openEditor(id)
    if (selectedCourse.value) {
      const slot = selectedSlot.value ?? selectedCourse.value.slots[0]
      if (slot) await pickSlot(selectedCourse.value, slot)
    }
    await loadTimetable()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    editorBusy.value = false
  }
}

async function loadStats() {
  if (!attClassId.value) return
  statsBusy.value = true
  try {
    stats.value = await attendanceApi.stats(Number(attClassId.value), {
      semesterId: attSemesterId.value ? Number(attSemesterId.value) : null,
    })
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    statsBusy.value = false
  }
}

/* ---------------- 我的考勤与申诉 ---------------- */

const myAttendance = ref<MyAttendanceView[]>([])
const myAttendanceBusy = ref(false)
const appealTarget = ref<MyAttendanceView | null>(null)
const appealReason = ref('')
const appealTargetStatus = ref<AttendanceStatus>('PRESENT')
const appealBusy = ref(false)

const appeals = ref<AppealView[]>([])
const appealsBusy = ref(false)
const reviewTarget = ref<AppealView | null>(null)
const reviewApprove = ref(true)
const reviewTargetStatus = ref<AttendanceStatus>('PRESENT')
const reviewComment = ref('')
const reviewBusy = ref(false)

async function loadMyAttendance() {
  myAttendanceBusy.value = true
  try {
    myAttendance.value = await attendanceApi.myAttendance()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    myAttendanceBusy.value = false
  }
}

function openAppeal(row: MyAttendanceView) {
  appealTarget.value = row
  appealReason.value = ''
  appealTargetStatus.value = 'PRESENT'
}

async function submitAppeal() {
  if (!appealTarget.value) return
  if (!appealReason.value.trim()) {
    toast.push('请写明情况，便于老师判断', 'danger')
    return
  }
  appealBusy.value = true
  try {
    await attendanceApi.submitAppeal({
      recordId: appealTarget.value.recordId,
      reason: appealReason.value.trim(),
      targetStatus: appealTargetStatus.value,
    })
    appealTarget.value = null
    toast.push('已提交申诉，等待复核', 'success')
    await loadMyAttendance()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    appealBusy.value = false
  }
}

async function loadAppeals() {
  appealsBusy.value = true
  try {
    appeals.value = await attendanceApi.appeals('PENDING')
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    appealsBusy.value = false
  }
}

function openReview(item: AppealView, approve: boolean) {
  reviewTarget.value = item
  reviewApprove.value = approve
  reviewTargetStatus.value = 'PRESENT'
  reviewComment.value = ''
}

async function submitReview() {
  if (!reviewTarget.value) return
  if (!reviewApprove.value && !reviewComment.value.trim()) {
    toast.push('驳回时请写明理由', 'danger')
    return
  }
  reviewBusy.value = true
  try {
    await attendanceApi.decideAppeal(reviewTarget.value.id, {
      approve: reviewApprove.value,
      targetStatus: reviewApprove.value ? reviewTargetStatus.value : undefined,
      comment: reviewComment.value.trim() || null,
    })
    reviewTarget.value = null
    toast.push(reviewApprove.value ? '已通过申诉' : '已驳回申诉', 'success')
    await loadAppeals()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    reviewBusy.value = false
  }
}

async function syncRoster(sessionId: number) {
  try {
    const res = await attendanceApi.syncRoster(sessionId)
    toast.push(`名单已同步，共 ${res.records.length} 人`, 'success')
    await openEditor(sessionId)
    if (selectedCourse.value && selectedSlot.value) await pickSlot(selectedCourse.value, selectedSlot.value)
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  }
}

/* ---------------- 生命周期 ---------------- */

async function refreshCurrent() {
  if (active.value === 'mine') await loadMine()
  else if (active.value === 'review') await loadPending()
  else if (active.value === 'query') await loadQuery()
  else if (active.value === 'mine-attendance') await loadMyAttendance()
  else if (active.value === 'appeals') await loadAppeals()
  else await loadTimetable()
}

async function load() {
  if (active.value === 'mine') await loadMine()
  else if (active.value === 'review') await loadPending()
  else if (active.value === 'query') {
    await Promise.all([loadQuery(), loadClasses()])
  } else if (active.value === 'mine-attendance') {
    await loadMyAttendance()
  } else if (active.value === 'appeals') {
    await loadAppeals()
  } else {
    if (!classes.value.length) await loadClasses()
    if (!semesters.value.length) {
      semesters.value = await semesterApi.list().catch(() => [])
      const activeSem = semesters.value.find((s) => s.status === 'ACTIVE')
      attSemesterId.value = String(activeSem?.id ?? semesters.value[0]?.id ?? '')
    }
    const firstClass = classes.value[0]
    if (!attClassId.value && firstClass) attClassId.value = String(firstClass.id)
    await loadTimetable()
  }
}

onMounted(async () => {
  pickTab()
  flow.value = await settingApi.leaveFlow().catch(() => null)
  await load()
})

watch(
  () => route.query.tab,
  () => pickTab(),
)

watch(active, () => {
  void load()
})

watch(mineStatus, () => {
  if (active.value === 'mine') void loadMine()
})

watch([queryStatus, queryClassId], () => {
  queryPage.value = 1
  if (active.value === 'query') void loadQuery()
})

watch([attClassId, attSemesterId], () => {
  selectedCourse.value = null
  selectedSlot.value = null
  sessions.value = []
  stats.value = null
  if (active.value === 'attendance') void loadTimetable()
})
</script>

<template>
  <div class="leave-page">
    <UiPageHeader
      label="请假"
      latin="Leave"
      title="请假与考勤"
      :lede="`提交后按「${flowText}」顺序审批；通过后返校记得销假。考勤按班级课表逐节登记。`"
    >
      <template #actions>
        <UiButton v-if="canSubmit" variant="primary" @click="openCreate">写一张请假条</UiButton>
      </template>
      <template #meta>
        <span v-if="active === 'mine' && canSubmit" class="mono">我的 {{ mine.length }} 条</span>
        <span v-if="active === 'review' && canReview" class="mono">待我审批 {{ pending.length }} 条</span>
        <span v-if="active === 'appeals'" class="mono">待复核 {{ appeals.length }} 条</span>
      </template>
    </UiPageHeader>

    <div class="bar">
      <div class="tabs" role="tablist" aria-label="请假视图">
        <button
          v-for="t in tabs"
          :key="t.key"
          type="button"
          class="tabs__btn"
          role="tab"
          :aria-selected="active === t.key"
          :class="{ 'is-active': active === t.key }"
          @click="go(t.key)"
        >
          {{ t.label }}
        </button>
      </div>
      <UiSelect v-if="active === 'mine'" v-model="mineStatus" label="筛选" :options="statusOptions" class="bar__filter" />
    </div>

    <!-- 我的请假 -->
    <template v-if="active === 'mine'">
      <p v-if="mineBusy" class="state">正在取请假条…</p>
      <UiEmpty
        v-else-if="mine.length === 0"
        title="还没有请假记录"
        :hint="mineStatus ? '这个状态下没有记录，换个筛选看看。' : '需要请假时，选一门已选课程并写明起止时间与原因。'"
      >
        <UiButton v-if="!mineStatus" size="sm" variant="primary" @click="openCreate">写一张</UiButton>
      </UiEmpty>
      <ul v-else class="list">
        <li v-for="l in mine" :key="l.id" class="item">
          <div class="item__main">
            <p class="item__course">
              {{ l.courseName }}<span class="item__code mono">{{ l.courseCode }}</span>
            </p>
            <p class="item__time">
              <span class="mono">{{ formatDateTime(l.startAt) }}</span>
              <span aria-hidden="true">—</span>
              <span class="mono">{{ formatDateTime(l.endAt) }}</span>
              <span v-if="l.className" class="item__class">{{ l.className }}</span>
            </p>
            <p class="item__reason">{{ l.reason }}</p>

            <div class="chain" aria-label="审批进度">
              <span
                v-for="(role, i) in l.approvalOrder"
                :key="role + i"
                class="chain__step"
                :class="{
                  'is-done': l.approvals.some((a) => a.stage === i + 1),
                  'is-current': l.status === 'PENDING' && l.currentStage === i + 1,
                }"
              >
                {{ LEAVE_STAGE_LABEL[role] ?? role }}
              </span>
              <span v-if="l.revokedAt" class="chain__revoked">已销假</span>
            </div>

            <ul v-if="l.approvals.length" class="approvals">
              <li v-for="a in l.approvals" :key="a.stage" class="approval">
                <span class="approval__who">{{ a.approverName ?? '审批人' }}（{{ LEAVE_STAGE_LABEL[a.approverRole] }}）</span>
                <span class="approval__decision" :class="{ 'is-reject': a.decision === 'REJECTED' }">
                  {{ a.decision === 'APPROVED' ? '通过' : '驳回' }}
                </span>
                <span v-if="a.comment" class="approval__comment">{{ a.comment }}</span>
              </li>
            </ul>
            <p v-if="l.revokeNote" class="item__review">销假说明：{{ l.revokeNote }}</p>
          </div>
          <div class="item__side">
            <UiTag :tone="leaveTone(l.status)">{{ LEAVE_STATUS_LABEL[l.status] ?? l.status }}</UiTag>
            <UiButton v-if="l.canCancel" size="sm" @click="cancel(l)">撤销</UiButton>
            <UiButton v-if="l.canRevoke" size="sm" variant="primary" @click="revokeTarget = l; revokeNote = ''">
              销假
            </UiButton>
          </div>
        </li>
      </ul>
    </template>

    <!-- 待审批 -->
    <template v-else-if="active === 'review'">
      <p v-if="pendingBusy" class="state">正在取待审批…</p>
      <UiEmpty
        v-else-if="pending.length === 0"
        title="没有轮到你审批的请假"
        hint="双级审批时，只有当前环节的审批人会看到这张请假条。"
      />
      <ul v-else class="list">
        <li v-for="l in pending" :key="l.id" class="item">
          <div class="item__main">
            <p class="item__course">
              {{ l.studentName }}<span class="item__code mono">{{ l.studentUsername }}</span>
              <span class="item__dot" aria-hidden="true">·</span>
              {{ l.courseName }}
            </p>
            <p class="item__time">
              <span class="mono">{{ formatDateTime(l.startAt) }}</span>
              <span aria-hidden="true">—</span>
              <span class="mono">{{ formatDateTime(l.endAt) }}</span>
              <span class="item__class">{{ LEAVE_STAGE_LABEL[l.currentStageRole] }}审批中 · 第 {{ l.currentStage }}/{{ l.totalStages }} 级</span>
            </p>
            <p class="item__reason">{{ l.reason }}</p>
            <ul v-if="l.approvals.length" class="approvals">
              <li v-for="a in l.approvals" :key="a.stage" class="approval">
                <span class="approval__who">{{ a.approverName ?? '审批人' }}（{{ LEAVE_STAGE_LABEL[a.approverRole] }}）</span>
                <span class="approval__decision" :class="{ 'is-reject': a.decision === 'REJECTED' }">
                  {{ a.decision === 'APPROVED' ? '通过' : '驳回' }}
                </span>
                <span v-if="a.comment" class="approval__comment">{{ a.comment }}</span>
              </li>
            </ul>
          </div>
          <div v-if="l.canReview" class="item__side item__side--row">
            <UiButton size="sm" variant="primary" @click="openDecide(l, 'approve')">通过</UiButton>
            <UiButton size="sm" variant="danger" @click="openDecide(l, 'reject')">驳回</UiButton>
          </div>
        </li>
      </ul>
    </template>

    <!-- 请假查询 -->
    <template v-else-if="active === 'query'">
      <div class="query-bar">
        <UiSelect v-model="queryStatus" label="状态" :options="statusOptions" />
        <UiSelect v-model="queryClassId" label="班级" :options="classOptions" placeholder="全部班级" />
        <p class="query-bar__count mono">{{ queryTotal }} 条</p>
      </div>

      <p v-if="queryBusy" class="state">正在查询…</p>
      <UiEmpty v-else-if="queryList.length === 0" title="没有符合条件的请假" hint="换个状态或班级看看。" />
      <template v-else>
        <div class="table-scroll">
          <table class="table">
          <thead>
            <tr>
              <th scope="col">学生</th>
              <th scope="col">班级</th>
              <th scope="col">课程</th>
              <th scope="col">起止时间</th>
              <th scope="col">状态</th>
              <th scope="col">进度</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="l in queryList" :key="l.id">
              <td>
                <span class="cell-name">{{ l.studentName }}</span>
                <span class="cell-code mono">{{ l.studentUsername }}</span>
              </td>
              <td class="muted">{{ l.className ?? '—' }}</td>
              <td>{{ l.courseName }}</td>
              <td class="mono small">{{ formatDateTime(l.startAt) }}<br />{{ formatDateTime(l.endAt) }}</td>
              <td>
                <UiTag :tone="leaveTone(l.status)">{{ LEAVE_STATUS_LABEL[l.status] }}</UiTag>
                <span v-if="l.revokedAt" class="cell-code">已销假</span>
              </td>
              <td class="mono small">
                第 {{ l.currentStage }}/{{ l.totalStages }} 级
                <span v-if="l.status === 'PENDING'">（{{ LEAVE_STAGE_LABEL[l.currentStageRole] }}）</span>
              </td>
            </tr>
          </tbody>
        </table>
        </div>
        <UiPager
          :page="queryPage"
          :pages="queryPages"
          :total="queryTotal"
          :disabled="queryBusy"
          @change="onQueryPage"
        />
      </template>
    </template>

    <!-- 班级考勤 -->
    <template v-else-if="active === 'attendance'">
      <div class="att-bar">
        <UiSelect v-model="attClassId" label="班级" :options="classOptions" placeholder="选择班级" />
        <UiSelect v-model="attSemesterId" label="学期" :options="semesterOptions" placeholder="当前学期" />
        <UiButton size="sm" :loading="statsBusy" :disabled="!attClassId" @click="loadStats">学生考勤统计</UiButton>
      </div>

      <p v-if="timetableBusy" class="state">正在取班级课表…</p>
      <p v-else-if="timetableError" class="state state--err" role="alert">{{ timetableError }}</p>
      <UiEmpty
        v-else-if="!timetable || timetable.courses.length === 0"
        title="这个班还没有可选课程"
        hint="只有班里学生已选课程才会出现在课表里；先完成选课再来登记考勤。"
      />

      <template v-else>
        <p class="att-summary muted">
          {{ timetable.className }} · {{ timetable.semesterName ?? '当前学期' }} ·
          已登记 <span class="mono">{{ timetable.sessionCount }}</span> 节 ·
          旷课 <span class="mono">{{ timetable.absentCount }}</span> 人次
        </p>

        <div class="courses">
          <article v-for="c in timetable.courses" :key="c.courseId" class="course">
            <header class="course__head">
              <div>
                <h2 class="course__name">{{ c.courseName }}</h2>
                <p class="course__meta mono">{{ c.courseCode }} · {{ c.teacherNames.join('、') || '教师待定' }}</p>
              </div>
              <p class="course__stat mono">
                {{ c.sessionCount }} 节 · 旷课 {{ c.absentCount }}
              </p>
            </header>
            <ul class="slots">
              <li v-for="s in c.slots" :key="s.scheduleId" class="slot">
                <button type="button" class="slot__btn" @click="pickSlot(c, s)">
                  <span class="slot__when mono">
                    {{ WEEKDAY_LABEL[s.dayOfWeek] }} 第 {{ s.startPeriod }}–{{ s.endPeriod }} 节
                  </span>
                  <span class="slot__room muted">{{ s.classroom || '教室待定' }}</span>
                  <span class="slot__stat mono">{{ s.sessionCount }} 节 / 旷 {{ s.absentCount }}</span>
                </button>
              </li>
              <li v-if="c.slots.length === 0" class="slot slot--empty muted">这门课还没有排课</li>
            </ul>
          </article>
        </div>

        <!-- 某节课的考勤记录 -->
        <section v-if="selectedCourse" class="sessions">
          <header class="sessions__head">
            <h2 class="sessions__title">
              {{ selectedCourse.courseName }}
              <span v-if="selectedSlot" class="mono sessions__slot">
                {{ WEEKDAY_LABEL[selectedSlot.dayOfWeek] }} 第 {{ selectedSlot.startPeriod }}–{{ selectedSlot.endPeriod }} 节
              </span>
            </h2>
            <UiButton v-if="canManageAttendance" size="sm" variant="primary" @click="openCreateSession">
              登记这节课
            </UiButton>
          </header>

          <p v-if="sessionsBusy" class="state">正在取考勤…</p>
          <UiEmpty v-else-if="sessions.length === 0" compact title="这节课还没有考勤记录" hint="点「登记这节课」开始点名。" />
          <ul v-else class="session-list">
            <li v-for="s in sessions" :key="s.id" class="session-row">
              <span class="mono">{{ s.sessionDate }} · 第 {{ s.startPeriod }}–{{ s.endPeriod }} 节</span>
              <span class="mono small">出勤 {{ s.presentCount }} · 旷课 {{ s.absentCount }} · 请假 {{ s.leaveCount }}</span>
              <UiTag :tone="s.status === 'FINAL' ? 'success' : 'muted'">{{ ATTENDANCE_SESSION_LABEL[s.status] }}</UiTag>
              <div class="row-actions">
                <UiButton v-if="canManageAttendance" size="sm" variant="link" @click="syncRoster(s.id)">
                  同步名单
                </UiButton>
                <UiButton size="sm" @click="openEditor(s.id)">查看/编辑</UiButton>
              </div>
            </li>
          </ul>
        </section>

        <!-- 学生统计 -->
        <section v-if="stats" class="stats">
          <header class="sessions__head">
            <h2 class="sessions__title">学生考勤统计</h2>
            <p class="mono muted">{{ stats.className }} · {{ stats.sessionCount }} 节</p>
          </header>
          <div class="table-scroll">
            <table class="table">
            <thead>
              <tr>
                <th scope="col">学生</th>
                <th scope="col" class="num">出勤</th>
                <th scope="col" class="num">旷课</th>
                <th scope="col" class="num">迟到</th>
                <th scope="col" class="num">请假</th>
                <th scope="col" class="num">旷课率</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in stats.students" :key="s.studentId">
                <td>
                  <span class="cell-name">{{ s.displayName }}</span>
                  <span class="cell-code mono">{{ s.username }}</span>
                </td>
                <td class="num mono">{{ s.present }}</td>
                <td class="num mono" :class="{ 'is-warn': s.absent > 0 }">{{ s.absent }}</td>
                <td class="num mono">{{ s.late }}</td>
                <td class="num mono">{{ s.leave }}</td>
                <td class="num mono" :class="{ 'is-warn': s.absenceRate >= 20 }">{{ s.absenceRate.toFixed(1) }}%</td>
              </tr>
            </tbody>
          </table>
          </div>
        </section>
      </template>
    </template>

    <!-- 我的考勤 -->
    <template v-else-if="active === 'mine-attendance'">
      <p class="att-summary muted">只有旷课或迟到可以申诉，需在申诉期限内提交；通过后考勤会改为出勤/迟到/请假。</p>
      <p v-if="myAttendanceBusy" class="state">正在取考勤…</p>
      <UiEmpty
        v-else-if="myAttendance.length === 0"
        title="还没有考勤记录"
        hint="任课教师登记考勤后，会按课程与节次出现在这里。"
      />
      <ul v-else class="list">
        <li v-for="r in myAttendance" :key="r.recordId" class="item">
          <div class="item__main">
            <p class="item__course">
              {{ r.courseName }}<span class="item__code mono">{{ r.courseCode }}</span>
            </p>
            <p class="item__time">
              <span class="mono">{{ r.sessionDate }} · 第 {{ r.startPeriod }}–{{ r.endPeriod }} 节</span>
            </p>
            <p v-if="r.remark" class="item__review">{{ r.remark }}</p>
          </div>
          <div class="item__side">
            <UiTag :tone="attendanceTone(r.status)">{{ ATTENDANCE_STATUS_LABEL[r.status] }}</UiTag>
            <UiTag v-if="r.appealStatus" :tone="appealTone(r.appealStatus)">
              {{ APPEAL_STATUS_LABEL[r.appealStatus] }}
            </UiTag>
            <UiButton v-if="r.canAppeal" size="sm" @click="openAppeal(r)">申诉</UiButton>
          </div>
        </li>
      </ul>
    </template>

    <!-- 考勤申诉复核 -->
    <template v-else-if="active === 'appeals'">
      <p v-if="appealsBusy" class="state">正在取申诉…</p>
      <UiEmpty
        v-else-if="appeals.length === 0"
        title="没有待复核的申诉"
        hint="学生提交后，任课教师、导员与管理员会在这里看到。"
      />
      <ul v-else class="list">
        <li v-for="a in appeals" :key="a.id" class="item">
          <div class="item__main">
            <p class="item__course">
              {{ a.studentName }}<span class="item__code mono">{{ a.studentUsername }}</span>
              <span class="item__dot" aria-hidden="true">·</span>{{ a.courseName }}
            </p>
            <p class="item__time">
              <span class="mono">{{ a.sessionDate }} · 第 {{ a.startPeriod }}–{{ a.endPeriod }} 节</span>
              <span class="item__class">
                原 {{ a.originalStatus ? ATTENDANCE_STATUS_LABEL[a.originalStatus] : '—' }} → 申诉改为
                {{ ATTENDANCE_STATUS_LABEL[a.targetStatus] }}
              </span>
            </p>
            <p class="item__reason">{{ a.reason }}</p>
          </div>
          <div class="item__side item__side--row">
            <UiButton size="sm" variant="primary" @click="openReview(a, true)">通过</UiButton>
            <UiButton size="sm" variant="danger" @click="openReview(a, false)">驳回</UiButton>
          </div>
        </li>
      </ul>
    </template>

    <!-- 提交请假 -->
    <UiDialog
      :open="createOpen"
      title="写一张请假条"
      eyebrow="请假"
      width="md"
      :dismissible="!createBusy"
      @close="createOpen = false"
    >
      <div class="form">
        <p class="form__note">审批流程：{{ flowText }}。班级未配置导员时自动跳过导员环节。</p>
        <UiSelect v-model="form.courseId" label="课程" :options="courseOptions" :error="formError.courseId" placeholder="选择一门已选课程" />
        <UiField v-model="form.startAt" label="开始时间" type="datetime-local" :error="formError.startAt" required />
        <UiField v-model="form.endAt" label="结束时间" type="datetime-local" :error="formError.endAt" required />
        <UiTextarea
          v-model="form.reason"
          label="原因"
          :rows="4"
          :maxlength="500"
          :error="formError.reason"
          hint="写清事由与去向，便于审批人判断。"
          placeholder="发热就医，上午两节课无法到课。"
        />
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="createBusy" @click="createOpen = false">取消</UiButton>
        <UiButton variant="primary" :loading="createBusy" @click="submitCreate">提交</UiButton>
      </template>
    </UiDialog>

    <!-- 审批 -->
    <UiDialog
      :open="!!decide"
      :title="decide?.mode === 'approve' ? '通过这张请假？' : '驳回这张请假？'"
      :eyebrow="decide ? LEAVE_STAGE_LABEL[decide.item.currentStageRole] + '审批' : ''"
      width="sm"
      :dismissible="!decideBusy"
      @close="decide = null"
    >
      <div class="form">
        <p class="decide__who">
          {{ decide?.item.studentName }} · {{ formatDateTime(decide?.item.startAt) }} —
          {{ formatDateTime(decide?.item.endAt) }}
        </p>
        <p v-if="decide && decide.mode === 'approve' && decide.item.currentStage < decide.item.totalStages" class="form__note">
          通过后还会流转到下一级（{{ nextStageLabel(decide.item) }}）。
        </p>
        <UiTextarea
          v-model="comment"
          :label="decide?.mode === 'approve' ? '留言（可空）' : '驳回理由'"
          :rows="3"
          :maxlength="500"
        />
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="decideBusy" @click="decide = null">取消</UiButton>
        <UiButton
          :variant="decide?.mode === 'reject' ? 'danger' : 'primary'"
          :loading="decideBusy"
          @click="submitDecide"
        >
          {{ decide?.mode === 'approve' ? '通过' : '驳回' }}
        </UiButton>
      </template>
    </UiDialog>

    <!-- 提交申诉 -->
    <UiDialog
      :open="!!appealTarget"
      title="申诉这条考勤"
      :eyebrow="appealTarget?.courseName ?? ''"
      width="md"
      :dismissible="!appealBusy"
      @close="appealTarget = null"
    >
      <div class="form">
        <p class="form__note">
          {{ appealTarget?.sessionDate }} · 第 {{ appealTarget?.startPeriod }}–{{ appealTarget?.endPeriod }} 节
        </p>
        <UiSelect
          v-model="appealTargetStatus"
          label="希望改为"
          :options="[
            { value: 'PRESENT', label: '出勤' },
            { value: 'LATE', label: '迟到' },
            { value: 'LEAVE', label: '请假（需已提交请假）' },
          ]"
        />
        <UiTextarea
          v-model="appealReason"
          label="情况说明"
          :rows="4"
          :maxlength="500"
          hint="写清当时的情况，例如已提交请假、老师点名遗漏等。"
          required
        />
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="appealBusy" @click="appealTarget = null">取消</UiButton>
        <UiButton variant="primary" :loading="appealBusy" @click="submitAppeal">提交申诉</UiButton>
      </template>
    </UiDialog>

    <!-- 复核申诉 -->
    <UiDialog
      :open="!!reviewTarget"
      :title="reviewApprove ? '通过这条申诉？' : '驳回这条申诉？'"
      :eyebrow="reviewTarget?.studentName ?? ''"
      width="sm"
      :dismissible="!reviewBusy"
      @close="reviewTarget = null"
    >
      <div class="form">
        <p class="form__note">
          {{ reviewTarget?.courseName }} · {{ reviewTarget?.sessionDate }} · 第
          {{ reviewTarget?.startPeriod }}–{{ reviewTarget?.endPeriod }} 节
        </p>
        <UiSelect
          v-if="reviewApprove"
          v-model="reviewTargetStatus"
          label="通过后改为"
          :options="[
            { value: 'PRESENT', label: '出勤' },
            { value: 'LATE', label: '迟到' },
            { value: 'LEAVE', label: '请假' },
          ]"
        />
        <UiTextarea
          v-model="reviewComment"
          :label="reviewApprove ? '意见（可空）' : '驳回理由'"
          :rows="3"
          :maxlength="500"
        />
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="reviewBusy" @click="reviewTarget = null">取消</UiButton>
        <UiButton
          :variant="reviewApprove ? 'primary' : 'danger'"
          :loading="reviewBusy"
          @click="submitReview"
        >
          {{ reviewApprove ? '通过并修改考勤' : '驳回' }}
        </UiButton>
      </template>
    </UiDialog>

    <!-- 销假 -->
    <UiDialog
      :open="!!revokeTarget"
      title="销假"
      :eyebrow="revokeTarget?.courseName ?? ''"
      width="sm"
      :dismissible="!revokeBusy"
      @close="revokeTarget = null"
    >
      <div class="form">
        <p class="form__note">销假表示你已返校/结束请假。销假后考勤仍按请假记录处理。</p>
        <UiTextarea v-model="revokeNote" label="说明（可空）" :rows="2" :maxlength="200" placeholder="已返校上课" />
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="revokeBusy" @click="revokeTarget = null">取消</UiButton>
        <UiButton variant="primary" :loading="revokeBusy" @click="submitRevoke">确认销假</UiButton>
      </template>
    </UiDialog>

    <!-- 新建考勤 -->
    <UiDialog
      :open="attCreateOpen"
      title="登记考勤"
      :eyebrow="selectedCourse?.courseName ?? ''"
      width="sm"
      :dismissible="!attCreateBusy"
      @close="createFormDialogClose"
    >
      <div class="form">
        <UiField v-model="createForm.sessionDate" label="上课日期" type="date" required />
        <UiSelect v-model="createForm.startPeriod" label="开始节次" :options="numberPeriods" />
        <UiSelect v-model="createForm.endPeriod" label="结束节次" :options="numberPeriods" />
        <UiField v-model="createForm.note" label="备注（可空）" :maxlength="200" />
        <p class="form__note">建立后默认全部出勤，当天已通过的请假会自动标为「请假」。</p>
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="createBusy" @click="createFormDialogClose">取消</UiButton>
        <UiButton variant="primary" :loading="attCreateBusy" @click="submitCreateSession">建立并点名</UiButton>
      </template>
    </UiDialog>

    <!-- 考勤明细 -->
    <UiDialog
      :open="!!detail"
      :title="detail ? `${detail.session.courseName ?? ''} · ${detail.session.sessionDate}` : ''"
      :eyebrow="detail ? `第 ${detail.session.startPeriod}–${detail.session.endPeriod} 节 · ${ATTENDANCE_SESSION_LABEL[detail.session.status]}` : ''"
      width="lg"
      :dismissible="!editorBusy"
      @close="detail = null"
    >
      <div v-if="detail" class="editor">
        <ul class="editor__list">
          <li v-for="d in drafts" :key="d.studentId" class="editor__row">
            <span class="editor__name">{{ d.displayName }}</span>
            <div class="statuses" role="group" :aria-label="`${d.displayName} 的考勤`">
              <button
                v-for="s in statusList"
                :key="s"
                type="button"
                class="statuses__btn"
                :class="[`statuses__btn--${s.toLowerCase()}`, { 'is-on': d.status === s }]"
                @click="setStatus(d.studentId, s)"
              >
                {{ ATTENDANCE_STATUS_LABEL[s] }}
              </button>
            </div>
            <input v-model="d.remark" class="editor__remark" maxlength="200" placeholder="备注" :aria-label="`${d.displayName} 的备注`" />
          </li>
        </ul>
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="editorBusy" @click="detail = null">关闭</UiButton>
        <UiButton :loading="editorBusy" @click="saveRecords(false)">保存</UiButton>
        <UiButton variant="primary" :loading="editorBusy" @click="saveRecords(true)">保存并提交</UiButton>
      </template>
    </UiDialog>
  </div>
</template>

<style scoped>
.leave-page {
  width: 100%;
  max-width: 1040px;
  margin: 0 auto;
  padding: var(--s-5) clamp(var(--s-4), 4vw, var(--s-6)) var(--s-7);
}

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
  flex-wrap: wrap;
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

.bar__filter {
  min-width: 160px;
}

.list {
  margin: 0;
  padding: 0;
  list-style: none;
  border-top: var(--hairline);
}

.item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--s-4);
  padding: var(--s-4) 0;
  border-bottom: var(--hairline);
}

.item__course {
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-size: 17px;
  font-weight: 600;
}

.item__code {
  margin-left: var(--s-2);
  color: var(--ink-2);
  font-size: 11px;
  font-weight: 400;
}

.item__dot {
  margin: 0 4px;
  color: var(--rule-strong);
}

.item__time {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2);
  margin: var(--s-1) 0 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.item__class {
  padding-left: var(--s-2);
  border-left: 1px solid var(--rule-strong);
}

.item__reason {
  max-width: 44em;
  margin: var(--s-2) 0 0;
  font-family: var(--font-serif);
  font-size: 15px;
  line-height: 1.7;
}

.item__review {
  margin: var(--s-1) 0 0;
  color: var(--ink-2);
  font-size: 11px;
}

.item__side {
  display: grid;
  justify-items: end;
  align-content: start;
  gap: var(--s-2);
}

.item__side--row {
  grid-auto-flow: column;
  align-items: center;
}

.chain {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--s-2);
  margin-top: var(--s-2);
}

.chain__step {
  padding: 1px 8px;
  border: 1px dashed var(--rule-strong);
  color: var(--ink-2);
  font-size: 11px;
}

.chain__step.is-done {
  border-style: solid;
  border-color: var(--success);
  color: var(--success);
}

.chain__step.is-current {
  border-style: solid;
  border-color: var(--warning);
  color: var(--warning);
}

.chain__revoked {
  color: var(--teal);
  font-size: 11px;
}

.approvals {
  margin: var(--s-2) 0 0;
  padding: 0;
  list-style: none;
}

.approval {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2);
  color: var(--ink-2);
  font-size: var(--fs-small);
  padding: 2px 0;
}

.approval__decision {
  color: var(--success);
}

.approval__decision.is-reject {
  color: var(--ochre);
}

.approval__comment {
  font-family: var(--font-serif);
}

.query-bar,
.att-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--s-3);
  margin-bottom: var(--s-4);
}

.query-bar__count {
  margin-left: auto;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-ui);
}

.table th {
  padding: var(--s-2);
  color: var(--ink-2);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-align: left;
  border-bottom: 1px solid var(--ink);
  white-space: nowrap;
}

.table td {
  padding: var(--s-2);
  border-bottom: var(--hairline);
  vertical-align: top;
}

.table .num {
  text-align: right;
}

.cell-name {
  font-family: var(--font-serif-cjk);
}

.cell-code {
  display: block;
  color: var(--ink-2);
  font-size: 11px;
}

.small {
  font-size: 11px;
}

.is-warn {
  color: var(--ochre);
}

.att-summary {
  margin: 0 0 var(--s-3);
  font-size: var(--fs-small);
}

.courses {
  display: grid;
  gap: var(--s-4);
}

.course {
  border-top: 2px solid var(--ink);
  padding-top: var(--s-2);
}

.course__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-3);
}

.course__name {
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-size: 18px;
  font-weight: 600;
}

.course__meta {
  margin: 2px 0 0;
  color: var(--ink-2);
  font-size: 11px;
}

.course__stat {
  color: var(--teal);
  font-size: 11px;
}

.slots {
  display: flex;
  flex-wrap: wrap;
  gap: 1px;
  margin: var(--s-2) 0 0;
  padding: 0;
  list-style: none;
  background: var(--rule);
  border: 1px solid var(--rule);
}

.slot {
  flex: 1 1 200px;
  background: var(--paper-lift);
}

.slot__btn {
  display: grid;
  gap: 2px;
  width: 100%;
  padding: var(--s-2) var(--s-3);
  border: 0;
  background: none;
  color: var(--ink);
  text-align: left;
  cursor: pointer;
}

.slot__btn:hover {
  background: var(--paper-deep);
}

.slot__when {
  font-size: 12px;
  color: var(--teal);
}

.slot__room {
  font-size: var(--fs-small);
}

.slot__stat {
  color: var(--ink-2);
  font-size: 10px;
}

.slot--empty {
  padding: var(--s-2) var(--s-3);
  font-size: var(--fs-small);
}

.sessions {
  margin-top: var(--s-5);
  padding-top: var(--s-3);
  border-top: 1px solid var(--ink);
}

.sessions__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-3);
  margin-bottom: var(--s-2);
}

.sessions__title {
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-size: 18px;
  font-weight: 600;
}

.sessions__slot {
  margin-left: var(--s-2);
  color: var(--ink-2);
  font-size: 11px;
  font-weight: 400;
}

.session-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.row-actions {
  display: flex;
  align-items: center;
  gap: var(--s-2);
}

.session-row {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr) auto auto;
  align-items: center;
  gap: var(--s-3);
  padding: var(--s-2) 0;
  border-bottom: var(--hairline);
}

.stats {
  margin-top: var(--s-5);
  padding-top: var(--s-3);
  border-top: 1px solid var(--ink);
}

.editor__list {
  margin: 0;
  padding: 0;
  list-style: none;
  max-height: 60vh;
  overflow: auto;
}

.editor__row {
  display: grid;
  grid-template-columns: 120px auto minmax(0, 1fr);
  align-items: center;
  gap: var(--s-3);
  padding: var(--s-2) 0;
  border-bottom: var(--hairline);
}

.editor__name {
  font-family: var(--font-serif-cjk);
  font-size: 15px;
}

.editor__remark {
  width: 100%;
  padding: 4px 6px;
  border: 1px solid var(--rule);
  background: var(--paper-lift);
  color: var(--ink);
  font-size: var(--fs-small);
}

.statuses {
  display: flex;
  gap: 2px;
}

.statuses__btn {
  padding: 4px 8px;
  border: 1px solid var(--rule-strong);
  background: var(--paper-lift);
  color: var(--ink-2);
  font-size: 12px;
  cursor: pointer;
}

.statuses__btn.is-on {
  color: var(--paper);
}

.statuses__btn--present.is-on {
  background: var(--success);
  border-color: var(--success);
}

.statuses__btn--absent.is-on {
  background: var(--ochre);
  border-color: var(--ochre);
}

.statuses__btn--late.is-on {
  background: var(--warning);
  border-color: var(--warning);
}

.statuses__btn--leave.is-on {
  background: var(--teal);
  border-color: var(--teal);
}

.form {
  display: grid;
  gap: var(--s-3);
}

.form__note {
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.7;
}

.decide__who {
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.muted {
  color: var(--ink-2);
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

@media (max-width: 760px) {
  .leave-page {
    padding: var(--s-4) 16px var(--s-6);
  }

  .bar {
    flex-wrap: wrap;
    padding-bottom: 6px;
    margin-bottom: 10px;
  }

  .tabs {
    flex-wrap: nowrap;
    overflow-x: auto;
    width: 100%;
    gap: 14px;
    scrollbar-width: none;
  }

  .tabs::-webkit-scrollbar {
    display: none;
  }

  .tabs__btn {
    flex: 0 0 auto;
    font-size: 15px;
  }

  .item {
    grid-template-columns: minmax(0, 1fr);
  }

  .query-bar,
  .att-bar {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
    align-items: end;
  }

  .query-bar :deep(.ui-btn),
  .att-bar :deep(.ui-btn) {
    grid-column: 1 / -1;
  }

  .query-bar__count {
    grid-column: 1 / -1;
    margin-left: 0;
  }

  .item__side {
    justify-items: start;
    grid-auto-flow: row;
  }

  .item__side.item__side--row {
    grid-auto-flow: column;
    align-items: center;
  }

  .item__side.item__side--row {
    grid-auto-flow: column;
    align-items: center;
  }

  .session-row {
    grid-template-columns: minmax(0, 1fr);
    gap: var(--s-1);
  }

  .editor__row {
    grid-template-columns: minmax(0, 1fr);
    gap: var(--s-2);
  }
}
</style>
