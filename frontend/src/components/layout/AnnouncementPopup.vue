<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { notificationApi } from '@/api/endpoints'
import type { NotificationView } from '@/api/types'
import { formatDateTime } from '@/lib/time'
import { useNotifyStore } from '@/stores/notify'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'

/**
 * 官方公告弹窗。
 * <p>
 * 只取「未读 + 要求弹窗」的公告（后端 /notifications/popups），一次展示一条，按发布顺序排队。
 * 关闭（包括点「我知道了」、Esc、点详情）即标为已读，所以同一条公告不会反复弹；
 * 站内信本身仍然保留在通知中心，弹窗只是「一定要看到」的那一层。
 */
const router = useRouter()
const notify = useNotifyStore()

const queue = ref<NotificationView[]>([])
const total = ref(0)
const closing = ref(false)
let timer: number | null = null

const current = computed(() => queue.value[0] ?? null)
const open = computed(() => current.value !== null && !closing.value)
const step = computed(() => (total.value > 1 ? `第 ${total.value - queue.value.length + 1} / ${total.value} 条` : ''))

async function load() {
  try {
    const rows = await notificationApi.popups()
    if (rows.length === 0) return
    // 已经展示中的公告不重复入队
    const showing = new Set(queue.value.map((n) => n.id))
    const fresh = rows.filter((n) => !showing.has(n.id))
    if (fresh.length === 0) return
    queue.value = [...queue.value, ...fresh]
    total.value = queue.value.length
  } catch {
    /* 弹窗不是关键路径，失败就下次再取 */
  }
}

async function dismiss(goto = false) {
  const item = current.value
  if (!item || closing.value) return
  closing.value = true
  try {
    await notificationApi.markRead(item.id)
    notify.set(notify.unread - 1)
  } catch {
    /* 已读失败也放行，避免把用户卡在弹窗里 */
  }
  queue.value = queue.value.slice(1)
  closing.value = false
  if (goto && item.linkUrl) await router.push(item.linkUrl)
}

onMounted(() => {
  void load()
  timer = window.setInterval(() => {
    if (!document.hidden) void load()
  }, 120_000)
})

onBeforeUnmount(() => {
  if (timer !== null) window.clearInterval(timer)
})
</script>

<template>
  <UiDialog
    :open="open"
    :title="current?.title ?? '公告'"
    :eyebrow="step || '官方公告'"
    width="md"
    @close="dismiss()"
  >
    <div class="notice">
      <p class="notice__body">{{ current?.body || '（无正文）' }}</p>
      <p class="notice__time mono">{{ current ? formatDateTime(current.createdAt) : '' }}</p>
    </div>
    <template #footer>
      <UiButton v-if="current?.linkUrl" variant="link" @click="dismiss(true)">查看详情</UiButton>
      <UiButton variant="primary" @click="dismiss()">我知道了</UiButton>
    </template>
  </UiDialog>
</template>

<style scoped>
.notice {
  display: grid;
  gap: var(--s-2);
}

.notice__body {
  margin: 0;
  font-family: var(--font-serif);
  font-size: var(--fs-read);
  line-height: 1.85;
  white-space: pre-wrap;
  word-break: break-word;
}

.notice__time {
  margin: 0;
  color: var(--ink-2);
  font-size: 11px;
}
</style>
