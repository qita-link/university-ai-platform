<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { forumApi } from '@/api/endpoints'
import type { ForumCategory, ForumPostSummary } from '@/api/types'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { formatShort } from '@/lib/time'
import { useClockStore } from '@/stores/clock'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiEmpty from '@/components/ui/UiEmpty.vue'
import UiField from '@/components/ui/UiField.vue'
import UiPageHeader from '@/components/ui/UiPageHeader.vue'
import UiPager from '@/components/ui/UiPager.vue'
import UiSelect from '@/components/ui/UiSelect.vue'
import UiTag from '@/components/ui/UiTag.vue'
import UiTextarea from '@/components/ui/UiTextarea.vue'
import ForumCategoryDialog from '@/components/forum/ForumCategoryDialog.vue'
import UserAvatar from '@/components/ui/UserAvatar.vue'
import PostView from '@/views/forum/PostView.vue'

/**
 * 论坛列表：左侧分类，右侧按「最近有动静」排序的帖子。
 * 置顶帖单独标出，其余按 lastActivityAt 倒序——与后端排序一致，前端不再二次排序。
 */
const auth = useAuthStore()
const toast = useToastStore()
const clock = useClockStore()
const router = useRouter()
const route = useRoute()

/** 帖子抽屉：点列表从右侧拉出阅读，而不是整页跳转；地址栏保留 ?post=ID 便于刷新/回退 */
const activePostId = ref<number | null>(null)

function syncPostFromQuery() {
  const raw = Array.isArray(route.query.post) ? route.query.post[0] : route.query.post
  const id = Number(raw)
  activePostId.value = Number.isInteger(id) && id > 0 ? id : null
}

watch(() => route.query.post, syncPostFromQuery, { immediate: true })

function openPost(id: number) {
  void router.replace({ query: { ...route.query, post: String(id) } })
}

function closePost() {
  const query = { ...route.query }
  delete query.post
  void router.replace({ query })
}

watch(activePostId, (id) => {
  document.body.style.overflow = id ? 'hidden' : ''
})

function onDrawerKey(e: KeyboardEvent) {
  if (e.key === 'Escape') closePost()
}

onMounted(() => document.addEventListener('keydown', onDrawerKey))
onBeforeUnmount(() => {
  document.removeEventListener('keydown', onDrawerKey)
  document.body.style.overflow = ''
})

const categories = ref<ForumCategory[]>([])
const posts = ref<ForumPostSummary[]>([])
const total = ref(0)
const pages = ref(0)
const page = ref(1)
const activeCategory = ref<number | null>(null)
const keyword = ref('')
/** 手机端检索默认收起，只留放大镜 */
const searchOpen = ref(false)
const searchInput = ref<HTMLInputElement | null>(null)

function toggleSearch() {
  searchOpen.value = !searchOpen.value
  if (searchOpen.value) nextTick(() => searchInput.value?.focus())
}

const loading = ref(true)
const loadError = ref<string | null>(null)

const canWrite = computed(() => auth.can('forum:post:write'))
const canAnnounce = computed(() => auth.can('forum:announce'))
/** 公告分类 id 集合：列表里据此给帖子打「公告」标 */
const announcementIds = computed(() => new Set(categories.value.filter((c) => c.announcement).map((c) => c.id)))
const draftIsAnnouncement = computed(() => {
  const id = Number(draft.value.categoryId)
  return id > 0 && announcementIds.value.has(id)
})
const canManageCategories = computed(() => auth.can('forum:category:manage'))
const categoryDialog = ref(false)

async function loadCategories() {
  try {
    categories.value = await forumApi.categories()
  } catch (e) {
    console.warn('分类加载失败', e)
  }
}

