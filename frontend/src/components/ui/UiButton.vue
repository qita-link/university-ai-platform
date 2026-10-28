<script setup lang="ts">
/**
 * 按钮：零圆角，1px 细线。
 * primary —— 烫印深青实底；quiet —— 细线描边；danger —— 赭红描边（驳回、删除）；link —— 文字按钮。
 */
withDefaults(
  defineProps<{
    variant?: 'primary' | 'quiet' | 'danger' | 'link'
    type?: 'button' | 'submit'
    loading?: boolean
    disabled?: boolean
    block?: boolean
    size?: 'md' | 'sm'
  }>(),
  { variant: 'quiet', type: 'button', loading: false, disabled: false, block: false, size: 'md' },
)
</script>

<template>
  <button
    :type="type"
    class="ui-btn"
    :class="[`ui-btn--${variant}`, `ui-btn--${size}`, { 'ui-btn--block': block, 'is-loading': loading }]"
    :disabled="disabled || loading"
    :aria-busy="loading || undefined"
  >
    <span class="ui-btn__label"><slot /></span>
    <span v-if="loading" class="ui-btn__dots" aria-hidden="true"><i /><i /><i /></span>
  </button>
</template>

<style scoped>
.ui-btn {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--s-2);
  min-height: 40px;
  padding: 0 var(--s-4);
  border: 1px solid var(--ink);
  background: transparent;
  color: var(--ink);
  font-family: var(--font-sans);
  font-size: var(--fs-ui);
  font-weight: 500;
  letter-spacing: 0.04em;
  cursor: pointer;
  transition:
    background-color var(--t-fast) var(--ease),
    color var(--t-fast) var(--ease),
    border-color var(--t-fast) var(--ease),
    transform var(--t-fast) var(--ease);
}

.ui-btn--sm {
  min-height: 32px;
  padding: 0 var(--s-3);
  font-size: var(--fs-small);
}

.ui-btn--block {
  display: flex;
  width: 100%;
}

.ui-btn:active:not(:disabled) {
  transform: translateY(1px);
}

.ui-btn:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.ui-btn.is-loading {
  cursor: progress;
  opacity: 1;
}

.ui-btn--primary {
  border-color: var(--teal);
  background: var(--teal);
  color: var(--paper);
}

.ui-btn--primary:hover:not(:disabled) {
  border-color: var(--teal-press);
  background: var(--teal-press);
}

.ui-btn--quiet {
  border-color: var(--rule-strong);
}

.ui-btn--quiet:hover:not(:disabled) {
  border-color: var(--ink);
  background: var(--paper-lift);
}

.ui-btn--danger {
  border-color: var(--ochre);
  color: var(--ochre);
}

.ui-btn--danger:hover:not(:disabled) {
  background: var(--ochre);
  color: var(--paper);
}

.ui-btn--link {
  min-height: auto;
  padding: 0;
  border: 0;
  color: var(--teal);
  text-decoration: underline;
  text-decoration-thickness: 1px;
  text-underline-offset: 3px;
}

.ui-btn--link:hover:not(:disabled) {
  text-decoration-thickness: 2px;
}

.is-loading .ui-btn__label {
  opacity: 0.6;
}

.ui-btn__dots {
  display: inline-flex;
  gap: 3px;
}

.ui-btn__dots i {
  width: 4px;
  height: 4px;
  background: currentColor;
  animation: ui-btn-dot 1s var(--ease) infinite;
}

.ui-btn__dots i:nth-child(2) {
  animation-delay: 0.15s;
}

.ui-btn__dots i:nth-child(3) {
  animation-delay: 0.3s;
}

@keyframes ui-btn-dot {
  0%,
  100% {
    opacity: 0.25;
  }
  50% {
    opacity: 1;
  }
}
</style>
