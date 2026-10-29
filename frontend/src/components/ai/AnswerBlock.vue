<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import MarginNote from './MarginNote.vue'
import type { ChatItem } from './types'
import { renderMarkdown } from '@/lib/markdown'
import { linkCitations, mentionedRanks } from '@/lib/citations'

/**
 * 一段 AI 回答：左为正文（衬线），右为页边旁注。
 * - 正文 [n] 角标与旁注双向联动：悬停高亮，点击角标定位到旁注。
 * - 依据不足（NO_EVIDENCE）时以斜体“编者按”呈现，不显示引用、不伪装成答案。
 * - 失败（FAILED）时说明原因，不渲染任何正文。
 */
const props = withDefaults(
  defineProps<{
    item: ChatItem
    /** 页边是否就地渲染旁注；为 false 时旁注由右侧「引用资料」栏统一展示 */
    showNotes?: boolean
    /** 外部（引用资料栏）指定的高亮序号，用于反向点亮正文角标 */
    highlight?: number | null
  }>(),
  { showNotes: true, highlight: null },
)
const emit = defineEmits<{
  reveal: [el: HTMLElement]
  cite: [rank: number]
  citeHover: [rank: number | null]
}>()

const prefix = computed(() => `cite-${props.item.key}`)
const active = ref<number | null>(null)
const notesEl = ref<HTMLElement | null>(null)
const proseEl = ref<HTMLElement | null>(null)

// 正文角标是 v-html 生成的，用 DOM 切换高亮类
watch(active, (r) => {
  proseEl.value?.querySelectorAll('a[data-rank]').forEach((a) => {
    a.classList.toggle('is-hl', r !== null && a.getAttribute('data-rank') === String(r))
  })
})

// 由引用资料栏悬停驱动：外部序号变化时同步正文高亮
watch(
  () => props.highlight,
  (r) => {
    active.value = r ?? null
  },
)

const ranks = computed(() => new Set(props.item.citations.map((c) => c.rankNo)))
const mentioned = computed(() => mentionedRanks(props.item.content))
const html = computed(() => linkCitations(renderMarkdown(props.item.content), ranks.value, prefix.value))

function rankFromEvent(e: Event): number | null {
  const a = (e.target as HTMLElement | null)?.closest?.('a[data-rank]')
  return a ? Number(a.getAttribute('data-rank')) : null
}

function onOver(e: MouseEvent) {
  const r = rankFromEvent(e)
  if (r !== null) {
    active.value = r
    emit('citeHover', r)
  }
}

function onOut(e: MouseEvent) {
  if (rankFromEvent(e) !== null) {
    active.value = null
    emit('citeHover', null)
  }
}

function onClick(e: MouseEvent) {
  const r = rankFromEvent(e)
  if (r === null) return
  e.preventDefault()
  active.value = r
  emit('cite', r)
  const note = notesEl.value?.querySelector<HTMLElement>(`#${CSS.escape(`${prefix.value}-${r}`)}`)
  if (note) {
    emit('reveal', note)
    note.focus({ preventScroll: true })
  }
}

const metaLine = computed(() => {
  const parts: string[] = []
  if (props.item.model) parts.push(props.item.model)
  if (props.item.totalTokens !== null) {
    parts.push(`${props.item.totalTokens} tokens${props.item.tokenSource === 'ESTIMATED' ? '（估算）' : ''}`)
  }
  if (props.item.citations.length) parts.push(`依据 ${props.item.citations.length} 处`)
  return parts.join(' · ')
})
</script>

<template>
  <article class="answer fade-in" :class="`answer--${item.status.toLowerCase()}`" :data-active-rank="active ?? undefined">
    <div class="answer__text">
      <p class="answer__label eyebrow" aria-hidden="true">答</p>

      <!-- 正常回答 -->
      <template v-if="item.status === 'SUCCESS'">
        <!-- html 已经 markdown-it(html:false) + DOMPurify 处理 -->
        <div
          ref="proseEl"
          class="prose answer__prose"
          @mouseover="onOver"
          @mouseout="onOut"
          @click="onClick"
          v-html="html"
        />
      </template>

      <!-- 依据不足：编者按 -->
      <template v-else-if="item.status === 'NO_EVIDENCE'">
        <div class="editor-note" role="note">
          <p class="editor-note__head">编者按</p>
          <p class="editor-note__body">{{ item.content || '根据当前知识库没有找到足够依据回答这个问题。' }}</p>
          <p class="editor-note__hint">这不是答案。可以换个说法、缩小或扩大检索范围，或直接向相关部门确认。</p>
        </div>
      </template>

      <!-- 生成中 -->
      <template v-else-if="item.status === 'PENDING'">
        <p class="pending" role="status">
          <span class="pending__rule" aria-hidden="true" />
          正在检索资料并撰写回答…
        </p>
      </template>

      <!-- 失败 -->
      <template v-else>
        <div class="failed" role="alert">
          <p class="failed__head">这次没有生成回答</p>
          <p class="failed__body">{{ item.errorText ?? '模型服务调用失败，问题已记录。请稍后重试。' }}</p>
          <p v-if="item.errorCode || item.traceId" class="failed__code mono">
            {{ item.errorCode }}<template v-if="item.traceId"> · traceId {{ item.traceId }}</template>
          </p>
        </div>
      </template>

      <p v-if="metaLine && item.status !== 'PENDING'" class="answer__meta mono">{{ metaLine }}</p>
    </div>

    <div
      v-if="showNotes && item.status === 'SUCCESS' && item.citations.length"
      ref="notesEl"
      class="answer__notes"
      aria-label="引用出处"
    >
      <MarginNote
        v-for="c in item.citations"
        :key="c.chunkId + '-' + c.rankNo"
        :citation="c"
        :anchor-id="`${prefix}-${c.rankNo}`"
        :active="active === c.rankNo"
        :unmentioned="!mentioned.has(c.rankNo)"
        @hover="active = $event"
      />
    </div>
  </article>
