<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { leaveApi, selectionApi } from '@/api/endpoints'
import type { LeaveView, SelectionView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { LEAVE_STATUS_LABEL, leaveTone } from '@/lib/labels'
import { formatDateTime } from '@/lib/time'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiEmpty from '@/components/ui/UiEmpty.vue'
import UiField from '@/components/ui/UiField.vue'
import UiPageHeader from '@/components/ui/UiPageHeader.vue'
import UiSelect from '@/components/ui/UiSelect.vue'
import UiTag from '@/components/ui/UiTag.vue'
import UiTextarea from '@/components/ui/UiTextarea.vue'

/**
 * 请假：学生提交与撤销，教师审批。
 * 「能不能审」由后端按 canReview 逐条给出，前端不重复推导课程归属。
 */
const auth = useAuthStore()
const toast = useToastStore()

const canSubmit = computed(() => auth.can('leave:submit'))
const canReview = computed(() => auth.can('leave:review', 'leave:manage_all'))
const tab = ref<'mine' | 'review'>(canReview.value && !canSubmit.value ? 'review' : 'mine')

const mine = ref<LeaveView[]>([])
const pending = ref<LeaveView[]>([])
const loading = ref(true)
const loadError = ref<string | null>(null)
const statusFilter = ref('')

const statusOptions = [
  { value: '', label: '全部状态' },
  { value: 'PENDING', label: '待审批' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'CANCELLED', label: '已撤销' },
]

async function loadMine() {
  const res = await leaveApi.mine(statusFilter.value ? { status: statusFilter.value } : {})
  mine.value = res.items
}

async function loadPending() {
  if (!canReview.value) return
  const res = await leaveApi.toReview({ status: 'PENDING' })
  pending.value = res.items
}

async function reload() {
  loading.value = true
  loadError.value = null
  try {
    await Promise.all([canSubmit.value ? loadMine() : Promise.resolve(), loadPending()])
  } catch (e) {
    loadError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

onMounted(reload)

// 状态筛选变化后重新取「我的申请」；用 watch 而不是 v-model 的事件，避免与 v-model 抢更新顺序
watch(statusFilter, () => {
  if (canSubmit.value) void loadMine()
})

/* ---------------- 提交 ---------------- */
const selections = ref<SelectionView[]>([])
const createOpen = ref(false)
const createBusy = ref(false)
const form = ref({ courseId: '', startAt: '', endAt: '', reason: '' })
const formError = ref<{ courseId?: string | null; startAt?: string | null; endAt?: string | null; reason?: string | null; general?: string | null }>({})

const courseOptions = computed(() =>
  selections.value
    .filter((s) => s.status === 'SELECTED')
    .map((s) => ({ value: String(s.courseId), label: `${s.courseName}（${s.courseCode}）` })),
)

async function openCreate() {
  form.value = { courseId: '', startAt: '', endAt: '', reason: '' }
  formError.value = {}
  createOpen.value = true
  try {
    selections.value = await selectionApi.mine()
    const first = courseOptions.value[0]
    if (first && !form.value.courseId) form.value.courseId = first.value
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  }
}

async function submitCreate() {
  formError.value = {}
  if (!form.value.courseId) {
    formError.value = { courseId: '请选择请假课程' }
    return
  }
  if (!form.value.startAt || !form.value.endAt) {
    formError.value = { startAt: '请填写开始与结束时间' }
    return
  }
  if (form.value.endAt <= form.value.startAt) {
    formError.value = { endAt: '结束时间必须晚于开始时间' }
    return
  }
  if (!form.value.reason.trim()) {
    formError.value = { reason: '请写明请假原因' }
    return
  }

  createBusy.value = true
  try {
    await leaveApi.create({
      courseId: Number(form.value.courseId),
      // datetime-local 给的是 2026-10-29T10:00，后端 LocalDateTime 可直接解析
      startAt: form.value.startAt,
      endAt: form.value.endAt,
      reason: form.value.reason.trim(),
    })
    createOpen.value = false
    toast.push('已提交，等待任课教师审批', 'success')
    tab.value = 'mine'
    await loadMine()
  } catch (e) {
    formError.value = { general: errorMessage(e) }
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    createBusy.value = false
  }
}

/* ---------------- 撤销 ---------------- */
const busyId = ref<number | null>(null)

async function cancel(item: LeaveView) {
  busyId.value = item.id
  try {
    await leaveApi.cancel(item.id)
    toast.push('已撤销', 'success')
    await Promise.all([loadMine(), loadPending()])
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    busyId.value = null
  }
}

/* ---------------- 审批 ---------------- */
const decide = ref<{ item: LeaveView; mode: 'approve' | 'reject' } | null>(null)
const comment = ref('')
const decideBusy = ref(false)

function openDecide(item: LeaveView, mode: 'approve' | 'reject') {
  decide.value = { item, mode }
  comment.value = ''
}

async function submitDecide() {
  if (!decide.value) return
  const { item, mode } = decide.value
  if (mode === 'reject' && !comment.value.trim()) {
    toast.push('驳回时请写明理由，便于学生理解', 'danger')
    return
  }
  decideBusy.value = true
  try {
    if (mode === 'approve') await leaveApi.approve(item.id, comment.value.trim() || undefined)
    else await leaveApi.reject(item.id, comment.value.trim())
    decide.value = null
    toast.push(mode === 'approve' ? '已通过' : '已驳回', 'success')
    await Promise.all([loadMine(), loadPending()])
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    decideBusy.value = false
  }
}
</script>

<template>
  <div class="leave-page">
    <UiPageHeader
      label="请假"
      latin="Leave"
      title="请假条"
      lede="只能对本人已选课程请假。提交后由任课教师审批；待审批期间可以自行撤销，终态不能再改。"
    >
      <template #actions>
        <UiButton v-if="canSubmit" variant="primary" @click="openCreate">写一张请假条</UiButton>
      </template>
      <template #meta>
        <span v-if="canSubmit" class="mono">我的申请 {{ mine.length }} 条</span>
        <span v-if="canReview" class="mono">待审批 {{ pending.length }} 条</span>
      </template>
    </UiPageHeader>

    <div class="bar">
      <div class="tabs" role="tablist" aria-label="视图">
        <button
          v-if="canSubmit"
          type="button"
          class="tabs__btn"
          role="tab"
          :aria-selected="tab === 'mine'"
          :class="{ 'is-active': tab === 'mine' }"
          @click="tab = 'mine'"
        >
          我的申请
        </button>
        <button
          v-if="canReview"
          type="button"
          class="tabs__btn"
          role="tab"
          :aria-selected="tab === 'review'"
          :class="{ 'is-active': tab === 'review' }"
          @click="tab = 'review'"
        >
          待审批
        </button>
      </div>

      <UiSelect
        v-if="tab === 'mine' && canSubmit"
        v-model="statusFilter"
        label="筛选"
        class="bar__filter"
        :options="statusOptions"
      />
    </div>

    <p v-if="loading" class="state">正在取请假条…</p>

    <div v-else-if="loadError" class="state state--err" role="alert">
      <p>{{ loadError }}</p>
      <UiButton size="sm" @click="reload">重试</UiButton>
    </div>

    <!-- 我的申请 -->
    <template v-else-if="tab === 'mine'">
      <UiEmpty
        v-if="mine.length === 0"
        title="还没有请假记录"
        :hint="statusFilter ? '这个状态下没有记录，换个筛选看看。' : '需要请假时，选一门已选课程并写明起止时间与原因。'"
      >
        <UiButton v-if="!statusFilter && canSubmit" size="sm" variant="primary" @click="openCreate">
          写一张
        </UiButton>
      </UiEmpty>

      <ul v-else class="list">
        <li v-for="l in mine" :key="l.id" class="item">
          <div class="item__main">
            <p class="item__course">
              {{ l.courseName }}<span class="item__code mono">{{ l.courseCode }}</span>
            </p>
            <p class="item__time">
              <span class="mono">{{ formatDateTime(l.startAt) }}</span>
              <span aria-hidden="true">—</span>
              <span class="mono">{{ formatDateTime(l.endAt) }}</span>
            </p>
            <p class="item__reason">{{ l.reason }}</p>
            <p v-if="l.reviewerName" class="item__review">
              {{ l.reviewerName }} · {{ l.reviewComment || '未留言' }}
            </p>
          </div>
          <div class="item__side">
            <UiTag :tone="leaveTone(l.status)">{{ LEAVE_STATUS_LABEL[l.status] ?? l.status }}</UiTag>
            <UiButton
              v-if="l.canCancel"
              size="sm"
              :loading="busyId === l.id"
              @click="cancel(l)"
            >
              撤销
            </UiButton>
          </div>
        </li>
      </ul>
    </template>

    <!-- 待审批 -->
    <template v-else>
      <UiEmpty
        v-if="pending.length === 0"
        title="没有待审批的请假"
        hint="学生提交后，任课教师会在这里看到；管理员可以看到全部。"
      />

      <ul v-else class="list">
        <li v-for="l in pending" :key="l.id" class="item">
          <div class="item__main">
            <p class="item__course">
              {{ l.studentName }}<span class="item__code mono">{{ l.studentUsername }}</span>
              <span class="item__dot" aria-hidden="true">·</span>
              {{ l.courseName }}
            </p>
            <p class="item__time">
              <span class="mono">{{ formatDateTime(l.startAt) }}</span>
              <span aria-hidden="true">—</span>
              <span class="mono">{{ formatDateTime(l.endAt) }}</span>
            </p>
            <p class="item__reason">{{ l.reason }}</p>
            <p class="item__review">提交于 {{ formatDateTime(l.createdAt) }}</p>
          </div>
          <div v-if="l.canReview" class="item__side item__side--row">
            <UiButton size="sm" variant="primary" @click="openDecide(l, 'approve')">通过</UiButton>
            <UiButton size="sm" variant="danger" @click="openDecide(l, 'reject')">驳回</UiButton>
          </div>
        </li>
      </ul>
    </template>

    <!-- 提交 -->
    <UiDialog
      :open="createOpen"
      title="写一张请假条"
      eyebrow="请假"
      width="md"
      :dismissible="!createBusy"
      @close="createOpen = false"
    >
      <div class="form">
        <UiSelect
          v-model="form.courseId"
          label="课程"
          :options="courseOptions"
          :error="formError.courseId"
          placeholder="选择一门已选课程"
          hint="只能对本人已选课程请假；若列表为空，先去选课。"
        />
        <UiField v-model="form.startAt" label="开始时间" type="datetime-local" :error="formError.startAt" required />
        <UiField v-model="form.endAt" label="结束时间" type="datetime-local" :error="formError.endAt" required />
        <UiTextarea
          v-model="form.reason"
          label="原因"
          :rows="4"
          :maxlength="500"
          :error="formError.reason"
          hint="写清事由与去向，便于教师判断。"
          placeholder="发热就医，上午两节课无法到课。"
        />
        <p v-if="formError.general" class="form__err" role="alert">{{ formError.general }}</p>
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="createBusy" @click="createOpen = false">取消</UiButton>
        <UiButton variant="primary" :loading="createBusy" @click="submitCreate">提交</UiButton>
      </template>
    </UiDialog>

    <!-- 审批 -->
    <UiDialog
      :open="!!decide"
      :title="decide?.mode === 'approve' ? '通过这张请假？' : '驳回这张请假？'"
      :eyebrow="decide?.item.courseName ?? ''"
      width="sm"
      :dismissible="!decideBusy"
      @close="decide = null"
    >
      <div class="form">
        <p class="decide__who">
          {{ decide?.item.studentName }} · {{ formatDateTime(decide?.item.startAt) }} —
          {{ formatDateTime(decide?.item.endAt) }}
        </p>
        <UiTextarea
          v-model="comment"
          :label="decide?.mode === 'approve' ? '留言（可空）' : '驳回理由'"
          :rows="3"
          :maxlength="200"
          :hint="decide?.mode === 'reject' ? '理由会展示给学生，请写清具体原因。' : ''"
        />
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="decideBusy" @click="decide = null">取消</UiButton>
        <UiButton
          :variant="decide?.mode === 'reject' ? 'danger' : 'primary'"
          :loading="decideBusy"
          @click="submitDecide"
        >
          {{ decide?.mode === 'approve' ? '通过' : '驳回' }}
        </UiButton>
      </template>
    </UiDialog>
  </div>
</template>

<style scoped>
.leave-page {
  width: 100%;
  max-width: 920px;
  margin: 0 auto;
  padding: var(--s-5) clamp(var(--s-4), 4vw, var(--s-6)) var(--s-7);
}

.bar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--s-4);
  padding-bottom: var(--s-2);
  margin-bottom: var(--s-4);
  border-bottom: 1px solid var(--ink);
}

.tabs {
  display: flex;
  gap: var(--s-4);
}

.tabs__btn {
  position: relative;
  padding: 0 0 var(--s-2);
  border: 0;
  background: none;
  color: var(--ink-2);
  font-family: var(--font-serif-cjk);
  font-size: 17px;
  cursor: pointer;
  transition: color var(--t-fast) var(--ease);
}

.tabs__btn:hover {
  color: var(--ink);
}

.tabs__btn.is-active {
  color: var(--ink);
  font-weight: 600;
}

.tabs__btn.is-active::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  background: var(--teal);
}

