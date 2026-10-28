import type { AiCitation, AiMessageView, AiChatResponse } from '@/api/types'

/** 问答页内部使用的消息形态：统一历史消息与本次请求的返回。 */
export interface ChatItem {
  key: string
  role: 'USER' | 'ASSISTANT'
  content: string
  status: 'SUCCESS' | 'FAILED' | 'NO_EVIDENCE' | 'PENDING'
  citations: AiCitation[]
  model: string | null
  totalTokens: number | null
  tokenSource: string | null
  errorCode: string | null
  errorText: string | null
  traceId: string | null
  createdAt: string | number
}

export function fromMessage(m: AiMessageView): ChatItem | null {
  if (m.role !== 'USER' && m.role !== 'ASSISTANT') return null
  const total =
    m.promptTokens !== null || m.completionTokens !== null ? (m.promptTokens ?? 0) + (m.completionTokens ?? 0) : null
  return {
    key: `m${m.id}`,
    role: m.role,
    content: m.content ?? '',
    status: m.role === 'USER' ? 'SUCCESS' : (m.status ?? 'SUCCESS'),
    citations: [...(m.citations ?? [])].sort((a, b) => a.rankNo - b.rankNo),
    model: m.model,
    totalTokens: total,
    tokenSource: m.tokenSource,
    errorCode: m.errorCode,
    errorText: null,
    traceId: null,
    createdAt: m.createdAt,
  }
}

export function fromChatResponse(r: AiChatResponse): ChatItem {
  return {
    key: `m${r.assistantMessageId}`,
    role: 'ASSISTANT',
    content: r.answer,
    status: r.sufficientEvidence ? 'SUCCESS' : 'NO_EVIDENCE',
    citations: [...r.citations].sort((a, b) => a.rankNo - b.rankNo),
    model: r.model,
    totalTokens: r.tokenUsage?.totalTokens ?? null,
    tokenSource: r.tokenUsage?.source ?? null,
    errorCode: null,
    errorText: null,
    traceId: null,
    createdAt: Date.now(),
  }
}
