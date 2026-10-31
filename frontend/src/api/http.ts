import type { ApiEnvelope, FieldError, LoginResult } from './types'

/** 后端统一响应体中的失败，或网络/网关层面的失败。 */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly errors: FieldError[]
  readonly traceId: string | null
  /** 429 时的 Retry-After（秒） */
  readonly retryAfter: number | null

  constructor(init: {
    status: number
    code: string
    message: string
    errors?: FieldError[]
    traceId?: string | null
    retryAfter?: number | null
  }) {
    super(init.message)
    this.name = 'ApiError'
    this.status = init.status
    this.code = init.code
    this.errors = init.errors ?? []
    this.traceId = init.traceId ?? null
    this.retryAfter = init.retryAfter ?? null
  }

  fieldError(field: string): string | undefined {
    return this.errors.find((e) => e.field === field)?.message
  }
}

export interface HttpHooks {
  getToken: () => string | null
  /** 刷新令牌；返回 null 表示没有可用的刷新令牌 */
  getRefreshToken: () => string | null
  /** 刷新成功：用新令牌覆盖本地登录态 */
  onRefreshed: (result: LoginResult) => void
  /** 刷新也失败时调用：清空登录态并回登录页。 */
  onUnauthorized: () => void
}

let hooks: HttpHooks = {
  getToken: () => null,
  getRefreshToken: () => null,
  onRefreshed: () => {},
  onUnauthorized: () => {},
}

export function configureHttp(next: HttpHooks): void {
  hooks = next
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  query?: Record<string, string | number | boolean | null | undefined>
  body?: unknown
  signal?: AbortSignal
  /** 为 true 时 401 不尝试刷新、也不触发全局登出（用于登录接口）。 */
  skipAuthRedirect?: boolean
}

const BASE = '/api/v1'

function buildUrl(path: string, query?: RequestOptions['query']): string {
  const url = new URL(BASE + path, window.location.origin)
  if (query) {
    for (const [k, v] of Object.entries(query)) {
      if (v !== null && v !== undefined && v !== '') url.searchParams.set(k, String(v))
    }
  }
  return url.pathname + url.search
}

function parseRetryAfter(header: string | null): number | null {
  if (!header) return null
  const n = Number(header)
  return Number.isFinite(n) && n >= 0 ? n : null
}

/** 同一时刻只允许一个刷新请求在飞，其余并发请求复用它的结果。 */
let refreshing: Promise<boolean> | null = null

async function refreshTokens(): Promise<boolean> {
  const refreshToken = hooks.getRefreshToken()
  if (!refreshToken) return false
  if (!refreshing) {
    refreshing = (async () => {
      try {
        const res = await fetch(buildUrl('/auth/refresh'), {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
          body: JSON.stringify({ refreshToken }),
          credentials: 'same-origin',
        })
        if (!res.ok) return false
        const envelope = (await res.json()) as ApiEnvelope<LoginResult>
        if (!envelope?.success || !envelope.data) return false
        hooks.onRefreshed(envelope.data)
        return true
      } catch {
        return false
      } finally {
        refreshing = null
      }
    })()
  }
  return refreshing
}

/** 发请求；401 时尝试用刷新令牌续期并重放一次。 */
async function send(path: string, opts: RequestOptions): Promise<Response> {
  const build = (): Promise<Response> => {
    const headers: Record<string, string> = { Accept: 'application/json' }
    const token = hooks.getToken()
    if (token) headers.Authorization = `Bearer ${token}`
    let body: BodyInit | undefined
    if (opts.body !== undefined) {
      headers['Content-Type'] = 'application/json'
      body = JSON.stringify(opts.body)
    }
    return fetch(buildUrl(path, opts.query), {
      method: opts.method ?? 'GET',
      headers,
      body,
      signal: opts.signal,
      credentials: 'same-origin',
    })
  }

  let res: Response
  try {
    res = await build()
    if (res.status === 401 && !opts.skipAuthRedirect) {
      const refreshed = await refreshTokens()
      if (refreshed) res = await build()
    }
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e
    throw new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '无法连接服务器，请检查网络或稍后再试' })
  }
  return res
}

export async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  return unwrap<T>(await send(path, opts), opts)
}

