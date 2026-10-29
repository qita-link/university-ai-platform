import { describe, expect, it } from 'vitest'
import { currentWeek, formatSchedule, matchesWeek, parityOf, weekRange, weekdayName } from './schedule'

describe('weekdayName', () => {
  it('1–7 对应周一到周日', () => {
    expect(weekdayName(1)).toBe('周一')
    expect(weekdayName(7)).toBe('周日')
  })

  it('越界时给出可读兜底', () => {
    expect(weekdayName(9)).toBe('第 9 天')
  })
})

describe('currentWeek', () => {
  // 学期 2026-09-07 开学（周一）
  it('开学当天是第 1 周', () => {
    const day1 = Date.parse('2026-09-07T09:00:00+08:00')
    expect(currentWeek('2026-09-07', day1)).toBe(1)
  })

  it('第 8 周周三落在第 8 周', () => {
    const d = Date.parse('2026-10-28T10:00:00+08:00')
    expect(currentWeek('2026-09-07', d)).toBe(8)
  })

  it('开学前的一天按第 1 周处理', () => {
    const before = Date.parse('2026-09-01T10:00:00+08:00')
    expect(currentWeek('2026-09-07', before)).toBe(1)
  })

  it('超过 30 周收敛到 30，无学期日期时回落到 1', () => {
    const far = Date.parse('2028-01-01T10:00:00+08:00')
    expect(currentWeek('2026-09-07', far)).toBe(30)
    expect(currentWeek(null)).toBe(1)
  })
})

describe('weekRange', () => {
  it('第 8 周对应 10.26 – 11.01', () => {
    expect(weekRange('2026-09-07', 8)).toEqual({ from: '10.26', to: '11.01' })
  })

  it('没有学期日期时返回 null', () => {
    expect(weekRange(null, 1)).toBeNull()
  })
})

describe('matchesWeek', () => {
  const odd = { startWeek: 3, endWeek: 14, weekParity: 'ODD' }
  const all = { startWeek: 1, endWeek: 16, weekParity: 'ALL' }

  it('单周课只出现在奇数周', () => {
    expect(matchesWeek(odd, 5)).toBe(true)
    expect(matchesWeek(odd, 6)).toBe(false)
  })

  it('周次区间外不出现', () => {
    expect(matchesWeek(odd, 1)).toBe(false)
    expect(matchesWeek(odd, 15)).toBe(false)
  })

  it('每周课在区间内都出现', () => {
    expect(matchesWeek(all, 1)).toBe(true)
    expect(matchesWeek(all, 16)).toBe(true)
    expect(matchesWeek(all, 17)).toBe(false)
  })

  it('未知单双周按每周处理', () => {
    expect(parityOf('???')).toBe('ALL')
    expect(matchesWeek({ startWeek: 1, endWeek: 4, weekParity: '???' }, 2)).toBe(true)
  })
})

describe('formatSchedule', () => {
  it('连堂课显示节次区间与单双周', () => {
    expect(
      formatSchedule({
        dayOfWeek: 5,
        startPeriod: 5,
        endPeriod: 6,
        startWeek: 3,
        endWeek: 14,
        weekParity: 'ODD',
        classroom: 'C305',
      }),
    ).toBe('周五 5–6 节 · 3–14 周，单周 · C305')
  })

  it('单节课不写区间，没有教室时省略地点', () => {
    expect(
      formatSchedule({
        dayOfWeek: 2,
        startPeriod: 4,
        endPeriod: 4,
        startWeek: 1,
        endWeek: 16,
        weekParity: 'ALL',
        classroom: null,
      }),
    ).toBe('周二 4 节 · 1–16 周')
  })
})
