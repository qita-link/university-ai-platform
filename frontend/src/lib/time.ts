/**
 * 时间工具。
 *
 * 后端约定：业务对象的时间（如 confirmExpiresAt、createdAt）是 Asia/Shanghai 本地时间，不带偏移；
 * 令牌过期时间等瞬时值带偏移。前端解析时必须区分，不能直接交给 Date.parse（会被当成浏览器本地时区）。
 */

export const BUSINESS_TZ = 'Asia/Shanghai'
const BUSINESS_OFFSET = '+08:00' // 上海无夏令时

const HAS_ZONE = /(Z|[+-]\d{2}:?\d{2})$/i
const LOCAL_DT = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})(?::(\d{2})(?:\.(\d+))?)?$/

/** 把后端时间字符串解析为毫秒时间戳；无偏移的按业务时区解释。无法解析返回 NaN。 */
export function parseServerTime(value: string | null | undefined): number {
  if (!value) return Number.NaN
  const s = value.trim()
  if (HAS_ZONE.test(s)) return Date.parse(s)
  const m = LOCAL_DT.exec(s)
  if (!m) return Number.NaN
  const [, date, hm, sec = '00', frac = ''] = m
  const ms = frac ? '.' + frac.padEnd(3, '0').slice(0, 3) : ''
  return Date.parse(`${date}T${hm}:${sec}${ms}${BUSINESS_OFFSET}`)
}

const fmtDateTime = new Intl.DateTimeFormat('zh-CN', {
  timeZone: BUSINESS_TZ,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})

const fmtTime = new Intl.DateTimeFormat('zh-CN', {
  timeZone: BUSINESS_TZ,
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})

const fmtMonthDay = new Intl.DateTimeFormat('zh-CN', {
  timeZone: BUSINESS_TZ,
  month: 'numeric',
  day: 'numeric',
})

/** 以业务时区显示：2026/10/28 15:02 */
export function formatDateTime(value: string | number | null | undefined): string {
  const t = typeof value === 'number' ? value : parseServerTime(value)
  return Number.isFinite(t) ? fmtDateTime.format(t) : '—'
}

/** 会话列表用的相对短格式：今天显示时刻，其余显示月日。 */
export function formatShort(value: string | null | undefined, now: number): string {
  const t = parseServerTime(value)
  if (!Number.isFinite(t)) return ''
  const sameDay = fmtMonthDay.format(t) === fmtMonthDay.format(now)
  return sameDay ? fmtTime.format(t) : fmtMonthDay.format(t)
}

/** 剩余毫秒格式化为 mm:ss（不足 0 时为 00:00）。 */
export function formatCountdown(remainingMs: number): string {
  const total = Math.max(0, Math.ceil(remainingMs / 1000))
  const m = Math.floor(total / 60)
  const s = total % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

/**
 * 根据一次请求估算本机与服务器的时钟差（server - local，毫秒）。
 * 取请求往返的中点作为服务器生成时间的近似本机时刻。
 */
export function estimateSkew(serverTime: string, sentAt: number, receivedAt: number): number {
  const server = parseServerTime(serverTime)
  if (!Number.isFinite(server)) return 0
  return server - (sentAt + receivedAt) / 2
}
