import { http } from './http'
import type {
  AiChatRequest,
  AiChatResponse,
  AiToolCallView,
  AiToolView,
  ConversationDetailView,
  ConversationView,
  CurrentUser,
  DocumentDetailView,
  LoginResult,
  PageResult,
  SpaceView,
  SystemInfo,
  ToolCallStatus,
} from './types'

export const systemApi = {
  info: () => http.get<SystemInfo>('/system/info'),
}

export const authApi = {
  login: (username: string, password: string) =>
    http.post<LoginResult>('/auth/login', { username, password }, { skipAuthRedirect: true }),
  logout: () => http.post<void>('/auth/logout'),
  me: () => http.get<CurrentUser>('/auth/me'),
}

export const knowledgeApi = {
  spaces: (query: { page?: number; size?: number; keyword?: string } = {}) =>
    http.get<PageResult<SpaceView>>('/knowledge/spaces', { page: 1, size: 100, ...query }),
  document: (id: number) => http.get<DocumentDetailView>(`/knowledge/documents/${id}`),
}

export const aiApi = {
  chat: (body: AiChatRequest) => http.post<AiChatResponse>('/ai/chat', body),
  conversations: (page = 1, size = 50) =>
    http.get<PageResult<ConversationView>>('/ai/conversations', { page, size }),
  conversation: (id: number) => http.get<ConversationDetailView>(`/ai/conversations/${id}`),
  deleteConversation: (id: number) => http.del<void>(`/ai/conversations/${id}`),

  tools: () => http.get<AiToolView[]>('/ai/tools'),
  invokeTool: (tool: string, args: Record<string, unknown>, conversationId: number | null) =>
    http.post<AiToolCallView>('/ai/tool-calls', { tool, arguments: args, conversationId }),
  confirmTool: (id: number) => http.post<AiToolCallView>(`/ai/tool-calls/${id}/confirm`),
  rejectTool: (id: number, reason?: string) =>
    http.post<AiToolCallView>(`/ai/tool-calls/${id}/reject`, reason ? { reason } : {}),
  toolCall: (id: number) => http.get<AiToolCallView>(`/ai/tool-calls/${id}`),
  toolCalls: (query: { status?: ToolCallStatus; toolName?: string; page?: number; size?: number } = {}) =>
    http.get<PageResult<AiToolCallView>>('/ai/tool-calls', { page: 1, size: 20, ...query }),
}
