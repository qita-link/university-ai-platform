<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { knowledgeApi } from '@/api/endpoints'
import type { KnowledgeCategory } from '@/api/types'
import { ApiError } from '@/api/http'
import { useToastStore } from '@/stores/toast'
import { errorMessage, traceIdOf } from '@/lib/errors'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiField from '@/components/ui/UiField.vue'
import UiSelect from '@/components/ui/UiSelect.vue'

/**
 * 分类管理：最多两级。有子分类时不能移动，有空间时不能删除——这两个约束都在后端，
 * 前端只负责把原因说清楚。
 */
const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: []; changed: [] }>()

const toast = useToastStore()

const categories = ref<KnowledgeCategory[]>([])
const loading = ref(false)
const loadError = ref<string | null>(null)

const mode = ref<'list' | 'form'>('list')
const editing = ref<KnowledgeCategory | null>(null)
const busy = ref(false)
const form = ref({ name: '', description: '', parentId: '', sortOrder: '0' })
const formError = ref<Record<string, string | null>>({})

const flat = computed(() => {
  const out: { node: KnowledgeCategory; depth: number }[] = []
  for (const c of categories.value) {
    out.push({ node: c, depth: 0 })
    for (const ch of c.children ?? []) out.push({ node: ch, depth: 1 })
  }
  return out
})

const parentOptions = computed(() => [
  { value: '', label: '顶级分类' },
  ...categories.value
    .filter((c) => c.id !== editing.value?.id)
    .map((c) => ({ value: String(c.id), label: c.name })),
])

async function load() {
  loading.value = true
  loadError.value = null
  try {
    categories.value = await knowledgeApi.categories()
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

function openCreate(parent?: KnowledgeCategory) {
  editing.value = null
  form.value = { name: '', description: '', parentId: parent ? String(parent.id) : '', sortOrder: '0' }
  formError.value = {}
  mode.value = 'form'
}

function openEdit(node: KnowledgeCategory) {
  editing.value = node
  form.value = {
    name: node.name,
    description: node.description ?? '',
    parentId: node.parentId ? String(node.parentId) : '',
    sortOrder: String(node.sortOrder),
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
    parentId: form.value.parentId ? Number(form.value.parentId) : null,
    name: form.value.name.trim(),
    description: form.value.description.trim() || null,
    sortOrder: Number(form.value.sortOrder) || 0,
  }
  try {
    if (editing.value) await knowledgeApi.updateCategory(editing.value.id, body)
    else await knowledgeApi.createCategory(body)
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

const removingId = ref<number | null>(null)

async function remove(node: KnowledgeCategory) {
  removingId.value = node.id
  try {
    await knowledgeApi.deleteCategory(node.id)
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
  <UiDialog :open="open" title="分类管理" eyebrow="知识库" width="md" :dismissible="!busy" @close="emit('close')">
    <p v-if="loading" class="muted">正在取分类…</p>

    <div v-else-if="loadError" class="muted" role="alert">
      {{ loadError }}
      <UiButton size="sm" variant="link" @click="load">重试</UiButton>
    </div>

    <template v-else>
      <!-- 列表 -->
      <template v-if="mode === 'list'">
        <p class="note">分类最多两级。分类下有空间时不能删除，需要先把空间移到别处。</p>

        <ul v-if="flat.length" class="rows">
          <li v-for="f in flat" :key="f.node.id" class="row">
            <span class="row__indent" :style="{ width: `${f.depth * 16}px` }" aria-hidden="true" />
            <div class="row__main">
              <p class="row__name">{{ f.node.name }}</p>
              <p v-if="f.node.description" class="row__desc">{{ f.node.description }}</p>
            </div>
            <div class="row__actions">
              <UiButton v-if="f.depth === 0" size="sm" variant="link" @click="openCreate(f.node)">加子类</UiButton>
              <UiButton size="sm" variant="link" @click="openEdit(f.node)">编辑</UiButton>
              <UiButton size="sm" variant="link" :loading="removingId === f.node.id" @click="remove(f.node)">
                删除
              </UiButton>
            </div>
          </li>
        </ul>
        <p v-else class="muted">还没有分类，新建一个再往书架里放空间。</p>

        <div class="foot">
          <UiButton variant="primary" @click="openCreate()">新建分类</UiButton>
        </div>
      </template>

      <!-- 表单 -->
      <div v-else class="form">
        <UiSelect v-model="form.parentId" label="上级分类" :options="parentOptions" hint="改上级即移动整棵子树；有子分类时后端会拒绝移动。" />
        <UiField v-model="form.name" label="名称" :error="formError.name" :maxlength="64" required />
        <UiField v-model="form.description" label="说明" :maxlength="200" hint="会显示在书架左栏上方，可留空。" />
        <UiField v-model="form.sortOrder" label="排序" inputmode="numeric" hint="越小越靠前。" />
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
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--s-2);
  padding: var(--s-2) 0;
  border-bottom: var(--hairline);
}

.row__indent {
  display: inline-block;
}

.row__name {
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-size: 15px;
}

.row__desc {
  margin: 0;
  color: var(--ink-2);
  font-size: 11px;
}

.row__actions {
  display: flex;
  gap: var(--s-3);
}

.foot {
  display: flex;
  justify-content: flex-start;
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
</style>
