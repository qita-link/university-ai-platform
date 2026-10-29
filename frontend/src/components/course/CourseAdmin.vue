<script setup lang="ts">
import { computed, ref } from 'vue'
import { courseApi, userApi } from '@/api/endpoints'
import type { CourseDetail, Schedule, UserView } from '@/api/types'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { COURSE_STATUS_LABEL } from '@/lib/labels'
import { PERIODS, weekdayName } from '@/lib/schedule'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiField from '@/components/ui/UiField.vue'
import UiSelect from '@/components/ui/UiSelect.vue'
import UiTextarea from '@/components/ui/UiTextarea.vue'

/**
 * 教师/管理员的课程操作区：改资料、开关课、排课、加教师。
 * 只负责「改」，改完通过 changed 事件让父级重新取详情。
 */
const props = defineProps<{ course: CourseDetail }>()
const emit = defineEmits<{ changed: [] }>()

const auth = useAuthStore()
const toast = useToastStore()

const canManageAll = computed(() => auth.can('course:manage_all'))
const canPickTeacher = computed(() => auth.can('user:read'))

/* ---------------- 编辑课程 ---------------- */
const editOpen = ref(false)
const editBusy = ref(false)
const editForm = ref({ name: '', credit: '', capacity: '', description: '' })
const editError = ref<Record<string, string | null>>({})

function openEdit() {
  editForm.value = {
    name: props.course.name,
    credit: String(props.course.credit),
    capacity: String(props.course.capacity),
    description: props.course.description ?? '',
  }
  editError.value = {}
  editOpen.value = true
}

async function submitEdit() {
  editError.value = {}
  const credit = Number(editForm.value.credit)
  const capacity = Number(editForm.value.capacity)
  if (!editForm.value.name.trim()) editError.value.name = '请填写课程名称'
  if (!Number.isFinite(credit) || credit < 0 || credit > 20) editError.value.credit = '学分需在 0–20 之间'
  if (!Number.isInteger(capacity) || capacity < props.course.selectedCount) {
    editError.value.capacity = `容量不能小于已选人数（${props.course.selectedCount}）`
  }
  if (Object.keys(editError.value).length) return

  editBusy.value = true
  try {
    await courseApi.update(props.course.id, {
      name: editForm.value.name.trim(),
      credit,
      description: editForm.value.description.trim() || null,
      orgUnitId: props.course.orgUnitId,
      capacity,
      version: props.course.version,
    })
    editOpen.value = false
    toast.push('课程已更新', 'success')
    emit('changed')
  } catch (e) {
    editError.value = {
      name: e instanceof ApiError ? (e.fieldError('name') ?? null) : null,
      general: e instanceof ApiError && e.status === 409 ? '课程刚被改过，请刷新后重试' : errorMessage(e),
    }
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    editBusy.value = false
  }
}

/* ---------------- 状态 ---------------- */
const statusTarget = ref<'OPEN' | 'CLOSED' | 'CANCELLED' | null>(null)
const statusReason = ref('')
const statusBusy = ref(false)

const STATUS_HINT: Record<string, string> = {
  OPEN: '开放后学生可以在选课窗口内选课。开放前必须已经排课。',
  CLOSED: '关闭后不再接受新的选课，已选记录保留。',
  CANCELLED: '停开是终态：会退掉全部有效选课、清零名额，并撤销该课程待审批的请假。',
}

async function submitStatus() {
  if (!statusTarget.value) return
  statusBusy.value = true
  try {
    await courseApi.setStatus(props.course.id, statusTarget.value, statusReason.value.trim() || undefined)
    toast.push('课程状态已更新', 'success')
    statusTarget.value = null
    emit('changed')
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    statusBusy.value = false
  }
}

/* ---------------- 排课 ---------------- */
const schedOpen = ref(false)
const schedBusy = ref(false)
const editingSchedule = ref<Schedule | null>(null)
const schedForm = ref({
  dayOfWeek: '1',
  startPeriod: '1',
  endPeriod: '2',
  startWeek: '1',
  endWeek: '16',
  weekParity: 'ALL',
  classroom: '',
})
const schedError = ref<string | null>(null)

const dayOptions = [1, 2, 3, 4, 5, 6, 7].map((d) => ({ value: String(d), label: weekdayName(d) }))
const periodOptions = Array.from({ length: PERIODS }, (_, i) => ({ value: String(i + 1), label: `第 ${i + 1} 节` }))
const parityOptions = [
  { value: 'ALL', label: '每周' },
  { value: 'ODD', label: '单周' },
  { value: 'EVEN', label: '双周' },
]

