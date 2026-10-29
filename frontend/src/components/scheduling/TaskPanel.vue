<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { schedulingApi } from '@/api/endpoints'
import type {
  SchedulingCourse,
  SchedulingRoom,
  SchedulingPrecheck,
  SchedulingTeacher,
  SchedulingTeachingClass,
  TeachingTask,
} from '@/api/types'
import { ApiError } from '@/api/http'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { useToastStore } from '@/stores/toast'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiEmpty from '@/components/ui/UiEmpty.vue'
import UiField from '@/components/ui/UiField.vue'
import UiSelect from '@/components/ui/UiSelect.vue'
import UiTag from '@/components/ui/UiTag.vue'
import UiTextarea from '@/components/ui/UiTextarea.vue'

/**
 * 教学任务：先看「学时对不对得上」，再决定能不能排。
 * 预检结果是排课前的唯一依据，ERROR 会直接阻止求解。
 */
const props = defineProps<{ semesterId: number; canManage: boolean }>()

const toast = useToastStore()
const tasks = ref<TeachingTask[]>([])
const courses = ref<SchedulingCourse[]>([])
const teachingClasses = ref<SchedulingTeachingClass[]>([])
const teachers = ref<SchedulingTeacher[]>([])
const rooms = ref<SchedulingRoom[]>([])
const loading = ref(false)
const precheck = ref<SchedulingPrecheck | null>(null)
const checking = ref(false)

const errorCount = computed(() => precheck.value?.errorCount ?? 0)
const warningCount = computed(() => precheck.value?.warningCount ?? 0)

/** 教学任务可能超过一页（后端 size 上限 100），逐页取完 */
async function loadAllTasks(): Promise<TeachingTask[]> {
  const all: TeachingTask[] = []
  let page = 1
  for (;;) {
    const chunk = await schedulingApi.teachingTasks({ semesterId: props.semesterId, page, size: 100 })
    all.push(...chunk.items)
    if (page >= chunk.pages || !chunk.items.length) break
    page += 1
  }
  return all
}

async function load() {
  if (!props.semesterId) return
  loading.value = true
  try {
    const [list, cs, tcs, ts, rs] = await Promise.all([
      loadAllTasks(),
      schedulingApi.courses({ status: 'ACTIVE' }),
      schedulingApi.teachingClasses({ semesterId: props.semesterId }),
      schedulingApi.teachers(),
      schedulingApi.rooms({ size: 100 }),
    ])
    tasks.value = list
    courses.value = cs
    teachingClasses.value = tcs
    teachers.value = ts
    rooms.value = rs.items
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    loading.value = false
  }
}

async function runPrecheck() {
  checking.value = true
  try {
    precheck.value = await schedulingApi.precheck(props.semesterId)
    toast.push(
      precheck.value.solvable
        ? `预检通过：${tasks.value.length} 个任务可排`
        : `预检未通过：${precheck.value.errorCount} 个错误`,
      precheck.value.solvable ? 'success' : 'danger',
    )
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    checking.value = false
  }
}

function issueTone(severity: string) {
  return severity === 'ERROR' ? 'ochre' : severity === 'WARNING' ? 'warning' : 'muted'
}

/* ---------------- 新建 / 编辑 ---------------- */
const WEEK_PARITIES = [
  { value: 'ALL', label: '每周' },
  { value: 'ODD', label: '单周' },
  { value: 'EVEN', label: '双周' },
]

const ROOM_TYPES = [
  { value: '', label: '不限定' },
  { value: 'NORMAL', label: '普通教室' },
  { value: 'MULTIMEDIA', label: '多媒体教室' },
  { value: 'COMPUTER_LAB', label: '计算机机房' },
  { value: 'LANGUAGE_LAB', label: '语音教室' },
  { value: 'LAB', label: '实验室' },
  { value: 'GYM', label: '体育场馆' },
  { value: 'OTHER', label: '其他' },
]

const open = ref(false)
const busy = ref(false)
const detailLoading = ref(false)
const editing = ref<TeachingTask | null>(null)
const form = ref({
  code: '',
  courseId: '',
  teachingClassId: '',
  startWeek: '1',
  endWeek: '16',
  weekParity: 'ALL',
  sessionsPerWeek: '2',
  periodsPerSession: '2',
  totalHours: '',
  roomRequired: true,
  roomType: '',
  priority: '5',
  allowSameDay: false,
  uniformStart: false,
  status: 'ACTIVE',
  note: '',
})
const pickedTeachers = ref<number[]>([])
/** 指定教室：REQUIRED 只能用这些教室，FORBIDDEN 不能用，PREFERRED 只是倾向 */
const roomRefs = ref<{ roomId: string; mode: string }[]>([])
const errors = ref<Record<string, string | null>>({})

