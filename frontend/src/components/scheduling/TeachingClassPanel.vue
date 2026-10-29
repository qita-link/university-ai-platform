<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { schedulingApi } from '@/api/endpoints'
import type { SchedulingClass, SchedulingTeachingClass } from '@/api/types'
import { ApiError } from '@/api/http'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { useToastStore } from '@/stores/toast'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiEmpty from '@/components/ui/UiEmpty.vue'
import UiField from '@/components/ui/UiField.vue'

/**
 * 教学班 = 实际一起上课的学生集合。整班参加时人数取班级档案；
 * 选修抽取要显式填人数，否则教室容量校验会按 0 人算，排出来一个坐不下的教室。
 */
const props = defineProps<{ semesterId: number; canManage: boolean }>()

const toast = useToastStore()
const classes = ref<SchedulingClass[]>([])
const teachingClasses = ref<SchedulingTeachingClass[]>([])
const loading = ref(false)
const loadError = ref<string | null>(null)

async function load() {
  if (!props.semesterId) return
  loading.value = true
  loadError.value = null
  try {
    const [cs, tcs] = await Promise.all([
      schedulingApi.classes(),
      schedulingApi.teachingClasses({ semesterId: props.semesterId }),
    ])
    classes.value = cs
    teachingClasses.value = tcs
  } catch (e) {
    loadError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(() => props.semesterId, load)

function memberText(tc: SchedulingTeachingClass) {
  if (!tc.members?.length) return '—'
  return tc.members
    .map((m) => `${m.className}${m.fullClass ? '' : `（${m.studentCount ?? 0} 人）`}`)
    .join('、')
}

/* ---------------- 新建 / 编辑 ---------------- */
interface MemberDraft {
  classId: number
  className: string
  fullClass: boolean
  studentCount: string
}

const open = ref(false)
const busy = ref(false)
const editing = ref<SchedulingTeachingClass | null>(null)
const form = ref({ code: '', name: '', studentCount: '' })
const members = ref<MemberDraft[]>([])
const memberKeyword = ref('')
const errors = ref<Record<string, string | null>>({})

function openCreate() {
  editing.value = null
  form.value = { code: '', name: '', studentCount: '' }
  members.value = classes.value.map((c) => ({
    classId: c.id,
    className: c.name,
    fullClass: true,
    studentCount: c.studentCount == null ? '' : String(c.studentCount),
  }))
  memberKeyword.value = ''
  errors.value = {}
  open.value = true
}

function openEdit(tc: SchedulingTeachingClass) {
  editing.value = tc
  form.value = {
    code: tc.code,
    name: tc.name,
    studentCount: tc.studentCount == null ? '' : String(tc.studentCount),
  }
  const byId = new Map(classes.value.map((c) => [c.id, c]))
  members.value = classes.value.map((c) => {
    const chosen = tc.members?.find((m) => m.classId === c.id)
    return {
      classId: c.id,
      className: c.name,
      fullClass: chosen ? chosen.fullClass : false,
      studentCount: chosen?.studentCount != null
        ? String(chosen.studentCount)
        : c.studentCount == null ? '' : String(c.studentCount),
    }
  })
  // 成员表里出现了不在班级列表里的历史班级时补一行，避免保存时静默丢人
  for (const m of tc.members ?? []) {
    if (!byId.has(m.classId)) {
      members.value.push({
        classId: m.classId,
        className: m.className,
        fullClass: m.fullClass,
        studentCount: m.studentCount == null ? '' : String(m.studentCount),
      })
    }
  }
  memberKeyword.value = ''
  errors.value = {}
  open.value = true
}

const shownMembers = computed(() => {
  const k = memberKeyword.value.trim()
  return k ? members.value.filter((m) => m.className.includes(k)) : members.value
})

const chosenIds = ref<Set<number>>(new Set())

function syncChosenFromEditing() {
  const set = new Set<number>()
  for (const m of editing.value?.members ?? []) set.add(m.classId)
  chosenIds.value = set
}

watch(open, (v) => {
  if (v) syncChosenFromEditing()
})

function toggleMember(m: MemberDraft, checked: boolean) {
  const next = new Set(chosenIds.value)
  if (checked) next.add(m.classId)
  else next.delete(m.classId)
  chosenIds.value = next
}

const totalStudents = computed(() =>
  members.value
    .filter((m) => chosenIds.value.has(m.classId))
    .reduce((sum, m) => sum + (m.fullClass ? Number(m.studentCount) || 0 : Number(m.studentCount) || 0), 0),
)

async function submit() {
  if (!props.semesterId) return
  errors.value = {}
  if (!form.value.code.trim()) errors.value.code = '请填写教学班编码'
  if (!form.value.name.trim()) errors.value.name = '请填写教学班名称'
  const picked = members.value.filter((m) => chosenIds.value.has(m.classId))
  if (!picked.length) errors.value.members = '至少选择一个班级'
  const electiveMissing = picked.some((m) => !m.fullClass && !m.studentCount.trim())
  if (electiveMissing) errors.value.members = '选修抽取的班级要填写人数'
  if (Object.keys(errors.value).length) return

  const body: Record<string, unknown> = {
    semesterId: props.semesterId,
    code: form.value.code.trim(),
    name: form.value.name.trim(),
    studentCount: form.value.studentCount.trim() === '' ? null : Number(form.value.studentCount),
    members: picked.map((m) => ({
      classId: m.classId,
      fullClass: m.fullClass,
      studentCount: m.fullClass ? null : Number(m.studentCount),
    })),
  }
  busy.value = true
  try {
    if (editing.value) await schedulingApi.updateTeachingClass(editing.value.id, body)
    else await schedulingApi.createTeachingClass(body)
    open.value = false
    toast.push(editing.value ? '教学班已更新' : '教学班已创建', 'success')
    await load()
  } catch (e) {
    const apiErr = e instanceof ApiError ? e : null
    errors.value = {
      code: apiErr?.fieldError('code') ?? null,
      name: apiErr?.fieldError('name') ?? null,
      members: apiErr?.fieldError('members') ?? null,
      general: errorMessage(e),
    }
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    busy.value = false
  }
}

const hasClasses = computed(() => classes.value.length > 0)
</script>

<template>
  <section class="tcp">
    <div class="tcp__bar">
      <p class="tcp__sub">
        共 {{ teachingClasses.length }} 个教学班
        <span v-if="!hasClasses"> · 系统里还没有行政班，请先在「管理 → 组织」创建班级</span>
      </p>
      <UiButton v-if="props.canManage" size="sm" variant="primary" :disabled="!hasClasses" @click="openCreate">
        新建教学班
      </UiButton>
    </div>

    <p v-if="loadError" class="tcp__error">{{ loadError }}</p>
    <UiEmpty
      v-else-if="!teachingClasses.length && !loading"
      title="这个学期还没有教学班"
      hint="可以整班参加，也可以多个班合班或按选修人数抽取"
    />

    <table v-else class="tcp__table">
      <thead>
        <tr>
          <th>编码</th>
          <th>名称</th>
          <th>成员班级</th>
          <th>人数</th>
          <th />
        </tr>
      </thead>
      <tbody>
        <tr v-for="tc in teachingClasses" :key="tc.id">
          <td class="mono">{{ tc.code }}</td>
          <td>{{ tc.name }}</td>
          <td class="tcp__sub">{{ memberText(tc) }}</td>
          <td>
            <span class="mono">{{ tc.computedStudentCount }}</span>
            <span v-if="tc.studentCount != null" class="tcp__sub"> （按 {{ tc.studentCount }} 计）</span>
          </td>
          <td class="tcp__ops">
            <UiButton v-if="props.canManage" size="sm" variant="link" @click="openEdit(tc)">编辑</UiButton>
          </td>
        </tr>
      </tbody>
    </table>

    <UiDialog
      :open="open"
      :title="editing ? '修改教学班' : '新建教学班'"
      eyebrow="学生集合"
      width="lg"
      @close="open = false"
    >
      <div class="tcp__grid">
        <UiField v-model="form.code" label="教学班编码" required placeholder="如 TC-CS101-01" :maxlength="48" :error="errors.code" />
        <UiField v-model="form.name" label="教学班名称" required placeholder="如 26 级 Java 1 班" :maxlength="128" :error="errors.name" />
        <UiField
          v-model="form.studentCount"
          label="人数覆盖"
          inputmode="numeric"
          hint="留空按成员班级自动汇总"
        />
      </div>

      <div class="tcp__members">
        <div class="tcp__members-head">
          <p class="tcp__members-title">成员班级</p>
          <input v-model="memberKeyword" class="tcp__input" type="search" placeholder="筛选班级" />
        </div>
        <div v-if="shownMembers.length" class="tcp__member-list">
          <div v-for="m in shownMembers" :key="m.classId" class="tcp__member">
            <label class="tcp__pick">
              <input
                type="checkbox"
                :checked="chosenIds.has(m.classId)"
                @change="toggleMember(m, ($event.target as HTMLInputElement).checked)"
              />
              <span>{{ m.className }}</span>
            </label>
            <template v-if="chosenIds.has(m.classId)">
              <label class="tcp__mode">
                <input v-model="m.fullClass" type="radio" :name="`mode-${m.classId}`" :value="true" />
                整班
              </label>
              <label class="tcp__mode">
                <input v-model="m.fullClass" type="radio" :name="`mode-${m.classId}`" :value="false" />
                部分
              </label>
              <input
                v-if="!m.fullClass"
                v-model="m.studentCount"
                class="tcp__num"
                type="text"
                inputmode="numeric"
                placeholder="人数"
              />
            </template>
          </div>
        </div>
        <p v-else class="tcp__sub">没有匹配的班级。</p>
        <p class="tcp__sub">合计 {{ totalStudents }} 人（整班取班级档案人数；部分选修取填写人数）</p>
      </div>

      <p v-if="errors.members" class="tcp__error">{{ errors.members }}</p>
      <p v-if="errors.general" class="tcp__error">{{ errors.general }}</p>
      <template #footer>
        <UiButton @click="open = false">取消</UiButton>
        <UiButton variant="primary" :loading="busy" @click="submit">保存</UiButton>
      </template>
    </UiDialog>
  </section>
</template>

<style scoped>
.tcp {
  display: grid;
  gap: var(--s-4);
}

.tcp__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  flex-wrap: wrap;
}

.tcp__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.tcp__table th {
  padding: 8px 10px;
  border-bottom: 1px solid var(--ink);
  color: var(--ink-2);
  font-size: var(--fs-small);
  font-weight: 600;
  text-align: left;
  white-space: nowrap;
}

.tcp__table td {
  padding: 10px;
  border-bottom: var(--hairline);
  vertical-align: top;
}

.tcp__sub {
  margin: 0;
  color: var(--ink-2);
  font-size: 12px;
}

.tcp__ops {
  text-align: right;
  white-space: nowrap;
}

.tcp__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--s-4);
}

