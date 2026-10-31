<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { forumReportApi } from '@/api/endpoints'
import type { ForumReportView, ReportStatus } from '@/api/types'
import { errorMessage } from '@/lib/errors'
import { formatDateTime } from '@/lib/time'
import UiButton from '@/components/ui/UiButton.vue'
import UiEmpty from '@/components/ui/UiEmpty.vue'
import UiPager from '@/components/ui/UiPager.vue'
import UiSelect from '@/components/ui/UiSelect.vue'
import UiTag from '@/components/ui/UiTag.vue'
import { useToastStore } from '@/stores/toast'

/**
 * 举报审核队列。
 * 处理动作是二选一：成立 → 帖子隐藏 / 评论软删除；驳回 → 内容保持原样。
 * 队列按「待处理优先」排序，处理完的记录仍可回看。
 */
const toast = useToastStore()

const items = ref<ForumReportView[]>([])
const total = ref(0)
const pages = ref(0)
const page = ref(1)
const loading = ref(true)
const loadError = ref<string | null>(null)
const statusFilter = ref('PENDING')
const handling = ref<number | null>(null)

const statusOptions = [
  { value: 'PENDING', label: '待处理' },
  { value: '', label: '全部' },
  { value: 'ACCEPTED', label: '已成立' },
  { value: 'REJECTED', label: '已驳回' },
]

const REASON_LABEL: Record<string, string> = {
  SPAM: '垃圾广告',
  ABUSE: '辱骂攻击',
  PORN: '色情低俗',
  ILLEGAL: '违法违规',
  ACADEMIC: '学术不端',
  OTHER: '其他',
}

const STATUS_LABEL: Record<ReportStatus, string> = {
  PENDING: '待处理',
  ACCEPTED: '已成立',
  REJECTED: '已驳回',
}

function statusTone(status: ReportStatus): 'warning' | 'teal' | 'neutral' {
  if (status === 'PENDING') return 'warning'
  if (status === 'ACCEPTED') return 'teal'
  return 'neutral'
}

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const res = await forumReportApi.list({
      page: page.value,
      size: 20,
      status: statusFilter.value || undefined,
    })
    items.value = res.items
    total.value = res.total
    pages.value = res.pages
  } catch (e) {
    items.value = []
    loadError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

async function handle(row: ForumReportView, accept: boolean) {
  if (handling.value) return
  handling.value = row.id
  try {
    await forumReportApi.handle(row.id, accept)
    toast.push(accept ? '举报已成立，内容已处理' : '举报已驳回', 'success')
    await load()
  } catch (e) {
    toast.push(errorMessage(e), 'danger')
  } finally {
    handling.value = null
  }
}

onMounted(load)
watch(statusFilter, () => {
  page.value = 1
  void load()
})
watch(page, load)
</script>

<template>
  <section class="panel">
    <header class="panel__head">
      <div>
        <h2 class="panel__title">举报审核</h2>
        <p class="panel__hint">共 {{ total }} 条。举报成立时帖子会被隐藏、评论会被删除。</p>
      </div>
      <UiSelect v-model="statusFilter" label="状态" :options="statusOptions" />
    </header>

    <p v-if="loadError" class="panel__error">{{ loadError }}</p>
    <p v-else-if="loading" class="panel__hint">加载中…</p>
    <UiEmpty v-else-if="!items.length" title="没有符合条件的举报" />

    <ul v-else class="reports">
      <li v-for="row in items" :key="row.id" class="report">
        <div class="report__main">
          <div class="report__top">
            <UiTag :tone="statusTone(row.status)">{{ STATUS_LABEL[row.status] }}</UiTag>
            <UiTag tone="neutral">{{ row.targetType === 'POST' ? '帖子' : '评论' }}</UiTag>
            <span class="report__reason">{{ REASON_LABEL[row.reason] ?? row.reason }}</span>
            <span v-if="!row.targetVisible" class="report__gone">内容已不可见</span>
          </div>
          <p class="report__target">{{ row.targetTitle || `#${row.targetId}` }}</p>
          <p v-if="row.detail" class="report__detail">{{ row.detail }}</p>
          <p class="report__meta">
            {{ row.reporterName || '匿名' }} 举报于 {{ formatDateTime(row.createdAt) }}
            <template v-if="row.handledAt">
              · {{ row.handlerName || '管理员' }} 于 {{ formatDateTime(row.handledAt) }} 处理
            </template>
          </p>
          <p v-if="row.handleNote" class="report__meta">处理备注：{{ row.handleNote }}</p>
        </div>
        <div v-if="row.status === 'PENDING'" class="report__actions">
          <UiButton size="sm" variant="primary" :loading="handling === row.id" @click="handle(row, true)">
            成立并处理
          </UiButton>
          <UiButton size="sm" :disabled="handling === row.id" @click="handle(row, false)">驳回</UiButton>
        </div>
      </li>
    </ul>

    <UiPager v-if="pages > 1" v-model:page="page" :pages="pages" :total="total" />
  </section>
</template>

<style scoped>
.panel__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
.panel__title {
  margin: 0 0 4px;
  font-size: 1.05rem;
}
.panel__hint {
  margin: 0;
  color: var(--fg-muted);
  font-size: 0.85rem;
}
.panel__error {
  color: var(--danger, #b42318);
}
.reports {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.report {
  display: flex;
  gap: 16px;
  justify-content: space-between;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface);
}
.report__top {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.report__reason {
  font-weight: 600;
  font-size: 0.9rem;
}
.report__gone {
  color: var(--fg-muted);
  font-size: 0.8rem;
}
.report__target {
  margin: 6px 0 4px;
  font-size: 0.92rem;
}
.report__detail {
  margin: 0 0 4px;
  color: var(--fg-muted);
  font-size: 0.85rem;
}
.report__meta {
  margin: 0;
  color: var(--fg-muted);
  font-size: 0.8rem;
}
.report__actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex-shrink: 0;
}
@media (max-width: 720px) {
  .report {
    flex-direction: column;
  }
  .report__actions {
    flex-direction: row;
  }
}
</style>