function openSchedule(s?: Schedule) {
  editingSchedule.value = s ?? null
  schedForm.value = s
    ? {
        dayOfWeek: String(s.dayOfWeek),
        startPeriod: String(s.startPeriod),
        endPeriod: String(s.endPeriod),
        startWeek: String(s.startWeek),
        endWeek: String(s.endWeek),
        weekParity: s.weekParity,
        classroom: s.classroom ?? '',
      }
    : { dayOfWeek: '1', startPeriod: '1', endPeriod: '2', startWeek: '1', endWeek: '16', weekParity: 'ALL', classroom: '' }
  schedError.value = null
  schedOpen.value = true
}

async function submitSchedule() {
  schedError.value = null
  const body = {
    dayOfWeek: Number(schedForm.value.dayOfWeek),
    startPeriod: Number(schedForm.value.startPeriod),
    endPeriod: Number(schedForm.value.endPeriod),
    startWeek: Number(schedForm.value.startWeek),
    endWeek: Number(schedForm.value.endWeek),
    weekParity: schedForm.value.weekParity,
    classroom: schedForm.value.classroom.trim() || null,
  }
  if (body.endPeriod < body.startPeriod) {
    schedError.value = '结束节次不能早于开始节次'
    return
  }
  if (body.endWeek < body.startWeek) {
    schedError.value = '结束周不能早于开始周'
    return
  }

  schedBusy.value = true
  try {
    if (editingSchedule.value) {
      await courseApi.updateSchedule(props.course.id, editingSchedule.value.id, body)
    } else {
      await courseApi.addSchedule(props.course.id, body)
    }
    schedOpen.value = false
    toast.push(editingSchedule.value ? '排课已更新' : '已新增排课', 'success')
    emit('changed')
  } catch (e) {
    schedError.value = errorMessage(e)
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    schedBusy.value = false
  }
}

const removingId = ref<number | null>(null)
async function removeSchedule(s: Schedule) {
  removingId.value = s.id
  try {
    await courseApi.removeSchedule(props.course.id, s.id)
    toast.push('排课已删除', 'success')
    emit('changed')
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    removingId.value = null
  }
}

/* ---------------- 教师 ---------------- */
const teacherOpen = ref(false)
const teacherBusy = ref(false)
const teacherKeyword = ref('')
const teacherResults = ref<UserView[]>([])
const pickedTeacher = ref<number | null>(null)
const pickedRole = ref<'PRIMARY' | 'ASSISTANT'>('ASSISTANT')
const teacherError = ref<string | null>(null)

async function openTeacher() {
  teacherOpen.value = true
  teacherKeyword.value = ''
  teacherResults.value = []
  pickedTeacher.value = null
  pickedRole.value = 'ASSISTANT'
  teacherError.value = null
  await searchTeachers()
}

async function searchTeachers() {
  try {
    const res = await userApi.list({ keyword: teacherKeyword.value.trim() || undefined, roleCode: 'TEACHER', size: 10 })
    teacherResults.value = res.items
  } catch (e) {
    teacherResults.value = []
    teacherError.value = errorMessage(e)
  }
}

async function addTeacher() {
  if (!pickedTeacher.value) {
    teacherError.value = '请先选择一位教师'
    return
  }
  if (pickedRole.value === 'PRIMARY' && !canManageAll.value) {
    teacherError.value = '只有管理员可以变更主讲教师'
    return
  }
  teacherBusy.value = true
  try {
    await courseApi.addTeacher(props.course.id, { teacherId: pickedTeacher.value, teacherRole: pickedRole.value })
    teacherOpen.value = false
    toast.push('已添加授课教师', 'success')
    emit('changed')
  } catch (e) {
    teacherError.value = errorMessage(e)
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    teacherBusy.value = false
  }
}

const removingTeacher = ref<number | null>(null)
async function removeTeacher(teacherId: number) {
  removingTeacher.value = teacherId
  try {
    await courseApi.removeTeacher(props.course.id, teacherId)
    toast.push('已移除该教师', 'success')
    emit('changed')
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    removingTeacher.value = null
  }
}
</script>

