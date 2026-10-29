<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { orgApi, schedulingApi } from '@/api/endpoints'
import type { ConstraintSetting, OrgTreeNode, ScheduleJob, ScheduleJobLog, ScheduleVersion, TeachingTask } from '@/api/types'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { useToastStore } from '@/stores/toast'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiEmpty from '@/components/ui/UiEmpty.vue'
import UiField from '@/components/ui/UiField.vue'
import UiSelect from '@/components/ui/UiSelect.vue'
import UiTag from '@/components/ui/UiTag.vue'

/**
 * 排课任务面板：创建 → 启动 → 轮询进度 → 查看日志。
 * 求解是异步的，这里用 2 秒轮询；离开页面或切换学期时清掉定时器。
 */
const props = defineProps<{
  semesterId: number
  canManage: boolean
  publishedVersionId: number | null
  versions?: ScheduleVersion[]
}>()
const emit = defineEmits<{ changed: []; openVersion: [versionId: number] }>()

const toast = useToastStore()

const jobs = ref<ScheduleJob[]>([])
const loading = ref(false)
const busy = ref(false)
const error = ref<string | null>(null)

const name = ref('')
const timeLimit = ref(60)
const workers = ref(4)
const allowPartial = ref(true)
const keepLocked = ref(true)
const mode = ref<'FULL' | 'INCREMENTAL'>('FULL')

/* ---------------- 高级参数 ---------------- */
const advanced = ref(false)
const scopeMode = ref<'ALL' | 'ORG' | 'TASKS'>('ALL')
const orgUnitId = ref('')
const pickedTasks = ref<number[]>([])
const taskKeyword = ref('')
const randomSeed = ref('')
const baseVersionId = ref('')
const weightOverrides = ref<Record<string, string>>({})

const orgs = ref<{ value: string; label: string }[]>([])
const taskOptions = ref<TeachingTask[]>([])
const constraints = ref<ConstraintSetting[]>([])
const advancedLoaded = ref(false)

const versionChoices = computed(() =>
  (props.versions ?? []).map((v) => ({
    value: String(v.id),
    label: `#${v.versionNo} ${v.name} · ${v.status === 'PUBLISHED' ? '已发布' : v.status === 'DRAFT' ? '草稿' : '归档'}`,
  })),
)

async function ensureAdvanced() {
  if (advancedLoaded.value || !props.semesterId) return
  advancedLoaded.value = true
  try {
    const [tree, tasks, cs] = await Promise.all([
      orgApi.tree(),
      loadAllTasks(),
      schedulingApi.constraints(props.semesterId),
    ])
    orgs.value = flatten(tree)
      .filter((n) => n.type !== 'CLASS')
      .map((n) => ({ value: String(n.id), label: `${n.name}（${n.code}）` }))
    taskOptions.value = tasks
    constraints.value = cs.filter((c) => !c.hard)
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  }
}

/** 教学任务可能超过一页（后端 size 上限 100），逐页取完 */
async function loadAllTasks() {
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

function flatten(nodes: OrgTreeNode[]): OrgTreeNode[] {
  return nodes.flatMap((n) => [n, ...flatten(n.children ?? [])])
}

const shownTasks = computed(() => {
  const k = taskKeyword.value.trim()
  const list = taskOptions.value
  return k ? list.filter((t) => `${t.code} ${t.courseName} ${t.teachingClassName}`.includes(k)) : list
})

function toggleTask(id: number) {
  pickedTasks.value = pickedTasks.value.includes(id)
    ? pickedTasks.value.filter((x) => x !== id)
    : [...pickedTasks.value, id]
}

const overrideCount = computed(() => Object.values(weightOverrides.value).filter((v) => v.trim() !== '').length)

function toggleAdvanced() {
  advanced.value = !advanced.value
  if (advanced.value) void ensureAdvanced()
}

function clearOverrides() {
  weightOverrides.value = {}
}

function selectAllTasks() {
  pickedTasks.value = shownTasks.value.map((t) => t.id)
}

const logsOpen = ref(false)
const logs = ref<ScheduleJobLog[]>([])
const logsFor = ref<ScheduleJob | null>(null)

let timer: number | null = null

const running = computed(() => jobs.value.some((j) => j.status === 'QUEUED' || j.status === 'RUNNING'))

const STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  QUEUED: '排队中',
  RUNNING: '求解中',
  SUCCEEDED: '已排满',
  PARTIAL: '部分安排',
  INFEASIBLE: '无可行解',
  UNKNOWN: '未确定',
  FAILED: '失败',
  CANCELLED: '已取消',
}

