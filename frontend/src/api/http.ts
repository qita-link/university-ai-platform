import type { ApiEnvelope, FieldError } from './types'

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
  /** 受保护接口返回 401 时调用（登录接口本身除外）。 */
  onUnauthorized: () => void
}

let hooks: HttpHooks = {
  getToken: () => null,
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
  /** 为 true 时 401 不触发全局登出（用于登录接口）。 */
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

export async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  const token = hooks.getToken()
  if (token) headers.Authorization = `Bearer ${token}`
  let body: BodyInit | undefined
  if (opts.body !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(opts.body)
  }

  let res: Response
  try {
    res = await fetch(buildUrl(path, opts.query), {
      method: opts.method ?? 'GET',
      headers,
      body,
      signal: opts.signal,
      credentials: 'same-origin',
    })
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e
    throw new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '无法连接服务器，请检查网络或稍后再试' })
  }

  return unwrap<T>(res, opts)
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

async function requestForm<T>(path: string, form: FormData, signal?: AbortSignal): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  const token = hooks.getToken()
  if (token) headers.Authorization = `Bearer ${token}`

  let res: Response
  try {
    res = await fetch(buildUrl(path), { method: 'POST', headers, body: form, signal, credentials: 'same-origin' })
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e
    throw new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '无法连接服务器，请检查网络或稍后再试' })
  }
  return unwrap<T>(res, { method: 'POST' })
}

/** 以 text/csv 之类的纯文本作为请求体提交（CSV 导入用，不是 JSON 信封）。 */
async function requestText<T>(path: string, text: string, contentType: string): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json', 'Content-Type': contentType }
  const token = hooks.getToken()
  if (token) headers.Authorization = `Bearer ${token}`

  let res: Response
  try {
    res = await fetch(buildUrl(path), { method: 'POST', headers, body: text, credentials: 'same-origin' })
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e
    throw new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '无法连接服务器，请检查网络或稍后再试' })
  }
  return unwrap<T>(res, { method: 'POST' })
}

/** 下载二进制/文本响应：令牌在 Authorization 头里，不能直接用 <a href> 打开。 */
async function requestBlob(path: string, query?: RequestOptions['query']): Promise<Blob> {
  const headers: Record<string, string> = {}
  const token = hooks.getToken()
  if (token) headers.Authorization = `Bearer ${token}`

  let res: Response
  try {
    res = await fetch(buildUrl(path, query), { headers, credentials: 'same-origin' })
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e
    throw new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '无法连接服务器，请检查网络或稍后再试' })
  }
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
