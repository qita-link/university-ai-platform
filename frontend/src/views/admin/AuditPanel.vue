<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { auditApi } from '@/api/endpoints'
import type { AuditLogView } from '@/api/types'
import { errorMessage } from '@/lib/errors'
import { AUDIT_RESULT_LABEL, AUDIT_SOURCE_LABEL, auditTone } from '@/lib/labels'
import { formatDateTime } from '@/lib/time'
import UiButton from '@/components/ui/UiButton.vue'
import UiEmpty from '@/components/ui/UiEmpty.vue'
import UiPager from '@/components/ui/UiPager.vue'
import UiSelect from '@/components/ui/UiSelect.vue'
import UiTag from '@/components/ui/UiTag.vue'

/**
 * 审计日志：谁在什么时候对什么做了什么。
 * 详情是后端脱敏后的 JSON，默认收起——一屏能扫到更多条，需要时再展开。
 */
const logs = ref<AuditLogView[]>([])
const total = ref(0)
const pages = ref(0)
const page = ref(1)
const loading = ref(true)
const loadError = ref<string | null>(null)

const moduleFilter = ref('')
const resultFilter = ref('')
const sourceFilter = ref('')
const expanded = ref<number | null>(null)

const resultOptions = [
  { value: '', label: '全部结果' },
  { value: 'SUCCESS', label: '成功' },
  { value: 'FAILURE', label: '失败' },
  { value: 'DENIED', label: '拒绝' },
]

const sourceOptions = [
  { value: '', label: '全部来源' },
  { value: 'API', label: '接口' },
  { value: 'AI_TOOL', label: 'AI 工具' },
  { value: 'SYSTEM', label: '系统' },
]

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const res = await auditApi.list({
      page: page.value,
      size: 20,
      module: moduleFilter.value.trim() || undefined,
      result: resultFilter.value || undefined,
      source: sourceFilter.value || undefined,
    })
    logs.value = res.items
    total.value = res.total
    pages.value = res.pages
  } catch (e) {
    logs.value = []
    loadError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  expanded.value = null
  void load()
}

watch(page, () => {
  expanded.value = null
  void load()
})

onMounted(load)
</script>

<template>
  <section class="panel">
    <div class="toolbar">
      <form class="search" role="search" @submit.prevent="search">
        <label class="search__label" for="audit-module">模块</label>
        <input
          id="audit-module"
          v-model="moduleFilter"
          class="search__input"
          type="search"
          placeholder="如 user / course / kb"
          autocomplete="off"
        />
        <UiButton size="sm" type="submit">查找</UiButton>
      </form>

      <div class="filters">
        <UiSelect v-model="resultFilter" label="结果" :options="resultOptions" @update:model-value="search" />
        <UiSelect v-model="sourceFilter" label="来源" :options="sourceOptions" @update:model-value="search" />
      </div>
    </div>

    <p v-if="loading" class="state">正在翻日志…</p>

    <div v-else-if="loadError" class="state state--err" role="alert">
      <p>{{ loadError }}</p>
      <UiButton size="sm" @click="load">重试</UiButton>
    </div>

    <UiEmpty v-else-if="logs.length === 0" title="没有匹配的记录" hint="换个模块名，或清空结果与来源筛选。" />

    <template v-else>
      <ol class="logs">
        <li v-for="l in logs" :key="l.id" class="log">
          <div class="log__head" role="button" tabindex="0" @click="expanded = expanded === l.id ? null : l.id" @keydown.enter="expanded = expanded === l.id ? null : l.id">
            <span class="log__time mono">{{ formatDateTime(l.createdAt) }}</span>
            <span class="log__actor">{{ l.actorUsername || '匿名' }}</span>
            <span class="log__action">
              <span class="log__module mono">{{ l.module }}</span>
              <span class="log__dot" aria-hidden="true">/</span>
              {{ l.action }}
            </span>
            <UiTag :tone="auditTone(l.result)">{{ AUDIT_RESULT_LABEL[l.result] ?? l.result }}</UiTag>
            <span class="log__source">{{ AUDIT_SOURCE_LABEL[l.source] ?? l.source }}</span>
          </div>

          <p v-if="l.targetType || l.targetId" class="log__target">
            目标：<span class="mono">{{ l.targetType || '—' }}{{ l.targetId ? `#${l.targetId}` : '' }}</span>
            <span v-if="l.errorCode" class="log__err mono">{{ l.errorCode }}</span>
          </p>

          <div v-if="expanded === l.id" class="log__detail">
            <p class="log__detail-row"><span>traceId</span><span class="mono">{{ l.traceId }}</span></p>
            <p class="log__detail-row"><span>来源 IP</span><span class="mono">{{ l.clientIp || '—' }}</span></p>
            <pre v-if="l.detail" class="log__json mono">{{ l.detail }}</pre>
            <p v-else class="log__detail-row"><span>详情</span><span>（无附加字段）</span></p>
          </div>
        </li>
      </ol>

      <UiPager v-if="pages > 1" :page="page" :pages="pages" :total="total" @change="page = $event" />
    </template>
  </section>
