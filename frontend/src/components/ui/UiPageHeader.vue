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
    /** 手机端把操作按钮并到标题同一行，省掉一整行高度 */
    compact?: boolean
  }>(),
  { lede: '', compact: false },
)
</script>

<template>
  <header class="ph" :class="{ 'ph--compact': compact }">
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

@media (max-width: 640px) {
  .ph {
    padding-bottom: 10px;
    margin-bottom: 14px;
  }

  /* 小标隐藏后只剩按钮，靠右才像正常的页头操作；小标在时用 auto 撑到左侧 */
  .ph__row {
    justify-content: flex-end;
    min-height: 0;
    align-items: flex-start;
    gap: var(--s-2);
  }

  .ph__eyebrow {
    margin-right: auto;
  }

  .ph__latin {
    display: none;
  }

  .ph__eyebrow {
    font-size: 11px;
  }

  .ph__title {
    margin-top: 2px;
    font-size: 25px;
  }

  /* 导语在手机上占掉半屏，第一眼看不到内容，直接收起 */
  .ph__lede {
    display: none;
  }

  .ph__meta {
    margin-top: 6px;
    gap: var(--s-2);
    font-size: 11px;
  }

  .ph__actions {
    gap: 6px;
  }

  .ph__actions :deep(.ui-btn) {
    min-height: 32px;
    padding: 0 10px;
    font-size: 13px;
  }

  /* 标题与操作同一行：书架「新建空间」、来信「写一封」不再单独占一行 */
  .ph--compact {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: center;
    column-gap: 8px;
  }

  .ph--compact .ph__row {
    grid-column: 2;
    grid-row: 1;
  }

  /* 标题已经说明是哪个页面，小标在紧凑模式里只会和按钮挤在一起 */
  .ph--compact .ph__eyebrow {
    display: none;
  }

  .ph--compact .ph__title {
    grid-column: 1;
    grid-row: 1;
    margin: 0;
  }

  .ph--compact .ph__meta {
    grid-column: 1 / -1;
  }
}
</style>
