import type { MyScheduleItem, Schedule, WeekParity } from '@/api/types'
import { parseServerTime } from './time'

/**
 * 课表相关的纯函数。
 * 周次口径：第 1 周从学期 startDate 当天算起，每 7 天一周（不处理节假日调课）。
 */

export const PERIODS = 12
export const WEEKDAYS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'] as const

export type TimetableItem = Pick<
  MyScheduleItem,
  'courseId' | 'courseCode' | 'courseName' | 'teacherNames' | 'dayOfWeek'
> &
  Pick<Schedule, 'startPeriod' | 'endPeriod' | 'startWeek' | 'endWeek' | 'weekParity' | 'classroom'> & {
    /** 手工排课 ID（自动排课来源时为 null） */
    scheduleId?: number | null
    /** 自动排课分配 ID（手工排课来源时为 null） */
    assignmentId?: number | null
    /** SCHEDULING / COURSE，用于区分来源 */
    source?: 'SCHEDULING' | 'COURSE'
  }

export function weekdayName(day: number): string {
  return WEEKDAYS[day - 1] ?? `第 ${day} 天`
}

/**
 * 学期总周数：第 1 周从 startDate 起算，有 endDate 时按结束日期反推；
 * 缺日期时退回 fallback。
 * <p>
 * 之前页面把周次写死上限 30，学期跨度一大（例如 3 月初到 11 月底 ≈ 38 周），
 * 「今天第几周」会被压到 30，而课程只排 1–16 周，于是永远匹配不到课、课表整页空白。
 */
export function semesterWeeks(
  startDate: string | null | undefined,
  endDate: string | null | undefined,
  fallback = 20,
): number {
  const start = parseServerTime(startDate ? `${startDate}T00:00:00` : null)
  if (Number.isNaN(start)) return fallback
  const end = parseServerTime(endDate ? `${endDate}T00:00:00` : null)
  if (Number.isNaN(end) || end < start) return fallback
  return Math.max(1, Math.ceil((end - start + 1) / 86_400_000 / 7))
}

/** 从学期开始日期推算「今天是第几周」，在 1–maxWeek 之间收敛 */
export function currentWeek(
  startDate: string | null | undefined,
  now = Date.now(),
  maxWeek = 30,
): number {
  const start = parseServerTime(startDate ? `${startDate}T00:00:00` : null)
  if (Number.isNaN(start)) return 1
  const days = Math.floor((now - start) / 86_400_000)
  return Math.max(1, Math.min(Math.max(1, maxWeek), Math.floor(days / 7) + 1))
}

/** 第 week 周对应的日期区间，用于页面上标注「10.06 – 10.12」 */
export function weekRange(startDate: string | null | undefined, week: number): { from: string; to: string } | null {
  const start = parseServerTime(startDate ? `${startDate}T00:00:00` : null)
  if (Number.isNaN(start)) return null
  const from = new Date(start + (week - 1) * 7 * 86_400_000)
  const to = new Date(from.getTime() + 6 * 86_400_000)
  const fmt = (d: Date) =>
    `${String(d.getMonth() + 1).padStart(2, '0')}.${String(d.getDate()).padStart(2, '0')}`
  return { from: fmt(from), to: fmt(to) }
}

export function parityOf(value: WeekParity | string | null | undefined): WeekParity {
  return value === 'ODD' || value === 'EVEN' ? value : 'ALL'
}

/** 某周是否会上这门课：周次区间命中，且单双周不冲突 */
export function matchesWeek(
  item: { startWeek: number; endWeek: number; weekParity: WeekParity | string },
  week: number,
): boolean {
  if (week < item.startWeek || week > item.endWeek) return false
  const parity = parityOf(item.weekParity)
  if (parity === 'ALL') return true
  return parity === 'ODD' ? week % 2 === 1 : week % 2 === 0
}

/** 「周一 1–2 节 · A101 · 1–16 周（单周）」 */
export function formatSchedule(s: {
  dayOfWeek: number
  startPeriod: number
  endPeriod: number
  startWeek: number
  endWeek: number
  weekParity: WeekParity | string
  classroom: string | null
}): string {
  const period = s.startPeriod === s.endPeriod ? `${s.startPeriod}` : `${s.startPeriod}–${s.endPeriod}`
  const parity = parityOf(s.weekParity)
  const parityText = parity === 'ODD' ? '，单周' : parity === 'EVEN' ? '，双周' : ''
  const weeks = `${s.startWeek}–${s.endWeek} 周${parityText}`
  const room = s.classroom ? ` · ${s.classroom}` : ''
  return `${weekdayName(s.dayOfWeek)} ${period} 节 · ${weeks}${room}`
}

/** 同一天、节次区间相交（用于前端提示，真正的冲突判定在后端） */
export function periodsOverlap(a: TimetableItem, b: TimetableItem): boolean {
  return a.dayOfWeek === b.dayOfWeek && a.startPeriod <= b.endPeriod && b.startPeriod <= a.endPeriod
}