</template>

<style scoped>
.panel {
  min-height: 240px;
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--s-4);
  padding-bottom: var(--s-3);
  margin-bottom: var(--s-3);
  border-bottom: var(--hairline);
}

.search {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex: 1 1 300px;
}

.search__label {
  font-size: var(--fs-small);
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--ink-2);
}

.search__input {
  flex: 1;
  min-width: 0;
  height: 32px;
  padding: 0 2px;
  border: 0;
  background: transparent;
  font-size: 15px;
}

.search__input:focus {
  outline: none;
  box-shadow: 0 1px 0 0 var(--teal);
}

.filters {
  display: flex;
  align-items: flex-end;
  gap: var(--s-3);
}

.filters :deep(.ui-select) {
  min-width: 130px;
}

.logs {
  margin: 0;
  padding: 0;
  list-style: none;
}

.log {
  border-bottom: var(--hairline);
}

.log__head {
  display: grid;
  grid-template-columns: 130px 110px minmax(0, 1fr) auto 64px;
  align-items: center;
  gap: var(--s-3);
  padding: var(--s-2) 0;
  cursor: pointer;
}

.log__head:hover {
  background: color-mix(in srgb, var(--paper-lift) 60%, transparent);
}

.log__time {
  color: var(--ink-2);
  font-size: 11px;
}

.log__actor {
  font-family: var(--font-serif-cjk);
  font-size: 14px;
}

.log__action {
  overflow: hidden;
  font-size: var(--fs-small);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.log__module {
  color: var(--teal);
  font-size: 11px;
}

.log__dot {
  margin: 0 4px;
  color: var(--rule-strong);
}

.log__source {
  color: var(--ink-2);
  font-size: 11px;
  text-align: right;
}

.log__target {
  margin: 0 0 var(--s-1) 130px;
  color: var(--ink-2);
  font-size: 11px;
}

.log__err {
  margin-left: var(--s-2);
  color: var(--ochre);
}

.log__detail {
  margin: 0 0 var(--s-3) 130px;
  padding: var(--s-2) var(--s-3);
  border-left: 2px solid var(--rule-strong);
  background: var(--paper-lift);
}

.log__detail-row {
  display: flex;
  gap: var(--s-3);
  margin: 0 0 4px;
  color: var(--ink-2);
  font-size: 11px;
}

.log__detail-row span:first-child {
  min-width: 64px;
  color: var(--rule-strong);
}

.log__json {
  max-height: 220px;
  margin: var(--s-2) 0 0;
  overflow: auto;
  font-size: 11px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}

.state {
  margin: 0;
  padding: var(--s-5) 0;
  color: var(--ink-2);
  font-family: var(--font-serif);
  font-size: var(--fs-read);
}

.state--err {
  color: var(--ochre);
}

@media (max-width: 860px) {
  .log__head {
    grid-template-columns: 110px minmax(0, 1fr) auto;
    row-gap: 2px;
  }

  .log__actor {
    grid-column: 2;
  }

  .log__source {
    display: none;
  }

  .log__target,
  .log__detail {
    margin-left: 0;
  }
}
</style>
