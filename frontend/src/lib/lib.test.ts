import { describe, expect, it } from 'vitest'
import { estimateSkew, formatCountdown, parseServerTime } from './time'
import { renderMarkdown } from './markdown'
import { linkCitations, mentionedRanks } from './citations'
import { coerceArguments } from './toolArgs'
import { safeRedirect } from '@/router/redirect'

describe('parseServerTime', () => {
  it('无偏移的业务时间按 Asia/Shanghai 解释', () => {
    expect(parseServerTime('2026-10-27T22:40:00')).toBe(Date.parse('2026-10-27T14:40:00Z'))
  })
  it('带小数秒', () => {
    expect(parseServerTime('2026-10-27T22:40:00.5')).toBe(Date.parse('2026-10-27T14:40:00.500Z'))
  })
  it('带偏移的瞬时值原样解析', () => {
    expect(parseServerTime('2026-10-27T22:30:00+08:00')).toBe(Date.parse('2026-10-27T14:30:00Z'))
    expect(parseServerTime('2026-10-27T14:30:00Z')).toBe(Date.parse('2026-10-27T14:30:00Z'))
  })
  it('非法输入返回 NaN', () => {
    expect(parseServerTime('')).toBeNaN()
    expect(parseServerTime(null)).toBeNaN()
    expect(parseServerTime('明天')).toBeNaN()
  })
})

describe('countdown & skew', () => {
  it('formatCountdown', () => {
    expect(formatCountdown(600_000)).toBe('10:00')
    expect(formatCountdown(59_001)).toBe('01:00')
    expect(formatCountdown(-5)).toBe('00:00')
  })
  it('estimateSkew 取往返中点', () => {
    const local = Date.parse('2026-10-27T14:30:00Z')
    // 服务器比本机快 30 秒，往返 200ms
    expect(estimateSkew('2026-10-27T22:30:30.100+08:00', local, local + 200)).toBe(30_000)
  })
})

describe('renderMarkdown', () => {
  it('源文中的 HTML 被转义，不会执行', () => {
    const out = renderMarkdown('<img src=x onerror=alert(1)> **粗**')
    expect(out).not.toContain('<img')
    expect(out).toContain('<strong>粗</strong>')
  })
  it('拦截 javascript: 链接', () => {
    const out = renderMarkdown('[点我](javascript:alert(1))')
    expect(out).not.toMatch(/href="javascript/i)
  })
  it('外链加 noopener', () => {
    const out = renderMarkdown('[学校](https://example.edu)')
    expect(out).toContain('rel="noopener noreferrer"')
    expect(out).toContain('target="_blank"')
  })
})

describe('linkCitations', () => {
  it('只替换存在的引用序号，跳过代码', () => {
    const html = renderMarkdown('需要录取通知书[1]和身份证[2]，见 `arr[1]`，另有[9]。')
    const out = linkCitations(html, new Set([1, 2]), 'c')
    expect(out).toContain('href="#c-1"')
    expect(out).toContain('href="#c-2"')
    expect(out).toContain('<code>arr[1]</code>')
    expect(out).toContain('[9]')
  })
  it('mentionedRanks', () => {
    expect([...mentionedRanks('a[1] b[3][1]')].sort()).toEqual([1, 3])
  })
})

describe('coerceArguments', () => {
  const params = [
    { name: 'courseId', type: 'integer' as const, required: true, description: '' },
    { name: 'startAt', type: 'datetime' as const, required: false, description: '' },
    { name: 'spaceIds', type: 'array' as const, required: false, description: '' },
  ]
  it('按类型整形', () => {
    const r = coerceArguments(params, { courseId: '12', startAt: '2026-10-28T10:00', spaceIds: '1, 2，3' })
    expect(r.errors).toEqual({})
    expect(r.args).toEqual({ courseId: 12, startAt: '2026-10-28T10:00:00', spaceIds: [1, 2, 3] })
  })
  it('必填缺失与类型错误', () => {
    expect(coerceArguments(params, { courseId: '' }).errors).toEqual({ courseId: '必填' })
    expect(coerceArguments(params, { courseId: '1.5' }).errors).toEqual({ courseId: '需要整数' })
  })
})

describe('safeRedirect', () => {
  it('只接受站内路径', () => {
    expect(safeRedirect('/ai/3')).toBe('/ai/3')
    expect(safeRedirect('//evil.com')).toBe('/')
    expect(safeRedirect('https://evil.com')).toBe('/')
    expect(safeRedirect(undefined)).toBe('/')
  })
})
