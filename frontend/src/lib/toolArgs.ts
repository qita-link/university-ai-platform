import type { ToolParameter } from '@/api/types'

/**
 * 把表单里的原始输入按工具参数声明（ToolParameter.type）整形为请求参数。
 * 这里只做形状整理和基础提示；后端 AiToolArguments 会再完整校验一次。
 */
export type RawValue = string | boolean

export interface CoerceResult {
  args: Record<string, unknown>
  errors: Record<string, string>
}

const INT = /^-?\d+$/

function coerceOne(p: ToolParameter, raw: RawValue): { value?: unknown; error?: string; empty: boolean } {
  if (p.type === 'boolean') return { value: raw === true, empty: false }

  const s = typeof raw === 'string' ? raw.trim() : ''
  if (s === '') return { empty: true }

  switch (p.type) {
    case 'integer':
      return INT.test(s) ? { value: Number(s), empty: false } : { error: '需要整数', empty: false }
    case 'number': {
      const n = Number(s)
      return Number.isFinite(n) ? { value: n, empty: false } : { error: '需要数字', empty: false }
    }
    case 'datetime': {
      // <input type="datetime-local"> 给出 2026-10-28T10:00；后端要 ISO 本地时间，补齐秒
      const m = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})(:\d{2})?$/.exec(s)
      return m ? { value: `${m[1]}T${m[2]}${m[3] ?? ':00'}`, empty: false } : { error: '时间格式不正确', empty: false }
    }
    case 'array': {
      const parts = s.split(/[\s,，、]+/).filter(Boolean)
      return { value: parts.map((x) => (INT.test(x) ? Number(x) : x)), empty: parts.length === 0 }
    }
    default:
      return { value: s, empty: false }
  }
}

export function coerceArguments(params: ToolParameter[], raw: Record<string, RawValue>): CoerceResult {
  const args: Record<string, unknown> = {}
  const errors: Record<string, string> = {}
  for (const p of params) {
    const r = coerceOne(p, raw[p.name] ?? (p.type === 'boolean' ? false : ''))
    if (r.error) errors[p.name] = r.error
    else if (r.empty) {
      if (p.required) errors[p.name] = '必填'
    } else args[p.name] = r.value
  }
  return { args, errors }
}

export function initialRaw(params: ToolParameter[]): Record<string, RawValue> {
  return Object.fromEntries(params.map((p) => [p.name, p.type === 'boolean' ? false : '']))
}

export const PARAM_TYPE_LABEL: Record<string, string> = {
  string: '文本',
  integer: '整数',
  number: '数字',
  boolean: '是/否',
  datetime: '时间',
  enum: '枚举',
  array: '列表，用逗号分隔',
}
