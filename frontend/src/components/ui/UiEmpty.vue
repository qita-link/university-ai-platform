<script setup lang="ts">
/**
 * 空态：书页留白的写法——一句校准过的说明 + 一个可选的下一步，
 * 不放插画、不放示例假数据。
 */
withDefaults(defineProps<{ title: string; hint?: string; compact?: boolean }>(), {
  hint: '',
  compact: false,
})
</script>

<template>
  <div class="empty" :class="{ 'empty--compact': compact }">
    <p class="empty__mark latin-italic" aria-hidden="true">—</p>
    <p class="empty__title">{{ title }}</p>
    <p v-if="hint" class="empty__hint">{{ hint }}</p>
    <div v-if="$slots.default" class="empty__actions">
      <slot />
    </div>
    <slot name="action" />
  </div>
</template>

<style scoped>
.empty {
  padding: var(--s-6) var(--s-4);
  border-top: 1px solid var(--rule);
  border-bottom: 1px solid var(--rule);
  text-align: center;
}

.empty__mark {
  margin: 0;
  color: var(--rule-strong);
  font-size: 24px;
  line-height: 1;
}

.empty__title {
  margin: var(--s-2) 0 0;
  font-family: var(--font-serif-cjk);
  font-size: 20px;
  font-weight: 600;
}

.empty__hint {
  max-width: 30em;
  margin: var(--s-2) auto 0;
  color: var(--ink-2);
  font-size: var(--fs-ui);
}

.empty__actions {
  display: flex;
  justify-content: center;
  gap: var(--s-2);
  margin-top: var(--s-4);
}

/* 嵌在章节里的空态：压低留白，不打断页面的纵向节奏 */
.empty--compact {
  padding: var(--s-4) var(--s-4);
}

.empty--compact .empty__mark {
  display: none;
}

.empty--compact .empty__title {
  margin-top: 0;
  font-size: 16px;
  font-weight: 400;
  color: var(--ink-2);
}
</style>
