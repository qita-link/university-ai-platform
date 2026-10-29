<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { schedulingApi } from '@/api/endpoints'
import type { SchedulingCourse, SchedulingTeacher } from '@/api/types'
import { ApiError } from '@/api/http'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { useToastStore } from '@/stores/toast'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiEmpty from '@/components/ui/UiEmpty.vue'
import UiField from '@/components/ui/UiField.vue'
import UiTag from '@/components/ui/UiTag.vue'

/**
 * 教师排课档案：教师账号本身在「管理 → 用户」里创建（角色 TEACHER），
 * 这里维护的是排课口径的上限与可授课程，不重复建人。
 */
const props = defineProps<{ canManage: boolean }>()

const toast = useToastStore()
const teachers = ref<SchedulingTeacher[]>([])
const courses = ref<SchedulingCourse[]>([])
const keyword = ref('')
const loading = ref(true)
const loadError = ref<string | null>(null)

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const [list, cs] = await Promise.all([
      schedulingApi.teachers(keyword.value.trim() || undefined),
      schedulingApi.courses({ status: 'ACTIVE' }),
    ])
    teachers.value = list
    courses.value = cs
  } catch (e) {
    loadError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

const courseName = computed(() => new Map(courses.value.map((c) => [c.id, c.name])))

function limitText(v: number | null | undefined) {
  return v == null ? '不限' : String(v)
}

/* ---------------- 编辑档案 ---------------- */
const open = ref(false)
const busy = ref(false)
const editing = ref<SchedulingTeacher | null>(null)
const form = ref({
  title: '',
  maxWeeklyPeriods: '',
  maxDailyPeriods: '',
  maxDailySessions: '',
  maxConsecutivePeriods: '',
})
const pickedCourses = ref<number[]>([])
const errors = ref<Record<string, string | null>>({})

function openEdit(t: SchedulingTeacher) {
  editing.value = t
  form.value = {
    title: t.title ?? '',
    maxWeeklyPeriods: t.maxWeeklyPeriods == null ? '' : String(t.maxWeeklyPeriods),
    maxDailyPeriods: t.maxDailyPeriods == null ? '' : String(t.maxDailyPeriods),
    maxDailySessions: t.maxDailySessions == null ? '' : String(t.maxDailySessions),
    maxConsecutivePeriods: t.maxConsecutivePeriods == null ? '' : String(t.maxConsecutivePeriods),
  }
  pickedCourses.value = [...(t.courseIds ?? [])]
  errors.value = {}
  open.value = true
}

function toggleCourse(id: number) {
  pickedCourses.value = pickedCourses.value.includes(id)
    ? pickedCourses.value.filter((x) => x !== id)
    : [...pickedCourses.value, id]
}

function numOrNull(v: string) {
  const n = Number(v.trim())
  return v.trim() === '' || !Number.isFinite(n) ? null : n
}

async function submit() {
  if (!editing.value) return
  errors.value = {}
  const body: Record<string, unknown> = {
    orgUnitId: editing.value.orgUnitId,
    title: form.value.title.trim() || null,
    homeCampusId: editing.value.homeCampusId,
    maxWeeklyPeriods: numOrNull(form.value.maxWeeklyPeriods),
    maxDailyPeriods: numOrNull(form.value.maxDailyPeriods),
    maxDailySessions: numOrNull(form.value.maxDailySessions),
    maxConsecutivePeriods: numOrNull(form.value.maxConsecutivePeriods),
    courseIds: pickedCourses.value,
  }
  busy.value = true
  try {
    await schedulingApi.updateTeacher(editing.value.id, body)
    open.value = false
    toast.push('教师排课档案已保存', 'success')
    await load()
  } catch (e) {
    const apiErr = e instanceof ApiError ? e : null
    errors.value = {
      maxWeeklyPeriods: apiErr?.fieldError('maxWeeklyPeriods') ?? null,
      maxDailyPeriods: apiErr?.fieldError('maxDailyPeriods') ?? null,
      general: errorMessage(e),
    }
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="tps">
    <div class="tps__bar">
      <div class="tps__search">
        <input v-model="keyword" class="tps__input" type="search" placeholder="按姓名或账号搜索" @keyup.enter="load" />
        <UiButton size="sm" @click="load">搜索</UiButton>
      </div>
      <p class="tps__hint">教师账号在「管理 → 用户」创建（角色 TEACHER），这里只维护排课档案</p>
    </div>

    <p v-if="loadError" class="tps__error">{{ loadError }}</p>
    <UiEmpty v-else-if="!teachers.length && !loading" title="还没有教师" hint="先在「管理 → 用户」创建角色为 TEACHER 的账号" />

    <table v-else class="tps__table">
      <thead>
        <tr>
          <th>教师</th>
          <th>所属部门</th>
          <th>职称</th>
          <th>周/日节数</th>
          <th>连续上限</th>
          <th>可授课程</th>
          <th />
        </tr>
      </thead>
      <tbody>
        <tr v-for="t in teachers" :key="t.id">
          <td>
            <p class="tps__name">{{ t.displayName }}</p>
            <p class="tps__sub mono">{{ t.username }}</p>
          </td>
          <td class="tps__sub">{{ t.orgUnitName || '—' }}</td>
          <td class="tps__sub">{{ t.title || '—' }}</td>
          <td class="mono">{{ limitText(t.maxWeeklyPeriods) }} / {{ limitText(t.maxDailyPeriods) }}</td>
          <td class="mono">{{ limitText(t.maxConsecutivePeriods) }}</td>
          <td>
            <span v-if="!t.courseIds?.length" class="tps__sub">不限</span>
            <UiTag v-for="id in (t.courseIds ?? []).slice(0, 3)" :key="id" tone="muted">
              {{ courseName.get(id) ?? `#${id}` }}
            </UiTag>
            <span v-if="(t.courseIds ?? []).length > 3" class="tps__sub">
              +{{ (t.courseIds ?? []).length - 3 }}
            </span>
          </td>
          <td class="tps__ops">
            <UiButton v-if="props.canManage" size="sm" variant="link" @click="openEdit(t)">档案</UiButton>
          </td>
        </tr>
      </tbody>
    </table>

    <UiDialog :open="open" :title="`${editing?.displayName ?? ''} 的排课档案`" eyebrow="教师约束" width="lg" @close="open = false">
      <div class="tps__grid">
        <UiField v-model="form.title" label="职称" placeholder="如 副教授" :maxlength="32" />
        <UiField
          v-model="form.maxWeeklyPeriods"
          label="每周最多次数（节）"
          inputmode="numeric"
          hint="留空＝不限"
          :error="errors.maxWeeklyPeriods"
        />
        <UiField
          v-model="form.maxDailyPeriods"
          label="每天最多节数"
          inputmode="numeric"
          hint="留空＝不限"
          :error="errors.maxDailyPeriods"
        />
        <UiField v-model="form.maxDailySessions" label="每天最多课次" inputmode="numeric" hint="留空＝不限" />
        <UiField v-model="form.maxConsecutivePeriods" label="连续上课上限（节）" inputmode="numeric" hint="留空＝不限" />
      </div>

      <div class="tps__courses">
        <p class="tps__courses-head">
          可授课程
          <span class="tps__sub">不选表示不限制；排课时会作为软约束参考</span>
        </p>
        <div v-if="courses.length" class="tps__course-list">
          <label v-for="c in courses" :key="c.id" class="tps__course">
            <input
              type="checkbox"
              :checked="pickedCourses.includes(c.id)"
              @change="toggleCourse(c.id)"
            />
            <span>{{ c.name }}</span>
            <span class="tps__sub mono">{{ c.code }}</span>
          </label>
        </div>
        <p v-else class="tps__sub">课程库还是空的，先在「课程」里建课。</p>
      </div>

      <p v-if="errors.general" class="tps__error">{{ errors.general }}</p>
      <template #footer>
        <UiButton @click="open = false">取消</UiButton>
        <UiButton variant="primary" :loading="busy" @click="submit">保存</UiButton>
      </template>
    </UiDialog>
  </section>
</template>

<style scoped>
.tps {
  display: grid;
  gap: var(--s-4);
}

.tps__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  flex-wrap: wrap;
}

.tps__search {
  display: flex;
  align-items: center;
  gap: var(--s-2);
}

.tps__input {
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

.tps__input:focus {
  outline: none;
  border-bottom-color: var(--teal);
  box-shadow: 0 1px 0 0 var(--teal);
}

.tps__hint {
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.tps__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.tps__table th {
  padding: 8px 10px;
  border-bottom: 1px solid var(--ink);
  color: var(--ink-2);
  font-size: var(--fs-small);
  font-weight: 600;
  text-align: left;
  white-space: nowrap;
}

.tps__table td {
  padding: 10px;
  border-bottom: var(--hairline);
  vertical-align: top;
}

.tps__name {
  margin: 0;
  font-weight: 600;
}

.tps__sub {
  margin: 2px 0 0;
  color: var(--ink-2);
  font-size: 12px;
}

.tps__ops {
  text-align: right;
  white-space: nowrap;
}

.tps__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--s-4);
}

.tps__courses {
  margin-top: var(--s-5);
}

.tps__courses-head {
  display: flex;
  align-items: baseline;
  gap: var(--s-2);
  margin: 0 0 var(--s-2);
  font-weight: 600;
}

.tps__course-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 4px 12px;
  max-height: 30vh;
  overflow: auto;
}

.tps__course {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 0;
  font-size: 13px;
  cursor: pointer;
}

.tps__error {
  margin: var(--s-3) 0 0;
  color: var(--danger);
  font-size: var(--fs-small);
}

@media (max-width: 760px) {
  .tps__input {
    width: 100%;
  }

  .tps__search {
    width: 100%;
  }

  .tps__table thead {
    display: none;
  }

  .tps__table tr {
    display: grid;
    gap: 2px;
    padding: var(--s-2) 0;
    border-bottom: var(--hairline);
  }

  .tps__table td {
    padding: 2px 0;
    border: 0;
  }

  .tps__ops {
    text-align: left;
  }
}
</style>
