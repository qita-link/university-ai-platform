<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { notificationApi } from '@/api/endpoints'
import type { NotificationView } from '@/api/types'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { formatDateTime } from '@/lib/time'
import { NOTIFICATION_CATEGORY_LABEL, notificationTone } from '@/lib/labels'
import { useNotifyStore } from '@/stores/notify'
import { useToastStore } from '@/stores/toast'
import UiButton from '@/components/ui/UiButton.vue'
import UiEmpty from '@/components/ui/UiEmpty.vue'
import UiPageHeader from '@/components/ui/UiPageHeader.vue'
import UiPager from '@/components/ui/UiPager.vue'
import UiTag from '@/components/ui/UiTag.vue'

/** 通知中心：请假、成绩、反馈、考勤、教室借用都汇总到这里。 */
const router = useRouter()
const notify = useNotifyStore()
const toast = useToastStore()

type Filter = 'all' | 'unread' | 'announcement'
const filter = ref<Filter>('all')
const items = ref<NotificationView[]>([])
const page = ref(1)
const total = ref(0)
const pages = ref(0)
const loading = ref(true)
const error = ref<string | null>(null)
const markingAll = ref(false)

const countText = computed(() => (notify.unread > 0 ? `${notify.unread} 条未读` : '没有未读'))

async function load() {
  loading.value = true
  error.value = null
  try {
    const res = await notificationApi.list({
      unreadOnly: filter.value === 'unread',
      category: filter.value === 'announcement' ? 'ANNOUNCEMENT' : undefined,
      page: page.value,
      size: 20,
    })
    items.value = res.items
    total.value = res.total
    pages.value = res.pages
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

async function open(n: NotificationView) {
  try {
    if (!n.readAt) {
      await notificationApi.markRead(n.id)
      n.readAt = new Date().toISOString()
      notify.set(notify.unread - 1)
    }
    if (n.linkUrl) await router.push(n.linkUrl)
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  }
}

async function markAll() {
  markingAll.value = true
  try {
    await notificationApi.markAllRead()
    notify.set(0)
    await load()
    toast.push('已全部标为已读', 'success')
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    markingAll.value = false
  }
}

function changePage(p: number) {
  page.value = p
  void load()
}

watch(filter, () => {
  page.value = 1
  void load()
})

onMounted(() => {
  void load()
  void notify.refresh()
})
</script>

<template>
  <div class="notices">
    <UiPageHeader label="通知" latin="Notices" title="消息" :lede="`请假审批、成绩发布、反馈回复、缺勤提醒、教室借用与论坛互动都在这里，官方公告还会额外弹窗。${countText}。`">
      <template #actions>
        <UiButton v-if="notify.unread > 0" size="sm" :loading="markingAll" @click="markAll">全部已读</UiButton>
      </template>
      <template #meta>
        <span class="mono">{{ countText }}</span>
      </template>
    </UiPageHeader>

    <div class="bar">
      <div class="tabs" role="tablist" aria-label="通知筛选">
        <button
          type="button"
          class="tabs__btn"
          :class="{ 'is-active': filter === 'all' }"
          @click="filter = 'all'"
        >
          全部
        </button>
        <button
          type="button"
          class="tabs__btn"
          :class="{ 'is-active': filter === 'unread' }"
          @click="filter = 'unread'"
        >
          未读<span v-if="notify.unread" class="tabs__count mono">{{ notify.unread }}</span>
        </button>
        <button
          type="button"
          class="tabs__btn"
          :class="{ 'is-active': filter === 'announcement' }"
          @click="filter = 'announcement'"
        >
          公告
        </button>
      </div>
    </div>

    <p v-if="loading" class="state">正在取消息…</p>
    <p v-else-if="error" class="state state--err" role="alert">{{ error }}</p>
    <UiEmpty
      v-else-if="items.length === 0"
      :title="filter === 'unread' ? '没有未读消息' : filter === 'announcement' ? '还没有公告' : '还没有消息'"
      hint="审批、成绩、反馈、论坛有变化时会在这里提醒你；公告还会弹窗。"
    />

    <ul v-else class="list">
      <li v-for="n in items" :key="n.id" class="item" :class="{ 'is-unread': !n.readAt }">
        <button type="button" class="item__btn" @click="open(n)">
          <span class="item__dot" :class="`item__dot--${n.level.toLowerCase()}`" aria-hidden="true" />
          <span class="item__main">
            <span class="item__head">
              <span class="item__title">{{ n.title }}</span>
              <UiTag :tone="notificationTone(n.category)">{{ NOTIFICATION_CATEGORY_LABEL[n.category] }}</UiTag>
              <UiTag v-if="n.popupRequired" tone="ochre">弹窗</UiTag>
            </span>
            <span v-if="n.body" class="item__body">{{ n.body }}</span>
            <span class="item__time mono">{{ formatDateTime(n.createdAt) }}</span>
          </span>
          <span v-if="!n.readAt" class="item__new mono">未读</span>
        </button>
      </li>
    </ul>

    <UiPager v-if="pages > 1" :page="page" :pages="pages" :total="total" :disabled="loading" @change="changePage" />
  </div>
</template>

<style scoped>
.notices {
  width: 100%;
  max-width: 820px;
  margin: 0 auto;
  padding: var(--s-5) clamp(var(--s-4), 4vw, var(--s-6)) var(--s-7);
}

.bar {
  display: flex;
  padding-bottom: var(--s-2);
  margin-bottom: var(--s-3);
  border-bottom: 1px solid var(--ink);
}

.tabs {
  display: flex;
  gap: var(--s-4);
}

.tabs__btn {
  position: relative;
  padding: 0 0 var(--s-2);
  border: 0;
  background: none;
  color: var(--ink-2);
  font-family: var(--font-serif-cjk);
  font-size: 17px;
  cursor: pointer;
}

.tabs__btn.is-active {
  color: var(--ink);
  font-weight: 600;
}

.tabs__btn.is-active::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  background: var(--teal);
}

.tabs__count {
  margin-left: 4px;
  color: var(--ochre);
  font-size: 11px;
}

.list {
  margin: 0;
  padding: 0;
  list-style: none;
  border-top: var(--hairline);
}

.item {
  border-bottom: var(--hairline);
}

.item__btn {
  display: grid;
  grid-template-columns: 8px minmax(0, 1fr) auto;
  align-items: start;
  gap: var(--s-3);
  width: 100%;
  padding: var(--s-3) 0;
  border: 0;
  background: none;
  color: var(--ink);
  text-align: left;
  cursor: pointer;
}

.item__btn:hover {
  background: color-mix(in srgb, var(--paper-deep) 55%, transparent);
}

.item__dot {
  width: 6px;
  height: 6px;
  margin-top: 8px;
  background: var(--rule-strong);
}

.item__dot--warning {
  background: var(--warning);
}

.item__dot--success {
  background: var(--success);
}

.item__dot--info {
  background: var(--teal);
}

.item__main {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.item__head {
  display: flex;
  align-items: baseline;
  gap: var(--s-2);
  flex-wrap: wrap;
}

.item__title {
  font-family: var(--font-serif-cjk);
  font-size: 16px;
}

.is-unread .item__title {
  font-weight: 600;
}

.item__body {
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.65;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.item__time {
  color: var(--ink-2);
  font-size: 11px;
}

.item__new {
  color: var(--ochre);
  font-size: 11px;
  align-self: center;
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

@media (max-width: 640px) {
  .notices {
    padding: var(--s-4) 16px var(--s-6);
  }

  .item__btn {
    gap: 10px;
    padding: 12px 0;
  }
}
</style>