async function loadPosts() {
  loading.value = true
  loadError.value = null
  try {
    const res = await forumApi.posts({
      page: page.value,
      size: 20,
      categoryId: activeCategory.value,
      keyword: keyword.value.trim() || undefined,
    })
    posts.value = res.items
    total.value = res.total
    pages.value = res.pages
  } catch (e) {
    posts.value = []
    loadError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

function selectCategory(id: number | null) {
  if (activeCategory.value === id) return
  activeCategory.value = id
  page.value = 1
  void loadPosts()
}

function search() {
  page.value = 1
  void loadPosts()
}

watch(page, () => void loadPosts())

onMounted(async () => {
  await Promise.all([loadCategories(), loadPosts()])
})

/* ---------------- 发帖 ---------------- */
const composeOpen = ref(false)
const composeBusy = ref(false)
const draft = ref({ categoryId: '', title: '', content: '' })
const draftError = ref<{ categoryId?: string | null; title?: string | null; content?: string | null; general?: string | null }>({})

const categoryOptions = computed(() =>
  categories.value.map((c) => ({ value: String(c.id), label: c.announcement ? `${c.name}（公告）` : c.name })),
)

function openCompose() {
  draft.value = {
    categoryId: activeCategory.value ? String(activeCategory.value) : String(categories.value[0]?.id ?? ''),
    title: '',
    content: '',
  }
  draftError.value = {}
  composeOpen.value = true
}

async function submitCompose() {
  draftError.value = {}
  if (!draft.value.categoryId) {
    draftError.value = { categoryId: '请选择分类' }
    return
  }
  if (!draft.value.title.trim()) {
    draftError.value = { title: '请填写标题' }
    return
  }
  if (!draft.value.content.trim()) {
    draftError.value = { content: '正文不能为空' }
    return
  }
  composeBusy.value = true
  try {
    const created = await forumApi.createPost({
      categoryId: Number(draft.value.categoryId),
      title: draft.value.title.trim(),
      content: draft.value.content,
    })
    composeOpen.value = false
    toast.push('已发布', 'success')
    await router.push({ name: 'post', params: { id: created.id } })
  } catch (e) {
    draftError.value = {
      categoryId: e instanceof ApiError ? e.fieldError('categoryId') : null,
      title: e instanceof ApiError ? e.fieldError('title') : null,
      content: e instanceof ApiError ? e.fieldError('content') : null,
      general: errorMessage(e),
    }
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    composeBusy.value = false
  }
}
</script>

<template>
  <div class="forum">
    <aside class="rail" aria-label="分类">
      <p class="rail__title eyebrow">分类</p>
      <ul class="rail__list">
        <li>
          <button
            type="button"
            class="rail__item"
            :class="{ 'is-active': activeCategory === null }"
            @click="selectCategory(null)"
          >
            <span>全部</span>
          </button>
        </li>
        <li v-for="c in categories" :key="c.id">
          <button
            type="button"
            class="rail__item"
            :class="{ 'is-active': activeCategory === c.id }"
            @click="selectCategory(c.id)"
          >
            <span>
              {{ c.name }}
              <span v-if="c.announcement" class="rail__flag">公告</span>
            </span>
          </button>
        </li>
      </ul>

      <button
        type="button"
        class="search-toggle"
        :class="{ 'is-open': searchOpen }"
        :aria-expanded="searchOpen"
        aria-label="搜索帖子"
        @click="toggleSearch"
      >
        <svg viewBox="0 0 24 24" width="17" height="17" aria-hidden="true">
          <circle cx="11" cy="11" r="6.4" fill="none" stroke="currentColor" stroke-width="1.6" />
          <path d="M16 16l4.2 4.2" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
        </svg>
        <span v-if="keyword" class="search-toggle__dot" aria-hidden="true" />
      </button>
      <p v-if="categories.length === 0" class="rail__hint">还没有分类，管理员建好分类后就能发帖了。</p>
    </aside>

    <section class="body">
      <UiPageHeader
        label="论坛"
        latin="Letters"
        title="来信"
        compact
        lede="同学之间的问答、经验与提醒。发帖即公开，请注意个人信息；版主可以隐藏不当内容。公告分类由管理员发布，会同时推送站内信并弹窗。"
      >
        <template #actions>
          <UiButton v-if="canManageCategories" size="sm" @click="categoryDialog = true">管理分类</UiButton>
          <UiButton v-if="canWrite" variant="primary" :disabled="categories.length === 0" @click="openCompose">
            写一封
          </UiButton>
        </template>
        <template #meta>
          <span>共 {{ total }} 封</span>
          <span v-if="activeCategory !== null">已按分类筛选</span>
        </template>
      </UiPageHeader>

      <form
        class="search search--collapsible"
        :class="{ 'is-open': searchOpen }"
        role="search"
        @submit.prevent="search"
      >
        <label class="search__label" for="forum-search">检索</label>
        <input
          id="forum-search"
          ref="searchInput"
          v-model="keyword"
          class="search__input"
          type="search"
          placeholder="按标题或正文查找"
          autocomplete="off"
        />
        <UiButton size="sm" type="submit">查找</UiButton>
        <UiButton v-if="keyword" size="sm" variant="link" @click="keyword = ''; search()">清除</UiButton>
      </form>

      <p v-if="loading" class="state">正在取信…</p>

      <div v-else-if="loadError" class="state state--err" role="alert">
        <p>{{ loadError }}</p>
        <UiButton size="sm" @click="loadPosts">重试</UiButton>
      </div>

      <UiEmpty
        v-else-if="posts.length === 0"
        :title="keyword ? '没有匹配的帖子' : '这里还没有人写信'"
        :hint="keyword ? '换个词试试，或清除筛选。' : canWrite ? '第一个提问往往最有价值。' : '登录后可以发帖与回复。'"
      >
        <UiButton v-if="keyword" size="sm" @click="keyword = ''; search()">看全部</UiButton>
        <UiButton v-else-if="canWrite" size="sm" variant="primary" @click="openCompose">写一封</UiButton>
      </UiEmpty>

      <ol v-else class="posts">
        <li v-for="p in posts" :key="p.id" class="post" :class="{ 'is-pinned': p.pinned }">
          <a class="post__link" :href="`/forum/posts/${p.id}`" @click.prevent="openPost(p.id)">
            <p class="post__tags">
              <UiTag v-if="p.pinned" tone="ochre">置顶</UiTag>
              <UiTag v-if="announcementIds.has(p.categoryId)" tone="ochre">公告</UiTag>
              <span class="post__cat">{{ p.categoryName }}</span>
              <span v-if="p.status === 'HIDDEN'" class="post__hidden">已隐藏</span>
            </p>
            <h2 class="post__title">{{ p.title }}</h2>
            <p class="post__excerpt">{{ p.excerpt || '（无摘要）' }}</p>
            <p class="post__meta">
              <UserAvatar
                :user-id="p.authorId"
                :version="p.authorAvatarVersion"
                :name="p.authorName"
                :size="20"
              />
              <span>{{ p.authorName }}</span>
              <span class="mono">{{ formatShort(p.lastActivityAt ?? p.createdAt, clock.now) }}</span>
              <span class="post__replies mono">{{ p.commentCount }} 回复</span>
            </p>
          </a>
        </li>
      </ol>

      <UiPager v-if="pages > 1" :page="page" :pages="pages" :total="total" @change="page = $event" />
    </section>

    <!-- 帖子抽屉：从右侧拉出，列表留在原处 -->
    <Teleport to="body">
      <Transition name="pdrawer">
        <div v-if="activePostId" class="pdrawer" role="presentation">
          <div class="pdrawer__scrim" @click="closePost" />
          <aside class="pdrawer__panel" role="dialog" aria-modal="true" aria-label="帖子">
            <header class="pdrawer__bar">
              <span class="eyebrow">论坛 · Letter</span>
              <button type="button" class="pdrawer__close" aria-label="关闭帖子" @click="closePost">×</button>
            </header>
            <div class="pdrawer__body">
              <PostView :post-id="activePostId" embedded @close="closePost" />
            </div>
          </aside>
        </div>
      </Transition>
    </Teleport>

    <!-- 分类管理 -->
    <ForumCategoryDialog
      :open="categoryDialog"
      @close="categoryDialog = false"
      @changed="loadCategories(); loadPosts()"
    />

    <UiDialog
      :open="composeOpen"
      title="写一封"
      eyebrow="发帖"
      width="lg"
      :dismissible="!composeBusy"
      @close="composeOpen = false"
    >
      <div class="form">
        <UiSelect v-model="draft.categoryId" label="分类" :options="categoryOptions" :error="draftError.categoryId" placeholder="选择分类" />
        <UiField v-model="draft.title" label="标题" :error="draftError.title" :maxlength="120" required />
        <UiTextarea
          v-model="draft.content"
          label="正文"
          :rows="12"
          :error="draftError.content"
          hint="支持 Markdown。写清楚背景与具体问题，更容易得到回答。"
          placeholder="我在选课时遇到……"
        />
        <p v-if="draftIsAnnouncement" class="form__warn">
          <template v-if="canAnnounce">
            这是公告分类：发布后会以弹窗和站内信通知全校，请确认内容已定稿。
          </template>
          <template v-else>这是公告分类，只有管理员可以发布，换个分类再发。</template>
        </p>
        <p v-if="draftError.general" class="form__err" role="alert">{{ draftError.general }}</p>
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="composeBusy" @click="composeOpen = false">取消</UiButton>
        <UiButton
          variant="primary"
          :loading="composeBusy"
          :disabled="draftIsAnnouncement && !canAnnounce"
          @click="submitCompose"
        >
          {{ draftIsAnnouncement ? '发布公告' : '发布' }}
        </UiButton>
      </template>
    </UiDialog>
  </div>
</template>

<style scoped>
.forum {
  --rail-w: 208px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) var(--rail-w);
  align-items: start;
  width: 100%;
  max-width: 1240px;
  margin: 0 auto;
  padding: var(--s-5) clamp(var(--s-4), 4vw, var(--s-6)) var(--s-7);
  gap: clamp(var(--s-4), 3vw, var(--s-6));
}

.rail {
  position: sticky;
  top: calc(var(--masthead-h) + var(--s-4));
  grid-column: 2;
  grid-row: 1;
}

.rail__title {
  margin: 0 0 var(--s-2);
  padding-bottom: var(--s-2);
  border-bottom: 1px solid var(--ink);
}

.rail__list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.rail__item {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-2);
  width: 100%;
  min-height: 34px;
  padding: 0 var(--s-2);
  border: 0;
  border-right: 2px solid transparent;
  background: none;
  color: var(--ink-2);
  font-family: var(--font-serif-cjk);
  font-size: 15px;
  text-align: left;
  cursor: pointer;
  transition:
    color var(--t-fast) var(--ease),
    border-color var(--t-fast) var(--ease),
    background-color var(--t-fast) var(--ease);
}

