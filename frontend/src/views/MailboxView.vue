<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { feedbackApi } from '@/api/endpoints'
import type { FeedbackView } from '@/api/types'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { formatDateTime } from '@/lib/time'
import { FEEDBACK_CATEGORY_LABEL, FEEDBACK_STATUS_LABEL, feedbackTone } from '@/lib/labels'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiEmpty from '@/components/ui/UiEmpty.vue'
import UiField from '@/components/ui/UiField.vue'
import UiPageHeader from '@/components/ui/UiPageHeader.vue'
import UiSelect from '@/components/ui/UiSelect.vue'
import UiTag from '@/components/ui/UiTag.vue'
import UiTextarea from '@/components/ui/UiTextarea.vue'

/**
 * 校长信箱：全员提交并查看自己的投诉建议；处理反馈额外要求 ADMIN 角色。
 * 权限仍由后端接口最终校验，这里的角色判断只控制菜单与页签是否出现。
 */
const auth = useAuthStore()
const toast = useToastStore()
const route = useRoute()
const router = useRouter()

type TabKey = 'feedback' | 'handle'
const isAdmin = computed(() => !!auth.user?.roles.includes('ADMIN'))
const canHandle = computed(() => isAdmin.value && auth.can('feedback:handle'))
const tabs = computed(() => {
  const list: { key: TabKey; label: string }[] = []
  if (auth.can('feedback:submit')) list.push({ key: 'feedback', label: '投诉与建议' })
  if (canHandle.value) list.push({ key: 'handle', label: '处理反馈' })
  return list
})
const active = ref<TabKey>('feedback')

function pickTab() {
  const q = String(route.query.tab ?? '') as TabKey
  const next = tabs.value.some((t) => t.key === q) ? q : (tabs.value[0]?.key ?? 'feedback')
  // 非管理员直接访问 ?tab=handle 时把地址改回可见页签，避免地址与内容不一致
  if (next !== q) {
    void router.replace({ query: { ...route.query, tab: next } })
  }
  active.value = next
}

function go(key: TabKey) {
  active.value = key
  void router.replace({ query: { ...route.query, tab: key } })
}

/* ---------------- 投诉与建议 ---------------- */
const form = ref({ category: 'SUGGESTION', title: '', content: '', anonymous: false })
const formError = ref<{ title?: string | null; content?: string | null }>({})
const submitBusy = ref(false)
const myFeedbacks = ref<FeedbackView[]>([])
const myBusy = ref(false)
const categoryOptions = Object.entries(FEEDBACK_CATEGORY_LABEL).map(([value, label]) => ({ value, label }))

async function loadMine() {
  myBusy.value = true
  try {
    myFeedbacks.value = (await feedbackApi.mine({ size: 50 })).items
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    myBusy.value = false
  }
}

async function submitFeedback() {
  formError.value = {}
  if (!form.value.title.trim()) {
    formError.value = { title: '请写一个标题' }
    return
  }
  if (form.value.content.trim().length < 10) {
    formError.value = { content: '请把情况写清楚，至少 10 个字' }
    return
  }
  submitBusy.value = true
  try {
    await feedbackApi.create({
      category: form.value.category,
      title: form.value.title.trim(),
      content: form.value.content.trim(),
      anonymous: form.value.anonymous,
    })
    form.value = { category: 'SUGGESTION', title: '', content: '', anonymous: false }
    toast.push('已提交，校方会在处理后回复', 'success')
    await loadMine()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    submitBusy.value = false
  }
}

/* ---------------- 处理反馈（仅系统管理员） ---------------- */
const allFeedbacks = ref<FeedbackView[]>([])
const handleStatus = ref('OPEN')
const handleBusy = ref(false)
const replyTarget = ref<FeedbackView | null>(null)
const replyStatus = ref('RESOLVED')
const replyText = ref('')
const replyBusy = ref(false)
const statusOptions = [
  { value: '', label: '全部状态' },
  { value: 'OPEN', label: '待受理' },
  { value: 'PROCESSING', label: '处理中' },
  { value: 'RESOLVED', label: '已回复' },
  { value: 'CLOSED', label: '已关闭' },
]

async function loadAll() {
  if (!canHandle.value) return
  handleBusy.value = true
  try {
    const res = await feedbackApi.list({ size: 50, ...(handleStatus.value ? { status: handleStatus.value } : {}) })
    allFeedbacks.value = res.items
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    handleBusy.value = false
  }
}

function openReply(item: FeedbackView) {
  replyTarget.value = item
  replyStatus.value = item.status === 'OPEN' ? 'RESOLVED' : item.status
  replyText.value = item.reply ?? ''
}