const ROOM_MODES = [
  { value: 'REQUIRED', label: '只能用' },
  { value: 'PREFERRED', label: '优先用' },
  { value: 'FORBIDDEN', label: '不能用' },
]

const roomOptions = computed(() =>
  rooms.value.map((r) => ({
    value: String(r.id),
    label: `${r.code} ${r.name}（${r.capacity} 座${r.campusName ? ' · ' + r.campusName : ''}）`,
  })),
)

function addRoomRef() {
  roomRefs.value = [...roomRefs.value, { roomId: rooms.value[0] ? String(rooms.value[0].id) : '', mode: 'REQUIRED' }]
}

function removeRoomRef(index: number) {
  roomRefs.value = roomRefs.value.filter((_, i) => i !== index)
}

const courseOptions = computed(() =>
  courses.value.map((c) => ({ value: String(c.id), label: `${c.code} ${c.name}` })),
)
const teachingClassOptions = computed(() =>
  teachingClasses.value.map((t) => ({ value: String(t.id), label: `${t.code} ${t.name}` })),
)

/** 按当前周次/单双周/每周次数估算学时，和总学时不一致时先给提示 */
const estimatedHours = computed(() => {
  const start = Number(form.value.startWeek) || 0
  const end = Number(form.value.endWeek) || 0
  if (end < start) return 0
  const span = end - start + 1
  const weeks = form.value.weekParity === 'ALL' ? span : form.value.weekParity === 'ODD'
    ? Math.ceil(span / 2)
    : Math.floor(span / 2)
  return weeks * (Number(form.value.sessionsPerWeek) || 0) * (Number(form.value.periodsPerSession) || 0)
})

const hoursMismatch = computed(
  () => form.value.totalHours.trim() !== '' && Number(form.value.totalHours) !== estimatedHours.value,
)

function resetForm() {
  form.value = {
    code: '',
    courseId: courses.value[0] ? String(courses.value[0].id) : '',
    teachingClassId: teachingClasses.value[0] ? String(teachingClasses.value[0].id) : '',
    startWeek: '1',
    endWeek: '16',
    weekParity: 'ALL',
    sessionsPerWeek: '2',
    periodsPerSession: '2',
    totalHours: '',
    roomRequired: true,
    roomType: '',
    priority: '5',
    allowSameDay: false,
    uniformStart: false,
    status: 'ACTIVE',
    note: '',
  }
  pickedTeachers.value = teachers.value[0] ? [teachers.value[0].id] : []
  roomRefs.value = []
  errors.value = {}
}

function openCreate() {
  editing.value = null
  resetForm()
  open.value = true
}

async function openEdit(t: TeachingTask) {
  editing.value = t
  errors.value = {}
  open.value = true
  detailLoading.value = true
  try {
    const detail = await schedulingApi.teachingTask(t.id)
    editing.value = detail
    form.value = {
      code: detail.code,
      courseId: String(detail.courseId),
      teachingClassId: String(detail.teachingClassId),
      startWeek: String(detail.startWeek),
      endWeek: String(detail.endWeek),
      weekParity: detail.weekParity || 'ALL',
      sessionsPerWeek: String(detail.sessionsPerWeek),
      periodsPerSession: String(detail.periodsPerSession),
      totalHours: String(detail.totalHours),
      roomRequired: detail.roomRequired,
      roomType: detail.roomType ?? '',
      priority: detail.priority == null ? '5' : String(detail.priority),
      allowSameDay: !!detail.allowSameDay,
      uniformStart: !!detail.uniformStart,
      status: detail.status,
      note: detail.note ?? '',
    }
    pickedTeachers.value = (detail.teachers ?? []).map((x) => x.teacherId)
    // 保留原有的指定教室，否则从界面保存一次就会把它们清空
    roomRefs.value = (detail.rooms ?? []).map((r) => ({ roomId: String(r.roomId), mode: r.mode }))
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
    open.value = false
  } finally {
    detailLoading.value = false
  }
}

function toggleTeacher(id: number) {
  pickedTeachers.value = pickedTeachers.value.includes(id)
    ? pickedTeachers.value.filter((x) => x !== id)
    : [...pickedTeachers.value, id]
}