<template>
  <section class="manage" aria-label="课程管理">
    <div class="manage__bar">
      <p class="eyebrow">课程管理</p>
      <div class="manage__actions">
        <UiButton v-if="course.status !== 'CANCELLED'" size="sm" @click="openEdit">编辑资料</UiButton>
        <UiButton
          v-if="course.status === 'DRAFT' || course.status === 'CLOSED'"
          size="sm"
          variant="primary"
          @click="statusTarget = 'OPEN'"
        >
          开放选课
        </UiButton>
        <UiButton v-if="course.status === 'OPEN'" size="sm" @click="statusTarget = 'CLOSED'">关闭选课</UiButton>
        <UiButton v-if="course.status !== 'CANCELLED'" size="sm" variant="danger" @click="statusTarget = 'CANCELLED'">
          停开
        </UiButton>
      </div>
    </div>

    <div class="manage__section">
      <div class="manage__head">
        <p class="manage__title">排课</p>
        <UiButton size="sm" variant="link" :disabled="course.status === 'CANCELLED'" @click="openSchedule()">
          新增排课
        </UiButton>
      </div>
      <p class="manage__note">同一课程或同一教师在本学期的时间冲突会被后端拦下；单双周可以共用同一时段。</p>

      <ol v-if="course.schedules.length" class="scheds">
        <li v-for="s in course.schedules" :key="s.id" class="sched">
          <span class="sched__text mono">
            {{ weekdayName(s.dayOfWeek) }} {{ s.startPeriod }}–{{ s.endPeriod }} 节 ·
            {{ s.startWeek }}–{{ s.endWeek }} 周（{{ s.weekParity === 'ALL' ? '每周' : s.weekParity === 'ODD' ? '单周' : '双周' }}）
            {{ s.classroom ? `· ${s.classroom}` : '' }}
          </span>
          <span class="sched__actions">
            <UiButton size="sm" variant="link" @click="openSchedule(s)">编辑</UiButton>
            <UiButton size="sm" variant="link" :loading="removingId === s.id" @click="removeSchedule(s)">删除</UiButton>
          </span>
        </li>
      </ol>
      <p v-else class="manage__note">还没有排课。开放选课前至少要有一条排课。</p>
    </div>

    <div class="manage__section">
      <div class="manage__head">
        <p class="manage__title">授课教师</p>
        <UiButton v-if="canPickTeacher" size="sm" variant="link" @click="openTeacher">添加教师</UiButton>
      </div>
      <ul class="teachers">
        <li v-for="t in course.teachers" :key="t.teacherId" class="teacher">
          <span class="teacher__name">{{ t.displayName }}</span>
          <span class="teacher__role">{{ t.teacherRole === 'PRIMARY' ? '主讲' : '助教' }}</span>
          <span class="teacher__id mono">{{ t.username }}</span>
          <template v-if="canPickTeacher">
            <UiButton
              v-if="t.teacherRole !== 'PRIMARY'"
              size="sm"
              variant="link"
              :loading="removingTeacher === t.teacherId"
              @click="removeTeacher(t.teacherId)"
            >
              移除
            </UiButton>
            <span v-else class="teacher__lock">主讲需先改派</span>
          </template>
        </li>
      </ul>
    </div>

    <!-- 编辑资料 -->
    <UiDialog
      :open="editOpen"
      title="编辑课程"
      eyebrow="课程"
      width="md"
      :dismissible="!editBusy"
      @close="editOpen = false"
    >
      <div class="form">
        <UiField v-model="editForm.name" label="课程名称" :error="editError.name" />
        <div class="form__row">
          <UiField v-model="editForm.credit" label="学分" inputmode="numeric" :error="editError.credit" />
          <UiField v-model="editForm.capacity" label="容量" inputmode="numeric" :error="editError.capacity" />
        </div>
        <UiTextarea v-model="editForm.description" label="课程简介" :rows="4" :maxlength="1000" />
        <p v-if="editError.general" class="form__err" role="alert">{{ editError.general }}</p>
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="editBusy" @click="editOpen = false">取消</UiButton>
        <UiButton variant="primary" :loading="editBusy" @click="submitEdit">保存</UiButton>
      </template>
    </UiDialog>

    <!-- 状态 -->
    <UiDialog
      :open="!!statusTarget"
      :title="statusTarget ? `确认${COURSE_STATUS_LABEL[statusTarget]}` : ''"
      :eyebrow="course.name"
      width="sm"
      :dismissible="!statusBusy"
      @close="statusTarget = null"
    >
      <div class="form">
        <p class="manage__note">{{ statusTarget ? STATUS_HINT[statusTarget] : '' }}</p>
        <UiField
          v-if="statusTarget === 'CANCELLED'"
          v-model="statusReason"
          label="停开原因"
          hint="写入审计日志，会一并留存。"
          :maxlength="200"
        />
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="statusBusy" @click="statusTarget = null">取消</UiButton>
        <UiButton
          :variant="statusTarget === 'CANCELLED' ? 'danger' : 'primary'"
          :loading="statusBusy"
          @click="submitStatus"
        >
          确认
        </UiButton>
      </template>
    </UiDialog>

    <!-- 排课 -->
    <UiDialog
      :open="schedOpen"
      :title="editingSchedule ? '修改排课' : '新增排课'"
      eyebrow="排课"
      width="md"
      :dismissible="!schedBusy"
      @close="schedOpen = false"
    >
      <div class="form">
        <UiSelect v-model="schedForm.dayOfWeek" label="星期" :options="dayOptions" />
        <div class="form__row">
          <UiSelect v-model="schedForm.startPeriod" label="开始节次" :options="periodOptions" />
          <UiSelect v-model="schedForm.endPeriod" label="结束节次" :options="periodOptions" />
        </div>
        <div class="form__row">
          <UiField v-model="schedForm.startWeek" label="开始周" inputmode="numeric" />
          <UiField v-model="schedForm.endWeek" label="结束周" inputmode="numeric" />
        </div>
        <UiSelect v-model="schedForm.weekParity" label="单双周" :options="parityOptions" />
        <UiField v-model="schedForm.classroom" label="教室" hint="可空，如 A101。" :maxlength="64" />
        <p v-if="schedError" class="form__err" role="alert">{{ schedError }}</p>
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="schedBusy" @click="schedOpen = false">取消</UiButton>
        <UiButton variant="primary" :loading="schedBusy" @click="submitSchedule">保存</UiButton>
      </template>
    </UiDialog>

    <!-- 添加教师 -->
    <UiDialog
      :open="teacherOpen"
      title="添加授课教师"
      eyebrow="课程"
      width="md"
      :dismissible="!teacherBusy"
      @close="teacherOpen = false"
    >
      <div class="form">
        <div class="search-row">
          <UiField v-model="teacherKeyword" label="按姓名/工号搜索教师" />
          <UiButton size="sm" @click="searchTeachers">查找</UiButton>
        </div>
        <ul v-if="teacherResults.length" class="candidates">
          <li v-for="u in teacherResults" :key="u.id">
            <label class="candidate">
              <input v-model="pickedTeacher" type="radio" :value="u.id" />
              <span>{{ u.displayName }}</span>
              <span class="candidate__id mono">{{ u.username }}</span>
            </label>
          </li>
        </ul>
        <p v-else class="manage__note">没有找到教师账号。可以换个关键词，或先到「用户」里创建。</p>
        <UiSelect
          v-model="pickedRole"
          label="角色"
          :options="[
            { value: 'ASSISTANT', label: '助教' },
            { value: 'PRIMARY', label: '主讲（需管理员）' },
          ]"
          hint="改为主讲后，原主讲自动转为助教。"
        />
        <p v-if="teacherError" class="form__err" role="alert">{{ teacherError }}</p>
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="teacherBusy" @click="teacherOpen = false">取消</UiButton>
        <UiButton variant="primary" :loading="teacherBusy" @click="addTeacher">添加</UiButton>
      </template>
    </UiDialog>
  </section>
