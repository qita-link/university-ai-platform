<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { notificationApi } from '@/api/endpoints'
import type { NotificationView } from '@/api/types'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { formatDateTime } from '@/lib/time'
import { NOTIFICATION_CATEGORY_LABEL, notificationTone } from '@/lib/labels'
import { useNotifyStore } from '@/stores/notify'
import { useToastStore } from '@/stores/toast'
import UiButton from '@/components/ui/UiButton.vue'
import UiTag from '@/components/ui/UiTag.vue'

/**
 * 通知抽屉：点顶栏铃铛后从右侧滑出，盖住一部分页面而不是整页跳转。
 * <p>
 * 只取最近 30 条做「扫一眼」；要翻页、按分类回看时走「查看全部」进入通知中心。
 * 点条目即标已读（无链接的落到通知中心），关闭后未读数同步给顶栏。
 */
const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: [] }>()

type Filter = 'all' | 'unread' | 'announcement'

const router = useRouter()
const notify = useNotifyStore()
const toast = useToastStore()

const panel = ref<HTMLElement | null>(null)
const items = ref<NotificationView[]>([])
const filter = ref<Filter>('all')
const loading = ref(false)
const error = ref<string | null>(null)
const busyAll = ref(false)
let restoreFocus: HTMLElement | null = null

const unreadText = computed(() => (notify.unread > 0 ? `${notify.unread} 条未读` : '没有未读'))

