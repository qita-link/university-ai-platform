<script setup lang="ts">
import { ref, watch } from 'vue'
import { forumApi } from '@/api/endpoints'
import type { ForumCategory } from '@/api/types'
import { ApiError } from '@/api/http'
import { useToastStore } from '@/stores/toast'
import { errorMessage, traceIdOf } from '@/lib/errors'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiField from '@/components/ui/UiField.vue'
import UiTag from '@/components/ui/UiTag.vue'

/**
 * 论坛分类管理（版主）。停用的分类对普通用户隐藏，其下帖子也不再出现在列表里。
 * 分类下只要有任何帖子（含已删除）就不能删除——这是后端的约束，直接照实提示。
 */
const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: []; changed: [] }>()

const toast = useToastStore()

const categories = ref<ForumCategory[]>([])
const loading = ref(false)
const loadError = ref<string | null>(null)

const mode = ref<'list' | 'form'>('list')
const editing = ref<ForumCategory | null>(null)
const busy = ref(false)
const form = ref({ name: '', description: '', sortOrder: '0', status: 'ACTIVE', announcement: false })
const formError = ref<Record<string, string | null>>({})

async function load() {
  loading.value = true
  loadError.value = null
  try {
    categories.value = await forumApi.categories(true)
  } catch (e) {
    loadError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

watch(
  () => props.open,
  (v) => {
    if (!v) return
    mode.value = 'list'
    void load()
  },
  { immediate: true },
)

function openCreate() {
  editing.value = null
  form.value = { name: '', description: '', sortOrder: '0', status: 'ACTIVE', announcement: false }
  formError.value = {}
  mode.value = 'form'
}

function openEdit(c: ForumCategory) {
  editing.value = c
  form.value = {
    name: c.name,
    description: c.description ?? '',
    sortOrder: String(c.sortOrder),
    status: c.status,
    announcement: c.announcement,
  }
  formError.value = {}
  mode.value = 'form'
}

async function submit() {
  formError.value = {}
  if (!form.value.name.trim()) {
    formError.value.name = '请填写分类名称'
    return
  }
  busy.value = true
  const body = {
    name: form.value.name.trim(),
    description: form.value.description.trim() || null,
    sortOrder: Number(form.value.sortOrder) || 0,
    status: form.value.status,
    announcement: form.value.announcement,
  }
  try {
    if (editing.value) await forumApi.updateCategory(editing.value.id, body)
    else await forumApi.createCategory(body)
    toast.push(editing.value ? '分类已更新' : '分类已创建', 'success')
    mode.value = 'list'
    await load()
    emit('changed')
  } catch (e) {
    formError.value = {
      name: e instanceof ApiError ? (e.fieldError('name') ?? null) : null,
      general: errorMessage(e),
    }
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    busy.value = false
  }
}

const togglingId = ref<number | null>(null)

async function toggle(c: ForumCategory) {
  togglingId.value = c.id
  try {
    await forumApi.updateCategory(c.id, {
      name: c.name,
      description: c.description,
      sortOrder: c.sortOrder,
      status: c.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE',
      announcement: c.announcement,
    })
    toast.push(c.status === 'ACTIVE' ? '已停用，普通用户不再看到' : '已启用', 'success')
    await load()
    emit('changed')
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    togglingId.value = null
  }
}

const removingId = ref<number | null>(null)

async function remove(c: ForumCategory) {
  removingId.value = c.id
  try {
    await forumApi.deleteCategory(c.id)
    toast.push('分类已删除', 'success')
    await load()
    emit('changed')
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    removingId.value = null
  }
}
</script>

<template>
  <UiDialog :open="open" title="分类管理" eyebrow="论坛" width="md" :dismissible="!busy" @close="emit('close')">
    <p v-if="loading" class="muted">正在取分类…</p>

    <div v-else-if="loadError" class="muted" role="alert">
      {{ loadError }}
      <UiButton size="sm" variant="link" @click="load">重试</UiButton>
    </div>

    <template v-else>
      <template v-if="mode === 'list'">
        <p class="note">停用的分类对普通用户隐藏。分类下只要还有帖子（含已删除）就不能删除。</p>

        <ul v-if="categories.length" class="rows">
          <li v-for="c in categories" :key="c.id" class="row">
            <div class="row__main">
              <p class="row__name">
                {{ c.name }}
                <UiTag :tone="c.status === 'ACTIVE' ? 'neutral' : 'muted'">
                  {{ c.status === 'ACTIVE' ? '启用' : '已停用' }}
                </UiTag>
                <UiTag v-if="c.announcement" tone="ochre">公告</UiTag>
              </p>
              <p class="row__meta">
                <span v-if="c.description">{{ c.description }}</span>
                <span class="mono">{{ c.postCount }} 帖</span>
                <span class="mono">排序 {{ c.sortOrder }}</span>
              </p>
            </div>
            <div class="row__actions">
              <UiButton size="sm" variant="link" @click="openEdit(c)">编辑</UiButton>
              <UiButton size="sm" variant="link" :loading="togglingId === c.id" @click="toggle(c)">
                {{ c.status === 'ACTIVE' ? '停用' : '启用' }}
              </UiButton>
              <UiButton size="sm" variant="link" :loading="removingId === c.id" @click="remove(c)">删除</UiButton>
            </div>
          </li>
        </ul>
        <p v-else class="muted">还没有分类，先建一个学生才能发帖。</p>

        <div class="foot">
          <UiButton variant="primary" @click="openCreate">新建分类</UiButton>
        </div>
      </template>

      <div v-else class="form">
        <UiField v-model="form.name" label="名称" :error="formError.name" :maxlength="64" required />
        <UiField v-model="form.description" label="说明" :maxlength="500" hint="显示在列表页的分类旁，可留空。" />
        <UiField v-model="form.sortOrder" label="排序" inputmode="numeric" hint="越小越靠前。" />
        <label class="check">
          <input v-model="form.announcement" type="checkbox" />
          <span>
            作为公告分类
            <em>只有持「发布官方公告」权限的账号能在这里发帖；每发一条都会给全校发站内信并弹窗。</em>
          </span>
        </label>
        <p v-if="formError.general" class="err" role="alert">{{ formError.general }}</p>
      </div>
    </template>

    <template #footer>
      <template v-if="mode === 'form'">
        <UiButton variant="link" :disabled="busy" @click="mode = 'list'">返回</UiButton>
        <UiButton variant="primary" :loading="busy" @click="submit">{{ editing ? '保存' : '创建' }}</UiButton>
      </template>
      <UiButton v-else variant="link" @click="emit('close')">关闭</UiButton>
    </template>
  </UiDialog>
</template>

<style scoped>
.note,
.muted {
  margin: 0 0 var(--s-3);
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.7;
}

.rows {
  margin: 0;
  padding: 0;
  list-style: none;
  border-top: var(--hairline);
}

.row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--s-3);
  padding: var(--s-2) 0;
  border-bottom: var(--hairline);
}

.row__name {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-size: 15px;
}

.row__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-3);
  margin: 2px 0 0;
  color: var(--ink-2);
  font-size: 11px;
}

.row__actions {
  display: flex;
  gap: var(--s-3);
}

.foot {
  margin-top: var(--s-4);
}

.form {
  display: grid;
  gap: var(--s-4);
}

.err {
  margin: 0;
  color: var(--ochre);
  font-size: var(--fs-small);
}

.check {
  display: flex;
  align-items: flex-start;
  gap: var(--s-2);
  font-size: var(--fs-small);
  line-height: 1.7;
  cursor: pointer;
}

.check input {
  margin-top: 4px;
  accent-color: var(--teal);
}

.check em {
  display: block;
  margin-top: 2px;
  color: var(--ink-2);
  font-style: normal;
  font-size: 11px;
}
</style>
