<script setup lang="ts">
import { useToastStore } from '@/stores/toast'

const toasts = useToastStore()
</script>

<template>
  <div class="toaster" role="status" aria-live="polite">
    <TransitionGroup name="toast">
      <div v-for="t in toasts.items" :key="t.id" class="toast" :class="`toast--${t.tone}`">
        <p class="toast__text">{{ t.text }}</p>
        <p v-if="t.traceId" class="toast__trace mono">traceId {{ t.traceId }}</p>
        <button type="button" class="toast__close" aria-label="关闭提示" @click="toasts.dismiss(t.id)">×</button>
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.toaster {
  position: fixed;
  right: var(--s-4);
  bottom: var(--s-4);
  z-index: 100;
  display: grid;
  gap: var(--s-2);
  width: min(360px, calc(100vw - 2 * var(--s-3)));
}

.toast {
  position: relative;
  padding: var(--s-3) var(--s-5) var(--s-3) var(--s-3);
  border: 1px solid var(--ink);
  background: var(--paper-lift);
  box-shadow: 4px 4px 0 var(--paper-deep);
}

.toast--success {
  border-color: var(--success);
}

.toast--danger {
  border-color: var(--ochre);
}

.toast__text {
  margin: 0;
}

.toast--danger .toast__text {
  color: var(--ochre);
}

.toast__trace {
  margin: var(--s-1) 0 0;
  color: var(--ink-2);
  font-size: 11px;
  user-select: all;
}

.toast__close {
  position: absolute;
  top: var(--s-2);
  right: var(--s-2);
  width: 24px;
  height: 24px;
  border: 0;
  background: none;
  color: var(--ink-2);
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
}

.toast-enter-active,
.toast-leave-active {
  transition:
    opacity var(--t-mid) var(--ease),
    transform var(--t-mid) var(--ease);
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(8px);
}
</style>