.tcp__members {
  margin-top: var(--s-5);
  padding-top: var(--s-3);
  border-top: var(--hairline);
}

.tcp__members-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  margin-bottom: var(--s-2);
}

.tcp__members-title {
  margin: 0;
  font-weight: 600;
}

.tcp__input {
  width: 160px;
  height: 34px;
  padding: 0 2px;
  border: 0;
  border-bottom: 1px solid var(--rule-strong);
  background: transparent;
  color: var(--ink);
  font: inherit;
  font-size: 14px;
}

.tcp__input:focus {
  outline: none;
  border-bottom-color: var(--teal);
}

.tcp__member-list {
  display: grid;
  gap: 2px;
  max-height: 32vh;
  overflow: auto;
  margin-bottom: var(--s-2);
}

.tcp__member {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  padding: 6px 0;
  border-bottom: var(--hairline);
  font-size: 13px;
}

.tcp__pick {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 0 0 auto;
  min-width: 180px;
  margin-right: auto;
  cursor: pointer;
}

.tcp__mode {
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--ink-2);
  font-size: 12px;
  cursor: pointer;
}

.tcp__num {
  width: 64px;
  height: 28px;
  padding: 0 6px;
  border: var(--hairline);
  background: var(--paper);
  color: var(--ink);
  font: inherit;
  font-size: 13px;
}

.tcp__error {
  margin: var(--s-3) 0 0;
  color: var(--danger);
  font-size: var(--fs-small);
}

@media (max-width: 760px) {
  .tcp__table thead {
    display: none;
  }

  .tcp__table tr {
    display: grid;
    gap: 2px;
    padding: var(--s-2) 0;
    border-bottom: var(--hairline);
  }

  .tcp__table td {
    padding: 2px 0;
    border: 0;
  }

  .tcp__ops {
    text-align: left;
  }

  .tcp__member {
    flex-wrap: wrap;
  }
}
</style>
