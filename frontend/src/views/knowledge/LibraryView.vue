<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { knowledgeApi } from '@/api/endpoints'
import type { KnowledgeCategory, SpaceView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useToastStore, } from '@/stores/toast'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { ACCESS_LABEL, VISIBILITY_LABEL } from '@/lib/labels'
import { formatDateTime } from '@/lib/time'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiEmpty from '@/components/ui/UiEmpty.vue'
import UiField from '@/components/ui/UiField.vue'
import UiPageHeader from '@/components/ui/UiPageHeader.vue'
import UiPager from '@/components/ui/UiPager.vue'
import UiSelect from '@/components/ui/UiSelect.vue'
import UiTag from '@/components/ui/UiTag.vue'
import CategoryDialog from '@/components/knowledge/CategoryDialog.vue'

/**
 * 书架：左侧分类目录，右侧空间（一排排书）。
 * 只做「找到一本书并打开」，写操作收在新建空间与后续的空间页里。
 */
const auth = useAuthStore()
const toast = useToastStore()

const categories = ref<KnowledgeCategory[]>([])
const spaces = ref<SpaceView[]>([])
const total = ref(0)
const pages = ref(0)
const page = ref(1)

const loading = ref(true)
const loadError = ref<string | null>(null)
const activeCategory = ref<number | null>(null)
const keyword = ref('')

const canCreate = computed(() => auth.can('kb:space:create', 'kb:space:manage_all'))
const canManageCategories = computed(() => auth.can('kb:category:manage'))
const categoryDialog = ref(false)

/** 分类树最多两级，这里摊平成「父 + 子」的下拉项与目录项 */
const flatCategories = computed(() => {
  const out: { id: number; name: string; depth: number }[] = []
  for (const c of categories.value) {
    out.push({ id: c.id, name: c.name, depth: 0 })
    for (const child of c.children ?? []) out.push({ id: child.id, name: child.name, depth: 1 })
  }
  return out
})

const categoryOptions = computed(() => [
  { value: '', label: '未分类' },
  ...flatCategories.value.map((c) => ({ value: String(c.id), label: '　'.repeat(c.depth) + c.name })),
])

/** 分类在目录里的位次，用来给书架排序（后端按更新时间返回，会打乱用途顺序） */
const categoryOrder = computed(() => {
  const map = new Map<number, number>()
  let i = 0
  for (const c of categories.value) {
    map.set(c.id, i++)
    for (const child of c.children ?? []) map.set(child.id, i++)
  }
  return map
})

const sortedSpaces = computed(() => {
  const order = categoryOrder.value
  const rank = (id: number | null) => (id ? (order.get(id) ?? 999) : 998)
  return [...spaces.value].sort((a, b) => {
    const diff = rank(a.categoryId) - rank(b.categoryId)
    return diff !== 0 ? diff : a.name.localeCompare(b.name, 'zh-Hans-CN')
  })
})

async function loadCategories() {
  try {
    categories.value = await knowledgeApi.categories()
  } catch (e) {
    // 分类读不到不应阻断书架；只在控制台留痕
    console.warn('分类加载失败', e)
  }
}

async function loadSpaces() {
  loading.value = true
  loadError.value = null
  try {
    const res = await knowledgeApi.spaces({
      page: page.value,
      size: 12,
      keyword: keyword.value.trim() || undefined,
      categoryId: activeCategory.value,
    })
    spaces.value = res.items
    total.value = res.total
    pages.value = res.pages
  } catch (e) {
    loadError.value = errorMessage(e)
    spaces.value = []
  } finally {
    loading.value = false
  }
}

function selectCategory(id: number | null) {
  if (activeCategory.value === id) return
  activeCategory.value = id
  page.value = 1
  void loadSpaces()
}

function search() {
  page.value = 1
  void loadSpaces()
}

watch(page, () => void loadSpaces())

onMounted(async () => {
  await Promise.all([loadCategories(), loadSpaces()])
})

/* ---------------- 新建空间 ---------------- */
const createOpen = ref(false)
const createBusy = ref(false)
const form = ref({ name: '', description: '', categoryId: '', visibility: 'PRIVATE' })
const formError = ref<{ name?: string | null; general?: string | null }>({})

const visibilityOptions = computed(() => {
  const base = [
    { value: 'PRIVATE', label: '私有：仅成员可读' },
    { value: 'ORG', label: '组织内：本组织成员可读' },
  ]
  if (auth.can('kb:space:manage_all')) base.unshift({ value: 'PUBLIC', label: '公开：全校可读' })
  return base
})

function openCreate() {
  form.value = { name: '', description: '', categoryId: '', visibility: 'PRIVATE' }
  formError.value = {}
  createOpen.value = true
}

