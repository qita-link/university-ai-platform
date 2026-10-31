import { defineStore } from 'pinia'
import { ref } from 'vue'
import { notificationApi } from '@/api/endpoints'

/**
 * 未读通知数：顶栏红点与通知页共用。
 * 轮询间隔 60 秒，页面隐藏时不请求，避免手机端无谓耗电。
 */
export const useNotifyStore = defineStore('notify', () => {
  const unread = ref(0)
  let timer: number | null = null

  async function refresh() {
    try {
      unread.value = await notificationApi.unreadCount()
    } catch {
      /* 顶栏红点不是关键路径，失败就保持原值 */
    }
  }

  function start() {
    if (timer !== null) return
    void refresh()
    timer = window.setInterval(() => {
      if (!document.hidden) void refresh()
    }, 60_000)
  }

  function stop() {
    if (timer !== null) {
      window.clearInterval(timer)
      timer = null
    }
  }

  function set(value: number) {
    unread.value = Math.max(0, value)
  }

  return { unread, refresh, start, stop, set }
})
