<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { schedulingApi } from '@/api/endpoints'
import type { ScheduleCandidate, ScheduleEntry } from '@/api/types'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { useToastStore } from '@/stores/toast'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiTag from '@/components/ui/UiTag.vue'

/**
 * 课时调整：列出求解器认可的候选位置（已排除与现有课表冲突的组合），
 * 也可以取消安排或锁定。后端在真正落库前会再复核一次，冲突会返回 409。
 */
const props = defineProps<{
  open: boolean
  versionId: number
  entry: ScheduleEntry | null
  editable: boolean
  canAdjust: boolean
}>()
const emit = defineEmits<{ close: []; changed: [] }>()

const toast = useToastStore()
const candidates = ref<ScheduleCandidate[]>([])
const loading = ref(false)
const busy = ref(false)

const DAY_NAMES = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日']

const available = computed(() => candidates.value.filter((c) => c.available))
const blocked = computed(() => candidates.value.filter((c) => !c.available).length)

/** 同一时段往往有多间教室可用，按「星期 + 起始节」归组，避免 80 个按钮重复同一个时间标签 */
const groups = computed(() => {
  const map = new Map<string, { day: number; start: number; end: number; rooms: ScheduleCandidate[] }>()
  for (const c of available.value) {
    const key = `${c.dayOfWeek}-${c.startPeriod}`
    const group = map.get(key) ?? { day: c.dayOfWeek, start: c.startPeriod, end: c.endPeriod, rooms: [] }
    group.rooms.push(c)
    map.set(key, group)
  }
  return [...map.values()]
    .sort((a, b) => a.day - b.day || a.start - b.start)
    .slice(0, 40)
    .map((g) => ({
      ...g,
      label: `${DAY_NAMES[g.day] ?? ''} ${g.start === g.end ? `第${g.start}节` : `第${g.start}-${g.end}节`}`,
      isCurrent: props.entry?.dayOfWeek === g.day && props.entry?.startPeriod === g.start,
    }))
})

/** 当前位置的原样按钮：点了也不会变，直接禁用，避免用户误以为没生效 */
function isCurrentRoom(c: ScheduleCandidate) {
  return (
    !!props.entry &&
    props.entry.dayOfWeek === c.dayOfWeek &&
    props.entry.startPeriod === c.startPeriod &&
    (props.entry.roomId ?? null) === (c.roomId ?? null)
  )
}

function slot(c: ScheduleCandidate) {
  const periods = c.startPeriod === c.endPeriod ? `第${c.startPeriod}节` : `第${c.startPeriod}-${c.endPeriod}节`
  return `${DAY_NAMES[c.dayOfWeek] ?? ''} ${periods}`
}

async function load() {
  if (!props.entry) return
  loading.value = true
  try {
    candidates.value = await schedulingApi.candidates(props.versionId, props.entry.id)
  } catch (e) {
    candidates.value = []
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    loading.value = false
  }
}

async function move(c: ScheduleCandidate) {
  if (!props.entry) return
  busy.value = true
  try {
    await schedulingApi.move(props.versionId, props.entry.id, {
      dayOfWeek: c.dayOfWeek,
      startPeriod: c.startPeriod,
      roomId: c.roomId,
      reason: '排课页调整',
    })
    toast.push(`已调整到 ${slot(c)}`, 'success')
    emit('changed')
    emit('close')
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    busy.value = false
  }
}

async function unplace() {
  if (!props.entry) return
  busy.value = true
  try {
    await schedulingApi.move(props.versionId, props.entry.id, { dayOfWeek: null, reason: '取消安排' })
    toast.push('已取消该课次安排', 'info')
    emit('changed')
    emit('close')
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    busy.value = false
  }
}

async function toggleLock() {
  if (!props.entry) return
  busy.value = true
  try {
    const locked = !props.entry.locked
    await schedulingApi.lock(props.versionId, props.entry.id, locked, locked ? '页面锁定' : '页面解锁')
    toast.push(locked ? '已锁定，重排时会保留' : '已解锁', 'success')
    emit('changed')
    emit('close')
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    busy.value = false
  }
}

watch(
  () => [props.open, props.entry?.id],
  ([open]) => {
    if (open) load()
  },
)
</script>