async function submitReply() {
  if (!replyTarget.value) return
  if (!replyText.value.trim()) {
    toast.push('请填写回复内容', 'danger')
    return
  }
  replyBusy.value = true
  try {
    await feedbackApi.reply(replyTarget.value.id, {
      status: replyStatus.value,
      reply: replyText.value.trim(),
    })
    replyTarget.value = null
    toast.push('已回复', 'success')
    await loadAll()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    replyBusy.value = false
  }
}

async function load() {
  if (active.value === 'handle') await loadAll()
  else await loadMine()
}

watch(() => route.query.tab, pickTab, { immediate: true })
watch(active, () => void load(), { immediate: true })
watch(handleStatus, () => {
  if (active.value === 'handle') void loadAll()
})
</script>

<template>
  <div class="mailbox">
    <UiPageHeader
      label="校长信箱"
      latin="Mailbox"
      title="校长信箱"
      lede="把投诉与建议写下来，处理进度与校方回复都留在同一处。"
    />

    <div class="bar">
      <div class="tabs" role="tablist" aria-label="校长信箱分区">
        <button
          v-for="t in tabs"
          :key="t.key"
          type="button"
          class="tabs__btn"
          role="tab"
          :aria-selected="active === t.key"
          :class="{ 'is-active': active === t.key }"
          @click="go(t.key)"
        >
          {{ t.label }}
        </button>
      </div>
    </div>

    <template v-if="active === 'feedback'">
      <div class="compose">
        <h2 class="section-title">写下来</h2>
        <div class="compose__grid">
          <UiSelect v-model="form.category" label="分类" :options="categoryOptions" />
          <UiField v-model="form.title" label="标题" :maxlength="120" :error="formError.title" required />
        </div>
        <UiTextarea
          v-model="form.content"
          label="内容"
          :rows="5"
          :maxlength="2000"
          :error="formError.content"
          hint="写清时间、地点与具体情况，处理会更快。"
          placeholder="例如：三教二层东侧饮水机连续一周没有热水，希望能检修。"
        />
        <div class="compose__foot">
          <label class="check">
            <input v-model="form.anonymous" type="checkbox" />
            <span>匿名提交（处理人看不到你的姓名）</span>
          </label>
          <UiButton variant="primary" :loading="submitBusy" @click="submitFeedback">提交</UiButton>
        </div>
      </div>

      <h2 class="section-title section-title--split">我提交过的</h2>
      <p v-if="myBusy" class="state">正在取记录…</p>
      <UiEmpty v-else-if="myFeedbacks.length === 0" title="还没有提交过" hint="投诉与建议都会留档，处理结果在这里查看。" />
      <ul v-else class="feedback-list">
        <li v-for="f in myFeedbacks" :key="f.id" class="feedback">
          <div class="feedback__head">
            <p class="feedback__title">{{ f.title }}</p>
            <UiTag :tone="feedbackTone(f.status)">{{ FEEDBACK_STATUS_LABEL[f.status] }}</UiTag>
          </div>
          <p class="feedback__meta mono">
            {{ FEEDBACK_CATEGORY_LABEL[f.category] }} · {{ formatDateTime(f.createdAt) }}
            <template v-if="f.anonymous"> · 匿名</template>
          </p>
          <p class="feedback__content">{{ f.content }}</p>
          <div v-if="f.reply" class="reply">
            <p class="reply__label">校方回复<span v-if="f.handlerName"> · {{ f.handlerName }}</span></p>
            <p class="reply__text">{{ f.reply }}</p>
          </div>
        </li>
      </ul>
    </template>

    <template v-else-if="canHandle">
      <div class="handle-bar">
        <UiSelect v-model="handleStatus" label="状态" :options="statusOptions" class="handle-bar__select" />
        <span class="muted mono">{{ allFeedbacks.length }} 条</span>
      </div>
      <p v-if="handleBusy" class="state">正在取反馈…</p>
      <UiEmpty v-else-if="allFeedbacks.length === 0" title="没有待处理的反馈" hint="师生提交后会出现在这里。" />
      <ul v-else class="feedback-list">
        <li v-for="f in allFeedbacks" :key="f.id" class="feedback">
          <div class="feedback__head">
            <p class="feedback__title">{{ f.title }}</p>
            <div class="feedback__tags">
              <UiTag :tone="feedbackTone(f.status)">{{ FEEDBACK_STATUS_LABEL[f.status] }}</UiTag>
              <UiButton size="sm" @click="openReply(f)">{{ f.reply ? '补充回复' : '回复' }}</UiButton>
            </div>
          </div>
          <p class="feedback__meta mono">
            {{ FEEDBACK_CATEGORY_LABEL[f.category] }} · {{ formatDateTime(f.createdAt) }} ·
            {{ f.anonymous ? '匿名' : (f.submitterName ?? '未知提交人') }}
          </p>
          <p class="feedback__content">{{ f.content }}</p>
          <div v-if="f.reply" class="reply">
            <p class="reply__label">已回复<span v-if="f.handlerName"> · {{ f.handlerName }}</span></p>
            <p class="reply__text">{{ f.reply }}</p>
          </div>
        </li>
      </ul>
    </template>

    <UiDialog
      :open="!!replyTarget"
      title="回复这条反馈"
      :eyebrow="replyTarget ? FEEDBACK_CATEGORY_LABEL[replyTarget.category] : ''"
      width="md"
      :dismissible="!replyBusy"
      @close="replyTarget = null"
    >
      <div class="reply-form">
        <p class="reply-form__title">{{ replyTarget?.title }}</p>
        <p class="reply-form__content">{{ replyTarget?.content }}</p>
        <UiSelect
          v-model="replyStatus"
          label="处理结果"
          :options="[
            { value: 'PROCESSING', label: '处理中' },
            { value: 'RESOLVED', label: '已回复/已解决' },
            { value: 'CLOSED', label: '关闭' },
          ]"
        />
        <UiTextarea v-model="replyText" label="回复内容" :rows="4" :maxlength="1000" required />
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="replyBusy" @click="replyTarget = null">取消</UiButton>
        <UiButton variant="primary" :loading="replyBusy" @click="submitReply">提交回复</UiButton>
      </template>
    </UiDialog>
  </div>
