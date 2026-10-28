import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi } from '@/api/endpoints'
import type { CurrentUser, LoginResult } from '@/api/types'
import { parseServerTime } from '@/lib/time'

/**
 * 登录态。令牌只存 sessionStorage：关闭标签页即失效，各标签页互不共享。
 * 权限点以 /auth/me 为准（后端每次实时从数据库加载），前端只用于显示控制，真正的拦截在后端。
 */
const STORAGE_KEY = 'campus.session'

interface StoredSession {
  token: string
  expiresAt: string
  user: CurrentUser
}

function readStored(): StoredSession | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const s = JSON.parse(raw) as StoredSession
    if (!s.token || !(parseServerTime(s.expiresAt) > Date.now())) {
      sessionStorage.removeItem(STORAGE_KEY)
      return null
    }
    return s
  } catch {
    sessionStorage.removeItem(STORAGE_KEY)
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  const initial = readStored()
  const token = ref<string | null>(initial?.token ?? null)
  const expiresAt = ref<string | null>(initial?.expiresAt ?? null)
  const user = ref<CurrentUser | null>(initial?.user ?? null)
  /** 本标签页是否已经用 /auth/me 校验过一次 */
  const verified = ref(false)

  const isAuthenticated = computed(() => !!token.value && !!user.value)
  const permissionSet = computed(() => new Set(user.value?.permissions ?? []))

  function persist() {
    if (token.value && user.value && expiresAt.value) {
      const s: StoredSession = { token: token.value, expiresAt: expiresAt.value, user: user.value }
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(s))
    } else {
      sessionStorage.removeItem(STORAGE_KEY)
    }
  }

  function applyLogin(result: LoginResult) {
    token.value = result.accessToken
    expiresAt.value = result.expiresAt
    user.value = result.user
    verified.value = true
    persist()
  }

  async function login(username: string, password: string) {
    applyLogin(await authApi.login(username, password))
  }

  /** 刷新当前用户与权限；令牌无效时 http 层会触发 clear()。 */
  async function refreshMe() {
    if (!token.value) return
    user.value = await authApi.me()
    verified.value = true
    persist()
  }

  function clear() {
    token.value = null
    expiresAt.value = null
    user.value = null
    verified.value = false
    sessionStorage.removeItem(STORAGE_KEY)
  }

  /** 退出：后端 token_version 自增，所有设备令牌失效。无论请求成败，本地都清空。 */
  async function logout() {
    try {
      if (token.value) await authApi.logout()
    } catch {
      // 令牌已失效等情况无需处理
    } finally {
      clear()
    }
  }

  /** 满足任意一个权限点即可 */
  function can(...perms: string[]): boolean {
    if (perms.length === 0) return true
    return perms.some((p) => permissionSet.value.has(p))
  }

  return { token, expiresAt, user, verified, isAuthenticated, login, refreshMe, logout, clear, can }
})