.rail__item:hover {
  color: var(--ink);
  background: color-mix(in srgb, var(--paper-deep) 60%, transparent);
}

.rail__item.is-active {
  border-right-color: var(--teal);
  color: var(--ink);
  font-weight: 600;
}

.rail__hint {
  margin: var(--s-3) 0 0;
  padding: 0 var(--s-2);
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.7;
}

.body {
  grid-column: 1;
  grid-row: 1;
  min-width: 0;
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

.posts {
  margin: 0;
  padding: 0;
  list-style: none;
}

.post {
  border-bottom: var(--hairline);
}

.post__link {
  display: block;
  padding: var(--s-4) var(--s-3);
  color: inherit;
  text-decoration: none;
  transition: background-color var(--t-fast) var(--ease);
}

.post__link:hover {
  background: color-mix(in srgb, var(--paper-lift) 70%, transparent);
}

.post__tags {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  margin: 0 0 var(--s-1);
}

.post__cat {
  color: var(--ink-2);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.12em;
}

.post__hidden {
  color: var(--ochre);
  font-size: 11px;
  letter-spacing: 0.08em;
}

.post__title {
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-size: 20px;
  line-height: 1.35;
}

.post__link:hover .post__title {
  color: var(--teal);
}

.post__excerpt {
  display: -webkit-box;
  max-width: 46em;
  margin: var(--s-1) 0 var(--s-2);
  overflow: hidden;
  color: var(--ink-2);
  font-size: var(--fs-ui);
  line-height: 1.6;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.post__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-3);
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.post__replies {
  margin-left: auto;
  color: var(--ink-2);
}

.post.is-pinned .post__title::before {
  content: '';
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

.form__warn {
  margin: 0;
  padding: var(--s-2) var(--s-3);
  border-left: 2px solid var(--ochre);
  background: color-mix(in srgb, var(--ochre) 8%, transparent);
  color: var(--ink);
  font-size: var(--fs-small);
  line-height: 1.7;
}

@media (max-width: 900px) {
  .forum {
    grid-template-columns: minmax(0, 1fr);
  }

  .rail,
  .body {
    grid-column: auto;
    grid-row: auto;
  }

  .rail,
  .body {
    grid-column: auto;
    grid-row: auto;
  }

  .rail {
    position: static;
  }

  .rail__list {
    display: flex;
    flex-wrap: wrap;
    gap: var(--s-1);
  }

  .rail__item {
    width: auto;
    gap: var(--s-1);
    border-right: 0;
    border-bottom: 2px solid transparent;
  }

  .rail__item.is-active {
    border-right: 0;
    border-bottom-color: var(--teal);
  }
}

/* 手机：分类单行横滑、隐藏摘要，第一屏尽量多列几封 */
@media (max-width: 640px) {
  .forum {
    padding: var(--s-3) 14px var(--s-6);
    gap: 8px;
  }

  .rail {
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .rail__title {
    display: none;
  }

  .rail__list {
    flex: 1 1 auto;
    min-width: 0;
    flex-wrap: nowrap;
    overflow-x: auto;
    gap: 6px;
    padding-bottom: 2px;
    scrollbar-width: none;
    mask-image: linear-gradient(to right, #000 calc(100% - 16px), transparent);
  }

  .rail__list::-webkit-scrollbar {
    display: none;
  }

  .rail__item {
    flex: 0 0 auto;
    min-height: 28px;
    padding: 0 8px;
    font-size: 13px;
    white-space: nowrap;
  }

  .body :deep(.ph) {
    padding-bottom: 6px;
    margin-bottom: 8px;
  }

  .body :deep(.ph__eyebrow) {
    display: none;
  }

  .body :deep(.ph__title) {
    font-size: 22px;
  }

  .post__link {
    padding: 10px 0;
  }

  .post__tags {
    gap: 6px;
    margin-bottom: 2px;
  }

  .post__cat {
    font-size: 10px;
  }

  .post__title {
    font-size: 17px;
    line-height: 1.35;
  }

  .post__excerpt {
    display: none;
  }

  .post__meta {
    gap: 10px;
    margin-top: 3px;
    font-size: 11px;
  }
}

/* ---------- 帖子抽屉 ---------- */
.pdrawer {
  position: fixed;
  inset: 0;
  z-index: 110;
}

.pdrawer__scrim {
  position: absolute;
  inset: 0;
  background: color-mix(in srgb, var(--ink) 24%, transparent);
}

.pdrawer__panel {
  position: absolute;
  top: 0;
  right: 0;
  display: flex;
  flex-direction: column;
  width: min(760px, 92vw);
  height: 100dvh;
  border-left: 1px solid var(--ink);
  background: var(--paper);
}

.pdrawer-enter-active .pdrawer__panel,
.pdrawer-leave-active .pdrawer__panel {
  transition: transform 240ms var(--ease);
}

.pdrawer-enter-active .pdrawer__scrim,
.pdrawer-leave-active .pdrawer__scrim {
  transition: opacity 200ms var(--ease);
}

.pdrawer-enter-from .pdrawer__panel,
.pdrawer-leave-to .pdrawer__panel {
  transform: translateX(100%);
}

.pdrawer-enter-from .pdrawer__scrim,
.pdrawer-leave-to .pdrawer__scrim {
  opacity: 0;
}

.pdrawer__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px var(--s-4);
  border-bottom: var(--hairline);
}

.pdrawer__close {
  width: 30px;
  height: 30px;
  border: 0;
  background: none;
  color: var(--ink-2);
  font-size: 22px;
  line-height: 1;
  cursor: pointer;
}

.pdrawer__close:hover {
  color: var(--ink);
}

.pdrawer__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 0 var(--s-4);
}

@media (max-width: 640px) {
  .pdrawer__panel {
    width: 100vw;
  }

  .pdrawer__body {
    padding: 0 14px;
  }
}

/* ---------- 帖子抽屉 ---------- */
.pdrawer {
  position: fixed;
  inset: 0;
  z-index: 110;
}

.pdrawer__scrim {
  position: absolute;
  inset: 0;
  background: color-mix(in srgb, var(--ink) 24%, transparent);
}

.pdrawer__panel {
  position: absolute;
  top: 0;
  right: 0;
  display: flex;
  flex-direction: column;
  width: min(760px, 92vw);
  height: 100dvh;
  border-left: 1px solid var(--ink);
  background: var(--paper);
}

.pdrawer-enter-active .pdrawer__panel,
.pdrawer-leave-active .pdrawer__panel {
  transition: transform 240ms var(--ease);
}

.pdrawer-enter-active .pdrawer__scrim,
.pdrawer-leave-active .pdrawer__scrim {
  transition: opacity 200ms var(--ease);
}

.pdrawer-enter-from .pdrawer__panel,
.pdrawer-leave-to .pdrawer__panel {
  transform: translateX(100%);
}

.pdrawer-enter-from .pdrawer__scrim,
.pdrawer-leave-to .pdrawer__scrim {
  opacity: 0;
}

.pdrawer__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px var(--s-4);
  border-bottom: var(--hairline);
}

.pdrawer__close {
  width: 30px;
  height: 30px;
  border: 0;
  background: none;
  color: var(--ink-2);
  font-size: 22px;
  line-height: 1;
  cursor: pointer;
}

.pdrawer__close:hover {
  color: var(--ink);
}

.pdrawer__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 0 var(--s-4);
}

@media (max-width: 640px) {
  .pdrawer__panel {
    width: 100vw;
  }

  .pdrawer__body {
    padding: 0 14px;
  }
}
</style>
