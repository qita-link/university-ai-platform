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

  if (res.ok && envelope?.success) {
    return envelope.data as T
  }

  const err = new ApiError({
    status: res.status,
    code: envelope?.code ?? (res.status >= 500 ? 'GATEWAY_ERROR' : 'UNKNOWN_ERROR'),
    message: envelope?.message ?? `服务器返回了无法识别的响应（HTTP ${res.status}）`,
    errors: envelope?.errors,
    traceId: envelope?.traceId ?? traceId,
    retryAfter: parseRetryAfter(res.headers.get('Retry-After')),
  })

  if (res.status === 401 && !opts.skipAuthRedirect) {
    hooks.onUnauthorized()
  }
  throw err
}

export const http = {
  get: <T>(path: string, query?: RequestOptions['query'], signal?: AbortSignal) =>
    request<T>(path, { query, signal }),
  post: <T>(path: string, body?: unknown, extra?: Omit<RequestOptions, 'method' | 'body'>) =>
    request<T>(path, { ...extra, method: 'POST', body }),
  put: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PUT', body }),
  patch: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PATCH', body }),
  del: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
}
