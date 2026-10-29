<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { knowledgeApi } from '@/api/endpoints'
import type { DocumentDetailView, SpaceView, VersionDetailView, VersionView } from '@/api/types'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { PROCESS_LABEL, SOURCE_LABEL, processTone } from '@/lib/labels'
import { renderMarkdown } from '@/lib/markdown'
import { formatDateTime } from '@/lib/time'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiField from '@/components/ui/UiField.vue'
import UiTag from '@/components/ui/UiTag.vue'
import UiTextarea from '@/components/ui/UiTextarea.vue'

/**
 * 文档页：正文居中阅读，页边放版本与操作。
 * 既是 AI 引用的落地页，也是唯一的编辑入口。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const toast = useToastStore()

const doc = ref<DocumentDetailView | null>(null)
const space = ref<SpaceView | null>(null)
const versions = ref<VersionView[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const bodyEl = ref<HTMLElement | null>(null)

/** 正在预览的旧版本；为 null 表示看当前版 */
const preview = ref<VersionDetailView | null>(null)
const previewLoading = ref(false)

const WRITE_LEVELS = ['ADMIN', 'OWNER', 'MANAGER', 'EDITOR']
const canEdit = computed(
  () => auth.can('kb:document:write') && !!space.value && WRITE_LEVELS.includes(space.value.accessLevel),
)

const displayContent = computed(() => preview.value?.content ?? doc.value?.content ?? '')
/** 正文开头若重复了文档标题（# 标题），渲染时去掉，避免与页头大标题撞车 */
const bodySource = computed(() => {
  const src = displayContent.value
  if (preview.value || !doc.value) return src
  const m = /^\s*#\s+(.+?)[ \t]*(?:\r?\n|$)/.exec(src)
  const heading = m?.[1]?.trim()
  if (!heading || heading !== doc.value.title.trim()) return src
  return src.slice(m![0].length).replace(/^\s*\n/, '')
})
/** 语料导入的文档带 sourcePath，正文里的 image/x.png 按同级目录解析到 /wiki/<dir>/ */
const assetBase = computed(() => {
  const p = doc.value?.sourcePath
  if (!p || !p.includes('/')) return undefined
  return `/wiki/${p.slice(0, p.lastIndexOf('/'))}/`
})
const html = computed(() => renderMarkdown(bodySource.value, { assetBase: assetBase.value }))
const citedVersion = computed(() => Number(route.query.v) || null)
const isOldCitation = computed(
  () => !!doc.value && citedVersion.value !== null && doc.value.currentVersionId !== citedVersion.value,
)

