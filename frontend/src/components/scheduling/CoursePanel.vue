<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { schedulingApi } from '@/api/endpoints'
import type { SchedulingCourse } from '@/api/types'
import { ApiError } from '@/api/http'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { useToastStore } from '@/stores/toast'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiEmpty from '@/components/ui/UiEmpty.vue'
import UiField from '@/components/ui/UiField.vue'
import UiSelect from '@/components/ui/UiSelect.vue'
import UiTag from '@/components/ui/UiTag.vue'
import UiTextarea from '@/components/ui/UiTextarea.vue'

/**
 * 课程库与学期无关：同一门课在多个学期由教学任务引用。
 * 停用而不是删除，否则会破坏历史教学任务与已发布课表。
 */
const props = defineProps<{ canManage: boolean }>()

const toast = useToastStore()

const ROOM_TYPES = [
  { value: '', label: '不限定' },
  { value: 'NORMAL', label: '普通教室' },
  { value: 'MULTIMEDIA', label: '多媒体教室' },
  { value: 'COMPUTER_LAB', label: '计算机机房' },
  { value: 'LANGUAGE_LAB', label: '语音教室' },
  { value: 'LAB', label: '实验室' },
  { value: 'GYM', label: '体育场馆' },
  { value: 'OTHER', label: '其他' },
]

const COURSE_TYPES = [
  { value: 'THEORY', label: '理论课' },
  { value: 'LAB', label: '实验课' },
  { value: 'PRACTICE', label: '实践课' },
  { value: 'MIXED', label: '理论+实践' },
]

const courses = ref<SchedulingCourse[]>([])
const keyword = ref('')
const statusFilter = ref('')
const loading = ref(true)
const loadError = ref<string | null>(null)

const statusOptions = [
  { value: '', label: '全部状态' },
  { value: 'ACTIVE', label: '启用' },
  { value: 'DISABLED', label: '停用' },
]

