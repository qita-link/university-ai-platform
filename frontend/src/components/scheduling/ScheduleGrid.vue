<script setup lang="ts">
import { computed } from 'vue'
import type { ScheduleEntry, TimetableRow } from '@/api/types'

/**
 * 排课课表网格：节次 × 星期，课程块按节次跨行。
 * 与课程模块的手工课表不同，这里的数据来自排课版本，可点击进入调整。
 */
const props = withDefaults(
  defineProps<{
    rows: TimetableRow[]
    days: number[]
    editable?: boolean
    highlightId?: number | null
    /** 当前视角：教师视角必须显示班级，否则一个老师教多个班时看不出这一节是哪个班 */
    scope?: 'ALL' | 'TEACHER' | 'CLASS' | 'ROOM'
    /** 显示密度：紧凑模式行高更小，适合一屏看整个学期 */
    density?: 'comfy' | 'compact'
    showClass?: boolean
    showTeacher?: boolean
    showRoom?: boolean
    showWeeks?: boolean
  }>(),
  {
    editable: false,
    highlightId: null,
    scope: 'ALL',
    density: 'comfy',
    showClass: true,
    showTeacher: true,
    showRoom: true,
    showWeeks: false,
  },
)

const emit = defineEmits<{ pick: [entry: ScheduleEntry] }>()

const DAY_NAMES = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日']

/**
 * 每行里「从本行开始」的课程块；跨节的块只在起始行渲染，靠 grid-row 跨行占位。
 * 「全部」视角下同一天会有多个班的时间重叠，这里按重叠层数做阶梯错位，
 * 否则后一个块会把前一个完全盖住，既看不见也点不到。
 */
const blocks = computed(() => {
  const out: {
    entry: ScheduleEntry
    style: Record<string, string>
    span: number
    layer: number
    day: number
    start: number
    end: number
  }[] = []
  props.rows.forEach((row) => {
    row.cells.forEach((cell, dayIndex) => {
      for (const entry of cell.entries) {
        if (entry.startPeriod !== row.periodNo) continue
        const start = entry.startPeriod ?? row.periodNo
        const end = entry.endPeriod ?? start
        const day = cell.dayOfWeek
        const layer = out.filter((b) => b.day === day && b.start <= end && start <= b.end).length
        out.push({
          entry,
          span: end - start + 1,
          layer,
          day,
          start,
          end,
          style: {
            gridColumn: String(dayIndex + 2),
            gridRow: `${start + 1} / ${end + 2}`,
          },
        })
      }
    })
  })
  return out
})

/** 有多少个课块与别的班叠在同一格（仅用于提示，不做错位） */
const overlapCount = computed(() => blocks.value.filter((b) => b.layer > 0).length)

function click(entry: ScheduleEntry) {
  if (props.editable) emit('pick', entry)
}

function slotText(entry: ScheduleEntry) {
  return `${entry.timeText}　${entry.roomCode ?? '未指定教室'}`
}

/** 班级：优先用行政班名（合班时会有多个），退回教学班名 */
function classText(entry: ScheduleEntry) {
  if (entry.classNames?.length) return entry.classNames.join('、')
  return entry.teachingClassName ?? ''
}

function blockTitle(entry: ScheduleEntry) {
  const parts = [entry.courseName ?? entry.taskCode ?? '']
  const cls = classText(entry)
  if (cls) parts.push('班级：' + cls)
  if (entry.teacherNames.length) parts.push('教师：' + entry.teacherNames.join('、'))
  parts.push(slotText(entry))
  if (entry.weekText) parts.push('周次：' + entry.weekText)
  return parts.join('\n')
}
</script>

<template>
  <div class="sg" :class="[`sg--${density}`, { 'sg--with-weeks': showWeeks }]">
    <div class="sg__scroll">
      <div
        class="sg__grid"
        :style="{ gridTemplateColumns: `64px repeat(${days.length}, minmax(150px, 1fr))` }"
      >
        <div class="sg__corner" style="grid-column: 1; grid-row: 1" aria-hidden="true" />
        <div
          v-for="(d, i) in days"
          :key="`d-${d}`"
          class="sg__day"
          :class="{ 'is-weekend': d >= 6 }"
          :style="{ gridColumn: i + 2, gridRow: 1 }"
        >
          {{ DAY_NAMES[d] ?? `第${d}天` }}
        </div>

        <template v-for="(row, r) in rows" :key="`r-${row.periodNo}`">
          <div class="sg__period" :style="{ gridColumn: 1, gridRow: r + 2 }">
            <span class="sg__no mono">{{ row.periodNo }}</span>
            <span class="sg__time mono">{{ row.startTime.slice(0, 5) }}</span>
          </div>
          <div
            v-for="(cell, d) in row.cells"
            :key="`c-${row.periodNo}-${cell.dayOfWeek}`"
            class="sg__cell"
            :class="{ 'is-weekend': cell.dayOfWeek >= 6 }"
            :style="{ gridColumn: d + 2, gridRow: r + 2 }"
          />
        </template>

        <article
          v-for="b in blocks"
          :key="b.entry.id"
          class="sg__block"
          :class="{
            'is-locked': b.entry.locked,
            'is-picked': highlightId === b.entry.id,
            'is-clickable': editable,
          }"
          :style="b.style"
          :title="blockTitle(b.entry)"
          @click="click(b.entry)"
        >
          <p class="sg__name">{{ b.entry.courseName ?? b.entry.taskCode }}</p>
          <!-- 周次角标：不依赖跨节，单节次也显示，教师视角同样可见 -->
          <span v-if="showWeeks && b.entry.weekText" class="sg__weektag">{{ b.entry.weekText }}</span>
          <p v-if="showRoom && b.span >= 2" class="sg__meta">{{ b.entry.roomCode ?? '未指定教室' }}</p>
          <p v-if="showClass && b.span >= 2" class="sg__meta sg__meta--class">
            {{ classText(b.entry) }}
          </p>
          <p v-if="showTeacher && b.span >= 2 && scope !== 'TEACHER'" class="sg__meta sg__meta--dim">
            {{ b.entry.teacherNames.join('、') }}
          </p>
          <span v-if="b.entry.locked" class="sg__lock" aria-label="已锁定">锁</span>
        </article>
      </div>
    </div>
    <p v-if="overlapCount && scope === 'ALL'" class="sg__hint">
      全部视角下多个班的时间会叠在同一格（本页有 {{ overlapCount }} 处重叠）。看某一节课的班级与教室，请切到「按班级」或「按教师」。
    </p>
    <p v-if="editable" class="sg__hint">点击课程块可调整时间、教室或锁定</p>
  </div>
