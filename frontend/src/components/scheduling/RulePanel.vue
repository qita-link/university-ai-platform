<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { schedulingApi } from '@/api/endpoints'
import type {
  RuleTemplate,
  SchedulingClass,
  SchedulingCourse,
  SchedulingRoom,
  SchedulingTeacher,
  TeachingTask,
  TimeRule,
} from '@/api/types'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { useToastStore } from '@/stores/toast'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiEmpty from '@/components/ui/UiEmpty.vue'
import UiField from '@/components/ui/UiField.vue'
import UiSelect from '@/components/ui/UiSelect.vue'
import UiTag from '@/components/ui/UiTag.vue'

/**
 * 时间规则：把「谁、在什么时候、不能排/必须排/尽量避开」表达成可配置项。
 * FORBIDDEN/FIXED 是硬约束，AVOID/PREFERRED 只影响目标函数。
 */
const props = defineProps<{ semesterId: number; canManage: boolean }>()

const toast = useToastStore()

const TARGET_TYPES = [
  { value: 'GLOBAL', label: '全局（所有课程）' },
  { value: 'TEACHER', label: '教师' },
  { value: 'CLASS', label: '行政班' },
  { value: 'ROOM', label: '教室' },
  { value: 'COURSE', label: '课程' },
  { value: 'TASK', label: '教学任务' },
]

const KINDS = [
  { value: 'FORBIDDEN', label: '禁排（硬）' },
  { value: 'FIXED', label: '固定时段（硬）' },
  { value: 'AVOID', label: '尽量避开（软）' },
  { value: 'PREFERRED', label: '优先安排（软）' },
]

const rules = ref<TimeRule[]>([])
const templates = ref<RuleTemplate[]>([])
const loading = ref(false)

const teachers = ref<SchedulingTeacher[]>([])
const classes = ref<SchedulingClass[]>([])
const rooms = ref<SchedulingRoom[]>([])
const courses = ref<SchedulingCourse[]>([])
const tasks = ref<TeachingTask[]>([])

async function load() {
  if (!props.semesterId) return
  loading.value = true
  try {
    const [list, tpl] = await Promise.all([
      schedulingApi.rules({ semesterId: props.semesterId }),
      schedulingApi.ruleTemplates(),
    ])
    rules.value = list
    templates.value = tpl
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    loading.value = false
  }
}

async function loadTargets(type: string) {
  try {
    if (type === 'TEACHER' && !teachers.value.length) teachers.value = await schedulingApi.teachers()
    if (type === 'CLASS' && !classes.value.length) classes.value = await schedulingApi.classes()
    if (type === 'ROOM' && !rooms.value.length) rooms.value = (await schedulingApi.rooms({ size: 100 })).items
    if (type === 'COURSE' && !courses.value.length) courses.value = await schedulingApi.courses({ status: 'ACTIVE' })
    if (type === 'TASK' && !tasks.value.length) {
      const all: TeachingTask[] = []
      let page = 1
      for (;;) {
        const chunk = await schedulingApi.teachingTasks({ semesterId: props.semesterId, page, size: 100 })
        all.push(...chunk.items)
        if (page >= chunk.pages || !chunk.items.length) break
        page += 1
      }
      tasks.value = all
    }
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  }
}

onMounted(load)
watch(() => props.semesterId, load)

function targetLabel(rule: TimeRule) {
  const type = TARGET_TYPES.find((t) => t.value === rule.targetType)
  if (rule.targetType === 'GLOBAL') return '全局'
  return `${type?.label ?? rule.targetType}：${rule.targetName ?? '#' + rule.targetId}`
}

function kindLabel(kind: string) {
  return KINDS.find((k) => k.value === kind)?.label ?? kind
}

/** 后端的 timeText 已含周次，这里只取「星期 + 节次」，周次单独成列 */
function slotText(rule: TimeRule) {
  return rule.timeText.replace(/\s*第\d+-\d+周$/, '')
}

function weekText(rule: TimeRule) {
  const start = rule.startWeek ?? 1
  const end = rule.endWeek ?? 0
  // 后端把「不填」归一化成 1-30 周；按全学期展示，避免误读
  return end <= 0 || (start <= 1 && end >= 30) ? '全学期' : `${start}-${end} 周`
}

