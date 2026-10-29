<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { knowledgeApi } from '@/api/endpoints'
import type { DocumentSummaryView, SpaceView, UploadBatchView } from '@/api/types'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { ACCESS_LABEL, PROCESS_LABEL, SOURCE_LABEL, VISIBILITY_LABEL, processTone } from '@/lib/labels'
import { formatDateTime } from '@/lib/time'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiEmpty from '@/components/ui/UiEmpty.vue'
import UiField from '@/components/ui/UiField.vue'
import UiPager from '@/components/ui/UiPager.vue'
import UiTag from '@/components/ui/UiTag.vue'
import UiTextarea from '@/components/ui/UiTextarea.vue'
import MembersDialog from '@/components/knowledge/MembersDialog.vue'

/**
 * 空间页：一个空间里的文档清单。
 * 阅读走文档页；这里负责「找、加、传」。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const toast = useToastStore()

const spaceId = computed(() => Number(route.params.id))

const space = ref<SpaceView | null>(null)
const spaceError = ref<string | null>(null)
const spaceLoading = ref(true)

const docs = ref<DocumentSummaryView[]>([])
const total = ref(0)
const pages = ref(0)
const page = ref(1)
const keyword = ref('')
const docsLoading = ref(false)
const docsError = ref<string | null>(null)

/** 有写权限的访问级别；成员身份不能绕过功能权限，两者都要满足 */
const WRITE_LEVELS = ['ADMIN', 'OWNER', 'MANAGER', 'EDITOR']
const canWrite = computed(
  () => auth.can('kb:document:write') && !!space.value && WRITE_LEVELS.includes(space.value.accessLevel),
)

/** 成员授权：所有者与管理者（含系统管理员） */
const canManageMembers = computed(
  () => !!space.value && ['ADMIN', 'OWNER', 'MANAGER'].includes(space.value.accessLevel),
)
const membersOpen = ref(false)

async function loadSpace(id: number) {
  spaceLoading.value = true
  spaceError.value = null
  try {
    space.value = await knowledgeApi.space(id)
  } catch (e) {
    space.value = null
    spaceError.value = errorMessage(e)
  } finally {
    spaceLoading.value = false
  }
}

async function loadDocs(id: number) {
  docsLoading.value = true
  docsError.value = null
  try {
    const res = await knowledgeApi.documents(id, {
      page: page.value,
      size: 20,
      keyword: keyword.value.trim() || undefined,
    })
    docs.value = res.items
    total.value = res.total
    pages.value = res.pages
  } catch (e) {
    docs.value = []
    docsError.value = errorMessage(e)
  } finally {
    docsLoading.value = false
  }
}

function search() {
  page.value = 1
  void loadDocs(spaceId.value)
}

watch(page, () => void loadDocs(spaceId.value))
watch(spaceId, (id) => {
  if (!Number.isInteger(id) || id <= 0) return
  page.value = 1
  keyword.value = ''
  void loadSpace(id)
  void loadDocs(id)
})

onMounted(() => {
  if (Number.isInteger(spaceId.value) && spaceId.value > 0) {
    void loadSpace(spaceId.value)
    void loadDocs(spaceId.value)
  }
})

/* ---------------- 新建文档 ---------------- */
const newOpen = ref(false)
const newBusy = ref(false)
const draft = ref({ title: '', content: '', changeNote: '' })
const draftError = ref<{ title?: string | null; content?: string | null; general?: string | null }>({})

function openNew() {
  draft.value = { title: '', content: '', changeNote: '' }
  draftError.value = {}
  newOpen.value = true
}

async function submitNew() {
  draftError.value = {}
  if (!draft.value.title.trim()) {
    draftError.value = { title: '请填写标题' }
    return
  }
  if (!draft.value.content.trim()) {
    draftError.value = { content: '正文不能为空，Markdown 语法可用' }
    return
  }
  newBusy.value = true
  try {
    const created = await knowledgeApi.createDocument(spaceId.value, {
      title: draft.value.title.trim(),
      content: draft.value.content,
      changeNote: draft.value.changeNote.trim() || null,
    })
    newOpen.value = false
    toast.push('文档已创建，正在切块入索引', 'success')
    await loadDocs(spaceId.value)
    await router.push({ name: 'document', params: { id: created.id } })
  } catch (e) {
    draftError.value = {
      title: e instanceof ApiError ? e.fieldError('title') : null,
      content: e instanceof ApiError ? e.fieldError('content') : null,
      general: errorMessage(e),
    }
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    newBusy.value = false
  }
}