async function submit() {
  errors.value = {}
  const f = form.value
  if (!f.code.trim()) errors.value.code = '请填写任务编码'
  if (!f.courseId) errors.value.courseId = '请选择课程'
  if (!f.teachingClassId) errors.value.teachingClassId = '请选择教学班'
  if (!pickedTeachers.value.length) errors.value.teachers = '至少选择一位授课教师'
  const start = Number(f.startWeek)
  const end = Number(f.endWeek)
  if (!Number.isFinite(start) || start < 1) errors.value.startWeek = '起始周需 ≥ 1'
  if (!Number.isFinite(end) || end < start) errors.value.endWeek = '结束周不能早于起始周'
  if (Object.keys(errors.value).length) return

  const body: Record<string, unknown> = {
    semesterId: props.semesterId,
    code: f.code.trim(),
    courseId: Number(f.courseId),
    teachingClassId: Number(f.teachingClassId),
    startWeek: start,
    endWeek: end,
    weekParity: f.weekParity,
    sessionsPerWeek: Number(f.sessionsPerWeek),
    periodsPerSession: Number(f.periodsPerSession),
    totalHours: f.totalHours.trim() === '' ? estimatedHours.value : Number(f.totalHours),
    roomRequired: f.roomRequired,
    roomType: f.roomType || null,
    priority: Number(f.priority) || 5,
    allowSameDay: f.allowSameDay,
    uniformStart: f.uniformStart,
    status: f.status,
    note: f.note.trim() || null,
    teacherIds: pickedTeachers.value,
    rooms: roomRefs.value
      .filter((r) => r.roomId)
      .map((r) => ({ roomId: Number(r.roomId), roomCode: null, mode: r.mode })),
  }
  if (editing.value) body.version = editing.value.version

  busy.value = true
  try {
    if (editing.value) await schedulingApi.updateTeachingTask(editing.value.id, body)
    else await schedulingApi.createTeachingTask(body)
    open.value = false
    precheck.value = null
    toast.push(editing.value ? '教学任务已更新' : '教学任务已创建', 'success')
    await load()
  } catch (e) {
    const apiErr = e instanceof ApiError ? e : null
    errors.value = {
      code: apiErr?.fieldError('code') ?? null,
      totalHours: apiErr?.fieldError('totalHours') ?? null,
      general: errorMessage(e),
    }
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    busy.value = false
  }
}

/* ---------------- 删除 ---------------- */
const deleteTarget = ref<TeachingTask | null>(null)
const deleteBusy = ref(false)

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteBusy.value = true
  try {
    await schedulingApi.deleteTeachingTask(deleteTarget.value.id)
    toast.push('教学任务已删除', 'success')
    deleteTarget.value = null
    precheck.value = null
    await load()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    deleteBusy.value = false
  }
}

/* ---------------- 导入 / 导出 ---------------- */
const fileInput = ref<HTMLInputElement | null>(null)
const importing = ref(false)
const exporting = ref(false)

function pickFile() {
  fileInput.value?.click()
}

async function onFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  importing.value = true
  try {
    const text = await file.text()
    const result = await schedulingApi.importTasks(props.semesterId, text)
    if (result.failed > 0) {
      const first = result.errors.slice(0, 3).map((e) => `第 ${e.line} 行：${e.message}`).join('；')
      toast.push(`导入完成：新增 ${result.created}，更新 ${result.updated}，失败 ${result.failed}。${first}`, 'danger')
    } else {
      toast.push(`导入完成：新增 ${result.created}，更新 ${result.updated}`, 'success')
    }
    precheck.value = null
    await load()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    importing.value = false
  }
}

async function exportCsv() {
  exporting.value = true
  try {
    const blob = await schedulingApi.exportTasks(props.semesterId)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `teaching-tasks-${props.semesterId}.csv`
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    exporting.value = false
  }
}

onMounted(load)
watch(() => props.semesterId, () => {
  precheck.value = null
  load()
})
</script>

