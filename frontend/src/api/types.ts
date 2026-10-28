// 与 docs/04-api-design.md 及后端 DTO 对齐的类型。只声明 v0 用到的部分。

export interface ApiEnvelope<T> {
  success: boolean
  code: string
  message: string
  data?: T
  errors?: FieldError[]
  traceId?: string
  timestamp?: string
}

export interface FieldError {
  field: string
  message: string
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
  pages: number
}

// ---- 系统 / 认证 ----
export interface SystemInfo {
  name: string
  apiVersion: string
  serverTime: string // 带偏移量的瞬时值
  timeZone: string
}

export interface CurrentUser {
  userId: number
  username: string
  displayName: string
  orgUnitId: number | null
  roles: string[]
  permissions: string[]
}

export interface LoginResult {
  accessToken: string
  tokenType: 'Bearer'
  expiresIn: number
  expiresAt: string
  user: CurrentUser
}

// ---- 知识库 ----
export interface SpaceView {
  id: number
  categoryId: number | null
  categoryName: string | null
  name: string
  description: string | null
  visibility: 'PUBLIC' | 'ORG' | 'PRIVATE' | 'COURSE'
  status: string
  accessLevel: string
  memberCount: number
}

export interface DocumentDetailView {
  id: number
  spaceId: number
  title: string
  originalFilename: string | null
  status: string
  processStatus: string
  currentVersionId: number | null
  versionNo: number | null
  uploadedByName: string | null
  content: string
  createdAt: string
  updatedAt: string
}

// ---- AI 问答 ----
export interface AiCitation {
  chunkId: number
  documentId: number
  versionId: number
  spaceId: number
  spaceName: string
  documentTitle: string
  headingPath: string | null
  snippet: string
  score: number
  rankNo: number
}

export interface TokenUsage {
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
  source: 'REAL' | 'ESTIMATED'
}

export interface AiChatRequest {
  message: string
  conversationId: number | null
  spaceIds: number[] | null
}

export interface AiChatResponse {
  conversationId: number
  userMessageId: number
  assistantMessageId: number
  answer: string
  sufficientEvidence: boolean
  model: string | null
  citations: AiCitation[]
  tokenUsage: TokenUsage | null
}

export type AiMessageStatus = 'SUCCESS' | 'FAILED' | 'NO_EVIDENCE'

export interface AiMessageView {
  id: number
  role: 'USER' | 'ASSISTANT' | 'SYSTEM'
  content: string | null
  model: string | null
  promptTokens: number | null
  completionTokens: number | null
  tokenSource: string | null
  latencyMs: number | null
  status: AiMessageStatus | null
  errorCode: string | null
  createdAt: string
  citations: AiCitation[] | null
}

export interface ConversationView {
  id: number
  title: string | null
  status: string
  lastMessageAt: string | null
  messageCount: number
  createdAt: string
  updatedAt: string
}

export interface ConversationDetailView {
  id: number
  title: string | null
  status: string
  lastMessageAt: string | null
  createdAt: string
  updatedAt: string
  messages: AiMessageView[]
}

// ---- AI 工具 ----
export type ToolParamType = 'string' | 'integer' | 'number' | 'boolean' | 'datetime' | 'enum' | 'array'

export interface ToolParameter {
  name: string
  type: ToolParamType
  required: boolean
  description: string
}

export interface AiToolView {
  name: string
  title: string
  description: string
  sideEffect: boolean
  requiredPermissions: string[]
  parameters: ToolParameter[]
}

export type ToolCallStatus =
  | 'PENDING_CONFIRM'
  | 'EXECUTING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'REJECTED'
  | 'EXPIRED'

export interface AiToolCallView {
  id: number
  toolName: string
  sideEffect: boolean
  arguments: Record<string, unknown> | null
  argumentSummary: string | null
  preview: string | null
  status: ToolCallStatus
  resultSummary: string | null
  errorCode: string | null
  errorMessage: string | null
  confirmExpiresAt: string | null // 业务时区本地时间，无偏移
  confirmedAt: string | null
  executedAt: string | null
  latencyMs: number | null
  createdAt: string
  result: unknown
}