/* ---------------- 批量上传 ---------------- */
const fileInput = ref<HTMLInputElement | null>(null)
const uploading = ref(false)
const batch = ref<UploadBatchView | null>(null)

function pickFiles() {
  fileInput.value?.click()
}

async function onFilesPicked(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (files.length === 0) return

  if (files.length > 20) {
    toast.push('一次最多上传 20 个文件', 'danger')
    return
  }
  uploading.value = true
  batch.value = null
  try {
    batch.value = await knowledgeApi.upload(spaceId.value, files)
    const b = batch.value
    toast.push(`上传完成：成功 ${b.successCount} 个，失败 ${b.failedCount} 个`, b.failedCount ? 'danger' : 'success')
    await loadDocs(spaceId.value)
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <div class="space-page">
    <p class="crumbs">
      <RouterLink to="/knowledge">书架</RouterLink>
      <span aria-hidden="true">/</span>
      <span>{{ space?.name ?? '空间' }}</span>
    </p>

    <p v-if="spaceLoading" class="state">正在打开这一格…</p>

    <div v-else-if="spaceError" class="state state--err" role="alert">
      <p>{{ spaceError }}</p>
      <UiButton size="sm" @click="loadSpace(spaceId)">重试</UiButton>
      <RouterLink to="/knowledge" class="state__link">回到书架</RouterLink>
    </div>

    <template v-else-if="space">
      <header class="head">
        <div class="head__row">
          <p class="head__eyebrow eyebrow">
            {{ space.categoryName || '未分类' }}<span class="head__latin latin-italic">Space</span>
          </p>
          <div class="head__actions">
            <UiButton v-if="canManageMembers" size="sm" @click="membersOpen = true">成员</UiButton>
            <UiButton v-if="canWrite" size="sm" :loading="uploading" @click="pickFiles">批量上传 .md</UiButton>
            <UiButton v-if="canWrite" size="sm" variant="primary" @click="openNew">新建文档</UiButton>
            <input
              ref="fileInput"
              class="visually-hidden"
              type="file"
              accept=".md,text/markdown"
              multiple
              @change="onFilesPicked"
            />
          </div>
        </div>
        <h1 class="head__title">{{ space.name }}</h1>
        <p v-if="space.description" class="head__lede">{{ space.description }}</p>
        <p class="head__meta">
          <UiTag :tone="space.visibility === 'PUBLIC' ? 'teal' : 'neutral'">
            {{ VISIBILITY_LABEL[space.visibility] ?? space.visibility }}
          </UiTag>
          <span>你的权限：{{ ACCESS_LABEL[space.accessLevel] ?? space.accessLevel }}</span>
          <span v-if="space.ownerName">所有者 {{ space.ownerName }}</span>
          <span class="mono">{{ space.memberCount }} 位成员</span>
          <span class="mono">{{ total }} 篇文档</span>
        </p>
      </header>

      <!-- 上传结果 -->
      <section v-if="batch" class="batch" aria-label="上传结果">
        <div class="batch__head">
          <p class="batch__title">
            本批 <span class="mono">{{ batch.totalCount }}</span> 个文件：成功
            <span class="mono">{{ batch.successCount }}</span> · 失败
            <span class="mono">{{ batch.failedCount }}</span>
          </p>
          <UiButton size="sm" variant="link" @click="batch = null">收起</UiButton>
        </div>
        <ul class="batch__list">
          <li v-for="it in batch.items" :key="it.filename" :class="{ 'is-fail': !it.success }">
            <span class="batch__file mono">{{ it.filename }}</span>
            <span class="batch__msg">{{ it.success ? '已受理' : it.error || '处理失败' }}</span>
          </li>
        </ul>
      </section>

      <form class="search" role="search" @submit.prevent="search">
        <label class="search__label" for="doc-search">检索</label>
        <input
          id="doc-search"
          v-model="keyword"
          class="search__input"
          type="search"
          placeholder="在本文档集内按标题查找"
          autocomplete="off"
        />
        <UiButton size="sm" type="submit">查找</UiButton>
        <UiButton v-if="keyword" size="sm" variant="link" @click="keyword = ''; search()">清除</UiButton>
      </form>

      <p v-if="docsLoading" class="state">正在翻页…</p>

      <div v-else-if="docsError" class="state state--err" role="alert">
        <p>{{ docsError }}</p>
        <UiButton size="sm" @click="loadDocs(spaceId)">重试</UiButton>
      </div>

      <UiEmpty
        v-else-if="docs.length === 0"
        :title="keyword ? '没有匹配的文档' : '这个空间还没有文档'"
        :hint="
          keyword
            ? '换个词试试，或清除筛选。'
            : canWrite
              ? '可以上传 Markdown 文件，或直接在线撰写第一篇。'
              : '空间成员添加文档后会显示在这里。'
        "
      >
        <UiButton v-if="keyword" size="sm" @click="keyword = ''; search()">看全部</UiButton>
        <UiButton v-else-if="canWrite" size="sm" variant="primary" @click="openNew">新建文档</UiButton>
      </UiEmpty>

      <ol v-else class="docs">
        <li v-for="(d, i) in docs" :key="d.id" class="doc-row">
          <span class="doc-row__no latin-italic">{{ String(i + 1).padStart(2, '0') }}</span>
          <div class="doc-row__main">
            <RouterLink class="doc-row__title" :to="{ name: 'document', params: { id: d.id } }">
              {{ d.title }}
            </RouterLink>
            <p class="doc-row__meta">
              <UiTag :tone="processTone(d.processStatus)">{{ PROCESS_LABEL[d.processStatus] ?? d.processStatus }}</UiTag>
              <span>{{ SOURCE_LABEL[d.sourceType] ?? d.sourceType }}</span>
              <span v-if="d.versionNo" class="mono">第 {{ d.versionNo }} 版</span>
              <span class="mono">{{ d.chunkCount }} 块</span>
              <span v-if="d.uploadedByName">{{ d.uploadedByName }}</span>
            </p>
          </div>
          <div class="doc-row__right">
            <span class="doc-row__time mono">{{ formatDateTime(d.updatedAt) }}</span>
            <RouterLink class="doc-row__open" :to="{ name: 'document', params: { id: d.id } }">阅读</RouterLink>
          </div>
        </li>
      </ol>

      <UiPager v-if="pages > 1" :page="page" :pages="pages" :total="total" @change="page = $event" />
    </template>

    <!-- 成员授权 -->
    <MembersDialog
      v-if="space && canManageMembers"
      :open="membersOpen"
      :space="space"
      @close="membersOpen = false"
      @changed="loadSpace(spaceId)"
    />

    <!-- 新建文档 -->
    <UiDialog
      :open="newOpen"
      title="新建文档"
      eyebrow="Markdown"
      width="lg"
      :dismissible="!newBusy"
      @close="newOpen = false"
    >
      <div class="form">
        <UiField v-model="draft.title" label="标题" :error="draftError.title" :maxlength="120" required />
        <UiTextarea
          v-model="draft.content"
          label="正文"
          :rows="14"
          mono
          :error="draftError.content"
          hint="支持 Markdown。# 开头的标题会成为切块与引用的章节路径。"
          placeholder="# 入学准备&#10;&#10;报到当天需要携带……"
        />
        <UiField
          v-model="draft.changeNote"
          label="修改说明"
          hint="可选，写在版本记录里，方便日后回溯改了什么。"
          :maxlength="200"
        />
        <p v-if="draftError.general" class="form__err" role="alert">{{ draftError.general }}</p>
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="newBusy" @click="newOpen = false">取消</UiButton>
        <UiButton variant="primary" :loading="newBusy" @click="submitNew">创建并打开</UiButton>
      </template>
    </UiDialog>
  </div>
</template>

<style scoped>
.space-page {
  width: 100%;
  max-width: 1080px;
  margin: 0 auto;
  padding: var(--s-5) clamp(var(--s-4), 4vw, var(--s-6)) var(--s-7);
}

.crumbs {
  display: flex;
  gap: var(--s-2);
  margin: 0 0 var(--s-4);
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.crumbs a {
  color: var(--ink-2);
}

.crumbs a:hover {
  color: var(--teal);
}

.head {
  padding-bottom: var(--s-4);
  margin-bottom: var(--s-5);
  border-bottom: 1px solid var(--ink);
}

.head__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-4);
  min-height: 32px;
}

.head__eyebrow {
  margin: 0;
}

.head__latin {
  margin-left: var(--s-2);
  font-weight: 400;
  letter-spacing: 0;
  color: var(--ink-2);
}

.head__actions {
  display: flex;
  gap: var(--s-2);
}

.head__title {
  margin: var(--s-2) 0 0;
  font-family: var(--font-serif-cjk);
  font-size: var(--fs-h2);
  line-height: 1.12;
}

.head__lede {
  max-width: 34em;
  margin: var(--s-3) 0 0;
  color: var(--ink-2);
  font-family: var(--font-serif);
  font-size: 17px;
  line-height: 1.6;
}

.head__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--s-3);
  margin: var(--s-3) 0 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

