<script setup lang="ts">
import { computed, ref } from 'vue'
import { aiApi } from '@/api/endpoints'
import type { AgentActResponse, AgentStep, ToolCallStatus } from '@/api/types'
import { errorMessage } from '@/lib/errors'
import { renderMarkdown } from '@/lib/markdown'
import { formatDateTime } from '@/lib/time'
import UiButton from '@/components/ui/UiButton.vue'
import UiTag from '@/components/ui/UiTag.vue'

/**
 * 一次“办事”的结果：经过 → 答复 → 确认单。
 * 与问答共用同一条消息流，不再单独占页面；写操作仍然必须先点确认才落库。
 */
const props = defineProps<{ response: AgentActResponse }>()

const pendingStatus = ref<ToolCallStatus | null>(props.response.pending?.status ?? null)
const pendingSummary = ref<string | null>(null)
const confirming = ref(false)
const rejecting = ref(false)
const actionError = ref<string | null>(null)
const showAllSteps = ref(false)

const steps = computed(() => props.response.steps)
const visibleSteps = computed(() => (showAllSteps.value ? steps.value : steps.value.slice(0, 2)))
const hasMoreSteps = computed(() => steps.value.length > 2)

const replyHtml = computed(() => renderMarkdown(props.response.reply ?? ''))
const pending = computed(() => props.response.pending)

const outcomeNote = computed(() => {
  if (props.response.outcome === 'MAX_ROUNDS') {
    return '这件事需要多步操作，先停在这里；可以拆成更具体的一句一句来。'
  }
  if (props.response.outcome === 'RAW_TEXT') {
    return '这次没有生成可执行的指令，下面只是原话，没有任何动作被执行。'
  }
  return ''
})

function stepTone(step: AgentStep): 'ochre' | 'warning' | 'teal' {
  if (step.status === 'FAILED') return 'ochre'
  if (step.status === 'PENDING_CONFIRM') return 'warning'
  return step.sideEffect ? 'warning' : 'teal'
}

function stepLabel(step: AgentStep): string {
  if (step.status === 'FAILED') return '失败'
  if (step.status === 'PENDING_CONFIRM') return '待确认'
  return step.sideEffect ? '已提交确认' : '已查'
}

async function confirmPending() {
  if (!pending.value) return
  confirming.value = true
  actionError.value = null
  try {
    const updated = await aiApi.confirmTool(pending.value.id)
    pendingStatus.value = updated.status
    pendingSummary.value = updated.resultSummary
  } catch (e) {
    actionError.value = errorMessage(e)
    try {
      const fresh = await aiApi.toolCall(pending.value.id)
      pendingStatus.value = fresh.status
      pendingSummary.value = fresh.resultSummary
    } catch {
      /* 保持原状态 */
    }
  } finally {
    confirming.value = false
  }
}

async function rejectPending() {
  if (!pending.value) return
  rejecting.value = true
  actionError.value = null
  try {
    const updated = await aiApi.rejectTool(pending.value.id, '用户放弃执行')
    pendingStatus.value = updated.status
  } catch (e) {
    actionError.value = errorMessage(e)
  } finally {
    rejecting.value = false
  }
}

function pendingLabel(): string {
  if (pendingStatus.value === 'PENDING_CONFIRM') return '待你确认'
  if (pendingStatus.value === 'SUCCEEDED') return '已执行'
  if (pendingStatus.value === 'REJECTED') return '已放弃'
  if (pendingStatus.value === 'EXPIRED') return '已过期'
  if (pendingStatus.value === 'FAILED') return '执行失败'
  return '未生效'
}
</script>

