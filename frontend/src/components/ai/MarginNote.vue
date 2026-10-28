<script setup lang="ts">
import { computed } from 'vue'
import type { AiCitation } from '@/api/types'
import { headingId } from '@/lib/markdown'

/** 页边旁注：一条引用。序号与正文角标 [n] 对应（rankNo）。 */
const props = defineProps<{
  citation: AiCitation
  anchorId: string
  active: boolean
  /** 正文没有提到该序号时降低视觉权重 */
  unmentioned: boolean
}>()
defineEmits<{ hover: [rank: number | null] }>()

const lastHeading = computed(() => {
  const parts = (props.citation.headingPath ?? '').split('>').map((s) => s.trim()).filter(Boolean)
  return parts.at(-1) ?? null
})

const target = computed(() => ({
  name: 'document',
  params: { id: props.citation.documentId },
  query: { v: props.citation.versionId, chunk: props.citation.chunkId },
  hash: lastHeading.value ? '#' + headingId(lastHeading.value) : '',
}))
</script>

<template>
  <aside
    :id="anchorId"
    class="note"
    :class="{ 'is-active': active, 'is-unmentioned': unmentioned }"
    tabindex="-1"
    @mouseenter="$emit('hover', citation.rankNo)"
    @mouseleave="$emit('hover', null)"
  >
    <span class="note__num latin-italic" aria-hidden="true">{{ citation.rankNo }}</span>
    <div class="note__body">
      <RouterLink :to="target" class="note__title">{{ citation.documentTitle }}</RouterLink>
      <p v-if="citation.headingPath" class="note__path">{{ citation.headingPath }}</p>
      <p class="note__snippet">{{ citation.snippet }}</p>
      <p class="note__space">{{ citation.spaceName }}</p>
    </div>
  </aside>
</template>

<style scoped>
.note {
  position: relative;
  display: grid;
  grid-template-columns: 20px 1fr;
  gap: var(--s-2);
  padding: var(--s-2) 0 var(--s-3);
  border-top: var(--hairline);
  transition:
    background-color var(--t-fast) var(--ease),
    opacity var(--t-fast) var(--ease);
}

.note:focus {
  outline: none;
}

.note::before {
  content: '';
  position: absolute;
  left: -12px;
  top: -1px;
  bottom: 0;
  width: 2px;
  background: var(--teal);
  transform: scaleY(0);
  transform-origin: top;
  transition: transform var(--t-mid) var(--ease);
}

.note.is-active::before {
  transform: scaleY(1);
}

.note.is-unmentioned {
  opacity: 0.72;
}

.note.is-active {
  opacity: 1;
}

.note__num {
  font-size: 18px;
  line-height: 1.3;
  color: var(--teal);
}

.note__body {
  min-width: 0;
}

.note__title {
  display: block;
  font-family: var(--font-serif-cjk);
  font-size: var(--fs-ui-lg);
  font-weight: 600;
  line-height: 1.4;
  color: var(--ink);
  text-decoration: none;
}

.note__title:hover {
  color: var(--teal);
  text-decoration: underline;
}

.note__path {
  margin: 2px 0 0;
  font-size: var(--fs-small);
  color: var(--ink-2);
  overflow-wrap: anywhere;
}

.note__snippet {
  display: -webkit-box;
  margin: var(--s-1) 0 0;
  overflow: hidden;
  font-family: var(--font-serif-cjk);
  font-size: 13px;
  line-height: 1.65;
  color: var(--ink);
  -webkit-line-clamp: 4;
  line-clamp: 4;
  -webkit-box-orient: vertical;
}

.note__space {
  margin: var(--s-1) 0 0;
  font-size: 11px;
  letter-spacing: 0.06em;
  color: var(--ink-2);
}
</style>
