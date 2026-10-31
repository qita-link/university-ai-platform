import { defineStore } from 'pinia'
import { ref } from 'vue'
import { http } from '@/api/http'
import type { CurrentUser } from '@/api/types'

/**
 * 头像缓存：后端头像接口要 Bearer 令牌，不能直接写进 <img src>，
 * 这里按「用户 + 版本」取 blob 再转成本地 object URL，多个页面共用同一份。
 * 换头像时后端自增 avatarVersion，key 变化后自动重新拉取。
 */
export const useAvatarStore = defineStore('avatar', () => {
  const urls = ref<Record<string, string>>({})
  const pending = new Set<string>()

  const keyOf = (userId: number, version: number) => `${userId}:${version}`

  function urlFor(userId: number | null | undefined, version: number | null | undefined): string | null {
    if (!userId || !version) return null
    return urls.value[keyOf(userId, version)] ?? null
  }

  async function ensure(userId: number | null | undefined, version: number | null | undefined) {
    if (!userId || !version) return
    const key = keyOf(userId, version)
    if (urls.value[key] || pending.has(key)) return
    pending.add(key)
    try {
      const blob = await http.blob(`/profile/avatar/${userId}`, { v: version })
      urls.value = { ...urls.value, [key]: URL.createObjectURL(blob) }
    } catch {
      /* 没有头像或读取失败：回落到姓名首字 */
    } finally {
      pending.delete(key)
    }
  }

  /** 兼容单用户调用：按登录态的版本预取本人头像 */
  async function load(user: CurrentUser | null) {
    if (!user) return
    await ensure(user.userId, user.avatarVersion)
  }

  function clear() {
    Object.values(urls.value).forEach((u) => URL.revokeObjectURL(u))
    urls.value = {}
    pending.clear()
  }

  return { urls, urlFor, ensure, load, clear }
})