/* 上传结果 */
.batch {
  padding: var(--s-3);
  margin-bottom: var(--s-5);
  border: 1px solid var(--rule-strong);
  background: var(--paper-lift);
}

.batch__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-3);
}

.batch__title {
  margin: 0;
  font-size: var(--fs-ui);
}

.batch__list {
  margin: var(--s-2) 0 0;
  padding: 0;
  list-style: none;
  font-size: var(--fs-small);
}

.batch__list li {
  display: flex;
  gap: var(--s-3);
  padding: 2px 0;
  border-top: var(--hairline);
}

.batch__file {
  color: var(--ink);
}

.batch__msg {
  color: var(--ink-2);
}

.batch__list li.is-fail .batch__msg {
  color: var(--ochre);
}

.search {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  padding-bottom: var(--s-3);
  margin-bottom: var(--s-4);
  border-bottom: var(--hairline);
}

.search__label {
  font-size: var(--fs-small);
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--ink-2);
}

.search__input {
  flex: 1;
  min-width: 0;
  height: 32px;
  padding: 0 2px;
  border: 0;
  background: transparent;
  font-size: 15px;
}

.search__input:focus {
  outline: none;
  box-shadow: 0 1px 0 0 var(--teal);
}

.state {
  margin: 0;
  padding: var(--s-5) 0;
  color: var(--ink-2);
  font-family: var(--font-serif);
  font-size: var(--fs-read);
}

