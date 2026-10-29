<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { semesterApi } from '@/api/endpoints'
import type { SemesterView } from '@/api/types'
import { ApiError } from '@/api/http'
import { useToastStore } from '@/stores/toast'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { SEMESTER_STATUS_LABEL } from '@/lib/labels'
import { formatDateTime } from '@/lib/time'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiEmpty from '@/components/ui/UiEmpty.vue'
import UiField from '@/components/ui/UiField.vue'
import UiTag from '@/components/ui/UiTag.vue'

/**
 * 学期管理：课程的容器。只有 ACTIVE 学期允许选课，
 * 且选课窗口另有一套起止时间——学期没结束，窗口也可以关着。
 */
const toast = useToastStore()

const semesters = ref<SemesterView[]>([])
const loading = ref(true)
const loadError = ref<string | null>(null)

async function load() {
  loading.value = true
  loadError.value = null
  try {
    semesters.value = await semesterApi.list()
  } catch (e) {
    semesters.value = []
    loadError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

/* ---------------- 新建 / 编辑 ---------------- */
const open = ref(false)
const busy = ref(false)
const editing = ref<SemesterView | null>(null)
const form = ref({ code: '', name: '', startDate: '', endDate: '', selectionStartAt: '', selectionEndAt: '' })
const formError = ref<Record<string, string | null>>({})

function toLocalInput(v: string | null) {
  // 后端返回 2026-10-01T00:00:00，datetime-local 只认前 16 位
  return v ? v.slice(0, 16) : ''
}

function openCreate() {
  editing.value = null
  form.value = { code: '', name: '', startDate: '', endDate: '', selectionStartAt: '', selectionEndAt: '' }
  formError.value = {}
  open.value = true
}

function openEdit(s: SemesterView) {
  editing.value = s
  form.value = {
    code: s.code,
    name: s.name,
    startDate: s.startDate,
    endDate: s.endDate,
    selectionStartAt: toLocalInput(s.selectionStartAt),
    selectionEndAt: toLocalInput(s.selectionEndAt),
  }
  formError.value = {}
  open.value = true
}

async function submit() {
  formError.value = {}
  if (!form.value.code.trim()) formError.value.code = '请填写学期编码'
  if (!form.value.name.trim()) formError.value.name = '请填写学期名称'
  if (!form.value.startDate || !form.value.endDate) formError.value.startDate = '请选择学期起止日期'
  if (!form.value.selectionStartAt || !form.value.selectionEndAt) formError.value.selectionStartAt = '请选择选课窗口'
  if (form.value.startDate && form.value.endDate && form.value.endDate < form.value.startDate) {
    formError.value.endDate = '结束日期不能早于开始日期'
  }
  if (Object.keys(formError.value).length) return

  busy.value = true
  const body = {
    code: form.value.code.trim(),
    name: form.value.name.trim(),
    startDate: form.value.startDate,
    endDate: form.value.endDate,
    selectionStartAt: form.value.selectionStartAt,
    selectionEndAt: form.value.selectionEndAt,
  }
  try {
    if (editing.value) await semesterApi.update(editing.value.id, body)
    else await semesterApi.create(body)
    open.value = false
    toast.push(editing.value ? '学期已更新' : '学期已创建（默认未启用）', 'success')
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

/* ---------------- 状态 ---------------- */
const statusBusy = ref<number | null>(null)

async function setStatus(s: SemesterView, status: 'ACTIVE' | 'CLOSED') {
  statusBusy.value = s.id
  try {
    await semesterApi.setStatus(s.id, status)
    toast.push(status === 'ACTIVE' ? '学期已启用' : '学期已归档', 'success')
    await load()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    statusBusy.value = null
  }
}

function windowText(s: SemesterView) {
  if (!s.selectionStartAt || !s.selectionEndAt) return '未设置'
  return `${formatDateTime(s.selectionStartAt)} — ${formatDateTime(s.selectionEndAt)}`
}
</script>

<template>
  <section class="panel">
    <div class="toolbar">
      <p class="toolbar__note">
        只有「进行中」的学期允许选课，且必须落在选课窗口内；归档（CLOSED）是终态，不能再修改。
      </p>
      <UiButton variant="primary" @click="openCreate">新建学期</UiButton>
    </div>

    <p v-if="loading" class="state">正在取学期…</p>

    <div v-else-if="loadError" class="state state--err" role="alert">
      <p>{{ loadError }}</p>
      <UiButton size="sm" @click="load">重试</UiButton>
    </div>

    <UiEmpty
      v-else-if="semesters.length === 0"
      title="还没有学期"
      hint="先建一个学期并设为进行中，之后才能创建课程、开放选课。"
    >
      <UiButton size="sm" variant="primary" @click="openCreate">新建学期</UiButton>
    </UiEmpty>

    <ol v-else class="list">
      <li v-for="s in semesters" :key="s.id" class="item">
        <div class="item__main">
          <p class="item__name">
            {{ s.name }}
            <span class="item__code mono">{{ s.code }}</span>
            <UiTag :tone="s.status === 'ACTIVE' ? 'success' : s.status === 'CLOSED' ? 'muted' : 'neutral'">
              {{ SEMESTER_STATUS_LABEL[s.status] ?? s.status }}
            </UiTag>
            <span v-if="s.selectionOpen" class="item__open">选课中</span>
          </p>
          <p class="item__meta">
            <span class="mono">学期 {{ s.startDate }} — {{ s.endDate }}</span>
            <span class="mono">窗口 {{ windowText(s) }}</span>
            <span class="mono">{{ s.courseCount }} 门课程</span>
          </p>
        </div>

        <div class="item__actions">
          <UiButton v-if="s.status !== 'CLOSED'" size="sm" variant="link" @click="openEdit(s)">编辑</UiButton>
          <UiButton
            v-if="s.status === 'DRAFT'"
            size="sm"
            variant="link"
            :loading="statusBusy === s.id"
            @click="setStatus(s, 'ACTIVE')"
          >
            启用
          </UiButton>
          <UiButton
            v-if="s.status !== 'CLOSED'"
            size="sm"
            variant="link"
            :loading="statusBusy === s.id"
            @click="setStatus(s, 'CLOSED')"
          >
            归档
          </UiButton>
          <span v-if="s.status === 'CLOSED'" class="item__locked">已归档，不可修改</span>
        </div>
      </li>
    </ol>

    <UiDialog
      :open="open"
      :title="editing ? '编辑学期' : '新建学期'"
      eyebrow="学期"
      width="md"
      :dismissible="!busy"
      @close="open = false"
    >
      <div class="form">
        <UiField v-model="form.code" label="编码" :error="formError.code" hint="如 2026-2027-1，学期内唯一。" />
        <UiField v-model="form.name" label="名称" :error="formError.name" hint="如 2026-2027 学年第一学期。" />
        <UiField v-model="form.startDate" label="开学日期" type="date" :error="formError.startDate" required />
        <UiField v-model="form.endDate" label="结束日期" type="date" :error="formError.endDate" required />
        <UiField
          v-model="form.selectionStartAt"
          label="选课开始"
          type="datetime-local"
          :error="formError.selectionStartAt"
          required
        />
        <UiField v-model="form.selectionEndAt" label="选课结束" type="datetime-local" required />
        <p class="hint">新建后默认「未启用」，确认无误再点启用；启用后学生即可在窗口内选课。</p>
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
  max-width: 48em;
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.7;
}

.list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--s-4);
  padding: var(--s-3) 0;
  border-bottom: var(--hairline);
}

.item__name {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--s-2);
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-size: 17px;
  font-weight: 600;
}

.item__code {
  color: var(--ink-2);
  font-size: 11px;
  font-weight: 400;
}

.item__open {
  color: var(--success);
  font-size: 11px;
  letter-spacing: 0.06em;
}

.item__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-3);
  margin: var(--s-1) 0 0;
  color: var(--ink-2);
  font-size: 11px;
}

.item__actions {
  display: flex;
  align-items: center;
  gap: var(--s-3);
}

.item__locked {
  color: var(--ink-2);
  font-size: 11px;
}

.form {
  display: grid;
  gap: var(--s-4);
}

.hint {
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.7;
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
  .item {
    grid-template-columns: minmax(0, 1fr);
    gap: var(--s-2);
  }

  .item__actions {
    justify-content: flex-start;
  }
}
</style>