</template>

<style scoped>
.sg {
  border-top: 1px solid var(--ink);
}

.sg__scroll {
  overflow-x: auto;
}

.sg__grid {
  display: grid;
  grid-auto-rows: 52px;
  min-width: 720px;
}

.sg__corner {
  border-right: 1px solid var(--ink);
  border-bottom: 1px solid var(--ink);
}

.sg__day {
  display: flex;
  align-items: center;
  justify-content: center;
  border-right: var(--hairline);
  border-bottom: 1px solid var(--ink);
  color: var(--ink-2);
  font-size: var(--fs-small);
  font-weight: 600;
  letter-spacing: 0.08em;
}

.sg__day:last-child {
  border-right: 0;
}

.sg__day.is-weekend {
  color: var(--rule-strong);
}

.sg__period {
  display: grid;
  align-content: center;
  justify-items: center;
  gap: 2px;
  border-right: 1px solid var(--ink);
  border-bottom: var(--hairline);
  color: var(--ink-2);
}

.sg__no {
  font-size: 12px;
  font-weight: 600;
}

.sg__time {
  font-size: 10px;
  opacity: 0.65;
}

.sg__cell {
  border-right: var(--hairline);
  border-bottom: var(--hairline);
}

.sg__cell.is-weekend {
  background: color-mix(in srgb, var(--paper-deep) 45%, transparent);
}

.sg__block {
  z-index: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 3px;
  padding: 6px 8px;
  overflow: hidden;
  border-left: 3px solid var(--teal);
  background: var(--paper-lift);
  box-shadow: inset 0 0 0 1px var(--rule);
  transition: background-color var(--t-fast) var(--ease);
}

.sg__block.is-clickable {
  cursor: pointer;
}

.sg__block.is-clickable:hover {
  background: var(--paper-deep);
}

.sg__block.is-locked {
  border-left-color: var(--ochre, #9a6b1f);
}

.sg__block.is-picked {
  box-shadow:
    inset 0 0 0 1px var(--teal),
    0 0 0 2px color-mix(in srgb, var(--teal) 35%, transparent);
}

.sg__name {
  margin: 0;
  overflow: hidden;
  color: var(--ink);
  font-family: var(--font-serif-cjk);
  font-size: 13px;
  font-weight: 600;
  line-height: 1.3;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.sg__meta {
  margin: 0;
  overflow: hidden;
  color: var(--ink-2);
  font-size: 11px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sg__meta--dim {
  opacity: 0.75;
}

/* 班级是「这一节上给谁」的关键信息，用深一档颜色与教师名区分 */
.sg__meta--class {
  color: var(--teal);
}

.sg--compact .sg__grid {
  grid-auto-rows: 42px;
}

.sg--compact .sg__block {
  margin: 2px;
  padding: 4px 6px;
  gap: 1px;
}

.sg--compact .sg__name {
  font-size: 12px;
  -webkit-line-clamp: 1;
}

.sg--compact .sg__meta {
  font-size: 10px;
}

.sg__weeks {
  margin-left: 4px;
  padding: 0 3px;
  border: 1px solid var(--rule-strong);
  color: var(--ink-2);
  font-size: 10px;
  line-height: 1.4;
  white-space: nowrap;
}

/* 周次角标：右上角常驻，单节次的课也能看到周次 */
.sg__weektag {
  position: absolute;
  top: 3px;
  right: 3px;
  padding: 0 3px;
  border: 1px solid var(--rule-strong);
  background: color-mix(in srgb, var(--paper-lift) 88%, transparent);
  color: var(--ink-2);
  font-family: var(--font-mono, monospace);
  font-size: 10px;
  line-height: 1.45;
  white-space: nowrap;
  pointer-events: none;
}

/* 有角标时给标题留出右侧空间，避免压字 */
.sg--with-weeks .sg__name {
  padding-right: 52px;
}

.sg--with-weeks .sg__block.is-locked .sg__name {
  padding-right: 52px;
}

.sg__lock {
  position: absolute;
  right: 4px;
  bottom: 4px;
  padding: 0 3px;
  color: var(--ochre, #9a6b1f);
  font-size: 10px;
  border: 1px solid currentColor;
  line-height: 1.4;
}

.sg__hint {
  margin: var(--s-2) 0 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.sg__block {
  position: relative;
}
</style>
