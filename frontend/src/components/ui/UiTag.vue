<script setup lang="ts">
/**
 * 状态标签：小型大写、细线描边、零圆角。
 * tone 只映射到既有语义色，不引入新色相。
 */
withDefaults(
  defineProps<{
    tone?: 'neutral' | 'teal' | 'ochre' | 'success' | 'warning' | 'muted'
    /** 未加修饰时的说明，供屏幕阅读器使用 */
    label?: string
  }>(),
  { tone: 'neutral', label: '' },
)
</script>

<template>
  <span class="tag" :class="`tag--${tone}`" :aria-label="label || undefined">
    <slot />
  </span>
</template>

<style scoped>
.tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 1px 6px;
  border: 1px solid var(--rule-strong);
  color: var(--ink-2);
  font-family: var(--font-sans);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.08em;
  line-height: 1.5;
  white-space: nowrap;
}

.tag--teal {
  border-color: var(--teal);
  color: var(--teal);
}

.tag--ochre {
  border-color: var(--ochre);
  color: var(--ochre);
}

.tag--success {
  border-color: var(--success);
  color: var(--success);
}

.tag--warning {
  border-color: var(--warning);
  color: var(--warning);
}

.tag--muted {
  border-color: var(--rule);
  color: var(--ink-2);
  opacity: 0.75;
}
</style>
