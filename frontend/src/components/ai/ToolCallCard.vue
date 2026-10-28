<script setup lang="ts">
import { computed, ref } from 'vue'
import UiButton from '@/components/ui/UiButton.vue'
import type { AiToolCallView } from '@/api/types'
import { aiApi } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import { errorMessage } from '@/lib/errors'
import { formatCountdown, formatDateTime, parseServerTime } from '@/lib/time'
import { useClockStore } from '@/stores/clock'

/**
 * 写操作工具的“预览 → 确认”卡片。
 * - 倒计时以后端 confirmExpiresAt 为准（业务时区本地时间，按 +08:00 解析），并用服务器时钟差校正；
 * - 本地倒计时归零后不再允许确认；后端仍是最终裁判，409 AI_TOOL_CONFIRM_EXPIRED 时同样置为已超时；
 * - 409 AI_TOOL_STATUS_INVALID（其他窗口已处理）时重新拉取状态。
 */
const props = defineProps<{ call: AiToolCallView; title: string }>()
const emit = defineEmits<{ update: [call: AiToolCallView]; dismiss: [id: number] }>()

const clock = useClockStore()
const busy = ref<'confirm' | 'reject' | null>(null)
const error = ref<string | null>(null)
const rejecting = ref(false)
const reason = ref('')

const expiresAt = computed(() => parseServerTime(props.call.confirmExpiresAt))
const createdAt = computed(() => parseServerTime(props.call.createdAt))
const remaining = computed(() => (Number.isFinite(expiresAt.value) ? expiresAt.value - clock.now : 0))
const total = computed(() => Math.max(1, expiresAt.value - createdAt.value))
const fraction = computed(() => Math.min(1, Math.max(0, remaining.value / total.value)))

const isPending = computed(() => props.call.status === 'PENDING_CONFIRM')
const locallyExpired = computed(() => isPending.value && remaining.value <= 0)
const urgent = computed(() => isPending.value && remaining.value > 0 && remaining.value <= 60_000)

async function confirm() {
  if (locallyExpired.value) return
  busy.value = 'confirm'
  error.value = null
  try {
    emit('update', await aiApi.confirmTool(props.call.id))
  } catch (e) {
    await handleError(e)
  } finally {
    busy.value = null
  }
}

async function reject() {
  busy.value = 'reject'
  error.value = null
  try {
    emit('update', await aiApi.rejectTool(props.call.id, reason.value.trim() || undefined))
    rejecting.value = false
  } catch (e) {
    await handleError(e)
  } finally {
    busy.value = null
  }
}

async function handleError(e: unknown) {
  if (e instanceof ApiError && e.code === 'AI_TOOL_CONFIRM_EXPIRED') {
    emit('update', { ...props.call, status: 'EXPIRED' })
    return
  }
  // 状态已变（并发确认、其他窗口处理）或业务失败（如名额已满）：以服务端记录为准
  if (e instanceof ApiError && (e.status === 409 || e.status === 422)) {
    try {
      emit('update', await aiApi.toolCall(props.call.id))
    } catch {
      /* 拉取失败时保留原状态 */
    }
  }
  error.value = errorMessage(e)
}
</script>

<template>
  <section
    class="slip"
    :class="{ 'is-urgent': urgent, 'is-expired': locallyExpired }"
    :aria-label="`待确认操作：${title}`"
  >
    <header class="slip__head">
      <p class="slip__kind eyebrow">待确认 · {{ title }}</p>
      <p class="slip__timer mono" :aria-live="urgent ? 'polite' : 'off'">
        <template v-if="locallyExpired">已超时</template>
        <template v-else>
          <span class="visually-hidden">剩余</span>{{ formatCountdown(remaining) }}
        </template>
      </p>
    </header>

    <!-- 剩余时间：像墨线一样逐渐收短 -->
    <div class="slip__rule" aria-hidden="true">
      <span :style="{ transform: `scaleX(${fraction})` }" />
    </div>

    <p class="slip__preview">{{ call.preview ?? '（后端未提供预览）' }}</p>
    <p v-if="call.argumentSummary" class="slip__args mono">{{ call.argumentSummary }}</p>
    <p class="slip__deadline">请在 {{ formatDateTime(call.confirmExpiresAt) }} 前确认；确认前不会执行任何操作。</p>

    <p v-if="error" class="slip__error" role="alert">{{ error }}</p>

    <div v-if="locallyExpired" class="slip__actions">
      <p class="slip__expired">确认期已过，这次操作没有执行。</p>
      <UiButton size="sm" @click="emit('dismiss', call.id)">移除</UiButton>
    </div>

    <div v-else-if="rejecting" class="slip__reject">
      <label class="slip__reason">
        <span>拒绝原因（可选）</span>
        <input v-model="reason" type="text" maxlength="200" />
      </label>
      <div class="slip__actions">
        <UiButton size="sm" variant="link" :disabled="!!busy" @click="rejecting = false">返回</UiButton>
        <UiButton size="sm" variant="danger" :loading="busy === 'reject'" @click="reject">确认拒绝</UiButton>
      </div>
    </div>

    <div v-else class="slip__actions">
      <UiButton size="sm" variant="quiet" :disabled="!!busy" @click="rejecting = true">不执行</UiButton>
      <UiButton size="sm" variant="primary" :loading="busy === 'confirm'" :disabled="!!busy" @click="confirm">
        确认执行
      </UiButton>
    </div>
  </section>
</template>

<style scoped>
.slip {
  padding: var(--s-3) var(--s-3) var(--s-3);
  border: 1px solid var(--ink);
  background: var(--paper-lift);
}

.slip.is-expired {
  border-color: var(--rule-strong);
  background: transparent;
}

.slip__head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: var(--s-3);
}

.slip__kind {
  margin: 0;
  color: var(--teal);
}

.is-expired .slip__kind {
  color: var(--ink-2);
}

.slip__timer {
  margin: 0;
  font-size: 15px;
  font-variant-numeric: tabular-nums;
}

.is-urgent .slip__timer {
  color: var(--ochre);
}

.slip__rule {
  height: 1px;
  margin: var(--s-2) 0 var(--s-3);
  background: var(--rule);
}

.slip__rule span {
  display: block;
  height: 100%;
  background: var(--teal);
  transform-origin: left;
  transition: transform 1s linear;
}

.is-urgent .slip__rule span {
  background: var(--ochre);
}

.slip__preview {
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-size: 16px;
  line-height: 1.6;
}

.slip__args {
  margin: var(--s-1) 0 0;
  font-size: 12px;
  color: var(--ink-2);
  overflow-wrap: anywhere;
}

.slip__deadline {
  margin: var(--s-2) 0 0;
  font-size: var(--fs-small);
  color: var(--ink-2);
}

.slip__error {
  margin: var(--s-2) 0 0;
  color: var(--ochre);
}

.slip__expired {
  flex: 1;
  margin: 0;
  color: var(--ink-2);
}

.slip__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--s-2);
  margin-top: var(--s-3);
}

.slip__reject {
  margin-top: var(--s-3);
}

.slip__reason {
  display: grid;
  gap: var(--s-1);
  font-size: var(--fs-small);
  color: var(--ink-2);
}

.slip__reason input {
  height: 36px;
  border: 0;
  border-bottom: 1px solid var(--rule-strong);
  background: transparent;
  color: var(--ink);
  font-size: 15px;
}

.slip__reason input:focus {
  outline: none;
  border-bottom-color: var(--teal);
  box-shadow: 0 1px 0 0 var(--teal);
}
</style>