async function load() {
  loading.value = true
  error.value = null
  try {
    const res = await notificationApi.list({
      unreadOnly: filter.value === 'unread',
      category: filter.value === 'announcement' ? 'ANNOUNCEMENT' : undefined,
      page: 1,
      size: 30,
    })
    items.value = res.items
  } catch (e) {
    items.value = []
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

function close() {
  emit('close')
}

async function openItem(n: NotificationView) {
  try {
    if (!n.readAt) {
      await notificationApi.markRead(n.id)
      notify.set(notify.unread - 1)
    }
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
    return
  }
  close()
  if (n.linkUrl) await router.push(n.linkUrl)
}

async function markAll() {
  busyAll.value = true
  try {
    await notificationApi.markAllRead()
    notify.set(0)
    await load()
    toast.push('已全部标为已读', 'success')
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    busyAll.value = false
  }
}

async function openAll() {
  close()
  await router.push('/notifications')
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    close()
    return
  }
  // 简易焦点圈：Tab 不跑到抽屉背后的页面上
  if (e.key !== 'Tab' || !panel.value) return
  const nodes = panel.value.querySelectorAll<HTMLElement>(
    'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
  )
  if (nodes.length === 0) return
  const first = nodes[0]
  const last = nodes[nodes.length - 1]
  if (!first || !last) return
  if (!e.shiftKey && document.activeElement === last) {
    e.preventDefault()
    first.focus()
  } else if (e.shiftKey && document.activeElement === first) {
    e.preventDefault()
    last.focus()
  }
}

/** 抽屉打开时锁住页面滚动，关闭后把焦点还给铃铛 */
watch(
  () => props.open,
  async (v) => {
    if (v) {
      restoreFocus = document.activeElement as HTMLElement | null
      filter.value = 'all'
      await load()
      void notify.refresh()
      document.body.style.overflow = 'hidden'
      document.addEventListener('keydown', onKeydown)
      await nextTick()
      panel.value?.focus()
    } else {
      document.body.style.overflow = ''
      document.removeEventListener('keydown', onKeydown)
      restoreFocus?.focus?.()
      restoreFocus = null
    }
  },
)

watch(filter, () => void load())

onBeforeUnmount(() => {
  document.body.style.overflow = ''
  document.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <Teleport to="body">
    <Transition name="nd">
      <div v-if="open" class="nd" role="presentation">
        <div class="nd__scrim" @click="close" />
        <aside
          ref="panel"
          class="nd__panel"
          role="dialog"
          aria-modal="true"
          aria-label="通知"
          tabindex="-1"
        >
          <header class="nd__head">
            <div>
              <p class="eyebrow">通知 · Notices</p>
              <h2 class="nd__title">消息</h2>
              <p class="nd__count mono">{{ unreadText }}</p>
            </div>
            <button type="button" class="nd__close" aria-label="关闭通知" @click="close">×</button>
          </header>

          <div class="nd__bar">
            <div class="nd__tabs" role="tablist" aria-label="通知筛选">
              <button
                type="button"
                class="nd__tab"
                :class="{ 'is-active': filter === 'all' }"
                @click="filter = 'all'"
              >
                全部
              </button>
              <button
                type="button"
                class="nd__tab"
                :class="{ 'is-active': filter === 'unread' }"
                @click="filter = 'unread'"
              >
                未读<span v-if="notify.unread" class="nd__tab-count mono">{{ notify.unread }}</span>
              </button>
              <button
                type="button"
                class="nd__tab"
                :class="{ 'is-active': filter === 'announcement' }"
                @click="filter = 'announcement'"
              >
                公告
              </button>
            </div>
          </div>

          <div class="nd__body">
            <p v-if="loading" class="nd__state">正在取消息…</p>
            <p v-else-if="error" class="nd__state nd__state--err" role="alert">{{ error }}</p>
            <div v-else-if="items.length === 0" class="nd__state">
              <p class="nd__empty-title">
                {{ filter === 'unread' ? '没有未读消息' : filter === 'announcement' ? '还没有公告' : '还没有消息' }}
              </p>
              <p class="nd__empty-hint">审批、成绩、反馈与论坛有变化时会在这里提醒你。</p>
            </div>

            <ul v-else class="nd__list">
              <li v-for="n in items" :key="n.id">
                <button type="button" class="nd__item" :class="{ 'is-unread': !n.readAt }" @click="openItem(n)">
                  <span class="nd__dot" :class="`nd__dot--${n.level.toLowerCase()}`" aria-hidden="true" />
                  <span class="nd__main">
                    <span class="nd__item-head">
                      <span class="nd__item-title">{{ n.title }}</span>
                      <UiTag :tone="notificationTone(n.category)">{{ NOTIFICATION_CATEGORY_LABEL[n.category] }}</UiTag>
                    </span>
                    <span v-if="n.body" class="nd__item-body">{{ n.body }}</span>
                    <span class="nd__item-time mono">{{ formatDateTime(n.createdAt) }}</span>
                  </span>
                </button>
              </li>
            </ul>
          </div>

          <footer class="nd__foot">
            <UiButton size="sm" variant="link" @click="openAll">查看全部</UiButton>
            <UiButton size="sm" :loading="busyAll" :disabled="notify.unread === 0" @click="markAll">
              全部已读
            </UiButton>
          </footer>
        </aside>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.nd {
  position: fixed;
  inset: 0;
  z-index: 120;
}

.nd__scrim {
  position: absolute;
  inset: 0;
  background: color-mix(in srgb, var(--ink) 26%, transparent);
}

.nd__panel {
  position: absolute;
  top: 0;
  right: 0;
  display: flex;
  flex-direction: column;
  width: min(420px, 88vw);
  height: 100dvh;
  background: var(--paper);
  border-left: 1px solid var(--ink);
  outline: none;
}

/* 从右往左拉出，遮罩同时淡入 */
.nd-enter-active .nd__panel,
.nd-leave-active .nd__panel {
  transition: transform 240ms var(--ease);
}

.nd-enter-active .nd__scrim,
.nd-leave-active .nd__scrim {
  transition: opacity 200ms var(--ease);
}

.nd-enter-from .nd__panel,
.nd-leave-to .nd__panel {
  transform: translateX(100%);
}

.nd-enter-from .nd__scrim,
.nd-leave-to .nd__scrim {
  opacity: 0;
}

.nd__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s-3);
  padding: var(--s-4) var(--s-4) var(--s-3);
  border-bottom: 1px solid var(--ink);
}

.nd__title {
  margin: 2px 0 0;
  font-family: var(--font-serif-cjk);
  font-size: 24px;
  line-height: 1.15;
}

.nd__count {
  margin: 4px 0 0;
  color: var(--ink-2);
  font-size: 11px;
}

.nd__close {
  flex: 0 0 auto;
  width: 30px;
  height: 30px;
  border: 0;
  background: none;
  color: var(--ink-2);
  font-size: 22px;
  line-height: 1;
  cursor: pointer;
}

.nd__close:hover {
  color: var(--ink);
}

.nd__bar {
  padding: 0 var(--s-4);
  border-bottom: var(--hairline);
}

.nd__tabs {
  display: flex;
  gap: var(--s-4);
}

.nd__tab {
  position: relative;
  padding: 10px 0;
  border: 0;
  background: none;
  color: var(--ink-2);
  font-family: var(--font-serif-cjk);
  font-size: 15px;
  cursor: pointer;
}

.nd__tab.is-active {
  color: var(--ink);
  font-weight: 600;
}

.nd__tab.is-active::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  background: var(--teal);
}