async function load(id: number) {
  loading.value = true
  error.value = null
  preview.value = null
  try {
    doc.value = await knowledgeApi.document(id)
    // 空间信息用于判断写权限；失败不影响阅读
    void knowledgeApi
      .space(doc.value.spaceId)
      .then((s) => (space.value = s))
      .catch(() => (space.value = null))
    void knowledgeApi
      .versions(id)
      .then((v) => (versions.value = v))
      .catch(() => (versions.value = []))
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

async function showVersion(no: number) {
  if (!doc.value) return
  if (no === doc.value.versionNo) {
    preview.value = null
    return
  }
  previewLoading.value = true
  try {
    preview.value = await knowledgeApi.version(doc.value.id, no)
    await nextTick()
    window.scrollTo({ top: 0, behavior: 'smooth' })
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    previewLoading.value = false
  }
}

watch(
  () => Number(route.params.id),
  (id) => {
    if (Number.isInteger(id) && id > 0) void load(id)
  },
)

onMounted(() => {
  const id = Number(route.params.id)
  if (Number.isInteger(id) && id > 0) void load(id)
})

/* ---------------- 编辑 ---------------- */
const editOpen = ref(false)
const editBusy = ref(false)
const draft = ref({ title: '', content: '', changeNote: '' })
const draftError = ref<{ title?: string | null; content?: string | null; general?: string | null }>({})

function openEdit() {
  if (!doc.value) return
  draft.value = { title: doc.value.title, content: doc.value.content, changeNote: '' }
  draftError.value = {}
  editOpen.value = true
}

async function submitEdit() {
  if (!doc.value) return
  draftError.value = {}
  if (!draft.value.title.trim()) {
    draftError.value = { title: '请填写标题' }
    return
  }
  if (!draft.value.content.trim()) {
    draftError.value = { content: '正文不能为空' }
    return
  }
  editBusy.value = true
  try {
    await knowledgeApi.updateDocument(doc.value.id, {
      title: draft.value.title.trim(),
      content: draft.value.content,
      changeNote: draft.value.changeNote.trim() || null,
      version: doc.value.version,
    })
    editOpen.value = false
    toast.push('已保存为新版本，旧版本的块标记为过期', 'success')
    await load(doc.value.id)
  } catch (e) {
    const conflict = e instanceof ApiError && e.status === 409
    draftError.value = {
      title: e instanceof ApiError ? e.fieldError('title') : null,
      content: e instanceof ApiError ? e.fieldError('content') : null,
      general: conflict ? '这篇文档已被其他人修改，请关闭后重新打开再编辑' : errorMessage(e),
    }
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    editBusy.value = false
  }
}

/* ---------------- 重新处理 / 删除 ---------------- */
const reprocessing = ref(false)
async function reprocess() {
  if (!doc.value) return
  reprocessing.value = true
  try {
    await knowledgeApi.reprocess(doc.value.id)
    toast.push('已提交重新切块，稍后刷新可看到新块', 'success')
    await load(doc.value.id)
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    reprocessing.value = false
  }
}

const deleteOpen = ref(false)
const deleteBusy = ref(false)
async function confirmDelete() {
  if (!doc.value) return
  deleteBusy.value = true
  const spaceId = doc.value.spaceId
  try {
    await knowledgeApi.deleteDocument(doc.value.id)
    deleteOpen.value = false
    toast.push('文档已删除', 'success')
    await router.push({ name: 'space', params: { id: spaceId } })
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    deleteBusy.value = false
  }
}
</script>

<template>
  <div class="doc">
    <p v-if="loading" class="doc__state">正在取书…</p>

    <div v-else-if="error" class="doc__state" role="alert">
      <p>{{ error }}</p>
      <RouterLink to="/knowledge">回到书架</RouterLink>
    </div>

    <article v-else-if="doc" class="doc__page fade-in">
      <p class="doc__crumbs">
        <RouterLink to="/knowledge">书架</RouterLink>
        <span aria-hidden="true">/</span>
        <RouterLink :to="{ name: 'space', params: { id: doc.spaceId } }">
          {{ space?.name ?? '空间' }}
        </RouterLink>
      </p>

      <header class="doc__head">
        <p class="eyebrow">知识库文档</p>
        <h1 class="doc__title">{{ doc.title }}</h1>
        <p class="doc__meta">
          <UiTag :tone="processTone(doc.processStatus)">{{ PROCESS_LABEL[doc.processStatus] ?? doc.processStatus }}</UiTag>
          <span v-if="doc.versionNo">第 {{ doc.versionNo }} 版</span>
          <span class="mono">{{ doc.chunkCount }} 块</span>
          <span>更新于 {{ formatDateTime(doc.updatedAt) }}</span>
          <span v-if="doc.uploadedByName">{{ doc.uploadedByName }}</span>
        </p>
      </header>

      <p v-if="doc.processStatus === 'FAILED'" class="doc__notice doc__notice--err" role="alert">
        这篇文档切块失败{{ doc.processError ? `：${doc.processError}` : '' }}。它不会出现在检索结果里，可尝试“重新处理”。
      </p>

      <p v-if="isOldCitation" class="doc__notice" role="note">
        引用来自该文档的较早版本，下面显示的是当前版本，内容可能已有变化。
      </p>

      <div class="doc__grid">
        <div class="doc__body-wrap">
          <div v-if="preview" class="doc__preview-note">
            <span>正在查看第 {{ preview.versionNo }} 版（历史版本，只读）</span>
            <UiButton size="sm" variant="link" @click="preview = null">回到当前版</UiButton>
          </div>
          <!-- html 已经 markdown-it(html:false) + DOMPurify 处理 -->
          <div ref="bodyEl" class="prose doc__body" v-html="html" />
        </div>

        <aside class="margin" aria-label="版本与操作">
          <div v-if="canEdit" class="margin__actions">
            <UiButton size="sm" variant="primary" @click="openEdit">编辑</UiButton>
            <UiButton size="sm" :loading="reprocessing" @click="reprocess">重新处理</UiButton>
            <UiButton size="sm" variant="danger" @click="deleteOpen = true">删除</UiButton>
          </div>

          <section class="margin__block">
            <p class="margin__title eyebrow">版本</p>
            <p v-if="versions.length === 0" class="margin__empty">暂无版本记录</p>
            <ol v-else class="versions">
              <li v-for="v in versions" :key="v.id" class="version" :class="{ 'is-current': v.versionNo === doc.versionNo }">
                <button type="button" class="version__btn" @click="showVersion(v.versionNo)">
                  <span class="version__no latin-italic">v{{ v.versionNo }}</span>
                  <span class="version__note">{{ v.changeNote || '（无说明）' }}</span>
                  <span class="version__time mono">{{ formatDateTime(v.createdAt) }}</span>
                </button>
              </li>
            </ol>
            <p v-if="previewLoading" class="margin__empty">正在取该版本…</p>
          </section>

          <section class="margin__block">
            <p class="margin__title eyebrow">文件</p>
            <dl class="facts">
              <dt>来源</dt>
              <dd>{{ SOURCE_LABEL[doc.sourceType] ?? doc.sourceType }}</dd>
              <dt>原名</dt>
              <dd class="mono">{{ doc.originalFilename || '—' }}</dd>
              <dt>大小</dt>
              <dd class="mono">{{ doc.sizeBytes ? Math.round(doc.sizeBytes / 1024) + ' KB' : '—' }}</dd>
            </dl>
          </section>
        </aside>
      </div>
    </article>

    <!-- 编辑 -->
    <UiDialog
      :open="editOpen"
      title="编辑文档"
      eyebrow="将生成新版本"
      width="lg"
      :dismissible="!editBusy"
      @close="editOpen = false"
    >
      <div class="form">
        <UiField v-model="draft.title" label="标题" :error="draftError.title" :maxlength="120" required />
        <UiTextarea
          v-model="draft.content"
          label="正文"
          :rows="16"
          mono
          :error="draftError.content"
          hint="保存后旧版本仍可查阅，旧的检索块会被标记为过期。"
        />
        <UiField
          v-model="draft.changeNote"
          label="修改说明"
          hint="写清楚改了什么，方便日后回溯。"
          :maxlength="200"
        />
        <p v-if="draftError.general" class="form__err" role="alert">{{ draftError.general }}</p>
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="editBusy" @click="editOpen = false">取消</UiButton>
        <UiButton variant="primary" :loading="editBusy" @click="submitEdit">保存为新版本</UiButton>
      </template>
    </UiDialog>

    <!-- 删除 -->
    <UiDialog
      :open="deleteOpen"
      title="删除这篇文档？"
      eyebrow="软删除"
      width="sm"
      :dismissible="!deleteBusy"
      @close="deleteOpen = false"
    >
      <p class="del-text">
        “{{ doc?.title }}” 会从空间与检索中移除，历史版本保留在数据库中。此操作不可在界面撤销。
      </p>
      <template #footer>
        <UiButton variant="link" :disabled="deleteBusy" @click="deleteOpen = false">取消</UiButton>
        <UiButton variant="danger" :loading="deleteBusy" @click="confirmDelete">确认删除</UiButton>
      </template>
    </UiDialog>
  </div>
</template>

<style scoped>
.doc {
  width: 100%;
  max-width: 1120px;
  margin: 0 auto;
  padding: var(--s-5) clamp(var(--s-4), 4vw, var(--s-6)) var(--s-7);
}

.doc__state {
  color: var(--ink-2);
  font-family: var(--font-serif);
  font-size: var(--fs-read);
}

.doc__crumbs {
  display: flex;
  gap: var(--s-2);
  margin: 0 0 var(--s-4);
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.doc__crumbs a {
  color: var(--ink-2);
}

.doc__crumbs a:hover {
  color: var(--teal);
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
  align-items: center;
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

.doc__notice--err {
  background: color-mix(in srgb, var(--ochre-tint) 70%, var(--paper));
}

.doc__grid {
  display: grid;
  grid-template-columns: minmax(0, var(--measure)) 208px;
  gap: clamp(var(--s-4), 3vw, var(--s-6));
  align-items: start;
}

.doc__body-wrap {
  min-width: 0;
}

.doc__preview-note {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-3);
  padding-bottom: var(--s-2);
  margin-bottom: var(--s-4);
  border-bottom: var(--hairline);
  color: var(--ink-2);
  font-size: var(--fs-small);
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

/* ---------- 页边 ---------- */
.margin {
  position: sticky;
  top: calc(var(--masthead-h) + var(--s-4));
  display: grid;
  gap: var(--s-5);
}

.margin__actions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--s-2);
}

/* 三个按钮时，最后一个（删除）占满一行，避免右下角空一块 */
.margin__actions > :last-child:nth-child(odd) {
  grid-column: 1 / -1;
}

.margin__title {
  margin: 0 0 var(--s-2);
  padding-bottom: var(--s-1);
  border-bottom: 1px solid var(--rule-strong);
}

.margin__empty {
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.versions {
  margin: 0;
  padding: 0;
  list-style: none;
}

.version__btn {
  display: grid;
  gap: 2px;
  width: 100%;
  padding: var(--s-2) 0;
  border: 0;
  border-bottom: var(--hairline);
  background: none;
  text-align: left;
  cursor: pointer;
}

.version__btn:hover .version__no {
  color: var(--teal);
}

.version__no {
  font-size: 14px;
  color: var(--ink-2);
}

.version__note {
  color: var(--ink);
  font-size: var(--fs-small);
  line-height: 1.5;
}

.version__time {
  color: var(--ink-2);
  font-size: 11px;
}

.is-current .version__no {
  color: var(--teal);
  font-weight: 600;
}

.is-current .version__note::after {
  content: '（当前）';
  color: var(--ink-2);
  font-size: 11px;
}

.facts {
  display: grid;
  grid-template-columns: 48px minmax(0, 1fr);
  gap: var(--s-1) var(--s-2);
  margin: 0;
  font-size: var(--fs-small);
}

.facts dt {
  color: var(--ink-2);
}

.facts dd {
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

.form {
  display: grid;
  gap: var(--s-4);
}

.form__err {
  margin: 0;
  color: var(--ochre);
  font-size: var(--fs-small);
}

.del-text {
  margin: 0;
  line-height: 1.7;
}

@media (max-width: 900px) {
  .doc__grid {
    grid-template-columns: minmax(0, 1fr);
  }

  /* 手机上：操作留在正文前，版本与文件信息移到正文之后 */
  .margin {
    display: contents;
  }

  .margin__actions {
    order: 1;
    margin-bottom: var(--s-2);
  }

  .doc__body-wrap {
    order: 2;
  }

  .margin__block {
    order: 3;
  }
}
</style>
