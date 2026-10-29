<script setup lang="ts">
import { useId } from 'vue'

/** 下拉选择：与 UiField 同一条底线，原生 select 保留键盘与移动端行为。 */
const model = defineModel<string>({ required: true })

withDefaults(
  defineProps<{
    label: string
    options: { value: string; label: string }[]
    placeholder?: string
    error?: string | null
    hint?: string
    disabled?: boolean
  }>(),
  { placeholder: '', error: null, hint: '', disabled: false },
)

const id = useId()
</script>

<template>
  <div class="ui-select" :class="{ 'has-error': !!error }">
    <label :for="id" class="ui-select__label">{{ label }}</label>
    <div class="ui-select__wrap">
      <select
        :id="id"
        v-model="model"
        class="ui-select__input"
        :disabled="disabled"
        :aria-invalid="!!error || undefined"
        :aria-describedby="error || hint ? `${id}-msg` : undefined"
      >
        <option v-if="placeholder" value="">{{ placeholder }}</option>
        <option v-for="o in options" :key="o.value" :value="o.value">{{ o.label }}</option>
      </select>
      <span class="ui-select__caret" aria-hidden="true">▾</span>
    </div>
    <p v-if="error" :id="`${id}-msg`" class="ui-select__msg ui-select__msg--error" role="alert">{{ error }}</p>
    <p v-else-if="hint" :id="`${id}-msg`" class="ui-select__msg">{{ hint }}</p>
  </div>
</template>

<style scoped>
.ui-select {
  display: grid;
  gap: var(--s-1);
}

.ui-select__label {
  font-size: var(--fs-small);
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--ink-2);
}

.ui-select__wrap {
  position: relative;
}

.ui-select__input {
  width: 100%;
  height: 44px;
  padding: 0 24px 0 2px;
  border: 0;
  border-bottom: 1px solid var(--rule-strong);
  background: transparent;
  font-size: 16px;
  appearance: none;
  cursor: pointer;
  transition:
    border-color var(--t-fast) var(--ease),
    box-shadow var(--t-fast) var(--ease);
}

.ui-select__input:hover:not(:disabled) {
  border-bottom-color: var(--ink);
}

.ui-select__input:focus {
  outline: none;
  border-bottom-color: var(--teal);
  box-shadow: 0 1px 0 0 var(--teal);
}

.ui-select__input:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.ui-select__caret {
  position: absolute;
  right: 2px;
  top: 50%;
  transform: translateY(-50%);
  pointer-events: none;
  color: var(--ink-2);
  font-size: 12px;
}

.has-error .ui-select__input {
  border-bottom-color: var(--ochre);
  box-shadow: 0 1px 0 0 var(--ochre);
}

.ui-select__msg {
  margin: 0;
  font-size: var(--fs-small);
  color: var(--ink-2);
}

.ui-select__msg--error {
  color: var(--ochre);
}
</style>