<template>
  <UiDialog
    :open="open"
    title="调整课时"
    :eyebrow="entry ? `${entry.courseName ?? ''} · ${entry.taskCode ?? ''}` : ''"
    width="lg"
    @close="emit('close')"
  >
    <div v-if="entry" class="adj">
      <div class="adj__current">
        <p class="adj__slot">{{ entry.timeText }}</p>
        <p class="adj__meta">
          {{ entry.teacherNames.join('、') }} · {{ entry.classNames.join('、') }} ·
          {{ entry.roomCode ?? '未指定教室' }} · {{ entry.weekText }}
        </p>
        <div class="adj__tags">
          <UiTag v-if="entry.locked" tone="ochre">已锁定</UiTag>
          <UiTag tone="muted">{{ entry.origin }}</UiTag>
        </div>
      </div>

      <template v-if="canAdjust && editable">
        <div class="adj__head">
          <p class="adj__title">可选位置</p>
          <p class="adj__hint">
            {{ loading ? '正在计算…' : `可用 ${available.length} 个` }}
            <span v-if="blocked">· 另有 {{ blocked }} 个被冲突占用</span>
          </p>
        </div>
        <div v-if="groups.length" class="adj__grid">
          <div v-for="g in groups" :key="`${g.day}-${g.start}`" class="adj__group">
            <p class="adj__cand-slot">
              {{ g.label }}
              <span v-if="g.isCurrent" class="adj__now">当前</span>
            </p>
            <div class="adj__rooms">
              <button
                v-for="c in g.rooms"
                :key="`${g.day}-${g.start}-${c.roomId}`"
                type="button"
                class="adj__cand"
                :class="{ 'is-current': isCurrentRoom(c) }"
                :disabled="busy || isCurrentRoom(c)"
                @click="move(c)"
              >
                {{ c.roomCode ?? '无需教室' }}
              </button>
            </div>
          </div>
        </div>
        <p v-else-if="!loading" class="adj__hint">没有可用的空位：该任务需要的教室与时段都已被占用。</p>

        <div class="adj__foot">
          <UiButton size="sm" :loading="busy" @click="toggleLock">
            {{ entry.locked ? '解除锁定' : '锁定此课次' }}
          </UiButton>
          <UiButton size="sm" variant="danger" :loading="busy" @click="unplace">取消安排</UiButton>
        </div>
      </template>
      <p v-else class="adj__hint">
        {{
          canAdjust
            ? '只有草稿版本可以调整。已发布版本请先复制为新草稿，或撤销发布后再改。'
            : '当前账号没有调整课表的权限（需要 schedule:adjust），只能查看。'
        }}
      </p>
    </div>
  </UiDialog>
</template>

<style scoped>
.adj {
  display: grid;
  gap: var(--s-4);
  max-height: 68vh;
  overflow: auto;
  padding-right: 2px;
}

.adj__current {
  padding: var(--s-3) var(--s-4);
  border-left: 3px solid var(--teal);
  background: var(--paper-lift);
}

.adj__slot {
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-size: 18px;
  font-weight: 600;
}

.adj__meta {
  margin: 4px 0 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.6;
}

.adj__tags {
  display: flex;
  gap: 6px;
  margin-top: 6px;
}

.adj__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-3);
  border-bottom: 1px solid var(--ink);
  padding-bottom: 6px;
}

.adj__title {
  margin: 0;
  font-weight: 600;
}

.adj__hint {
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.adj__grid {
  display: grid;
  gap: 2px;
  max-height: min(40vh, 340px);
  overflow: auto;
}

.adj__group {
  display: grid;
  grid-template-columns: 150px 1fr;
  gap: var(--s-3);
  align-items: center;
  padding: 6px 0;
  border-bottom: var(--hairline);
}

.adj__rooms {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.adj__cand {
  padding: 4px 10px;
  border: var(--hairline);
  background: var(--paper);
  color: var(--ink);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  transition: background-color var(--t-fast) var(--ease);
}

.adj__now {
  margin-left: 6px;
  color: var(--teal);
  font-size: 11px;
  font-weight: 600;
}

.adj__cand:hover:not(:disabled) {
  background: var(--paper-deep);
}

.adj__cand:disabled {
  opacity: 0.55;
  cursor: default;
}

.adj__cand.is-current {
  border-color: var(--teal);
  color: var(--teal);
}

.adj__cand-slot {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
}

.adj__foot {
  position: sticky;
  bottom: 0;
  background: var(--paper);
  display: flex;
  gap: var(--s-2);
  justify-content: flex-end;
  padding-top: var(--s-2);
  border-top: var(--hairline);
}
</style>
