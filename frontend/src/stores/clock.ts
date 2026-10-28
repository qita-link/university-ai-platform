import { defineStore } from 'pinia'
import { ref } from 'vue'
import { systemApi } from '@/api/endpoints'
import { estimateSkew } from '@/lib/time'

/**
 * 服务器时钟。倒计时用 “本机时间 + 时钟差” 计算，降低用户电脑时钟不准带来的偏差；
 * 后端仍是最终裁判：确认时若已过期会返回 409 AI_TOOL_CONFIRM_EXPIRED。
 * 每秒更新一次 now，供所有倒计时共享，避免每个组件各开定时器。
 */
export const useClockStore = defineStore('clock', () => {
  const skewMs = ref(0)
  const synced = ref(false)
  const now = ref(Date.now())
  let timer: number | null = null

  async function sync() {
    try {
      const sentAt = Date.now()
      const info = await systemApi.info()
      skewMs.value = estimateSkew(info.serverTime, sentAt, Date.now())
      synced.value = true
    } catch {
      skewMs.value = 0
    }
    tick()
  }

  function tick() {
    now.value = Date.now() + skewMs.value
  }

  function start() {
    if (timer !== null) return
    tick()
    timer = window.setInterval(tick, 1000)
  }

  return { skewMs, synced, now, sync, start }
})
