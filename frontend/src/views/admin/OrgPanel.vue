<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { orgApi } from '@/api/endpoints'
import type { OrgTreeNode } from '@/api/types'
import { ApiError } from '@/api/http'
import { useToastStore } from '@/stores/toast'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { ORG_TYPE_LABEL } from '@/lib/labels'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiEmpty from '@/components/ui/UiEmpty.vue'
import UiField from '@/components/ui/UiField.vue'
import UiSelect from '@/components/ui/UiSelect.vue'
import UiTag from '@/components/ui/UiTag.vue'

/**
 * 组织树：学院 → 系 → 专业 → 班级。
 * 用扁平化渲染 + 缩进，而不是嵌套组件——层级固定四层，树形 UI 不需要无限递归。
 */
const toast = useToastStore()

interface FlatNode {
  node: OrgTreeNode
  depth: number
}

const tree = ref<OrgTreeNode[]>([])
const flat = ref<FlatNode[]>([])
const loading = ref(true)
const loadError = ref<string | null>(null)
const showDisabled = ref(true)

function walk(nodes: OrgTreeNode[], depth = 0, out: FlatNode[] = []) {
  for (const n of nodes) {
    out.push({ node: n, depth })
    if (n.children?.length) walk(n.children, depth + 1, out)
  }
  return out
}