async function load() {
  loading.value = true
  loadError.value = null
  try {
    courses.value = await schedulingApi.courses({
      keyword: keyword.value.trim() || undefined,
      status: statusFilter.value || undefined,
    })
  } catch (e) {
    loadError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

function courseTypeLabel(v: string) {
  return COURSE_TYPES.find((t) => t.value === v)?.label ?? v
}

function roomTypeLabel(v: string | null) {
  if (!v) return '不限定'
  return ROOM_TYPES.find((t) => t.value === v)?.label ?? v
}

/* ---------------- 新建 / 编辑 ---------------- */
const open = ref(false)
const busy = ref(false)
const editing = ref<SchedulingCourse | null>(null)
const form = ref({
  code: '',
  name: '',
  courseType: 'THEORY',
  credit: '2',
  totalHours: '32',
  defaultRoomType: '',
  requiredFeatures: '',
  priority: '5',
  description: '',
})
const errors = ref<Record<string, string | null>>({})

function openCreate() {
  editing.value = null
  form.value = {
    code: '',
    name: '',
    courseType: 'THEORY',
    credit: '2',
    totalHours: '32',
    defaultRoomType: '',
    requiredFeatures: '',
    priority: '5',
    description: '',
  }
  errors.value = {}
  open.value = true
}

function openEdit(c: SchedulingCourse) {
  editing.value = c
  form.value = {
    code: c.code,
    name: c.name,
    courseType: c.courseType,
    credit: String(c.credit),
    totalHours: String(c.totalHours),
    defaultRoomType: c.defaultRoomType ?? '',
    requiredFeatures: (c.requiredFeatures ?? []).join(', '),
    priority: c.priority == null ? '5' : String(c.priority),
    description: c.description ?? '',
  }
  errors.value = {}
  open.value = true
}

function splitList(v: string) {
  return v
    .split(/[,，\s]+/)
    .map((x) => x.trim())
    .filter(Boolean)
}

async function submit() {
  errors.value = {}
  const f = form.value
  if (!f.code.trim()) errors.value.code = '请填写课程代码'
  if (!f.name.trim()) errors.value.name = '请填写课程名称'
  const credit = Number(f.credit)
  const totalHours = Number(f.totalHours)
  if (!Number.isFinite(credit) || credit < 0 || credit > 20) errors.value.credit = '学分需在 0~20 之间'
  if (!Number.isFinite(totalHours) || totalHours < 1) errors.value.totalHours = '总学时必须大于 0'
  if (Object.keys(errors.value).length) return

  const body: Record<string, unknown> = {
    code: f.code.trim(),
    name: f.name.trim(),
    courseType: f.courseType,
    credit,
    totalHours,
    defaultRoomType: f.defaultRoomType || null,
    requiredFeatures: splitList(f.requiredFeatures),
    priority: Number(f.priority) || 5,
    status: editing.value?.status ?? 'ACTIVE',
    description: f.description.trim() || null,
  }
  if (editing.value) body.version = editing.value.version

  busy.value = true
  try {
    if (editing.value) await schedulingApi.updateCourse(editing.value.id, body)
    else await schedulingApi.createCourse(body)
    open.value = false
    toast.push(editing.value ? '课程已更新' : '课程已创建', 'success')
    await load()
  } catch (e) {
    const apiErr = e instanceof ApiError ? e : null
    errors.value = {
      code: apiErr?.fieldError('code') ?? null,
      name: apiErr?.fieldError('name') ?? null,
      credit: apiErr?.fieldError('credit') ?? null,
      totalHours: apiErr?.fieldError('totalHours') ?? null,
      general: errorMessage(e),
    }
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    busy.value = false
  }
}

/* ---------------- 停用 ---------------- */
const disableTarget = ref<SchedulingCourse | null>(null)
const disableBusy = ref(false)

async function confirmDisable() {
  if (!disableTarget.value) return
  disableBusy.value = true
  try {
    await schedulingApi.disableCourse(disableTarget.value.id)
    toast.push('课程已停用', 'success')
    disableTarget.value = null
    await load()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    disableBusy.value = false
  }
}

const activeCount = computed(() => courses.value.filter((c) => c.status === 'ACTIVE').length)
</script>

<template>
  <section class="cp">
    <div class="cp__bar">
      <div class="cp__search">
        <input v-model="keyword" class="cp__input" type="search" placeholder="课程代码或名称" @keyup.enter="load" />
        <UiSelect
          :model-value="statusFilter"
          label=""
          :options="statusOptions"
          @update:model-value="(v: string) => { statusFilter = v; load() }"
        />
        <UiButton size="sm" @click="load">搜索</UiButton>
      </div>
      <div class="cp__right">
        <span class="cp__sub">共 {{ courses.length }} 门 · 启用 {{ activeCount }}</span>
        <UiButton v-if="props.canManage" size="sm" variant="primary" @click="openCreate">新建课程</UiButton>
      </div>
    </div>

    <p v-if="loadError" class="cp__error">{{ loadError }}</p>
    <UiEmpty v-else-if="!courses.length && !loading" title="课程库还是空的" hint="先建课程，才能建教学任务与教学班" />

    <table v-else class="cp__table">
      <thead>
        <tr>
          <th>代码</th>
          <th>课程</th>
          <th>类型</th>
          <th>学分</th>
          <th>总学时</th>
          <th>教室要求</th>
          <th>优先级</th>
          <th>状态</th>
          <th />
        </tr>
      </thead>
      <tbody>
        <tr v-for="c in courses" :key="c.id">
          <td class="mono">{{ c.code }}</td>
          <td>
            <p class="cp__name">{{ c.name }}</p>
            <p v-if="c.description" class="cp__sub cp__desc">{{ c.description }}</p>
          </td>
          <td class="cp__sub">{{ courseTypeLabel(c.courseType) }}</td>
          <td class="mono">{{ c.credit }}</td>
          <td class="mono">{{ c.totalHours }}</td>
          <td class="cp__sub">
            {{ roomTypeLabel(c.defaultRoomType) }}
            <span v-if="c.requiredFeatures?.length"> · {{ c.requiredFeatures.join('、') }}</span>
          </td>
          <td class="mono">{{ c.priority ?? '—' }}</td>
          <td>
            <UiTag :tone="c.status === 'ACTIVE' ? 'teal' : 'muted'">
              {{ c.status === 'ACTIVE' ? '启用' : '停用' }}
            </UiTag>
          </td>
          <td class="cp__ops">
            <template v-if="props.canManage">
              <UiButton size="sm" variant="link" @click="openEdit(c)">编辑</UiButton>
              <UiButton v-if="c.status === 'ACTIVE'" size="sm" variant="link" @click="disableTarget = c">停用</UiButton>
            </template>
          </td>
        </tr>
      </tbody>
    </table>

    <UiDialog
      :open="open"
      :title="editing ? '修改课程' : '新建课程'"
      eyebrow="课程库"
      width="lg"
      @close="open = false"
    >
      <div class="cp__grid">
        <UiField v-model="form.code" label="课程代码" required placeholder="如 CS101" :maxlength="32" :error="errors.code" />
        <UiField v-model="form.name" label="课程名称" required placeholder="如 Java 程序设计" :maxlength="128" :error="errors.name" />
        <UiSelect
          :model-value="form.courseType"
          label="课程类型"
          :options="COURSE_TYPES"
          @update:model-value="(v: string) => (form.courseType = v)"
        />
        <UiField v-model="form.credit" label="学分" required inputmode="numeric" :error="errors.credit" />
        <UiField
          v-model="form.totalHours"
          label="总学时"
          required
          inputmode="numeric"
          hint="排课时按「每周次数 × 每次节数 × 教学周」校验"
          :error="errors.totalHours"
        />
        <UiSelect
          :model-value="form.defaultRoomType"
          label="默认教室类型"
          :options="ROOM_TYPES"
          @update:model-value="(v: string) => (form.defaultRoomType = v)"
        />
        <UiField v-model="form.requiredFeatures" label="必需设备标签" placeholder="投影, 电脑" hint="逗号分隔" />
        <UiField v-model="form.priority" label="优先级 1~10" inputmode="numeric" hint="越高越优先排上午" />
      </div>
      <div class="cp__desc-field">
        <UiTextarea v-model="form.description" label="课程简介" :rows="2" :maxlength="500" />
      </div>
      <p v-if="errors.general" class="cp__error">{{ errors.general }}</p>
      <template #footer>
        <UiButton @click="open = false">取消</UiButton>
        <UiButton variant="primary" :loading="busy" @click="submit">保存</UiButton>
      </template>
    </UiDialog>

    <UiDialog :open="!!disableTarget" title="停用课程" width="sm" @close="disableTarget = null">
      <p class="cp__confirm">
        停用「{{ disableTarget?.name }}」后，它不能再被新的教学任务引用；
        已存在的教学任务与历史课表不受影响。
      </p>
      <template #footer>
        <UiButton @click="disableTarget = null">取消</UiButton>
        <UiButton variant="danger" :loading="disableBusy" @click="confirmDisable">停用</UiButton>
      </template>
    </UiDialog>
  </section>
</template>

<style scoped>
.cp {
  display: grid;
  gap: var(--s-4);
}

.cp__bar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--s-3);
  flex-wrap: wrap;
}