function kindTone(kind: string) {
  if (kind === 'FORBIDDEN' || kind === 'FIXED') return 'ochre'
  return 'muted'
}

/* ---------------- 新建 / 编辑 ---------------- */
const open = ref(false)
const busy = ref(false)
const editing = ref<TimeRule | null>(null)
const form = ref({
  targetType: 'GLOBAL',
  targetId: '',
  kind: 'FORBIDDEN',
  dayOfWeek: '',
  startPeriod: '1',
  endPeriod: '2',
  startWeek: '',
  endWeek: '',
  weight: '5',
  note: '',
})
const errors = ref<Record<string, string | null>>({})

const targetOptions = computed(() => {
  switch (form.value.targetType) {
    case 'TEACHER':
      return teachers.value.map((t) => ({ value: String(t.id), label: `${t.displayName}（${t.username}）` }))
    case 'CLASS':
      return classes.value.map((c) => ({ value: String(c.id), label: `${c.name}（${c.code}）` }))
    case 'ROOM':
      return rooms.value.map((r) => ({ value: String(r.id), label: `${r.code} ${r.name}` }))
    case 'COURSE':
      return courses.value.map((c) => ({ value: String(c.id), label: `${c.code} ${c.name}` }))
    case 'TASK':
      return tasks.value.map((t) => ({ value: String(t.id), label: `${t.code} ${t.courseName ?? ''}` }))
    default:
      return []
  }
})

function openCreate() {
  editing.value = null
  form.value = {
    targetType: 'GLOBAL',
    targetId: '',
    kind: 'FORBIDDEN',
    dayOfWeek: '',
    startPeriod: '1',
    endPeriod: '2',
    startWeek: '',
    endWeek: '',
    weight: '5',
    note: '',
  }
  errors.value = {}
  open.value = true
}

function openEdit(rule: TimeRule) {
  editing.value = rule
  form.value = {
    targetType: rule.targetType,
    targetId: rule.targetId == null ? '' : String(rule.targetId),
    kind: rule.kind,
    dayOfWeek: rule.dayOfWeek == null ? '' : String(rule.dayOfWeek),
    startPeriod: String(rule.startPeriod),
    endPeriod: String(rule.endPeriod),
    startWeek: rule.startWeek == null ? '' : String(rule.startWeek),
    endWeek: rule.endWeek == null ? '' : String(rule.endWeek),
    weight: String(rule.weight ?? 5),
    note: rule.note ?? '',
  }
  errors.value = {}
  void loadTargets(rule.targetType)
  open.value = true
}

function applyTemplate(t: RuleTemplate) {
  editing.value = null
  form.value = {
    targetType: 'GLOBAL',
    targetId: '',
    kind: t.kind,
    dayOfWeek: t.dayOfWeek == null ? '' : String(t.dayOfWeek),
    startPeriod: String(t.startPeriod),
    endPeriod: String(t.endPeriod),
    startWeek: '',
    endWeek: '',
    weight: '5',
    note: t.description ?? '',
  }
  errors.value = {}
  open.value = true
}

function onTargetTypeChange(v: string) {
  form.value.targetType = v
  form.value.targetId = ''
  if (v !== 'GLOBAL') void loadTargets(v)
}

async function submit() {
  errors.value = {}
  const f = form.value
  if (f.targetType !== 'GLOBAL' && !f.targetId) errors.value.targetId = '请选择规则对象'
  const start = Number(f.startPeriod)
  const end = Number(f.endPeriod)
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) errors.value.period = '结束节不能早于起始节'
  if (Object.keys(errors.value).length) return

  const body: Record<string, unknown> = {
    semesterId: props.semesterId,
    targetType: f.targetType,
    targetId: f.targetType === 'GLOBAL' ? null : Number(f.targetId),
    kind: f.kind,
    dayOfWeek: f.dayOfWeek === '' ? null : Number(f.dayOfWeek),
    startPeriod: start,
    endPeriod: end,
    startWeek: f.startWeek === '' ? null : Number(f.startWeek),
    endWeek: f.endWeek === '' ? null : Number(f.endWeek),
    weight: Number(f.weight) || 5,
    note: (f.note ?? '').trim() || null,
  }
  busy.value = true
  try {
    if (editing.value) await schedulingApi.updateRule(editing.value.id, body)
    else await schedulingApi.createRule(body)
    open.value = false
    toast.push(editing.value ? '规则已更新' : '规则已创建', 'success')
    await load()
  } catch (e) {
    errors.value = { general: errorMessage(e) }
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    busy.value = false
  }
}

