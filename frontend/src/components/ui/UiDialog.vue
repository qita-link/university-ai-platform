<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'

/**
 * 对话框：基于原生 <dialog>，自带焦点约束、Esc 关闭和顶层渲染。
 * 不支持点击遮罩关闭，避免误触丢掉正在填写或待确认的内容。
 */
const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    eyebrow?: string
    width?: 'sm' | 'md' | 'lg'
    /** 为 false 时隐藏右上角关闭按钮并忽略 Esc（例如请求进行中） */
    dismissible?: boolean
  }>(),
  { eyebrow: '', width: 'md', dismissible: true },
)
const emit = defineEmits<{ close: [] }>()

const el = ref<HTMLDialogElement | null>(null)

watch(
  () => props.open,
  (open) => {
    const d = el.value
    if (!d) return
    if (open && !d.open) d.showModal()
    if (!open && d.open) d.close()
  },
  { flush: 'post' },
)

function onCancel(e: Event) {
  e.preventDefault()
  if (props.dismissible) emit('close')
}

onBeforeUnmount(() => {
  if (el.value?.open) el.value.close()
})
</script>

<template>
  <dialog
    ref="el"
    class="ui-dialog"
    :class="`ui-dialog--${width}`"
    aria-labelledby="ui-dialog-title"
    @cancel="onCancel"
  >
    <template v-if="open">
      <header class="ui-dialog__head">
        <div>
          <p v-if="eyebrow" class="eyebrow">{{ eyebrow }}</p>
          <h2 id="ui-dialog-title" class="ui-dialog__title">{{ title }}</h2>
        </div>
        <button v-if="dismissible" type="button" class="ui-dialog__close" aria-label="关闭" @click="emit('close')">
          ×
        </button>
      </header>
      <div class="ui-dialog__body">
        <slot />
      </div>
      <footer v-if="$slots.footer" class="ui-dialog__foot">
        <slot name="footer" />
      </footer>
    </template>
  </dialog>
</template>

<style scoped>
.ui-dialog {
  width: min(100vw - 2 * var(--s-3), 560px);
  max-height: min(100vh - 2 * var(--s-5), 760px);
  padding: 0;
  border: 1px solid var(--ink);
  background: var(--paper);
  color: var(--ink);
  box-shadow: 8px 8px 0 var(--paper-deep);
}

.ui-dialog[open] {
  display: flex;
  flex-direction: column;
  animation: fade-in var(--t-mid) var(--ease) both;
}

.ui-dialog--sm {
  width: min(100vw - 2 * var(--s-3), 420px);
}

.ui-dialog--lg {
  width: min(100vw - 2 * var(--s-3), 720px);
}

.ui-dialog::backdrop {
  background: rgba(26, 26, 24, 0.32);
}

.ui-dialog__head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: var(--s-3);
  padding: var(--s-4) var(--s-4) var(--s-3);
  border-bottom: 1px solid var(--ink);
}

.ui-dialog__title {
  margin-top: 2px;
  font-size: var(--fs-h3);
}

.ui-dialog__close {
  width: 32px;
  height: 32px;
  margin: -4px -8px 0 0;
  border: 0;
  background: none;
  color: var(--ink-2);
  font-size: 22px;
  line-height: 1;
  cursor: pointer;
}

.ui-dialog__close:hover {
  color: var(--ink);
}

.ui-dialog__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--s-4);
}

.ui-dialog__foot {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: var(--s-2);
  padding: var(--s-3) var(--s-4);
  border-top: var(--hairline);
  background: var(--paper-deep);
}
</style>
