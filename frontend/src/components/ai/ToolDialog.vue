<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import type { AiToolCallView, AiToolView } from '@/api/types'
import { aiApi } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import { errorMessage } from '@/lib/errors'
import { coerceArguments, initialRaw, PARAM_TYPE_LABEL, type RawValue } from '@/lib/toolArgs'

/**
 * 办事：从工具目录选择一项，填写参数后发起。
 * 只读工具立即返回结果；写操作只生成待确认记录，由调用方展示确认卡片。
 * 后端不会把模型自由文本解析成工具调用，所以发起始终来自用户在这里的显式操作。
 */
const props = defineProps<{
  open: boolean
  tools: AiToolView[]
  loading: boolean
  loadError: string | null
  conversationId: number | null
}>()
const emit = defineEmits<{ close: []; created: [call: AiToolCallView] }>()

const selected = ref<AiToolView | null>(null)
const raw = ref<Record<string, RawValue>>({})
const fieldErrors = ref<Record<string, string>>({})
const submitting = ref(false)
const error = ref<string | null>(null)

const readTools = computed(() => props.tools.filter((t) => !t.sideEffect))
const writeTools = computed(() => props.tools.filter((t) => t.sideEffect))

watch(
  () => props.open,
  (o) => {
    if (o) {
      selected.value = null
      error.value = null
    }
  },
)

function pick(t: AiToolView) {
  selected.value = t
  raw.value = initialRaw(t.parameters)
  fieldErrors.value = {}
  error.value = null
}