function tone(status: string): 'neutral' | 'teal' | 'success' | 'warning' | 'ochre' | 'muted' {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'PARTIAL' || status === 'UNKNOWN') return 'warning'
  if (status === 'FAILED' || status === 'INFEASIBLE') return 'ochre'
  if (status === 'CANCELLED') return 'muted'
  return 'neutral'
}

async function load() {
  if (!props.semesterId) return
  loading.value = true
  try {
    const page = await schedulingApi.jobs({ semesterId: props.semesterId, page: 1, size: 20 })
    jobs.value = page.items
    error.value = null
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

function ensureTimer() {
  if (timer !== null) return
  timer = window.setInterval(async () => {
    if (!running.value) return
    const before = jobs.value.map((j) => `${j.id}:${j.status}`).join(',')
    await load()
    const after = jobs.value.map((j) => `${j.id}:${j.status}`).join(',')
    if (before !== after && !running.value) emit('changed')
  }, 2000)
}

async function start() {
  if (!props.semesterId) return
  busy.value = true
  try {
    const overrides: Record<string, number> = {}
    if (advanced.value) {
      for (const [code, raw] of Object.entries(weightOverrides.value)) {
        if (raw.trim() !== '') overrides[code] = Number(raw)
      }
    }
    const job = await schedulingApi.createJob({
      semesterId: props.semesterId,
      name: name.value.trim() || `排课 ${new Date().toLocaleString('zh-CN')}`,
      mode: mode.value,
      baseVersionId: mode.value === 'INCREMENTAL'
        ? (baseVersionId.value ? Number(baseVersionId.value) : props.publishedVersionId)
        : null,
      timeLimitSeconds: timeLimit.value,
      workers: workers.value,
      allowPartial: allowPartial.value,
      keepLocked: keepLocked.value,
      taskIds: advanced.value && scopeMode.value === 'TASKS' ? pickedTasks.value : undefined,
      orgUnitId: advanced.value && scopeMode.value === 'ORG' && orgUnitId.value ? Number(orgUnitId.value) : undefined,
      randomSeed: advanced.value && randomSeed.value.trim() !== '' ? Number(randomSeed.value) : undefined,
      weightOverrides: Object.keys(overrides).length ? overrides : undefined,
    })
    await schedulingApi.runJob(job.id)
    toast.push('排课任务已提交，正在求解', 'success')
    name.value = ''
    await load()
    ensureTimer()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    busy.value = false
  }
}

async function cancel(job: ScheduleJob) {
  busy.value = true
  try {
    await schedulingApi.cancelJob(job.id)
    toast.push('已请求取消', 'info')
    await load()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    busy.value = false
  }
}

async function openLogs(job: ScheduleJob) {
  logsFor.value = job
  logsOpen.value = true
  try {
    logs.value = await schedulingApi.jobLogs(job.id)
  } catch (e) {
    logs.value = []
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  }
}

function fmtTime(value: string | null) {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 19)
}

function fmtElapsed(ms: number) {
  if (!ms) return '—'
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`
}

onMounted(async () => {
  await load()
  ensureTimer()
})

watch(
  () => props.semesterId,
  async () => {
    jobs.value = []
    await load()
  },
)

onBeforeUnmount(() => {
  if (timer !== null) window.clearInterval(timer)
})
</script>

<template>
  <section class="jobs">
    <div class="jobs__form">
      <div class="jobs__form-main">
        <label class="jobs__field">
          <span>任务名称</span>
          <input v-model="name" type="text" placeholder="例如：2026 秋第一轮全量排课" maxlength="128" />
        </label>
        <label class="jobs__field jobs__field--sm">
          <span>求解上限（秒）</span>
          <input v-model.number="timeLimit" type="number" min="1" max="3600" />
        </label>
        <label class="jobs__field jobs__field--sm">
          <span>并行线程</span>
          <input v-model.number="workers" type="number" min="1" max="8" />
        </label>
        <label class="jobs__field jobs__field--sm">
          <span>模式</span>
          <select v-model="mode">
            <option value="FULL">全量重排</option>
            <option value="INCREMENTAL" :disabled="!publishedVersionId">增量（基于已发布）</option>
          </select>
        </label>
      </div>
      <div class="jobs__form-foot">
        <label class="jobs__check">
          <input v-model="allowPartial" type="checkbox" />
          <span>允许部分安排（否则必须排满）</span>
        </label>
        <label class="jobs__check">
          <input v-model="keepLocked" type="checkbox" />
          <span>保留已锁定课次</span>
        </label>
        <UiButton
          v-if="canManage"
          variant="primary"
          :loading="busy"
          :disabled="busy || running"
          @click="start"
        >
          {{ running ? '已有任务在执行' : '开始排课' }}
        </UiButton>
      </div>
      <div class="jobs__adv-toggle">
        <button type="button" class="jobs__adv-btn" @click="toggleAdvanced">
          {{ advanced ? '收起高级参数' : '高级参数（范围 / 随机种子 / 权重覆盖）' }}
        </button>
        <span v-if="advanced && (scopeMode !== 'ALL' || overrideCount || randomSeed)" class="jobs__adv-badge">已设置</span>
      </div>

      <div v-if="advanced" class="jobs__adv">
        <UiSelect
          :model-value="scopeMode"
          label="求解范围"
          :options="[
            { value: 'ALL', label: '整个学期' },
            { value: 'ORG', label: '指定学院/专业' },
            { value: 'TASKS', label: '指定教学任务' },
          ]"
          @update:model-value="(v: string) => (scopeMode = v as 'ALL' | 'ORG' | 'TASKS')"
        />
        <UiSelect
          v-if="scopeMode === 'ORG'"
          :model-value="orgUnitId"
          label="学院 / 专业"
          :options="orgs"
          placeholder="选择范围"
          @update:model-value="(v: string) => (orgUnitId = v)"
        />
        <UiSelect
          v-if="mode === 'INCREMENTAL'"
          :model-value="baseVersionId"
          label="基准版本"
          :options="versionChoices"
          placeholder="默认用已发布版本"
          @update:model-value="(v: string) => (baseVersionId = v)"
        />
        <UiField v-model="randomSeed" label="随机种子" inputmode="numeric" hint="固定种子 + 单线程可复现同一课表" />

        <div v-if="scopeMode === 'TASKS'" class="jobs__tasks">
          <div class="jobs__tasks-head">
            <span class="jobs__label">选择教学任务（已选 {{ pickedTasks.length }} 个）</span>
            <div class="jobs__tasks-actions">
              <UiButton size="sm" variant="link" @click="selectAllTasks">全选当前</UiButton>
              <UiButton size="sm" variant="link" @click="pickedTasks = []">清空</UiButton>
            </div>
          </div>
          <input v-model="taskKeyword" class="jobs__search" type="search" placeholder="按任务编号 / 课程 / 教学班筛选" />
          <div class="jobs__task-list">
            <label v-for="t in shownTasks" :key="t.id" class="jobs__task">
              <input type="checkbox" :checked="pickedTasks.includes(t.id)" @change="toggleTask(t.id)" />
              <span class="mono">{{ t.code }}</span>
              <span>{{ t.courseName }}</span>
              <span class="jobs__task-cls">{{ t.teachingClassName }}</span>
            </label>
            <p v-if="!shownTasks.length" class="jobs__sub">没有匹配的教学任务。</p>
          </div>
        </div>

        <div class="jobs__weights">
          <div class="jobs__tasks-head">
            <span class="jobs__label">软约束权重覆盖（留空＝用学期设置）</span>
            <div class="jobs__tasks-actions">
              <span class="jobs__sub">已覆盖 {{ overrideCount }} 项</span>
              <UiButton size="sm" variant="link" @click="clearOverrides">清空</UiButton>
            </div>
          </div>
          <div class="jobs__weight-grid">
            <label v-for="c in constraints" :key="c.code" class="jobs__weight">
              <span class="jobs__weight-label">{{ c.label }}</span>
              <input
                :value="weightOverrides[c.code] ?? ''"
                class="jobs__weight-input mono"
                type="number"
                min="0"
                max="1000"
                :placeholder="String(c.weight)"
                @input="weightOverrides[c.code] = ($event.target as HTMLInputElement).value"
              />
            </label>
          </div>
        </div>
      </div>

      <p v-if="error" class="jobs__error">{{ error }}</p>
    </div>

    <UiEmpty v-if="!jobs.length && !loading" title="这个学期还没有排课记录" hint="设置求解上限后点击「开始排课」" />
    <table v-else class="jobs__table">
      <thead>
        <tr>
          <th>任务</th>
          <th>状态</th>
          <th>进度</th>
          <th>求解器</th>
          <th>版本</th>
          <th>耗时</th>
          <th aria-label="操作" />
        </tr>
      </thead>
      <tbody>
        <tr v-for="job in jobs" :key="job.id">
          <td>
            <p class="jobs__name">{{ job.name }}</p>
            <p class="jobs__sub mono">{{ job.mode }} · #{{ job.id }} · {{ fmtTime(job.startedAt) }}</p>
            <p v-if="job.message" class="jobs__msg">{{ job.message }}</p>
          </td>
          <td>
            <UiTag :tone="tone(job.status)">{{ STATUS_LABEL[job.status] ?? job.status }}</UiTag>
          </td>
          <td>
            <div class="jobs__bar" :aria-label="`进度 ${job.progress}%`">
              <span :style="{ width: `${job.progress}%` }" />
            </div>
            <p class="jobs__sub">{{ job.phase ?? '—' }}</p>
          </td>
          <td class="mono jobs__sub">{{ job.solverStatus ?? '—' }}</td>
          <td>
            <button
              v-if="job.resultVersionId"
              type="button"
              class="jobs__link"
              @click="emit('openVersion', job.resultVersionId)"
            >
              #{{ job.resultVersionId }}
            </button>
            <span v-else class="jobs__sub">—</span>
          </td>
          <td class="mono jobs__sub">{{ fmtElapsed(job.elapsedMillis) }}</td>
          <td class="jobs__actions">
            <UiButton size="sm" variant="link" @click="openLogs(job)">日志</UiButton>
            <UiButton
              v-if="canManage && (job.status === 'RUNNING' || job.status === 'QUEUED')"
              size="sm"
              variant="link"
              @click="cancel(job)"
            >
              取消
            </UiButton>
          </td>
        </tr>
      </tbody>
    </table>

    <UiDialog :open="logsOpen" title="排课日志" :eyebrow="logsFor?.name ?? ''" width="lg" @close="logsOpen = false">
      <div class="logs">
        <p v-if="!logs.length" class="logs__empty">还没有日志</p>
        <p v-for="(line, i) in logs" :key="i" class="logs__line" :class="`is-${line.level.toLowerCase()}`">
          <span class="logs__time mono">{{ line.createdAt.slice(11, 19) }}</span>
          <span class="logs__level mono">{{ line.level }}</span>
          <span class="logs__text">{{ line.message }}</span>
        </p>
      </div>
    </UiDialog>
  </section>
</template>

<style scoped>
.jobs {
  display: grid;
  gap: var(--s-4);
}

.jobs__form {
  display: grid;
  gap: var(--s-3);
  padding: var(--s-4);
  border: var(--hairline);
  background: var(--paper-lift);
}

.jobs__form-main {
  display: grid;
  grid-template-columns: minmax(220px, 2fr) repeat(3, minmax(110px, 1fr));
  gap: var(--s-3);
}

.jobs__field {
  display: grid;
  gap: 4px;
  font-size: var(--fs-small);
  color: var(--ink-2);
}

.jobs__field input,
.jobs__field select {
  min-height: 36px;
  padding: 0 10px;
  border: 1px solid var(--rule);
  background: var(--paper);
  color: var(--ink);
  font: inherit;
  font-size: 14px;
}

.jobs__form-foot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--s-4);
}

.jobs__check {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.jobs__form-foot .ui-btn {
  margin-left: auto;
}

.jobs__adv-toggle {
  display: flex;
  align-items: center;
  gap: var(--s-2);
}

.jobs__adv-btn {
  padding: 0;
  border: 0;
  background: none;
  color: var(--teal);
  font: inherit;
  font-size: var(--fs-small);
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 3px;
}

.jobs__adv-badge {
  padding: 1px 6px;
  border: 1px solid var(--teal);
  color: var(--teal);
  font-size: 11px;
}

.jobs__adv {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--s-3) var(--s-4);
  padding: var(--s-3);
  border: var(--hairline);
  background: var(--paper-lift);
}

.jobs__label {
  color: var(--ink-2);
  font-size: var(--fs-small);
  font-weight: 600;
  letter-spacing: 0.06em;
}

.jobs__tasks,
.jobs__weights {
  grid-column: 1 / -1;
  display: grid;
  gap: var(--s-2);
}

.jobs__tasks-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-3);
  flex-wrap: wrap;
}

.jobs__tasks-actions {
  display: flex;
  align-items: center;
  gap: var(--s-3);
}

.jobs__search {
  height: 34px;
  padding: 0 8px;
  border: var(--hairline);
  background: var(--paper);
  color: var(--ink);
  font: inherit;
  font-size: 13px;
}

.jobs__task-list {
  display: grid;
  gap: 2px;
  max-height: 180px;
  overflow: auto;
  padding: 4px;
  border: var(--hairline);
  background: var(--paper);
}

.jobs__task {
  display: grid;
  grid-template-columns: 18px 64px 1fr auto;
  gap: 8px;
  align-items: center;
  padding: 3px 4px;
  font-size: 13px;
  cursor: pointer;
}

.jobs__task:hover {
  background: var(--paper-lift);
}

.jobs__task-cls {
  color: var(--ink-2);
  font-size: 12px;
}

.jobs__weight-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 4px var(--s-4);
}

.jobs__weight {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-2);
  font-size: 13px;
}

.jobs__weight-label {
  color: var(--ink-2);
}

.jobs__weight-input {
  width: 64px;
  height: 28px;
  padding: 0 6px;
  border: var(--hairline);
  background: var(--paper);
  color: var(--ink);
  font-size: 13px;
}

.jobs__error {
  margin: 0;
  color: var(--danger);
  font-size: var(--fs-small);
}

.jobs__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.jobs__table th {
  padding: 8px 10px;
  border-bottom: 1px solid var(--ink);
  color: var(--ink-2);
  font-size: var(--fs-small);
  font-weight: 600;
  text-align: left;
  white-space: nowrap;
}

.jobs__table td {
  padding: 10px;
  border-bottom: var(--hairline);
  vertical-align: top;
}

.jobs__name {
  margin: 0;
  font-weight: 600;
}

.jobs__sub {
  margin: 2px 0 0;
  color: var(--ink-2);
  font-size: 12px;
}

.jobs__msg {
  margin: 4px 0 0;
  max-width: 46ch;
  color: var(--ink-2);
  font-size: 12px;
  line-height: 1.5;
}

.jobs__bar {
  width: 100px;
  height: 4px;
  background: var(--paper-deep);
}

.jobs__bar span {
  display: block;
  height: 100%;
  background: var(--teal);
  transition: width 0.2s var(--ease);
}

.jobs__link {
  padding: 0;
  border: 0;
  background: none;
  color: var(--teal);
  font: inherit;
  cursor: pointer;
}

.jobs__link:hover {
  text-decoration: underline;
}

.jobs__actions {
  white-space: nowrap;
  text-align: right;
}

.logs {
  max-height: 56vh;
  overflow: auto;
  font-size: 13px;
}

.logs__line {
  display: grid;
  grid-template-columns: 64px 48px 1fr;
  gap: 8px;
  margin: 0;
  padding: 5px 0;
  border-bottom: var(--hairline);
  line-height: 1.5;
}

.logs__time,
.logs__level {
  color: var(--ink-2);
  font-size: 11px;
}

.logs__line.is-error .logs__level {
  color: var(--danger);
}

.logs__line.is-warn .logs__level {
  color: var(--ochre, #9a6b1f);
}

.logs__empty {
  margin: 0;
  color: var(--ink-2);
}

@media (max-width: 760px) {
  .jobs__form-main {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