.nd__tab-count {
  margin-left: 3px;
  color: var(--ochre);
  font-size: 10px;
}

.nd__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  -webkit-overflow-scrolling: touch;
}

.nd__state {
  margin: 0;
  padding: var(--s-5) var(--s-4);
  color: var(--ink-2);
  font-family: var(--font-serif);
  font-size: var(--fs-ui);
  line-height: 1.7;
}

.nd__state--err {
  color: var(--ochre);
}

.nd__empty-title {
  margin: 0;
  color: var(--ink);
  font-family: var(--font-serif-cjk);
  font-size: 16px;
}

.nd__empty-hint {
  margin: 4px 0 0;
  font-size: var(--fs-small);
}

.nd__list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.nd__item {
  display: grid;
  grid-template-columns: 8px minmax(0, 1fr);
  gap: var(--s-2);
  width: 100%;
  padding: 12px var(--s-4);
  border: 0;
  border-bottom: var(--hairline);
  background: none;
  color: var(--ink);
  text-align: left;
  cursor: pointer;
}

.nd__item:hover {
  background: color-mix(in srgb, var(--paper-deep) 55%, transparent);
}

/* 未读整条带一点底色，扫一眼就能分辨 */
.nd__item.is-unread {
  background: color-mix(in srgb, var(--paper-lift) 65%, transparent);
}

/* 未读整条带一点底色，扫一眼就能分辨 */
.nd__item.is-unread {
  background: color-mix(in srgb, var(--paper-lift) 65%, transparent);
}

.nd__dot {
  width: 6px;
  height: 6px;
  margin-top: 7px;
  background: var(--rule-strong);
}

.nd__dot--warning {
  background: var(--warning);
}

.nd__dot--success {
  background: var(--success);
}

.nd__dot--info {
  background: var(--teal);
}

.nd__main {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.nd__item-head {
  display: flex;
  align-items: baseline;
  gap: 6px;
  flex-wrap: wrap;
}

.nd__item-title {
  font-family: var(--font-serif-cjk);
  font-size: 15px;
  line-height: 1.4;
}

.is-unread .nd__item-title {
  font-weight: 600;
}

.nd__item-body {
  display: -webkit-box;
  overflow: hidden;
  color: var(--ink-2);
  font-size: 12px;
  line-height: 1.55;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.nd__item-time {
  color: var(--ink-2);
  font-size: 10px;
}

.nd__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  padding: var(--s-3) var(--s-4);
  border-top: 1px solid var(--ink);
}

@media (max-width: 640px) {
  .nd__panel {
    width: 90vw;
  }

  .nd__head {
    padding: 14px 14px 10px;
  }

  .nd__title {
    font-size: 21px;
  }

  .nd__bar {
    padding: 0 14px;
  }

  .nd__tabs {
    gap: 18px;
  }

  .nd__item {
    padding: 10px 14px;
  }

  .nd__foot {
    padding: 10px 14px;
    padding-bottom: max(10px, env(safe-area-inset-bottom));
  }
}
</style>