const deleteTarget = ref<TimeRule | null>(null)
const deleteBusy = ref(false)

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleteBusy.value = true
  try {
    await schedulingApi.deleteRule(deleteTarget.value.id)
    toast.push('规则已删除', 'success')
    deleteTarget.value = null
    await load()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    deleteBusy.value = false
  }
}

const hardCount = computed(() => rules.value.filter((r) => r.kind === 'FORBIDDEN' || r.kind === 'FIXED').length)
</script>

<template>
  <section class="rp">
    <div class="rp__bar">
      <p class="rp__summary">
        共 <strong>{{ rules.length }}</strong> 条规则（硬约束 {{ hardCount }} 条）
        <span class="rp__sub">禁排/固定会直接排除候选，回避/优先只影响评分</span>
      </p>
      <div class="rp__actions">
        <UiButton v-if="props.canManage" size="sm" @click="openCreate">新建规则</UiButton>
      </div>
    </div>

    <div v-if="templates.length" class="rp__tpl">
      <span class="rp__tpl-label">常用模板</span>
      <button
        v-for="t in templates"
        :key="t.code"
        type="button"
        class="rp__tpl-item"
        :disabled="!props.canManage"
        :title="t.description"
        @click="applyTemplate(t)"
      >
        {{ t.name }}
      </button>
    </div>

    <UiEmpty
      v-if="!rules.length && !loading"
      title="还没有时间规则"
      hint="可以直接点上面的模板，或新建一条（例如午休禁排、周五下午回避）"
    />

    <table v-else class="rp__table">
      <thead>
        <tr>
          <th>对象</th>
          <th>规则</th>
          <th>时间</th>
          <th>周次</th>
          <th>权重</th>
          <th>备注</th>
          <th />
        </tr>
      </thead>
      <tbody>
        <tr v-for="r in rules" :key="r.id">
          <td>{{ targetLabel(r) }}</td>
          <td><UiTag :tone="kindTone(r.kind)">{{ kindLabel(r.kind) }}</UiTag></td>
          <td class="rp__sub">{{ slotText(r) }}</td>
          <td class="rp__sub">{{ weekText(r) }}</td>
          <td class="mono">{{ r.kind === 'FORBIDDEN' || r.kind === 'FIXED' ? '—' : r.weight ?? 5 }}</td>
          <td class="rp__sub">{{ r.note || '—' }}</td>
          <td class="rp__ops">
            <template v-if="props.canManage">
              <UiButton size="sm" variant="link" @click="openEdit(r)">编辑</UiButton>
              <UiButton size="sm" variant="link" @click="deleteTarget = r">删除</UiButton>
            </template>
          </td>
        </tr>
      </tbody>
    </table>

    <UiDialog :open="open" :title="editing ? '修改时间规则' : '新建时间规则'" eyebrow="规则" width="lg" @close="open = false">
      <div class="rp__grid">
        <UiSelect
          :model-value="form.targetType"
          label="规则对象"
          :options="TARGET_TYPES"
          @update:model-value="onTargetTypeChange"
        />
        <UiSelect
          v-if="form.targetType !== 'GLOBAL'"
          :model-value="form.targetId"
          label="选择对象"
          :options="targetOptions"
          placeholder="请选择"
          :error="errors.targetId"
          @update:model-value="(v: string) => (form.targetId = v)"
        />
        <UiSelect
          :model-value="form.kind"
          label="规则类型"
          :options="KINDS"
          @update:model-value="(v: string) => (form.kind = v)"
        />
        <UiSelect
          :model-value="form.dayOfWeek"
          label="星期"
          :options="[
            { value: '', label: '每天' },
            { value: '1', label: '周一' },
            { value: '2', label: '周二' },
            { value: '3', label: '周三' },
            { value: '4', label: '周四' },
            { value: '5', label: '周五' },
            { value: '6', label: '周六' },
            { value: '7', label: '周日' },
          ]"
          @update:model-value="(v: string) => (form.dayOfWeek = v)"
        />
        <UiField v-model="form.startPeriod" label="起始节" inputmode="numeric" :error="errors.period" />
        <UiField v-model="form.endPeriod" label="结束节" inputmode="numeric" />
        <UiField v-model="form.startWeek" label="起始周" inputmode="numeric" hint="留空表示全学期" />
        <UiField v-model="form.endWeek" label="结束周" inputmode="numeric" />
        <UiField
          v-model="form.weight"
          label="权重（软规则）"
          inputmode="numeric"
          hint="越大越优先满足；硬规则忽略此项"
        />
      </div>
      <UiField v-model="form.note" class="rp__note" label="备注" :maxlength="200" />
      <p v-if="errors.general" class="rp__error">{{ errors.general }}</p>
      <template #footer>
        <UiButton @click="open = false">取消</UiButton>
        <UiButton variant="primary" :loading="busy" @click="submit">保存</UiButton>
      </template>
    </UiDialog>

    <UiDialog :open="!!deleteTarget" title="删除时间规则" width="sm" @close="deleteTarget = null">
      <p class="rp__confirm">
        确定删除「{{ deleteTarget ? targetLabel(deleteTarget) : '' }} / {{ deleteTarget ? kindLabel(deleteTarget.kind) : '' }}」这条规则吗？
      </p>
      <template #footer>
        <UiButton @click="deleteTarget = null">取消</UiButton>
        <UiButton variant="danger" :loading="deleteBusy" @click="confirmDelete">删除</UiButton>
      </template>
    </UiDialog>
  </section>