<template>
  <article class="act fade-in">
    <div class="act__body">
      <p class="act__label eyebrow" aria-hidden="true">办</p>

      <ol v-if="visibleSteps.length" class="steps">
        <li v-for="step in visibleSteps" :key="`${step.roundNo}-${step.toolName}`" class="step">
          <div class="step__main">
            <p class="step__title">
              {{ step.title }}
              <span class="step__tool mono">{{ step.toolName }}</span>
            </p>
            <p v-if="step.summary" class="step__note">{{ step.summary }}</p>
            <p v-else-if="step.errorMessage" class="step__err">{{ step.errorMessage }}</p>
          </div>
          <UiTag :tone="stepTone(step)">{{ stepLabel(step) }}</UiTag>
        </li>
      </ol>
      <button v-if="hasMoreSteps" type="button" class="steps__more" @click="showAllSteps = !showAllSteps">
        {{ showAllSteps ? '收起经过' : `展开全部 ${steps.length} 步` }}
      </button>

      <div v-if="replyHtml" class="prose act__reply" v-html="replyHtml" />
      <p v-if="outcomeNote" class="act__note">{{ outcomeNote }}</p>

      <section v-if="pending" class="chit" :class="{ 'is-settled': pendingStatus !== 'PENDING_CONFIRM' }">
        <div class="chit__head">
          <p class="chit__label">确认单 · <span class="mono">{{ pending.toolName }}</span></p>
          <UiTag :tone="pendingStatus === 'PENDING_CONFIRM' ? 'warning' : 'success'">{{ pendingLabel() }}</UiTag>
        </div>
        <p class="chit__preview">{{ pending.preview }}</p>
        <p v-if="pendingStatus === 'PENDING_CONFIRM' && pending.confirmExpiresAt" class="chit__ttl mono">
          请在 {{ formatDateTime(pending.confirmExpiresAt) }} 前确认
        </p>
        <div v-if="pendingStatus === 'PENDING_CONFIRM'" class="chit__actions">
          <UiButton variant="primary" size="sm" :loading="confirming" @click="confirmPending">确认执行</UiButton>
          <UiButton size="sm" :disabled="confirming || rejecting" :loading="rejecting" @click="rejectPending">
            放弃
          </UiButton>
        </div>
        <p v-else-if="pendingStatus === 'SUCCEEDED'" class="chit__done">
          已执行{{ pendingSummary ? `：${pendingSummary}` : '。' }}
        </p>
        <p v-else-if="pendingStatus === 'REJECTED'" class="chit__done">已放弃，没有改动任何数据。</p>
        <p v-if="actionError" class="chit__err" role="alert">{{ actionError }}</p>
      </section>
    </div>
  </article>
</template>

<style scoped>
.act {
  container-type: inline-size;
}

.act__body {
  position: relative;
  min-width: 0;
  max-width: 100%;
}

.act__label {
  position: absolute;
  left: -40px;
  top: 6px;
  margin: 0;
  color: var(--ochre);
}

.steps {
  margin: 0 0 var(--s-3);
  padding: 0;
  list-style: none;
  border-left: 1px solid var(--rule-strong);
}

.step {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s-3);
  padding: 0 0 var(--s-2) var(--s-3);
}

.step__main {
  min-width: 0;
}

.step__title {
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-size: 14px;
}

.step__tool {
  margin-left: var(--s-2);
  color: var(--ink-2);
  font-size: 10px;
}

.step__note,
.step__err {
  margin: 2px 0 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.6;
}

.step__err {
  color: var(--ochre);
}

.steps__more {
  margin: 0 0 var(--s-3);
  padding: 0;
  border: 0;
  background: none;
  color: var(--teal);
  font-size: var(--fs-small);
  text-decoration: underline;
  text-underline-offset: 3px;
  cursor: pointer;
}

.act__reply {
  font-family: var(--font-serif-cjk);
  font-size: var(--fs-read);
  line-height: var(--lh-read);
}

.act__reply :deep(> :first-child) {
  margin-top: 0;
}

.act__reply :deep(> :last-child) {
  margin-bottom: 0;
}

.act__note {
  margin: var(--s-2) 0 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.7;
}

.chit {
  margin-top: var(--s-3);
  padding: var(--s-3);
  border: 1px solid var(--ink);
  background: var(--paper-lift);
}

.chit.is-settled {
  border-color: var(--rule);
}

.chit__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-2);
}

.chit__label {
  margin: 0;
  color: var(--teal);
  font-size: 11px;
  letter-spacing: 0.08em;
}

.chit__preview {
  margin: var(--s-2) 0 0;
  font-family: var(--font-serif-cjk);
  font-size: 15px;
  line-height: 1.7;
}

.chit__ttl {
  margin: var(--s-2) 0 0;
  color: var(--ink-2);
  font-size: 11px;
}

.chit__actions {
  display: flex;
  gap: var(--s-2);
  margin-top: var(--s-3);
}

.chit__actions :deep(.ui-btn) {
  flex: 1;
}

.chit__done {
  margin: var(--s-2) 0 0;
  color: var(--success);
  font-size: var(--fs-small);
}

.chit__err {
  margin: var(--s-2) 0 0;
  color: var(--ochre);
  font-size: var(--fs-small);
}

@container (max-width: 600px) {
  .act__label {
    position: static;
    margin-bottom: var(--s-1);
  }
}
</style>
