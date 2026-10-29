<script setup lang="ts">
import { computed } from 'vue'
import { PERIODS, WEEKDAYS, type TimetableItem, formatSchedule } from '@/lib/schedule'
import type { TermPeriod } from '@/api/types'

/**
 * 周课表：12 节 × 7 天。格子用真实 DOM 画线，课程块按节次跨行。
 * 只渲染「本周会上的课」，单双周与周次区间的过滤在调用方完成。
 */
const props = defineProps<{
  items: TimetableItem[]
  /** 学期节次作息（第几节 几点到几点）；没配时左侧只显示节次号 */
  periods?: TermPeriod[]
}>()

/** 08:00:00 → 08:00 */
function hhmm(value: string | null | undefined): string {
  const text = String(value ?? '')
  const m = /^(\d{1,2}):(\d{2})/.exec(text)
  if (!m) return ''
  return `${(m[1] ?? '0').padStart(2, '0')}:${m[2] ?? '00'}`
}

/** 节次号 → 「08:00」「08:45」；按后端返回的 periodNo 对齐 */
const times = computed(() => {
  const map = new Map<number, { start: string; end: string }>()
  for (const p of props.periods ?? []) {
    map.set(p.periodNo, { start: hhmm(p.startTime), end: hhmm(p.endTime) })
  }
  return map
})

/**
 * 画几行：优先按学期配置的节次数量（配了 10 节就不画 11、12 两行空行），
 * 同时不少于本周实际用到的最晚节次；完全没配作息时保持 12 节的默认网格。
 */
const rowCount = computed(() => {
  const configured = props.periods?.length ?? 0
  const used = props.items.reduce((max, it) => Math.max(max, it.endPeriod), 0)
  return configured > 0 ? Math.max(configured, used) : Math.max(PERIODS, used)
})

const rows = computed(() => Array.from({ length: rowCount.value }, (_, i) => i + 1))

const blocks = computed(() =>
  props.items.map((it) => {
    const span = it.endPeriod - it.startPeriod + 1
    return {
      ...it,
      span,
      gridStyle: {
        gridColumn: String(it.dayOfWeek + 1),
        gridRow: `${it.startPeriod + 1} / ${it.endPeriod + 2}`,
      },
      fullText: `${it.courseName}｜${formatSchedule(it)}`,
    }
  }),
)
</script>

<template>
  <div class="tt">
    <div class="tt__scroll">
      <div class="tt__grid">
        <div class="tt__corner" style="grid-column: 1; grid-row: 1" aria-hidden="true" />
        <div
          v-for="(d, i) in WEEKDAYS"
          :key="d"
          class="tt__day"
          :class="{ 'is-weekend': i >= 5 }"
          :style="{ gridColumn: i + 2, gridRow: 1 }"
        >
          {{ d }}
        </div>

        <template v-for="p in rows" :key="`row-${p}`">
          <div class="tt__period" :style="{ gridColumn: 1, gridRow: p + 1 }">
            <span class="tt__no mono">{{ p }}</span>
            <span v-if="times.get(p)?.start" class="tt__time mono">{{ times.get(p)?.start }}</span>
            <span v-if="times.get(p)?.end" class="tt__time tt__time--end mono">{{ times.get(p)?.end }}</span>
          </div>
          <div
            v-for="d in 7"
            :key="`cell-${d}-${p}`"
            class="tt__cell"
            :class="{ 'is-weekend': d >= 6 }"
            :style="{ gridColumn: d + 1, gridRow: p + 1 }"
          />
        </template>

        <article v-for="b in blocks" :key="b.scheduleId" class="block" :style="b.gridStyle" :title="b.fullText">
          <p class="block__name">{{ b.courseName }}</p>
          <p v-if="b.span >= 2" class="block__meta">{{ b.classroom || '地点待定' }}</p>
          <p v-if="b.span >= 3" class="block__meta block__meta--dim">{{ b.teacherNames.join('、') }}</p>
        </article>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tt {
  border-top: 1px solid var(--ink);
}

.tt__scroll {
  overflow-x: auto;
}

.tt__grid {
  display: grid;
  grid-template-columns: 82px repeat(7, minmax(0, 1fr));
  /* 第一行是星期表头，其余每行一节 */
  grid-template-rows: 40px;
  grid-auto-rows: 62px;
  min-width: 964px;
}

.tt__corner {
  border-right: 1px solid var(--ink);
  border-bottom: 1px solid var(--ink);
}

.tt__day {
  display: flex;
  align-items: center;
  justify-content: center;
  border-right: var(--hairline);
  border-bottom: 1px solid var(--ink);
  color: var(--ink-2);
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 0.08em;
}

.tt__day:last-child {
  border-right: 0;
}

.tt__day.is-weekend {
  color: var(--rule-strong);
}

.tt__period {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 1px;
  border-right: 1px solid var(--ink);
  border-bottom: var(--hairline);
  color: var(--ink-2);
}

/* 节次号：左侧列表的锚点，稍粗一点才看得见 */
.tt__no {
  font-size: 13px;
  font-weight: 600;
  line-height: 1.1;
  color: var(--ink);
}

/* 上课时间：08:00 / 08:45 两行，字号小，靠色阶区分开始与结束 */
.tt__time {
  font-size: 11px;
  line-height: 1.25;
  color: var(--ink-2);
  letter-spacing: 0;
}

.tt__time--end {
  color: var(--rule-strong);
}

.tt__cell {
  border-right: var(--hairline);
  border-bottom: var(--hairline);
}

.tt__cell.is-weekend {
  background: color-mix(in srgb, var(--paper-deep) 45%, transparent);
}

.block {
  z-index: 1;
  margin: 3px;
  padding: 6px 8px;
  overflow: hidden;
  border-left: 3px solid var(--teal);
  background: var(--paper-lift);
  box-shadow: inset 0 0 0 1px var(--rule);
  cursor: default;
  transition: background-color var(--t-fast) var(--ease);
}

.block:hover {
  background: var(--paper-deep);
}

.block__name {
  margin: 0;
  overflow: hidden;
  color: var(--ink);
  font-family: var(--font-serif-cjk);
  font-size: 15px;
  font-weight: 600;
  line-height: 1.35;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.block__meta {
  margin: 3px 0 0;
  overflow: hidden;
  color: var(--ink-2);
  font-size: 12px;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.block__meta--dim {
  opacity: 0.75;
}
</style>