async function submit() {
  const tool = selected.value
  if (!tool) return
  const { args, errors } = coerceArguments(tool.parameters, raw.value)
  fieldErrors.value = errors
  if (Object.keys(errors).length) return

  submitting.value = true
  error.value = null
  try {
    const call = await aiApi.invokeTool(tool.name, args, props.conversationId)
    emit('created', call)
    emit('close')
  } catch (e) {
    if (e instanceof ApiError && e.errors.length) {
      fieldErrors.value = Object.fromEntries(e.errors.map((x) => [x.field.replace(/^arguments\./, ''), x.message]))
    }
    error.value = errorMessage(e)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <UiDialog
    :open="open"
    :title="selected ? selected.title : '办事'"
    :eyebrow="selected ? (selected.sideEffect ? '写操作 · 需确认' : '查询') : '选择一项'"
    width="lg"
    :dismissible="!submitting"
    @close="emit('close')"
  >
    <!-- 目录 -->
    <template v-if="!selected">
      <p v-if="loading" class="muted">正在载入可用操作…</p>
      <p v-else-if="loadError" class="err">{{ loadError }}</p>
      <p v-else-if="tools.length === 0" class="muted">当前账号没有可用的操作。</p>
      <div v-else class="catalog">
        <section v-if="readTools.length">
          <h3 class="catalog__h">查询 <span class="latin-italic">read</span></h3>
          <ul class="catalog__list">
            <li v-for="t in readTools" :key="t.name">
              <button type="button" class="catalog__item" @click="pick(t)">
                <span class="catalog__title">{{ t.title }}</span>
                <span class="catalog__desc">{{ t.description }}</span>
              </button>
            </li>
          </ul>
        </section>
        <section v-if="writeTools.length">
          <h3 class="catalog__h">办理 <span class="latin-italic">write</span></h3>
          <p class="catalog__note">以下操作提交后先生成预览，需要你再次确认才会执行。</p>
          <ul class="catalog__list">
            <li v-for="t in writeTools" :key="t.name">
              <button type="button" class="catalog__item" @click="pick(t)">
                <span class="catalog__title">{{ t.title }}</span>
                <span class="catalog__desc">{{ t.description }}</span>
              </button>
            </li>
          </ul>
        </section>
      </div>
    </template>

    <!-- 参数 -->
    <form v-else id="tool-form" class="params" novalidate @submit.prevent="submit">
      <p class="params__desc">{{ selected.description }}</p>
      <p v-if="selected.parameters.length === 0" class="muted">此操作不需要参数。</p>
      <div v-for="p in selected.parameters" :key="p.name" class="param" :class="{ 'has-error': fieldErrors[p.name] }">
        <label :for="`p-${p.name}`" class="param__label">
          <span class="mono">{{ p.name }}</span>
          <span class="param__type">{{ PARAM_TYPE_LABEL[p.type] ?? p.type }}{{ p.required ? ' · 必填' : '' }}</span>
        </label>
        <input
          v-if="p.type === 'boolean'"
          :id="`p-${p.name}`"
          v-model="raw[p.name]"
          type="checkbox"
          class="param__check"
        />
        <input
          v-else
          :id="`p-${p.name}`"
          v-model="raw[p.name]"
          class="param__input"
          :type="p.type === 'datetime' ? 'datetime-local' : 'text'"
          :inputmode="p.type === 'integer' || p.type === 'number' ? 'numeric' : 'text'"
          :aria-invalid="!!fieldErrors[p.name] || undefined"
          :aria-describedby="`p-${p.name}-d`"
        />
        <p :id="`p-${p.name}-d`" class="param__hint">
          <span v-if="fieldErrors[p.name]" class="err">{{ fieldErrors[p.name] }}。</span>{{ p.description }}
        </p>
      </div>
      <p v-if="error" class="err" role="alert">{{ error }}</p>
    </form>

    <template v-if="selected" #footer>
      <UiButton variant="link" :disabled="submitting" @click="selected = null">返回目录</UiButton>
      <UiButton type="submit" form="tool-form" variant="primary" :loading="submitting" @click="submit">
        {{ selected.sideEffect ? '生成预览' : '查询' }}
      </UiButton>
    </template>
  </UiDialog>
</template>

<style scoped>
.muted {
  margin: 0;
  color: var(--ink-2);
}

.err {
  margin: 0;
  color: var(--ochre);
}

.catalog {
  display: grid;
  gap: var(--s-5);
}

.catalog__h {
  margin-bottom: var(--s-2);
  font-size: var(--fs-ui-lg);
}

.catalog__h .latin-italic {
  margin-left: var(--s-1);
  color: var(--ink-2);
  font-size: 15px;
}

.catalog__note {
  margin: 0 0 var(--s-2);
  font-size: var(--fs-small);
  color: var(--ink-2);
}

.catalog__list {
  margin: 0;
  padding: 0;
  list-style: none;
  border-top: 1px solid var(--ink);
}

.catalog__item {
  display: grid;
  grid-template-columns: 9em 1fr;
  gap: var(--s-3);
  width: 100%;
  padding: var(--s-2) var(--s-2);
  border: 0;
  border-bottom: var(--hairline);
  background: none;
  text-align: left;
  cursor: pointer;
  transition: background-color var(--t-fast) var(--ease);
}

.catalog__item:hover {
  background: var(--paper-lift);
}

.catalog__title {
  font-weight: 600;
}

.catalog__desc {
  color: var(--ink-2);
}

.params {
  display: grid;
  gap: var(--s-4);
}

.params__desc {
  margin: 0;
  color: var(--ink-2);
}

.param {
  display: grid;
  gap: var(--s-1);
}

.param__label {
  display: flex;
  align-items: baseline;
  gap: var(--s-2);
}

.param__type {
  font-size: var(--fs-small);
  color: var(--ink-2);
}

.param__input {
  height: 40px;
  border: 0;
  border-bottom: 1px solid var(--rule-strong);
  background: transparent;
  font-size: 15px;
}

.param__input:focus {
  outline: none;
  border-bottom-color: var(--teal);
  box-shadow: 0 1px 0 0 var(--teal);
}

.has-error .param__input {
  border-bottom-color: var(--ochre);
}

.param__check {
  justify-self: start;
  width: 18px;
  height: 18px;
  accent-color: var(--teal);
}

.param__hint {
  margin: 0;
  font-size: var(--fs-small);
  color: var(--ink-2);
}

@media (max-width: 600px) {
  .catalog__item {
    grid-template-columns: 1fr;
    gap: 2px;
  }
}
</style>