<template>
  <section class="tp">
    <div class="tp__bar">
      <div>
        <p class="tp__summary">
          共 <strong>{{ tasks.length }}</strong> 个教学任务
          <span v-if="precheck" class="tp__pre" :class="{ 'is-bad': !precheck.solvable }">
            · {{ precheck.summary }}
          </span>
        </p>
        <p v-if="!courses.length || !teachingClasses.length" class="tp__sub">
          建任务前需要：课程库有课程、本学期有教学班、有 TEACHER 角色账号
        </p>
      </div>
      <div class="tp__actions">
        <UiButton size="sm" :loading="checking" @click="runPrecheck">数据预检</UiButton>
        <UiButton size="sm" :loading="exporting" @click="exportCsv">导出 CSV</UiButton>
        <UiButton v-if="props.canManage" size="sm" :loading="importing" @click="pickFile">导入 CSV</UiButton>
        <UiButton
          v-if="props.canManage"
          size="sm"
          variant="primary"
          :disabled="!courses.length || !teachingClasses.length || !teachers.length"
          @click="openCreate"
        >
          新建任务
        </UiButton>
        <input ref="fileInput" type="file" accept=".csv,text/csv" hidden @change="onFile" />
      </div>
    </div>

    <UiEmpty
      v-if="!tasks.length && !loading"
      title="还没有教学任务"
      hint="新建任务，或按模板导入 CSV（导出 CSV 可当模板用）"
    />

    <table v-else class="tp__table">
      <thead>
        <tr>
          <th>任务</th>
          <th>课程 / 教学班</th>
          <th>教师</th>
          <th>周次</th>
          <th>每周</th>
          <th>学时</th>
          <th>状态</th>
          <th />
        </tr>
      </thead>
      <tbody>
        <tr v-for="t in tasks" :key="t.id">
          <td class="mono">{{ t.code }}</td>
          <td>
            <p class="tp__name">{{ t.courseName }}</p>
            <p class="tp__sub">{{ t.teachingClassName }}</p>
          </td>
          <td class="tp__sub">{{ t.teachers.map((x) => x.teacherName).join('、') || '—' }}</td>
          <td class="tp__sub">{{ t.startWeek }}-{{ t.endWeek }} 周 {{ t.weekParity === 'ALL' ? '' : t.weekParity }}</td>
          <td class="tp__sub">{{ t.sessionsPerWeek }} 次 × {{ t.periodsPerSession }} 节</td>
          <td>
            <span class="mono" :class="{ 'is-bad': !t.hoursConsistent }">{{ t.providedHours }}</span>
            <span class="tp__sub"> / {{ t.totalHours }}</span>
          </td>
          <td>
            <UiTag :tone="t.status === 'ACTIVE' ? 'teal' : 'muted'">
              {{ t.status === 'ACTIVE' ? '启用' : '停用' }}
            </UiTag>
            <UiTag v-if="!t.hoursConsistent" tone="ochre">学时不一致</UiTag>
          </td>
          <td class="tp__ops">
            <template v-if="props.canManage">
              <UiButton size="sm" variant="link" @click="openEdit(t)">编辑</UiButton>
              <UiButton size="sm" variant="link" @click="deleteTarget = t">删除</UiButton>
            </template>
          </td>
        </tr>
      </tbody>
    </table>

    <div v-if="precheck" class="tp__issues">
      <div class="tp__issues-head">
        <p class="tp__issues-title">
          预检结果
          <UiTag :tone="precheck.solvable ? 'success' : 'ochre'">
            {{ precheck.solvable ? '可求解' : '不可求解' }}
          </UiTag>
        </p>
        <p class="tp__sub">错误 {{ errorCount }} · 警告 {{ warningCount }}</p>
      </div>
      <p v-if="!precheck.issues.length" class="tp__sub">没有发现问题。</p>
      <ul v-else class="tp__issue-list">
        <li v-for="(issue, i) in precheck.issues" :key="i" class="tp__issue">
          <UiTag :tone="issueTone(issue.severity)">{{ issue.severity }}</UiTag>
          <span class="tp__issue-code mono">{{ issue.code }}</span>
          <span class="tp__issue-msg">{{ issue.message }}</span>
        </li>
      </ul>
    </div>

    <UiDialog
      :open="open"
      :title="editing ? '修改教学任务' : '新建教学任务'"
      eyebrow="学期任务"
      width="lg"
      class="tp__dialog"
      :dismissible="!busy"
      @close="open = false"
    >
      <p v-if="detailLoading" class="tp__sub">正在读取任务详情…</p>
      <div v-else class="tp__form">
        <div class="tp__grid">
          <UiField v-model="form.code" label="任务编码" required placeholder="如 T-CS101-01" :maxlength="48" :error="errors.code" />
          <UiSelect
            :model-value="form.courseId"
            label="课程"
            :options="courseOptions"
            placeholder="选择课程"
            :error="errors.courseId"
            @update:model-value="(v: string) => (form.courseId = v)"
          />
          <UiSelect
            :model-value="form.teachingClassId"
            label="教学班"
            :options="teachingClassOptions"
            placeholder="选择教学班"
            :error="errors.teachingClassId"
            @update:model-value="(v: string) => (form.teachingClassId = v)"
          />
          <UiField v-model="form.startWeek" label="起始周" inputmode="numeric" :error="errors.startWeek" />
          <UiField v-model="form.endWeek" label="结束周" inputmode="numeric" :error="errors.endWeek" />
          <UiSelect
            :model-value="form.weekParity"
            label="单双周"
            :options="WEEK_PARITIES"
            @update:model-value="(v: string) => (form.weekParity = v)"
          />
          <UiField v-model="form.sessionsPerWeek" label="每周次数" inputmode="numeric" hint="同一周重复几次" />
          <UiField v-model="form.periodsPerSession" label="每次节数" inputmode="numeric" hint="2 表示连堂" />
          <UiField
            v-model="form.totalHours"
            label="总学时"
            inputmode="numeric"
            :hint="`按当前设置约 ${estimatedHours} 学时；留空按此估算`"
            :error="errors.totalHours ?? (hoursMismatch ? '与估算值不一致，预检会报错' : null)"
          />
          <UiSelect
            :model-value="form.roomType"
            label="教室类型要求"
            :options="ROOM_TYPES"
            @update:model-value="(v: string) => (form.roomType = v)"
          />
          <UiField v-model="form.priority" label="优先级 1~10" inputmode="numeric" />
          <UiSelect
            :model-value="form.status"
            label="状态"
            :options="[{ value: 'ACTIVE', label: '启用' }, { value: 'DISABLED', label: '停用' }]"
            @update:model-value="(v: string) => (form.status = v)"
          />
        </div>

        <div class="tp__checks">
          <label class="tp__check">
            <input v-model="form.roomRequired" type="checkbox" />
            需要教室
          </label>
          <label class="tp__check">
            <input v-model="form.allowSameDay" type="checkbox" />
            允许同一天安排多次
          </label>
          <label class="tp__check">
            <input v-model="form.uniformStart" type="checkbox" />
            每次课起始节一致
          </label>
        </div>

        <div class="tp__teachers">
          <p class="tp__teachers-head">
            授课教师
            <span class="tp__sub">共同授课全部计入工作量与冲突检查</span>
          </p>
          <div v-if="teachers.length" class="tp__teacher-list">
            <label v-for="tc in teachers" :key="tc.id" class="tp__check">
              <input
                type="checkbox"
                :checked="pickedTeachers.includes(tc.id)"
                @change="toggleTeacher(tc.id)"
              />
              {{ tc.displayName }}
            </label>
          </div>
          <p v-else class="tp__sub">没有可用教师，请先在「管理 → 用户」创建角色为 TEACHER 的账号。</p>
        </div>

        <div class="tp__rooms">
          <div class="tp__rooms-head">
            <p class="tp__teachers-head">
              指定教室
              <span class="tp__sub">不填则由算法按容量与类型选择</span>
            </p>
            <UiButton size="sm" variant="link" @click="addRoomRef">＋ 添加</UiButton>
          </div>
          <div v-if="roomRefs.length" class="tp__room-rows">
            <div v-for="(r, i) in roomRefs" :key="i" class="tp__room-row">
              <UiSelect
                :model-value="r.roomId"
                label=""
                :options="roomOptions"
                placeholder="选择教室"
                @update:model-value="(v: string) => (roomRefs[i]!.roomId = v)"
              />
              <UiSelect
                :model-value="r.mode"
                label=""
                :options="ROOM_MODES"
                @update:model-value="(v: string) => (roomRefs[i]!.mode = v)"
              />
              <UiButton size="sm" variant="link" @click="removeRoomRef(i)">移除</UiButton>
            </div>
          </div>
          <p v-else class="tp__sub">没有指定教室要求，算法会按容量、类型与设备自动匹配。</p>
        </div>

        <UiTextarea v-model="form.note" label="备注" :rows="2" :maxlength="200" />
      </div>

      <p v-if="errors.teachers" class="tp__error">{{ errors.teachers }}</p>
      <p v-if="errors.general" class="tp__error">{{ errors.general }}</p>
      <template #footer>
        <UiButton :disabled="busy" @click="open = false">取消</UiButton>
        <UiButton variant="primary" :loading="busy" :disabled="detailLoading" @click="submit">保存</UiButton>
      </template>
    </UiDialog>

    <UiDialog :open="!!deleteTarget" title="删除教学任务" width="sm" @close="deleteTarget = null">
      <p class="tp__confirm">
        确定删除「{{ deleteTarget?.code }} {{ deleteTarget?.courseName }}」吗？
        已出现在课表版本中的任务不能删除，只能改为「停用」。
      </p>
      <template #footer>
        <UiButton @click="deleteTarget = null">取消</UiButton>
        <UiButton variant="danger" :loading="deleteBusy" @click="confirmDelete">删除</UiButton>
      </template>
    </UiDialog>
  </section>