.cp__search {
  display: flex;
  align-items: flex-end;
  gap: var(--s-3);
}

.cp__search :deep(.ui-select) {
  min-width: 130px;
}

.cp__input {
  width: 220px;
  height: 38px;
  padding: 0 2px;
  border: 0;
  border-bottom: 1px solid var(--rule-strong);
  background: transparent;
  color: var(--ink);
  font: inherit;
  font-size: 15px;
}

.cp__input:focus {
  outline: none;
  border-bottom-color: var(--teal);
  box-shadow: 0 1px 0 0 var(--teal);
}

.cp__right {
  display: flex;
  align-items: center;
  gap: var(--s-3);
}

.cp__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.cp__table th {
  padding: 8px 10px;
  border-bottom: 1px solid var(--ink);
  color: var(--ink-2);
  font-size: var(--fs-small);
  font-weight: 600;
  text-align: left;
  white-space: nowrap;
}

.cp__table td {
  padding: 10px;
  border-bottom: var(--hairline);
  vertical-align: top;
}

.cp__name {
  margin: 0;
  font-weight: 600;
}

.cp__sub {
  margin: 2px 0 0;
  color: var(--ink-2);
  font-size: 12px;
}

.cp__desc {
  max-width: 320px;
}

.cp__ops {
  text-align: right;
  white-space: nowrap;
}

.cp__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--s-4);
}

.cp__desc-field {
  margin-top: var(--s-4);
}

.cp__confirm {
  margin: 0;
  color: var(--ink-2);
  line-height: 1.7;
}

.cp__error {
  margin: var(--s-3) 0 0;
  color: var(--danger);
  font-size: var(--fs-small);
}

@media (max-width: 760px) {
  .cp__search,
  .cp__input {
    width: 100%;
  }

  .cp__search {
    flex-wrap: wrap;
  }

  .cp__table thead {
    display: none;
  }

  .cp__table tr {
    display: grid;
    gap: 2px;
    padding: var(--s-2) 0;
    border-bottom: var(--hairline);
  }

  .cp__table td {
    padding: 2px 0;
    border: 0;
  }

  .cp__ops {
    text-align: left;
  }
}
</style>