.state--err {
  color: var(--ochre);
}

.state__link {
  margin-left: var(--s-3);
}

/* 文档清单：编号 + 标题 + 元信息 + 右侧时间 */
.docs {
  margin: 0;
  padding: 0;
  list-style: none;
}

.doc-row {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) auto;
  align-items: start;
  gap: var(--s-3);
  padding: var(--s-3) 0;
  border-bottom: var(--hairline);
  transition: background-color var(--t-fast) var(--ease);
}

.doc-row:hover {
  background: color-mix(in srgb, var(--paper-lift) 70%, transparent);
}

.doc-row__no {
  color: var(--rule-strong);
  font-size: 15px;
  line-height: 1.5;
  text-align: right;
}

.doc-row__main {
  min-width: 0;
}

.doc-row__title {
  display: inline-block;
  color: var(--ink);
  font-family: var(--font-serif-cjk);
  font-size: 18px;
  font-weight: 600;
  line-height: 1.4;
  text-decoration: none;
}

.doc-row__title:hover {
  color: var(--teal);
  text-decoration: underline;
  text-decoration-thickness: 1px;
  text-underline-offset: 3px;
}

.doc-row__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--s-2);
  margin: var(--s-1) 0 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.doc-row__right {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  white-space: nowrap;
}

.doc-row__time {
  color: var(--ink-2);
  font-size: 11px;
}

.doc-row__open {
  font-size: var(--fs-small);
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

@media (max-width: 640px) {
  .doc-row {
    grid-template-columns: 28px minmax(0, 1fr);
  }

  .doc-row__right {
    grid-column: 2;
    justify-content: space-between;
    margin-top: var(--s-1);
  }
}
</style>