async function submitCreate() {
  formError.value = {}
  if (!form.value.name.trim()) {
    formError.value = { name: '请填写空间名称' }
    return
  }
  createBusy.value = true
  try {
    await knowledgeApi.createSpace({
      categoryId: form.value.categoryId ? Number(form.value.categoryId) : null,
      name: form.value.name.trim(),
      description: form.value.description.trim() || null,
      visibility: form.value.visibility,
    })
    createOpen.value = false
    toast.push('空间已创建', 'success')
    await Promise.all([loadCategories(), loadSpaces()])
  } catch (e) {
    formError.value = { general: errorMessage(e) }
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    createBusy.value = false
  }
}
</script>

<template>
  <div class="library">
    <!-- 目录 -->
    <aside class="rail" aria-label="分类目录">
      <p class="rail__title eyebrow">分类</p>
      <ul class="rail__list">
        <li>
          <button
            type="button"
            class="rail__item"
            :class="{ 'is-active': activeCategory === null }"
            :aria-current="activeCategory === null ? 'true' : undefined"
            @click="selectCategory(null)"
          >
            <span class="rail__name">全部</span>
          </button>
        </li>
        <li v-for="c in flatCategories" :key="c.id">
          <button
            type="button"
            class="rail__item"
            :class="{ 'is-active': activeCategory === c.id, 'is-child': c.depth > 0 }"
            :aria-current="activeCategory === c.id ? 'true' : undefined"
            @click="selectCategory(c.id)"
          >
            <span class="rail__name">{{ c.name }}</span>
          </button>
        </li>
      </ul>

      <p v-if="flatCategories.length === 0" class="rail__hint">还没有分类。建好分类后，书会按类归架。</p>

      <UiButton
        v-if="canManageCategories"
        size="sm"
        variant="link"
        class="rail__manage"
        @click="categoryDialog = true"
      >
        管理分类
      </UiButton>
    </aside>

    <!-- 书架 -->
    <section class="body">
      <UiPageHeader
        label="知识库"
        latin="Library"
        title="书架"
        lede="这里存放全校共享的文档。每一本都可以被问答引用；是否可读，取决于空间对你开放的层级。"
      >
        <template #actions>
          <UiButton v-if="canCreate" variant="primary" @click="openCreate">新建空间</UiButton>
        </template>
        <template #meta>
          <span>共 {{ total }} 个空间</span>
          <span v-if="activeCategory !== null">已按分类筛选</span>
        </template>
      </UiPageHeader>

      <form class="search" role="search" @submit.prevent="search">
        <label class="search__label" for="kb-search">检索</label>
        <input
          id="kb-search"
          v-model="keyword"
          class="search__input"
          type="search"
          placeholder="按名称或说明查找空间"
          autocomplete="off"
        />
        <UiButton size="sm" type="submit">查找</UiButton>
        <UiButton v-if="keyword" size="sm" variant="link" @click="keyword = ''; search()">清除</UiButton>
      </form>

      <p v-if="loading" class="state">正在取书…</p>

      <div v-else-if="loadError" class="state state--err" role="alert">
        <p>{{ loadError }}</p>
        <UiButton size="sm" @click="loadSpaces">重试</UiButton>
      </div>

      <UiEmpty
        v-else-if="spaces.length === 0"
        :title="keyword ? '没有找到匹配的空间' : '书架还是空的'"
        :hint="
          keyword
            ? '换个词试试，或清除筛选看全部。'
            : '管理员可以新建空间，随后在其中上传或撰写 Markdown 文档。'
        "
      >
        <UiButton v-if="keyword" size="sm" @click="keyword = ''; search()">看全部</UiButton>
        <UiButton v-else-if="canCreate" size="sm" variant="primary" @click="openCreate">新建空间</UiButton>
      </UiEmpty>

      <ul v-else class="spaces">
        <li v-for="s in sortedSpaces" :key="s.id">
          <RouterLink class="space" :to="{ name: 'space', params: { id: s.id } }">
            <p class="space__cat">{{ s.categoryName || '未分类' }}</p>
            <h2 class="space__name">{{ s.name }}</h2>
            <p class="space__desc">{{ s.description || '暂无说明。' }}</p>
            <p class="space__foot">
              <UiTag :tone="s.visibility === 'PUBLIC' ? 'teal' : 'neutral'">
                {{ VISIBILITY_LABEL[s.visibility] ?? s.visibility }}
              </UiTag>
              <span class="space__access">{{ ACCESS_LABEL[s.accessLevel] ?? s.accessLevel }}</span>
              <span class="space__members mono">{{ s.memberCount }} 位成员</span>
            </p>
            <p class="space__time">更新于 {{ formatDateTime(s.updatedAt) }}</p>
          </RouterLink>
        </li>
      </ul>

      <UiPager v-if="pages > 1" :page="page" :pages="pages" :total="total" @change="page = $event" />
    </section>

    <!-- 分类管理 -->
    <CategoryDialog
      :open="categoryDialog"
      @close="categoryDialog = false"
      @changed="loadCategories(); loadSpaces()"
    />

    <!-- 新建空间 -->
    <UiDialog
      :open="createOpen"
      title="新建空间"
      eyebrow="知识库"
      width="sm"
      :dismissible="!createBusy"
      @close="createOpen = false"
    >
      <div class="form">
        <UiField v-model="form.name" label="名称" :error="formError.name" :maxlength="64" required />
        <UiField v-model="form.description" label="说明" hint="一句话说明这里放什么，会显示在书脊下方。" :maxlength="200" />
        <UiSelect v-model="form.categoryId" label="分类" :options="categoryOptions" />
        <UiSelect
          v-model="form.visibility"
          label="可见范围"
          :options="visibilityOptions"
          hint="创建后仍可在空间设置中调整（需管理权限）。"
        />
        <p v-if="formError.general" class="form__err" role="alert">{{ formError.general }}</p>
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="createBusy" @click="createOpen = false">取消</UiButton>
        <UiButton variant="primary" :loading="createBusy" @click="submitCreate">创建</UiButton>
      </template>
    </UiDialog>
  </div>
