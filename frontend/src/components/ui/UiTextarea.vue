<script setup lang="ts">
import { ref, useId } from 'vue'

/**
 * 多行文本域：与 UiField 同一套「只有底线」的写法，用于正文、评论等长文本。
 * 预览/编辑切换由调用方负责，这里只做输入。
 */
const model = defineModel<string>({ required: true })

withDefaults(
  defineProps<{
    label: string
    rows?: number
    placeholder?: string
    error?: string | null
    hint?: string
    maxlength?: number
    required?: boolean
    disabled?: boolean
    readonly?: boolean
    mono?: boolean
  }>(),
  {
    rows: 6,
    placeholder: '',
    error: null,
    hint: '',
    maxlength: undefined,
    required: false,
    disabled: false,
    readonly: false,
    mono: false,
  },
)

const id = useId()
const input = ref<HTMLTextAreaElement | null>(null)

/** 供父组件在「点回复」后把焦点放进输入框 */
function focus() {
  input.value?.focus()
}

defineExpose({ focus })
</script>

<template>
  <div class="ui-textarea" :class="{ 'has-error': !!error }">
    <div class="ui-textarea__top">
      <label :for="id" class="ui-textarea__label">{{ label }}</label>
      <span v-if="maxlength" class="ui-textarea__count mono">{{ model.length }} / {{ maxlength }}</span>
    </div>
    <textarea
      :id="id"
      ref="input"
      v-model="model"
      class="ui-textarea__input"
      :class="{ 'is-mono': mono }"
      :rows="rows"
      :placeholder="placeholder"
      :maxlength="maxlength"
      :required="required"
      :disabled="disabled"
      :readonly="readonly"
      :aria-invalid="!!error || undefined"
      :aria-describedby="error || hint ? `${id}-msg` : undefined"
    />
    <p v-if="error" :id="`${id}-msg`" class="ui-textarea__msg ui-textarea__msg--error" role="alert">{{ error }}</p>
    <p v-else-if="hint" :id="`${id}-msg`" class="ui-textarea__msg">{{ hint }}</p>
  </div>
</template>

<style scoped>
.ui-textarea {
  display: grid;
  gap: var(--s-1);
}

.ui-textarea__top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-2);
}

.ui-textarea__label {
  font-size: var(--fs-small);
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--ink-2);
}

.ui-textarea__count {
  font-size: 11px;
  color: var(--ink-2);
}

.ui-textarea__input {
  width: 100%;
  padding: var(--s-2) 2px;
  border: 0;
  border-bottom: 1px solid var(--rule-strong);
  background: transparent;
  font-size: 16px;
  line-height: 1.7;
  resize: vertical;
  transition:
    border-color var(--t-fast) var(--ease),
    box-shadow var(--t-fast) var(--ease);
}

.ui-textarea__input.is-mono {
  font-family: var(--font-mono);
  font-size: 14px;
  line-height: 1.65;
}

.ui-textarea__input::placeholder {
  color: var(--ink-2);
  opacity: 0.6;
}

.ui-textarea__input:hover:not(:disabled) {
  border-bottom-color: var(--ink);
}

.ui-textarea__input:focus {
  outline: none;
  border-bottom-color: var(--teal);
  box-shadow: 0 1px 0 0 var(--teal);
}

.ui-textarea__input:disabled {
  opacity: 0.5;
}

.has-error .ui-textarea__input {
  border-bottom-color: var(--ochre);
  box-shadow: 0 1px 0 0 var(--ochre);
}

.ui-textarea__msg {
  margin: 0;
  font-size: var(--fs-small);
  color: var(--ink-2);
}

.ui-textarea__msg--error {
  color: var(--ochre);
}
</style>