</template>

<style scoped>
/* 任务表单比通用弹窗高：放宽高度，避免备注与指定教室被折在滚动区外 */
:global(dialog.ui-dialog.tp__dialog) {
  max-height: min(100vh - 80px, 900px);
}

.tp {
  display: grid;
  gap: var(--s-4);
}

.tp__bar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s-3);
  flex-wrap: wrap;
}

.tp__actions {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex-wrap: wrap;
}

.tp__summary {
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.tp__pre.is-bad {
  color: var(--danger);
}

.tp__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.tp__table th {
  padding: 8px 10px;
  border-bottom: 1px solid var(--ink);
  color: var(--ink-2);
  font-size: var(--fs-small);
  font-weight: 600;
  text-align: left;
  white-space: nowrap;
}

.tp__table td {
  padding: 10px;
  border-bottom: var(--hairline);
  vertical-align: top;
}

.tp__name {
  margin: 0;
  font-weight: 600;
}

.tp__sub {
  margin: 2px 0 0;
  color: var(--ink-2);
  font-size: 12px;
}

.tp__sub.is-bad,
.mono.is-bad {
  color: var(--danger);
}

.tp__ops {
  text-align: right;
  white-space: nowrap;
}

.tp__issues {
  padding: var(--s-4);
  border: var(--hairline);
  background: var(--paper-lift);
}

.tp__issues-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-3);
  margin-bottom: var(--s-2);
}