</template>

<style scoped>
.answer {
  display: grid;
  grid-template-columns: minmax(0, var(--measure)) minmax(176px, 260px);
  column-gap: clamp(24px, 2.6vw, 44px);
  align-items: start;
}

.answer__text {
  position: relative;
  min-width: 0;
}

/* 页边标记“答” */
.answer__label {
  position: absolute;
  left: -40px;
  top: 6px;
  margin: 0;
  color: var(--teal);
}

.answer__prose :deep(.cite-mark) {
  margin-left: 1px;
  font-size: 0.68em;
  line-height: 0;
}

.answer__prose :deep(.cite-mark a) {
  display: inline-block;
  min-width: 1.4em;
  padding: 0 0.15em;
  border-bottom: 1px solid currentColor;
  font-family: var(--font-latin-italic);
  font-style: italic;
  text-align: center;
  text-decoration: none;
  color: var(--teal);
  transition:
    background-color var(--t-fast) var(--ease),
    color var(--t-fast) var(--ease);
}

.answer__prose :deep(.cite-mark a:hover),
.answer__prose :deep(.cite-mark a:focus-visible) {
  background: var(--teal);
  color: var(--paper);
}

/* 由旁注悬停反向高亮正文角标 */
.answer__prose :deep(a.is-hl) {
  background: var(--teal);
  color: var(--paper);
}

.answer__meta {
  margin: var(--s-3) 0 0;
  font-size: 11px;
  color: var(--ink-2);
}

/* 右侧引用栏：独立成栏，与正文用一道竖线分开 */
.answer__notes {
  display: grid;
  gap: 0;
  align-content: start;
  padding: 4px 0 0 var(--s-3);
  border-left: 1px solid var(--rule);
}

.answer__notes > :first-child {
  border-top-color: var(--ink);
}

/* 编者按：斜体、居中缩进、上下细线，明确区别于正文 */
.editor-note {
  max-width: var(--measure);
  padding: var(--s-3) 0;
  border-top: 1px solid var(--ink);
  border-bottom: var(--hairline);
}

.editor-note__head {
  margin: 0 0 var(--s-2);
  font-family: var(--font-serif-cjk);
  font-size: var(--fs-ui);
  font-weight: 600;
  letter-spacing: 0.3em;
  color: var(--ochre);
}

.editor-note__body {
  margin: 0;
  font-family: var(--font-serif);
  font-size: var(--fs-read);
  font-style: italic;
  line-height: var(--lh-read);
}

.editor-note__hint {
  margin: var(--s-2) 0 0;
  font-size: var(--fs-ui);
  color: var(--ink-2);
}

.pending {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  margin: 0;
  font-family: var(--font-serif);
  font-size: var(--fs-read);
  font-style: italic;
  color: var(--ink-2);
}

.pending__rule {
  position: relative;
  width: 48px;
  height: 1px;
  overflow: hidden;
  background: var(--rule);
}

.pending__rule::after {
  content: '';
  position: absolute;
  inset: 0;
  background: var(--teal);
  animation: pending-ink 1.4s var(--ease) infinite;
}

@keyframes pending-ink {
  from {
    transform: translateX(-100%);
  }
  to {
    transform: translateX(100%);
  }
}

.failed {
  padding: var(--s-3) 0;
  border-top: 1px solid var(--ochre);
  border-bottom: var(--hairline);
}

.failed__head {
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-weight: 600;
  color: var(--ochre);
}

.failed__body {
  margin: var(--s-1) 0 0;
}

.failed__code {
  margin: var(--s-2) 0 0;
  font-size: 11px;
  color: var(--ink-2);
  user-select: all;
}

@container reading (max-width: 780px) {
  .answer {
    grid-template-columns: minmax(0, 1fr);
  }

  .answer__notes {
    margin-top: var(--s-4);
    padding-left: 0;
    border-left: 0;
  }

  .answer__label {
    position: static;
    margin-bottom: var(--s-2);
  }
}
</style>