</template>

<style scoped>
.library {
  --rail-w: 208px;
  display: grid;
  grid-template-columns: var(--rail-w) minmax(0, 1fr);
  align-items: start;
  width: 100%;
  max-width: 1240px;
  margin: 0 auto;
  padding: var(--s-5) clamp(var(--s-4), 4vw, var(--s-6)) var(--s-7);
  gap: clamp(var(--s-4), 3vw, var(--s-6));
}

/* ---------- 目录 ---------- */
.rail {
  position: sticky;
  top: calc(var(--masthead-h) + var(--s-4));
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
  gap: var(--s-2);
  width: 100%;
  min-height: 34px;
  padding: 0 var(--s-2);
  border: 0;
  border-left: 2px solid transparent;
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
  border-left-color: var(--teal);
  color: var(--ink);
  font-weight: 600;
}

.rail__item.is-child {
  padding-left: var(--s-4);
  font-size: 14px;
}

.rail__hint {
  margin: var(--s-3) 0 0;
  padding: 0 var(--s-2);
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.7;
}

.rail__manage {
  margin: var(--s-3) 0 0 var(--s-2);
}

/* ---------- 书架 ---------- */
.body {
  min-width: 0;
}

.search {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  padding-bottom: var(--s-3);
  margin-bottom: var(--s-5);
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

/* 书脊：左侧一道竖线，hover 时线变深青 */
.spaces {
  display: flex;
  flex-wrap: wrap;
  gap: 0;
  margin: 0;
  padding: 0;
  list-style: none;
}

/* 每格至少 300px，少的时候自然拉满一行——分隔线不会半途断掉 */
.spaces > li {
  flex: 1 1 300px;
  min-width: 0;
  border-bottom: 1px solid var(--rule);
}

.space {
  display: block;
  height: 100%;
  padding: var(--s-4) var(--s-4) var(--s-4) var(--s-3);
  border-left: 2px solid transparent;
  color: inherit;
  text-decoration: none;
  transition:
    border-color var(--t-mid) var(--ease),
    background-color var(--t-mid) var(--ease);
}

.space:hover {
  border-left-color: var(--teal);
  background: color-mix(in srgb, var(--paper-lift) 70%, transparent);
}

.space__cat {
  margin: 0;
  color: var(--ink-2);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.12em;
}

.space__name {
  margin: var(--s-1) 0 var(--s-2);
  font-family: var(--font-serif-cjk);
  font-size: 21px;
  line-height: 1.25;
}

.space:hover .space__name {
  color: var(--teal);
}

.space__desc {
  display: -webkit-box;
  min-height: 3em;
  margin: 0 0 var(--s-3);
  overflow: hidden;
  color: var(--ink-2);
  font-size: var(--fs-ui);
  line-height: 1.6;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.space__foot {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  margin: 0;
  font-size: var(--fs-small);
  color: var(--ink-2);
}

.space__access {
  color: var(--teal);
}

.space__members {
  margin-left: auto;
  font-size: 11px;
  color: var(--ink-2);
}

.space__time {
  margin: var(--s-1) 0 0;
  color: var(--ink-2);
  font-size: 11px;
  opacity: 0.8;
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

@media (max-width: 900px) {
  .library {
    grid-template-columns: minmax(0, 1fr);
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
    border-left: 0;
    border-bottom: 2px solid transparent;
  }

  .rail__item.is-active {
    border-left: 0;
    border-bottom-color: var(--teal);
  }

  .rail__item.is-child {
    padding-left: var(--s-2);
  }
}
</style>
