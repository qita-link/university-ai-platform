<script setup lang="ts">
import { computed, ref } from 'vue'
import type { AiToolCallView } from '@/api/types'
import { formatDateTime } from '@/lib/time'

/** 已结束的工具调用：在对话流中以“回执”形式记录（成功、失败、拒绝、超时）。 */
const props = defineProps<{ call: AiToolCallView; title: string }>()
const showRaw = ref(false)

const STATUS: Record<string, { text: string; tone: 'ok' | 'bad' | 'muted' }> = {
  SUCCEEDED: { text: '已完成', tone: 'ok' },
  FAILED: { text: '未成功', tone: 'bad' },
  REJECTED: { text: '已拒绝', tone: 'muted' },
  EXPIRED: { text: '已超时，未执行', tone: 'muted' },
  EXECUTING: { text: '执行中', tone: 'muted' },
}
const status = computed(() => STATUS[props.call.status] ?? { text: props.call.status, tone: 'muted' as const })
const hasResult = computed(() => props.call.result !== null && props.call.result !== undefined)
const resultJson = computed(() => JSON.stringify(props.call.result, null, 2))
</script>

<template>
  <article class="receipt fade-in" :class="`receipt--${status.tone}`">
    <header class="receipt__head">
      <span class="eyebrow">{{ call.sideEffect ? '办理' : '查询' }} · {{ title }}</span>
      <span class="receipt__status">{{ status.text }}</span>
    </header>
    <p v-if="call.preview" class="receipt__line">{{ call.preview }}</p>
    <p v-if="call.resultSummary" class="receipt__line">{{ call.resultSummary }}</p>
    <p v-if="call.errorMessage" class="receipt__line receipt__line--err">
      {{ call.errorMessage }} <span v-if="call.errorCode" class="mono">{{ call.errorCode }}</span>
    </p>
    <p class="receipt__meta mono">
      #{{ call.id }} · {{ formatDateTime(call.executedAt ?? call.createdAt) }}
      <template v-if="call.latencyMs !== null"> · {{ call.latencyMs }}ms</template>
    </p>
    <template v-if="hasResult">
      <button type="button" class="receipt__toggle" :aria-expanded="showRaw" @click="showRaw = !showRaw">
        {{ showRaw ? '收起返回数据' : '查看返回数据' }}
      </button>
      <pre v-if="showRaw" class="receipt__raw">{{ resultJson }}</pre>
    </template>
  </article>
</template>

<style scoped>
.receipt {
  max-width: var(--measure);
  padding: var(--s-2) 0 var(--s-2) var(--s-3);
  border-left: 1px solid var(--rule-strong);
  font-size: var(--fs-ui);
}

.receipt__head {
  display: flex;
  gap: var(--s-3);
  align-items: baseline;
}

.receipt__status {
  font-size: var(--fs-small);
  font-weight: 600;
}

.receipt--ok .receipt__status {
  color: var(--success);
}

.receipt--bad .receipt__status {
  color: var(--ochre);
}

.receipt--muted .receipt__status {
  color: var(--ink-2);
}

.receipt__line {
  margin: var(--s-1) 0 0;
}

.receipt__line--err {
  color: var(--ochre);
}

.receipt__meta {
  margin: var(--s-1) 0 0;
  font-size: 11px;
  color: var(--ink-2);
}

.receipt__toggle {
  margin-top: var(--s-1);
  padding: 0;
  border: 0;
  background: none;
  color: var(--teal);
  font-size: var(--fs-small);
  text-decoration: underline;
  text-underline-offset: 3px;
  cursor: pointer;
}

.receipt__raw {
  max-height: 280px;
  margin: var(--s-2) 0 0;
  padding: var(--s-2);
  overflow: auto;
  background: var(--paper-deep);
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.5;
}
</style>