.bar__filter {
  min-width: 160px;
}

.list {
  margin: 0;
  padding: 0;
  list-style: none;
  border-top: var(--hairline);
}

.item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--s-4);
  padding: var(--s-4) 0;
  border-bottom: var(--hairline);
}

.item__course {
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-size: 17px;
  font-weight: 600;
}

.item__code {
  margin-left: var(--s-2);
  color: var(--ink-2);
  font-size: 11px;
  font-weight: 400;
}

.item__dot {
  margin: 0 4px;
  color: var(--rule-strong);
}

.item__time {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2);
  margin: var(--s-1) 0 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.item__reason {
  max-width: 40em;
  margin: var(--s-2) 0 0;
  font-family: var(--font-serif);
  font-size: 15px;
  line-height: 1.7;
}

.item__review {
  margin: var(--s-1) 0 0;
  color: var(--ink-2);
  font-size: 11px;
}

.item__side {
  display: grid;
  justify-items: end;
  align-content: start;
  gap: var(--s-2);
}

.item__side--row {
  grid-auto-flow: column;
  align-items: center;
  gap: var(--s-2);
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

.decide__who {
  margin: 0;
  color: var(--ink-2);
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

@media (max-width: 640px) {
  .bar {
    flex-direction: column;
    align-items: stretch;
  }

  .item {
    grid-template-columns: minmax(0, 1fr);
  }

  .item__side {
    justify-items: start;
  }
}
</style>