/** 解析响应体并统一抛出 ApiError；success=false 或非 2xx 都走同一套错误映射。 */
async function unwrap<T>(res: Response, opts: RequestOptions): Promise<T> {
  const traceId = res.headers.get('X-Trace-Id')
  let envelope: ApiEnvelope<T> | null = null
  const text = await res.text()
  if (text) {
    try {
      envelope = JSON.parse(text) as ApiEnvelope<T>
    } catch {
      envelope = null
    }
  }

  if (res.ok && envelope?.success) return envelope.data as T

  if (res.status === 401 && !opts.skipAuthRedirect) hooks.onUnauthorized()

  throw new ApiError({
    status: res.status,
    code: envelope?.code ?? (res.status >= 500 ? 'GATEWAY_ERROR' : 'UNKNOWN_ERROR'),
    message: envelope?.message ?? `服务器返回了无法识别的响应（HTTP ${res.status}）`,
    errors: envelope?.errors,
    traceId: envelope?.traceId ?? traceId,
    retryAfter: parseRetryAfter(res.headers.get('Retry-After')),
  })
}

/** 带刷新重试的通用请求（multipart / text / blob 复用）。 */
async function sendCustom(build: () => Promise<Response>, retryOn401: boolean): Promise<Response> {
  let res: Response
  try {
    res = await build()
    if (res.status === 401 && retryOn401) {
      const refreshed = await refreshTokens()
      if (refreshed) res = await build()
    }
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e
    throw new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '无法连接服务器，请检查网络或稍后再试' })
  }
  return res
}

async function requestForm<T>(path: string, form: FormData, signal?: AbortSignal): Promise<T> {
  const build = () => {
    const headers: Record<string, string> = { Accept: 'application/json' }
    const token = hooks.getToken()
    if (token) headers.Authorization = `Bearer ${token}`
    return fetch(buildUrl(path), { method: 'POST', headers, body: form, signal, credentials: 'same-origin' })
  }
  return unwrap<T>(await sendCustom(build, true), { method: 'POST' })
}

/** 以 text/csv 之类的纯文本作为请求体提交（CSV 导入用，不是 JSON 信封）。 */
async function requestText<T>(path: string, text: string, contentType: string): Promise<T> {
  const build = () => {
    const headers: Record<string, string> = { Accept: 'application/json', 'Content-Type': contentType }
    const token = hooks.getToken()
    if (token) headers.Authorization = `Bearer ${token}`
    return fetch(buildUrl(path), { method: 'POST', headers, body: text, credentials: 'same-origin' })
  }
  return unwrap<T>(await sendCustom(build, true), { method: 'POST' })
}

/** 下载二进制/文本响应：令牌在 Authorization 头里，不能直接用 <a href> 打开。 */
async function requestBlob(path: string, query?: RequestOptions['query']): Promise<Blob> {
  const build = () => {
    const headers: Record<string, string> = {}
    const token = hooks.getToken()
    if (token) headers.Authorization = `Bearer ${token}`
    return fetch(buildUrl(path, query), { headers, credentials: 'same-origin' })
  }
  const res = await sendCustom(build, true)
  if (!res.ok) {
    if (res.status === 401) hooks.onUnauthorized()
    throw new ApiError({ status: res.status, code: 'DOWNLOAD_FAILED', message: `导出失败（HTTP ${res.status}）` })
  }
  return res.blob()
}

export const http = {
  get: <T>(path: string, query?: RequestOptions['query'], signal?: AbortSignal) =>
    request<T>(path, { query, signal }),
  /** 纯文本请求体（text/csv 等） */
  postText: <T>(path: string, text: string, contentType = 'text/csv') => requestText<T>(path, text, contentType),
  /** 需要鉴权的文件下载 */
  blob: (path: string, query?: RequestOptions['query']) => requestBlob(path, query),
  post: <T>(path: string, body?: unknown, extra?: Omit<RequestOptions, 'method' | 'body'>) =>
    request<T>(path, { ...extra, method: 'POST', body }),
  put: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PUT', body }),
  patch: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PATCH', body }),
  del: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
  /** multipart 上传：不设置 Content-Type，交给浏览器补齐 boundary。 */
  upload: <T>(path: string, form: FormData, signal?: AbortSignal) => requestForm<T>(path, form, signal),
}