async function load() {
  loading.value = true
  loadError.value = null
  try {
    tree.value = await orgApi.tree(showDisabled.value)
    flat.value = walk(tree.value)
  } catch (e) {
    tree.value = []
    flat.value = []
    loadError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

const typeOptions = [
  { value: 'COLLEGE', label: '学院' },
  { value: 'DEPARTMENT', label: '系' },
  { value: 'MAJOR', label: '专业' },
  { value: 'CLASS', label: '班级' },
]

const parentOptions = computed(() => [
  { value: '', label: '顶级（无上级）' },
  ...flat.value
    .filter((f) => f.node.id !== editing.value?.id)
    .map((f) => ({ value: String(f.node.id), label: '　'.repeat(f.depth) + f.node.name })),
])

/* ---------------- 新建 / 编辑 ---------------- */
const open = ref(false)
const busy = ref(false)
const editing = ref<OrgTreeNode | null>(null)
const form = ref({ parentId: '', code: '', name: '', type: 'COLLEGE', sortOrder: '0' })
const formError = ref<Record<string, string | null>>({})

function openCreate(parent?: OrgTreeNode) {
  editing.value = null
  form.value = {
    parentId: parent ? String(parent.id) : '',
    code: '',
    name: '',
    type: parent ? nextType(parent.type) : 'COLLEGE',
    sortOrder: '0',
  }
  formError.value = {}
  open.value = true
}

function nextType(t: string) {
  return t === 'COLLEGE' ? 'DEPARTMENT' : t === 'DEPARTMENT' ? 'MAJOR' : 'CLASS'
}

function openEdit(n: OrgTreeNode) {
  editing.value = n
  form.value = {
    parentId: n.parentId ? String(n.parentId) : '',
    code: n.code,
    name: n.name,
    type: n.type,
    sortOrder: String(n.sortOrder),
  }
  formError.value = {}
  open.value = true
}

async function submit() {
  formError.value = {}
  if (!form.value.code.trim()) formError.value.code = '请填写编码'
  if (!form.value.name.trim()) formError.value.name = '请填写名称'
  if (Object.keys(formError.value).length) return

  busy.value = true
  const body = {
    parentId: form.value.parentId ? Number(form.value.parentId) : null,
    code: form.value.code.trim(),
    name: form.value.name.trim(),
    type: form.value.type,
    sortOrder: Number(form.value.sortOrder) || 0,
  }
  try {
    if (editing.value) await orgApi.update(editing.value.id, body)
    else await orgApi.create(body)
    open.value = false
    toast.push(editing.value ? '组织已更新' : '组织已创建', 'success')
    await load()
  } catch (e) {
    const apiErr = e instanceof ApiError ? e : null
    formError.value = {
      code: apiErr?.fieldError('code') ?? null,
      name: apiErr?.fieldError('name') ?? null,
      general: errorMessage(e),
    }
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    busy.value = false
  }
}

/* ---------------- 启停 ---------------- */
const togglingId = ref<number | null>(null)

async function toggle(n: OrgTreeNode) {
  togglingId.value = n.id
  try {
    await orgApi.setStatus(n.id, n.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE')
    toast.push(n.status === 'ACTIVE' ? '已停用' : '已启用', 'success')
    await load()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    togglingId.value = null
  }
}
</script>

<template>
  <section class="panel">
    <div class="toolbar">
      <p class="toolbar__note">
        编码全局唯一，层级类型必须逐级下降（学院 → 系 → 专业 → 班级）。停用前需先停用全部下级。
      </p>
      <div class="toolbar__right">
        <label class="switch">
          <input v-model="showDisabled" type="checkbox" @change="load" />
          <span>显示已停用</span>
        </label>
        <UiButton variant="primary" @click="openCreate()">新建学院</UiButton>
      </div>
    </div>

    <p v-if="loading" class="state">正在取组织…</p>

    <div v-else-if="loadError" class="state state--err" role="alert">
      <p>{{ loadError }}</p>
      <UiButton size="sm" @click="load">重试</UiButton>
    </div>

    <UiEmpty
      v-else-if="flat.length === 0"
      title="还没有组织结构"
      hint="先建一个学院作为根节点，再逐级向下添加系、专业与班级。关掉「显示已停用」也会看到这个空态。"
    >
      <UiButton size="sm" variant="primary" @click="openCreate()">新建学院</UiButton>
    </UiEmpty>

    <ol v-else class="tree">
      <li v-for="f in flat" :key="f.node.id" class="node" :class="{ 'is-off': f.node.status === 'DISABLED' }">
        <span class="node__indent" :style="{ width: `${f.depth * 20}px` }" aria-hidden="true">{{ f.depth ? '└' : '' }}</span>
        <div class="node__main">
          <p class="node__name">
            {{ f.node.name }}
            <span class="node__code mono">{{ f.node.code }}</span>
            <UiTag :tone="f.node.status === 'ACTIVE' ? 'neutral' : 'muted'">
              {{ ORG_TYPE_LABEL[f.node.type] ?? f.node.type }}
            </UiTag>
            <span v-if="f.node.status === 'DISABLED'" class="node__off">已停用</span>
          </p>
        </div>
        <div class="node__actions">
          <UiButton v-if="f.node.type !== 'CLASS'" size="sm" variant="link" @click="openCreate(f.node)">
            加下级
          </UiButton>
          <UiButton size="sm" variant="link" @click="openEdit(f.node)">编辑</UiButton>
          <UiButton size="sm" variant="link" :loading="togglingId === f.node.id" @click="toggle(f.node)">
            {{ f.node.status === 'ACTIVE' ? '停用' : '启用' }}
          </UiButton>
        </div>
      </li>
    </ol>

    <UiDialog
      :open="open"
      :title="editing ? '编辑组织' : '新建组织'"
      eyebrow="组织树"
      width="md"
      :dismissible="!busy"
      @close="open = false"
    >
      <div class="form">
        <UiSelect v-model="form.parentId" label="上级组织" :options="parentOptions" hint="修改上级即移动整棵子树；不能移动到自身或下级。" />
        <UiField v-model="form.code" label="编码" :error="formError.code" hint="全局唯一，如 CS / SE / 2026-01。" />
        <UiField v-model="form.name" label="名称" :error="formError.name" />
        <UiSelect v-model="form.type" label="类型" :options="typeOptions" />
        <UiField v-model="form.sortOrder" label="排序" inputmode="numeric" hint="同级排序，越小越靠前。" />
        <p v-if="formError.general" class="form__err" role="alert">{{ formError.general }}</p>
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="busy" @click="open = false">取消</UiButton>
        <UiButton variant="primary" :loading="busy" @click="submit">{{ editing ? '保存' : '创建' }}</UiButton>
      </template>
    </UiDialog>
  </section>
</template>

<style scoped>
.panel {
  min-height: 240px;
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  padding-bottom: var(--s-3);
  margin-bottom: var(--s-3);
  border-bottom: var(--hairline);
}

.toolbar__note {
  max-width: 46em;
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.7;
}

.toolbar__right {
  display: flex;
  align-items: center;
  gap: var(--s-4);
}

.switch {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  color: var(--ink-2);
  font-size: var(--fs-small);
  cursor: pointer;
}

.switch input {
  width: 14px;
  height: 14px;
  accent-color: var(--teal);
}

.tree {
  margin: 0;
  padding: 0;
  list-style: none;
}

.node {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--s-2);
  padding: var(--s-2) 0;
  border-bottom: var(--hairline);
}

.node.is-off {
  opacity: 0.6;
}

.node__indent {
  display: inline-block;
  color: var(--rule-strong);
  font-family: var(--font-mono);
  font-size: 12px;
  text-align: right;
}

.node__main {
  min-width: 0;
}

.node__name {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--s-2);
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-size: 16px;
}

.node__code {
  color: var(--ink-2);
  font-size: 11px;
}

.node__off {
  color: var(--ochre);
  font-size: 11px;
}

.node__actions {
  display: flex;
  gap: var(--s-3);
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

@media (max-width: 720px) {
  .node {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .node__actions {
    grid-column: 2;
    justify-content: flex-start;
  }
}
</style>