</template>

<style scoped>
.manage {
  padding-top: var(--s-5);
  margin-top: var(--s-5);
  border-top: 1px solid var(--ink);
}

.manage__bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  margin-bottom: var(--s-4);
}

.manage__bar p {
  margin: 0;
}

.manage__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2);
}

.manage__section {
  padding-top: var(--s-4);
  margin-top: var(--s-4);
  border-top: var(--hairline);
}

.manage__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-3);
}

.manage__title {
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-size: 16px;
  font-weight: 600;
}

.manage__note {
  margin: var(--s-1) 0 var(--s-2);
  color: var(--ink-2);
  font-size: 11px;
  line-height: 1.7;
}

.scheds,
.teachers,
.candidates {
  margin: 0;
  padding: 0;
  list-style: none;
}

.sched,
.teacher {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  padding: var(--s-2) 0;
  border-bottom: var(--hairline);
}

.sched__text {
  flex: 1;
  min-width: 0;
  font-size: 12px;
}

.sched__actions {
  display: flex;
  gap: var(--s-2);
}

.teacher__name {
  font-family: var(--font-serif-cjk);
  font-size: 15px;
}

.teacher__role {
  color: var(--teal);
  font-size: 11px;
}

.teacher__id {
  color: var(--ink-2);
  font-size: 11px;
}

.teacher__lock {
  margin-left: auto;
  color: var(--ink-2);
  font-size: 11px;
}

.teacher :deep(button) {
  margin-left: auto;
}

.candidate {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  min-height: 34px;
  cursor: pointer;
}

.candidate input {
  accent-color: var(--teal);
}

.candidate__id {
  margin-left: auto;
  color: var(--ink-2);
  font-size: 11px;
}

.search-row {
  display: flex;
  align-items: flex-end;
  gap: var(--s-3);
}

.search-row :deep(.ui-field) {
  flex: 1;
}

.form {
  display: grid;
  gap: var(--s-4);
}

.form__row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--s-3);
}

.form__err {
  margin: 0;
  color: var(--ochre);
  font-size: var(--fs-small);
}
</style>
