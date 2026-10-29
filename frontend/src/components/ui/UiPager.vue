<script setup lang="ts">
import { computed } from 'vue'
import UiButton from './UiButton.vue'

/** 分页：只给上一页/下一页，页码用「第 x / y 页」表述，像书的页码而不是后台表格。 */
const props = withDefaults(
  defineProps<{ page: number; pages: number; total: number; disabled?: boolean }>(),
  { disabled: false },
)
const emit = defineEmits<{ change: [page: number] }>()

const canPrev = computed(() => props.page > 1 && !props.disabled)
const canNext = computed(() => props.page < props.pages && !props.disabled)

function go(p: number) {
  if (p < 1 || p > props.pages || p === props.page || props.disabled) return
  emit('change', p)
}
</script>

<template>
  <nav class="pager" aria-label="分页">
    <p class="pager__count">
      共 <span class="mono">{{ total }}</span> 条 · 第 <span class="mono">{{ page }}</span> /
      <span class="mono">{{ Math.max(pages, 1) }}</span> 页
    </p>
    <div class="pager__btns">
      <UiButton size="sm" :disabled="!canPrev" @click="go(page - 1)">上一页</UiButton>
      <UiButton size="sm" :disabled="!canNext" @click="go(page + 1)">下一页</UiButton>
    </div>
  </nav>
</template>

<style scoped>
.pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-4);
  padding-top: var(--s-4);
  margin-top: var(--s-4);
  border-top: 1px solid var(--rule);
}

.pager__count {
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.pager__btns {
  display: flex;
  gap: var(--s-2);
}
</style>