</template>

<style scoped>
.mailbox {
  width: 100%;
  max-width: 980px;
  margin: 0 auto;
  padding: var(--s-5) clamp(var(--s-4), 4vw, var(--s-6)) var(--s-7);
}

.bar {
  padding-bottom: var(--s-2);
  margin-bottom: var(--s-4);
  border-bottom: 1px solid var(--ink);
}

.tabs {
  display: flex;
  gap: var(--s-4);
  flex-wrap: wrap;
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

.section-title {
  margin: 0 0 var(--s-3);
  font-family: var(--font-serif-cjk);
  font-size: var(--fs-h3);
  font-weight: 600;
}

.section-title--split {
  margin-top: var(--s-6);
  padding-top: var(--s-4);
  border-top: 1px solid var(--ink);
}

.compose {
  display: grid;
  gap: var(--s-3);
}

.compose__grid {
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr);
  gap: var(--s-3);
}

.compose__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
}

.check {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.feedback-list {
  margin: 0;
  padding: 0;
  list-style: none;
  border-top: var(--hairline);
}

.feedback {
  padding: var(--s-3) 0;
  border-bottom: var(--hairline);
}

.feedback__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-3);
}

.feedback__title {
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-size: 17px;
  font-weight: 600;
}

.feedback__tags {
  display: flex;
  align-items: center;
  gap: var(--s-2);
}

.feedback__meta {
  margin: 2px 0 0;
  color: var(--ink-2);
  font-size: 11px;
}

.feedback__content {
  margin: var(--s-2) 0 0;
  max-width: 62em;
  font-family: var(--font-serif);
  font-size: 15px;
  line-height: 1.75;
  white-space: pre-wrap;
}

.reply {
  margin-top: var(--s-3);
  padding: var(--s-2) var(--s-3);
  border-left: 2px solid var(--teal);
  background: var(--teal-tint);
}

.reply__label {
  margin: 0;
  color: var(--teal);
  font-size: 11px;
  letter-spacing: 0.08em;
}

.reply__text {
  margin: var(--s-1) 0 0;
  font-family: var(--font-serif);
  font-size: 15px;
  line-height: 1.7;
  white-space: pre-wrap;
}

.handle-bar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--s-3);
  margin-bottom: var(--s-3);
}

.handle-bar__select {
  max-width: 220px;
}

.reply-form {
  display: grid;
  gap: var(--s-3);
}

.reply-form__title {
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-size: 17px;
}

.reply-form__content {
  margin: 0;
  padding: var(--s-2) var(--s-3);
  border-left: 2px solid var(--rule-strong);
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.7;
  white-space: pre-wrap;
}

.muted,
.state {
  color: var(--ink-2);
}

.state {
  margin: 0;
  padding: var(--s-5) 0;
  font-family: var(--font-serif);
  font-size: var(--fs-read);
}

@media (max-width: 760px) {
  .mailbox {
    padding: var(--s-4) 16px var(--s-6);
  }

  .bar {
    padding-bottom: 6px;
    margin-bottom: 10px;
  }

  .tabs {
    flex-wrap: nowrap;
    overflow-x: auto;
    gap: 14px;
    scrollbar-width: none;
  }

  .tabs::-webkit-scrollbar {
    display: none;
  }

  .tabs__btn {
    flex: 0 0 auto;
    font-size: 15px;
  }

  .compose__grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .compose__foot {
    align-items: flex-start;
  }
}
</style>
