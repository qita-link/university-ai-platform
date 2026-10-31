<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'

/** 提问框：Enter 发送，Shift+Enter 换行；输入法组字时不发送。上限与后端一致（2000 字）。 */
const MAX = 2000
const text = defineModel<string>({ required: true })
const props = defineProps<{ busy: boolean }>()
const emit = defineEmits<{ send: [] }>()

const el = ref<HTMLTextAreaElement | null>(null)
const over = computed(() => text.value.length > MAX)
const canSend = computed(() => !props.busy && text.value.trim().length > 0 && !over.value)

function autosize() {
  const t = el.value
  if (!t) return
  t.style.height = 'auto'
  t.style.height = Math.min(t.scrollHeight, 240) + 'px'
}
watch(text, () => nextTick(autosize))

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing && e.keyCode !== 229) {
    e.preventDefault()
    if (canSend.value) emit('send')
  }
}

defineExpose({ focus: () => el.value?.focus() })
</script>

<template>
  <div class="composer" :class="{ 'is-over': over }">
    <label for="composer-input" class="visually-hidden">输入问题或要办的事</label>
    <textarea
      id="composer-input"
      ref="el"
      v-model="text"
      class="composer__input"
      rows="1"
      placeholder="查点什么，或直接说要办的事……"
      :aria-describedby="over ? 'composer-count' : undefined"
      @keydown="onKeydown"
    />
    <div class="composer__bar">
      <slot name="tools" />
      <span id="composer-count" class="composer__count mono" :aria-live="over ? 'polite' : 'off'">
        {{ text.length }} / {{ MAX }}
      </span>
      <button type="button" class="composer__send" :disabled="!canSend" @click="emit('send')">
        {{ busy ? '处理中' : '发送' }}
        <span aria-hidden="true" class="composer__arrow">→</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.composer {
  border: 1px solid var(--ink);
  background: var(--paper-lift);
  transition: box-shadow var(--t-fast) var(--ease);
}

.composer:focus-within {
  box-shadow: 4px 4px 0 var(--paper-deep);
}

.composer__input {
  display: block;
  width: 100%;
  min-height: 56px;
  max-height: 240px;
  padding: var(--s-3) var(--s-3) var(--s-2);
  border: 0;
  background: transparent;
  resize: none;
  font-family: var(--font-serif);
  font-size: 17px;
  line-height: 1.6;
}

.composer__input::placeholder {
  color: var(--ink-2);
  font-style: italic;
}

.composer__input:focus {
  outline: none;
}

.composer__bar {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  padding: var(--s-1) var(--s-1) var(--s-1) var(--s-2);
  border-top: var(--hairline);
}

.composer__count {
  margin-left: auto;
  font-size: 11px;
  color: var(--ink-2);
}

.is-over .composer__count {
  color: var(--ochre);
}

.composer__send {
  display: inline-flex;
  align-items: center;
  gap: var(--s-2);
  min-height: 36px;
  padding: 0 var(--s-3);
  border: 0;
  background: var(--teal);
  color: var(--paper);
  font-weight: 500;
  letter-spacing: 0.06em;
  cursor: pointer;
  transition: background-color var(--t-fast) var(--ease);
}

.composer__send:hover:not(:disabled) {
  background: var(--teal-press);
}

.composer__send:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.composer__arrow {
  transition: transform var(--t-fast) var(--ease);
}

.composer__send:hover:not(:disabled) .composer__arrow {
  transform: translateX(3px);
}
</style>