.tp__issues-title {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  margin: 0;
  font-weight: 600;
}

.tp__issue-list {
  display: grid;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
  max-height: 40vh;
  overflow: auto;
}

.tp__issue {
  display: grid;
  grid-template-columns: 64px 140px 1fr;
  gap: 8px;
  align-items: start;
  padding: 6px 0;
  border-bottom: var(--hairline);
  font-size: 13px;
  line-height: 1.55;
}

.tp__issue-code {
  color: var(--ink-2);
  font-size: 11px;
  padding-top: 3px;
}

.tp__issue-msg {
  color: var(--ink);
}

.tp__form {
  display: grid;
  gap: var(--s-3);
}

.tp__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--s-3) var(--s-4);
}

.tp__form :deep(.ui-field__input),
.tp__form :deep(.ui-select__input) {
  height: 40px;
}

.tp__checks {
  display: flex;
  align-items: center;
  gap: var(--s-4);
  flex-wrap: wrap;
}

.tp__check {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  cursor: pointer;
}

.tp__teachers {
  padding-top: var(--s-2);
  border-top: var(--hairline);
}

.tp__teachers-head {
  display: flex;
  align-items: baseline;
  gap: var(--s-2);
  margin: 0 0 var(--s-2);
  font-weight: 600;
}

.tp__rooms {
  padding-top: var(--s-2);
  border-top: var(--hairline);
}

.tp__rooms-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-3);
}

.tp__room-rows {
  display: grid;
  gap: var(--s-2);
}

.tp__room-row {
  display: grid;
  grid-template-columns: 1fr 130px auto;
  gap: var(--s-2);
  align-items: end;
}

.tp__teacher-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px var(--s-4);
}

.tp__confirm {
  margin: 0;
  color: var(--ink-2);
  line-height: 1.7;
}

.tp__error {
  margin: var(--s-2) 0 0;
  color: var(--danger);
  font-size: var(--fs-small);
}

@media (max-width: 760px) {
  .tp__issue {
    grid-template-columns: 1fr;
  }

  .tp__table thead {
    display: none;
  }

  .tp__table tr {
    display: grid;
    gap: 2px;
    padding: var(--s-2) 0;
    border-bottom: var(--hairline);
  }

  .tp__table td {
    padding: 2px 0;
    border: 0;
  }

  .tp__ops {
    text-align: left;
  }

  .tp__room-row {
    grid-template-columns: 1fr;
    align-items: stretch;
  }
}
</style>
