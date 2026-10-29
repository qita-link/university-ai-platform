<script setup lang="ts">
import { useId } from 'vue'

/**
 * 表单字段：标签在上，输入框只有底线；focus 时底线加粗为深青。
 * 错误信息用赭红，并通过 aria-describedby 关联到输入框。
 */
const model = defineModel<string>({ required: true })

withDefaults(
  defineProps<{
    label: string
    type?: 'text' | 'password' | 'datetime-local' | 'date'
    autocomplete?: string
    error?: string | null
    hint?: string
    required?: boolean
    disabled?: boolean
    maxlength?: number
    inputmode?: 'text' | 'numeric'
  }>(),
  { type: 'text', autocomplete: 'off', error: null, hint: '', required: false, disabled: false, inputmode: 'text' },
)

const id = useId()
</script>

<template>
  <div class="ui-field" :class="{ 'has-error': !!error }">
    <label :for="id" class="ui-field__label">{{ label }}</label>
    <input
      :id="id"
      v-model="model"
      class="ui-field__input"
      :type="type"
      :autocomplete="autocomplete"
      :required="required"
      :disabled="disabled"
      :maxlength="maxlength"
      :inputmode="inputmode"
      :aria-invalid="!!error || undefined"
      :aria-describedby="error || hint ? `${id}-msg` : undefined"
    />
    <p v-if="error" :id="`${id}-msg`" class="ui-field__msg ui-field__msg--error" role="alert">{{ error }}</p>
    <p v-else-if="hint" :id="`${id}-msg`" class="ui-field__msg">{{ hint }}</p>
  </div>
</template>

<style scoped>
.ui-field {
  display: grid;
  gap: var(--s-1);
}

.ui-field__label {
  font-size: var(--fs-small);
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--ink-2);
}

.ui-field__input {
  width: 100%;
  height: 44px;
  padding: 0 2px;
  border: 0;
  border-bottom: 1px solid var(--rule-strong);
  background: transparent;
  font-size: 16px; /* ≥16px，避免 iOS 聚焦时自动放大 */
  transition:
    border-color var(--t-fast) var(--ease),
    box-shadow var(--t-fast) var(--ease);
}

.ui-field__input:hover:not(:disabled) {
  border-bottom-color: var(--ink);
}

.ui-field__input:focus {
  outline: none;
  border-bottom-color: var(--teal);
  box-shadow: 0 1px 0 0 var(--teal);
}

.ui-field__input:focus-visible {
  outline: none;
}

.ui-field__input:disabled {
  opacity: 0.5;
}

.has-error .ui-field__input {
  border-bottom-color: var(--ochre);
  box-shadow: 0 1px 0 0 var(--ochre);
}

.ui-field__msg {
  margin: 0;
  font-size: var(--fs-small);
  color: var(--ink-2);
}

.ui-field__msg--error {
  color: var(--ochre);
}
</style>