</template>

<style scoped>
.rp {
  display: grid;
  gap: var(--s-3);
}

.rp__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  flex-wrap: wrap;
}

.rp__summary {
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.rp__sub {
  margin: 0;
  color: var(--ink-2);
  font-size: 12px;
}

.rp__tpl {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  padding: var(--s-2) 0;
  border-top: var(--hairline);
  border-bottom: var(--hairline);
}

.rp__tpl-label {
  color: var(--ink-2);
  font-size: 12px;
  letter-spacing: 0.08em;
}

.rp__tpl-item {
  padding: 4px 10px;
  border: var(--hairline);
  background: var(--paper);
  color: var(--ink);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}

.rp__tpl-item:hover:not(:disabled) {
  border-color: var(--teal);
  color: var(--teal);
}

.rp__tpl-item:disabled {
  opacity: 0.5;
  cursor: default;
}

.rp__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
  table-layout: fixed;
}

.rp__table th:nth-child(1) { width: 26%; }
.rp__table th:nth-child(2) { width: 13%; }
.rp__table th:nth-child(3) { width: 22%; }
.rp__table th:nth-child(4) { width: 10%; }
.rp__table th:nth-child(5) { width: 7%; }
.rp__table th:nth-child(6) { width: 14%; }

.rp__table th {
  padding: 8px 10px;
  border-bottom: 1px solid var(--ink);
  color: var(--ink-2);
  font-size: var(--fs-small);
  font-weight: 600;
  text-align: left;
  white-space: nowrap;
}

.rp__table td {
  padding: 9px 10px;
  border-bottom: var(--hairline);
  vertical-align: top;
}

.rp__ops {
  text-align: right;
  white-space: nowrap;
}

.rp__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--s-3) var(--s-4);
}

.rp__note {
  margin-top: var(--s-4);
}

.rp__confirm {
  margin: 0;
  color: var(--ink-2);
  line-height: 1.7;
}

.rp__error {
  margin: var(--s-2) 0 0;
  color: var(--danger);
  font-size: var(--fs-small);
}

@media (max-width: 760px) {
  .rp__table thead {
    display: none;
  }

  .rp__table tr {
    display: grid;
    gap: 2px;
    padding: var(--s-2) 0;
    border-bottom: var(--hairline);
  }

  .rp__table td {
    padding: 2px 0;
    border: 0;
  }

  .rp__ops {
    text-align: left;
  }
}
</style>
