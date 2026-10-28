<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { knowledgeApi } from '@/api/endpoints'
import type { DocumentDetailView } from '@/api/types'
import { errorMessage } from '@/lib/errors'
import { renderMarkdown } from '@/lib/markdown'
import { formatDateTime } from '@/lib/time'

/**
 * 文档阅读页（v0 只读版）：作为 AI 引用的跳转目标。
 * 完整的知识库书架、目录、版本切换在下一轮实现。
 */
const route = useRoute()
const doc = ref<DocumentDetailView | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)
const bodyEl = ref<HTMLElement | null>(null)

const html = computed(() => renderMarkdown(doc.value?.content))
const citedVersion = computed(() => Number(route.query.v) || null)
const isOldVersion = computed(
  () => !!doc.value && citedVersion.value !== null && doc.value.currentVersionId !== citedVersion.value,
)

async function load(id: number) {
  loading.value = true
  error.value = null
  try {
    doc.value = await knowledgeApi.document(id)
    await nextTick()
    highlightHash()
  } catch (e) {
    doc.value = null
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

function highlightHash() {
  const hash = route.hash ? decodeURIComponent(route.hash.slice(1)) : ''
  if (!hash || !bodyEl.value) return
  const target = bodyEl.value.querySelector<HTMLElement>(`#${CSS.escape(hash)}`)
  if (!target) return
  target.classList.add('is-cited')
  window.scrollTo({ top: window.scrollY + target.getBoundingClientRect().top - 96, behavior: 'smooth' })
}

watch(
  () => Number(route.params.id),
  (id) => {
    if (Number.isInteger(id) && id > 0) void load(id)
  },
  { immediate: true },
)
</script>

<template>
  <div class="doc">
    <p v-if="loading" class="doc__state">正在取书…</p>
    <div v-else-if="error" class="doc__state" role="alert">
      <p>{{ error }}</p>
      <RouterLink to="/ai">回到问答</RouterLink>
    </div>
    <article v-else-if="doc" class="doc__page fade-in">
      <header class="doc__head">
        <p class="eyebrow">知识库文档</p>
        <h1 class="doc__title">{{ doc.title }}</h1>
        <p class="doc__meta">
          <span v-if="doc.versionNo">第 {{ doc.versionNo }} 版</span>
          <span>更新于 {{ formatDateTime(doc.updatedAt) }}</span>
          <span v-if="doc.uploadedByName">{{ doc.uploadedByName }}</span>
        </p>
      </header>

      <p v-if="isOldVersion" class="doc__notice" role="note">
        引用来自该文档的较早版本，下面显示的是当前版本，内容可能已有变化。
      </p>

      <!-- html 已经 markdown-it(html:false) + DOMPurify 处理 -->
      <div ref="bodyEl" class="prose doc__body" v-html="html" />
    </article>
  </div>
</template>

<style scoped>
.doc {
  width: 100%;
  max-width: 1080px;
  padding: var(--s-6) clamp(var(--s-4), 5vw, var(--s-7)) var(--s-7);
}

.doc__state {
  color: var(--ink-2);
  font-family: var(--font-serif);
  font-size: var(--fs-read);
}

.doc__head {
  max-width: var(--measure);
  padding-bottom: var(--s-4);
  margin-bottom: var(--s-5);
  border-bottom: 1px solid var(--ink);
}

.doc__title {
  margin: var(--s-2) 0 var(--s-3);
  font-family: var(--font-serif-cjk);
  font-size: var(--fs-h2);
}

.doc__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-3);
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-ui);
}

.doc__notice {
  max-width: var(--measure);
  margin: 0 0 var(--s-5);
  padding: var(--s-2) var(--s-3);
  border-left: 2px solid var(--ochre);
  background: var(--ochre-tint);
}

.doc__body :deep(.is-cited) {
  position: relative;
}

.doc__body :deep(.is-cited)::before {
  content: '引';
  position: absolute;
  left: -40px;
  top: 0.15em;
  font-family: var(--font-sans);
  font-size: var(--fs-small);
  font-weight: 600;
  letter-spacing: 0.12em;
  color: var(--teal);
}
</style>
