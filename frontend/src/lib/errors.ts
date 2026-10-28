import { ApiError } from '@/api/http'

/** 面向用户的错误文案。后端 message 已是中文，这里只对需要额外指引的错误码改写。 */
const OVERRIDES: Record<string, string> = {
  NETWORK_ERROR: '无法连接服务器，请检查网络或稍后再试',
  GATEWAY_ERROR: '服务暂时不可用，请稍后再试',
  SERVICE_UNAVAILABLE: '模型服务暂时不可用，问题已记录，请稍后再试',
  AI_TOOL_CONFIRM_EXPIRED: '确认已超时，这次操作没有执行。如仍需要，请重新发起',
  AI_TOOL_STATUS_INVALID: '这条操作已被处理（可能在其他窗口确认或拒绝过），请刷新状态',
  FORBIDDEN: '你没有执行此操作的权限',
  NOT_FOUND: '内容不存在，或你无权查看',
}

export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) return OVERRIDES[e.code] ?? e.message
  if (e instanceof Error) return e.message
  return '发生未知错误'
}

export function traceIdOf(e: unknown): string | null {
  return e instanceof ApiError ? e.traceId : null
}
