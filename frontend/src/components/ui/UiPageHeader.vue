<script setup lang="ts">
/**
 * 页面报头：中文小标 + 拉丁短题 + 宋体大标题 + 导语，底部一道粗线收口。
 * 所有一级页面共用，保证「翻到新一章」的观感一致。
 */
withDefaults(
  defineProps<{
    label: string
    latin: string
    title: string
    lede?: string
  }>(),
  { lede: '' },
)
</script>

<template>
  <header class="ph">
    <div class="ph__row">
      <p class="ph__eyebrow eyebrow">
        {{ label }}<span class="ph__latin latin-italic">{{ latin }}</span>
      </p>
      <div v-if="$slots.actions" class="ph__actions"><slot name="actions" /></div>
    </div>
    <h1 class="ph__title">{{ title }}</h1>
    <p v-if="lede" class="ph__lede">{{ lede }}</p>
    <div v-if="$slots.meta" class="ph__meta"><slot name="meta" /></div>
  </header>
</template>

<style scoped>
.ph {
  padding-bottom: var(--s-4);
  margin-bottom: var(--s-5);
  border-bottom: 1px solid var(--ink);
}

.ph__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-4);
  min-height: 32px;
}

.ph__eyebrow {
  margin: 0;
}

.ph__latin {
  margin-left: var(--s-2);
  font-weight: 400;
  letter-spacing: 0;
  text-transform: none;
  color: var(--ink-2);
}

.ph__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2);
}

.ph__title {
  margin: var(--s-2) 0 0;
  font-family: var(--font-serif-cjk);
  font-size: var(--fs-h2);
  line-height: 1.1;
}

.ph__lede {
  max-width: 34em;
  margin: var(--s-3) 0 0;
  color: var(--ink-2);
  font-family: var(--font-serif);
  font-size: 17px;
  line-height: 1.6;
}

.ph__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-3);
  margin-top: var(--s-3);
  color: var(--ink-2);
  font-size: var(--fs-small);
}
</style>
