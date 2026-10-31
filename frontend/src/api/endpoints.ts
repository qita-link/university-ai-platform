import { http } from './http'
import type {
  ActHistoryView,
  AgentActResponse,
  AiChatRequest,
  AiChatResponse,
  AiToolCallView,
  AiToolView,
  AuditLogView,
  AvailableCourse,
  ChunkView,
  ConversationDetailView,
  ConversationView,
  CourseDetail,
  CourseSummary,
  CourseTeacher,
  CurrentUser,
  DocumentDetailView,
  DocumentSummaryView,
  ForumCategory,
  ForumComment,
  ForumCommentPage,
  ForumPostDetail,
  ForumPostSummary,
  KnowledgeCategory,
  LeaveView,
  LoginResult,
  MyScheduleItem,
  OrgTreeNode,
  OrgUnitView,
  PageResult,
  PermissionView,
  RoleView,
  RosterItem,
  Schedule,
  SelectionView,
  SemesterView,
  SpaceMemberView,
  SpaceView,
  SystemInfo,
  ToolCallStatus,
  UploadBatchView,
  UserView,
  VersionDetailView,
  VersionView,
  TranscriptView,
  GradeBookView,
  GradeRuleView,
  EvaluationTaskView,
  EvaluationOverview,
  EvaluationCourseSummary,
  FeedbackView,
  FreeSlotView,
  FreeWeekView,
  AttendanceSessionView,
  AttendanceSessionDetailView,
  ClassTimetableView,
  ClassAttendanceStatsView,
  ClassOptionView,
  ClassCounselorView,
  SettingView,
  LeaveFlowView,
  ProfileView,
  ProfilePostView,
  NotificationView,
  MyAttendanceView,
  AppealView,
  RoomBookingView,
} from './types'

export const systemApi = {
  info: () => http.get<SystemInfo>('/system/info'),
}

export const authApi = {
  login: (username: string, password: string) =>
    http.post<LoginResult>('/auth/login', { username, password }, { skipAuthRedirect: true }),
  logout: () => http.post<void>('/auth/logout'),
  me: () => http.get<CurrentUser>('/auth/me'),
  /** 成功后旧令牌全部失效，返回新的登录态（含新令牌）。 */
  changePassword: (oldPassword: string, newPassword: string) =>
    http.put<LoginResult>('/auth/password', { oldPassword, newPassword }),
}

export const knowledgeApi = {
  // 分类
  categories: () => http.get<KnowledgeCategory[]>('/knowledge/categories'),
  createCategory: (body: { parentId: number | null; name: string; description: string | null; sortOrder: number }) =>
    http.post<KnowledgeCategory>('/knowledge/categories', body),
  updateCategory: (
    id: number,
    body: { parentId: number | null; name: string; description: string | null; sortOrder: number },
  ) => http.put<KnowledgeCategory>(`/knowledge/categories/${id}`, body),
  deleteCategory: (id: number) => http.del<void>(`/knowledge/categories/${id}`),

  // 成员
  members: (spaceId: number) => http.get<SpaceMemberView[]>(`/knowledge/spaces/${spaceId}/members`),
  addMember: (spaceId: number, body: { userId: number; memberRole: string }) =>
    http.post<SpaceMemberView>(`/knowledge/spaces/${spaceId}/members`, body),
  updateMember: (spaceId: number, userId: number, memberRole: string) =>
    http.put<SpaceMemberView>(`/knowledge/spaces/${spaceId}/members/${userId}`, { memberRole }),
  removeMember: (spaceId: number, userId: number) =>
    http.del<void>(`/knowledge/spaces/${spaceId}/members/${userId}`),

  // 空间
  spaces: (
    query: {
      page?: number
      size?: number
      keyword?: string
      categoryId?: number | null
      visibility?: string
      includeArchived?: boolean
    } = {},
  ) => http.get<PageResult<SpaceView>>('/knowledge/spaces', { page: 1, size: 12, ...query }),
  space: (id: number) => http.get<SpaceView>(`/knowledge/spaces/${id}`),
  createSpace: (body: {
    categoryId: number | null
    name: string
    description: string | null
    visibility: string
    scopeOrgUnitId?: number | null
  }) => http.post<SpaceView>('/knowledge/spaces', body),

  // 文档
  documents: (
    spaceId: number,
    query: { page?: number; size?: number; keyword?: string; processStatus?: string } = {},
  ) =>
    http.get<PageResult<DocumentSummaryView>>(`/knowledge/spaces/${spaceId}/documents`, {
      page: 1,
      size: 20,
      ...query,
    }),
  document: (id: number) => http.get<DocumentDetailView>(`/knowledge/documents/${id}`),
  createDocument: (
    spaceId: number,
    body: { title: string; content: string; changeNote: string | null },
  ) => http.post<DocumentSummaryView>(`/knowledge/spaces/${spaceId}/documents`, body),
  updateDocument: (
    id: number,
    body: { title: string; content: string; changeNote: string | null; version: number },
  ) => http.put<DocumentSummaryView>(`/knowledge/documents/${id}`, body),
  deleteDocument: (id: number) => http.del<void>(`/knowledge/documents/${id}`),
  reprocess: (id: number) => http.post<DocumentSummaryView>(`/knowledge/documents/${id}/reprocess`),
  chunks: (id: number) => http.get<ChunkView[]>(`/knowledge/documents/${id}/chunks`),
  versions: (id: number) => http.get<VersionView[]>(`/knowledge/documents/${id}/versions`),
  version: (id: number, versionNo: number) =>
    http.get<VersionDetailView>(`/knowledge/documents/${id}/versions/${versionNo}`),

  upload: (spaceId: number, files: File[]) => {
    const form = new FormData()
    for (const f of files) form.append('files', f, f.name)
    return http.upload<UploadBatchView>(`/knowledge/spaces/${spaceId}/documents/batch-upload`, form)
  },
}

export const forumApi = {
  categories: (includeDisabled = false) =>
    http.get<ForumCategory[]>('/forum/categories', includeDisabled ? { includeDisabled: true } : {}),
  createCategory: (body: {
    name: string
    description: string | null
    sortOrder: number
    status?: string
    announcement?: boolean
  }) => http.post<ForumCategory>('/forum/categories', body),
  updateCategory: (
    id: number,
    body: { name: string; description: string | null; sortOrder: number; status?: string; announcement?: boolean },
  ) => http.put<ForumCategory>(`/forum/categories/${id}`, body),
  deleteCategory: (id: number) => http.del<void>(`/forum/categories/${id}`),
  posts: (
    query: {
      page?: number
      size?: number
      categoryId?: number | null
      keyword?: string
      authorId?: number
      status?: string
    } = {},
  ) => http.get<PageResult<ForumPostSummary>>('/forum/posts', { page: 1, size: 20, ...query }),
  post: (id: number) => http.get<ForumPostDetail>(`/forum/posts/${id}`),
  createPost: (body: { categoryId: number; title: string; content: string }) =>
    http.post<ForumPostDetail>('/forum/posts', body),
  updatePost: (
    id: number,
    body: { categoryId: number; title: string; content: string; version: number },
  ) => http.put<ForumPostDetail>(`/forum/posts/${id}`, body),
  deletePost: (id: number) => http.del<void>(`/forum/posts/${id}`),
  setPostStatus: (id: number, body: { status?: string; pinned?: boolean; pinnedDays?: number; reason?: string }) =>
    http.patch<ForumPostDetail>(`/forum/posts/${id}/status`, body),
  comments: (postId: number, page = 1, size = 20) =>
    http.get<ForumCommentPage>(`/forum/posts/${postId}/comments`, { page, size }),
  addComment: (postId: number, content: string, parentId: number | null) =>
    http.post<ForumComment>(`/forum/posts/${postId}/comments`, { content, parentId }),
  deleteComment: (id: number) => http.del<void>(`/forum/comments/${id}`),
}

export const semesterApi = {
  list: () => http.get<SemesterView[]>('/semesters'),
  current: () => http.get<SemesterView | null>('/semesters/current'),
  detail: (id: number) => http.get<SemesterView>(`/semesters/${id}`),
  create: (body: {
    code: string
    name: string
    startDate: string
    endDate: string
    selectionStartAt: string
    selectionEndAt: string
  }) => http.post<SemesterView>('/semesters', body),
  update: (
    id: number,
    body: {
      code: string
      name: string
      startDate: string
      endDate: string
      selectionStartAt: string
      selectionEndAt: string
    },
  ) => http.put<SemesterView>(`/semesters/${id}`, body),
  setStatus: (id: number, status: string) => http.patch<SemesterView>(`/semesters/${id}/status`, { status }),
}

export const courseApi = {
  list: (
    query: {
      page?: number
      size?: number
      semesterId?: number | null
      keyword?: string
      status?: string
      teacherId?: number
      orgUnitId?: number
    } = {},
  ) => http.get<PageResult<CourseSummary>>('/courses', { page: 1, size: 20, ...query }),
  /** 可选课程：本学期 OPEN，逐条带 selectable 与 blockedReason */
  available: (semesterId?: number | null) =>
    http.get<AvailableCourse[]>(
      '/courses/available',
      semesterId ? { semesterId } : undefined,
    ),
  detail: (id: number) => http.get<CourseDetail>(`/courses/${id}`),
  create: (body: {
    semesterId: number
    code: string
    name: string
    credit: number
    description: string | null
    orgUnitId: number | null
    capacity: number
  }) => http.post<CourseDetail>('/courses', body),
  update: (
    id: number,
    body: { name: string; credit: number; description: string | null; orgUnitId: number | null; capacity: number; version: number },
  ) => http.put<CourseDetail>(`/courses/${id}`, body),
  setStatus: (id: number, status: string, reason?: string) =>
    http.patch<CourseDetail>(`/courses/${id}/status`, { status, reason }),
  teachers: (id: number) => http.get<CourseTeacher[]>(`/courses/${id}/teachers`),
  addTeacher: (id: number, body: { teacherId: number; teacherRole: 'PRIMARY' | 'ASSISTANT' }) =>
    http.post<CourseTeacher[]>(`/courses/${id}/teachers`, body),
  removeTeacher: (id: number, teacherId: number) =>
    http.del<CourseTeacher[]>(`/courses/${id}/teachers/${teacherId}`),
  roster: (id: number, page = 1, size = 50) =>
    http.get<PageResult<RosterItem>>(`/courses/${id}/selections`, { page, size }),
  schedules: (id: number) => http.get<Schedule[]>(`/courses/${id}/schedules`),
  addSchedule: (
    courseId: number,
    body: {
      dayOfWeek: number
      startPeriod: number
      endPeriod: number
      startWeek: number
      endWeek: number
      weekParity: string
      classroom: string | null
    },
  ) => http.post<Schedule[]>(`/courses/${courseId}/schedules`, body),
  updateSchedule: (courseId: number, scheduleId: number, body: Record<string, unknown>) =>
    http.put<Schedule[]>(`/courses/${courseId}/schedules/${scheduleId}`, body),
  removeSchedule: (courseId: number, scheduleId: number) =>
    http.del<Schedule[]>(`/courses/${courseId}/schedules/${scheduleId}`),
}

export const scheduleApi = {
  /** 统一课表：自动排课已发布结果优先，手工排课兜底（source 字段标明来源） */
  mine: (semesterId?: number | null) =>
    http.get<MyScheduleItem[]>('/timetables/me', semesterId ? { semesterId } : undefined),

  /** 课表左侧的时间列：第几节几点到几点（timetable:read 即可读，学生也有） */
  periods: (semesterId: number) =>
    http.get<TermPeriod[]>('/scheduling/timetables/periods', { semesterId }),
}

export const selectionApi = {
  create: (courseId: number) => http.post<SelectionView>('/course-selections', { courseId }),
  mine: (semesterId?: number | null) =>
    http.get<SelectionView[]>('/course-selections/me', semesterId ? { semesterId } : undefined),
  drop: (id: number) => http.del<void>(`/course-selections/${id}`),
}

export const leaveApi = {
  create: (body: { courseId: number; scheduleId?: number | null; startAt: string; endAt: string; reason: string }) =>
    http.post<LeaveView>('/leave-requests', body),
  /** 注意：返回的是分页对象，不是数组 */
  mine: (query: { status?: string; semesterId?: number | null; courseId?: number; page?: number; size?: number } = {}) =>
    http.get<PageResult<LeaveView>>('/leave-requests/me', { page: 1, size: 50, ...query }),
  toReview: (query: { status?: string; page?: number; size?: number } = {}) =>
    http.get<PageResult<LeaveView>>('/leave-requests/to-review', { page: 1, size: 50, ...query }),
  all: (
    query: { status?: string; classId?: number | null; courseId?: number | null; semesterId?: number | null; page?: number; size?: number } = {},
  ) => http.get<PageResult<LeaveView>>('/leave-requests/all', { page: 1, size: 20, ...query }),
  detail: (id: number) => http.get<LeaveView>(`/leave-requests/${id}`),
  approve: (id: number, comment?: string) =>
    http.post<LeaveView>(`/leave-requests/${id}/approve`, comment ? { comment } : {}),
  reject: (id: number, comment?: string) =>
    http.post<LeaveView>(`/leave-requests/${id}/reject`, comment ? { comment } : {}),
  cancel: (id: number) => http.post<LeaveView>(`/leave-requests/${id}/cancel`),
  revoke: (id: number, note?: string) =>
    http.post<LeaveView>(`/leave-requests/${id}/revoke`, note ? { note } : {}),
  counselors: () => http.get<ClassCounselorView[]>('/leave-requests/counselors'),
  setCounselors: (classId: number, counselorIds: number[]) =>
    http.put<ClassCounselorView>(`/leave-requests/counselors/${classId}`, { counselorIds }),
}

export const gradeApi = {
  me: () => http.get<TranscriptView>('/grades/me'),
  rules: () => http.get<GradeRuleView[]>('/grades/rules'),
  gradeBook: (courseId: number) => http.get<GradeBookView>(`/grades/course/${courseId}`),
  save: (courseId: number, items: { studentId: number; score: number | null; remark: string | null }[]) =>
    http.put<GradeBookView>(`/grades/course/${courseId}`, { items }),
  publish: (courseId: number) => http.post<GradeBookView>(`/grades/course/${courseId}/publish`),
}

export const evaluationApi = {
  tasks: () => http.get<EvaluationTaskView[]>('/evaluations/tasks'),
  submit: (body: {
    courseId: number
    teacherId: number
    scoreTeaching: number
    scoreContent: number
    scoreInteraction: number
    scoreWorkload: number
    comment: string | null
    anonymous: boolean
  }) => http.post<EvaluationTaskView>('/evaluations', body),
  courseSummary: (courseId: number) => http.get<EvaluationCourseSummary>(`/evaluations/course/${courseId}`),
  mine: () => http.get<EvaluationOverview>('/evaluations/me'),
}

export const feedbackApi = {
  create: (body: { category: string; title: string; content: string; anonymous: boolean }) =>
    http.post<FeedbackView>('/feedbacks', body),
  mine: (query: { status?: string; category?: string; keyword?: string; page?: number; size?: number } = {}) =>
    http.get<PageResult<FeedbackView>>('/feedbacks/me', { page: 1, size: 20, ...query }),
  list: (query: { status?: string; category?: string; keyword?: string; page?: number; size?: number } = {}) =>
    http.get<PageResult<FeedbackView>>('/feedbacks', { page: 1, size: 20, ...query }),
  reply: (id: number, body: { status: string; reply: string | null }) =>
    http.post<FeedbackView>(`/feedbacks/${id}/reply`, body),
}

export const classroomApi = {
  free: (query: {
    date?: string
    startPeriod?: number
    endPeriod?: number
    minCapacity?: number
    building?: string
    roomType?: string
  } = {}) => http.get<FreeSlotView>('/classrooms/free', query),
  week: (query: {
    from?: string
    startPeriod?: number
    endPeriod?: number
    minCapacity?: number
    building?: string
    roomType?: string
  } = {}) => http.get<FreeWeekView>('/classrooms/week', query),
}

export const attendanceApi = {
  classes: () => http.get<ClassOptionView[]>('/attendance/classes'),
  timetable: (classId: number, semesterId?: number | null) =>
    http.get<ClassTimetableView>(`/attendance/classes/${classId}/timetable`, { semesterId }),
  stats: (classId: number, query: { semesterId?: number | null; courseId?: number | null } = {}) =>
    http.get<ClassAttendanceStatsView>(`/attendance/classes/${classId}/stats`, query),
  sessions: (classId: number, query: { courseId?: number | null; from?: string; to?: string } = {}) =>
    http.get<AttendanceSessionView[]>('/attendance/sessions', { classId, ...query }),
  createSession: (body: {
    courseId: number
    scheduleId?: number | null
    classId: number
    sessionDate: string
    startPeriod: number
    endPeriod?: number | null
    note?: string | null
  }) => http.post<AttendanceSessionDetailView>('/attendance/sessions', body),
  session: (id: number) => http.get<AttendanceSessionDetailView>(`/attendance/sessions/${id}`),
  save: (id: number, items: { studentId: number; status: string; remark: string | null }[]) =>
    http.put<AttendanceSessionDetailView>(`/attendance/sessions/${id}`, { items }),
  finalize: (id: number) => http.post<AttendanceSessionDetailView>(`/attendance/sessions/${id}/finalize`),
  syncRoster: (id: number) => http.post<AttendanceSessionDetailView>(`/attendance/sessions/${id}/sync-roster`),
  myAttendance: (status?: string) => http.get<MyAttendanceView[]>('/attendance/me', status ? { status } : undefined),
  submitAppeal: (body: { recordId: number; reason: string; targetStatus?: string }) =>
    http.post<AppealView>('/attendance/appeals', body),
  myAppeals: () => http.get<AppealView[]>('/attendance/appeals/me'),
  appeals: (status?: string) => http.get<AppealView[]>('/attendance/appeals', status ? { status } : undefined),
  decideAppeal: (id: number, body: { approve: boolean; targetStatus?: string; comment: string | null }) =>
    http.post<AppealView>(`/attendance/appeals/${id}/decide`, body),
}

export const notificationApi = {
  list: (query: { unreadOnly?: boolean; category?: string; page?: number; size?: number } = {}) =>
    http.get<PageResult<NotificationView>>('/notifications', { page: 1, size: 20, ...query }),
  /** 未读且要求弹窗的官方公告 */
  popups: () => http.get<NotificationView[]>('/notifications/popups'),
  unreadCount: () => http.get<number>('/notifications/unread-count'),
  markRead: (id: number) => http.post<void>(`/notifications/${id}/read`),
  markAllRead: () => http.post<number>('/notifications/read-all'),
}

export const roomBookingApi = {
  create: (body: {
    roomId: number
    bookingDate: string
    startPeriod: number
    endPeriod: number
    purpose: string
    attendees?: number | null
    contact?: string | null
  }) => http.post<RoomBookingView>('/room-bookings', body),
  mine: (query: { status?: string; page?: number; size?: number } = {}) =>
    http.get<PageResult<RoomBookingView>>('/room-bookings/me', { page: 1, size: 20, ...query }),
  review: (query: { status?: string; page?: number; size?: number } = {}) =>
    http.get<PageResult<RoomBookingView>>('/room-bookings', { page: 1, size: 20, ...query }),
  decide: (id: number, body: { approve: boolean; comment: string | null }) =>
    http.post<RoomBookingView>(`/room-bookings/${id}/decide`, body),
  cancel: (id: number) => http.post<RoomBookingView>(`/room-bookings/${id}/cancel`),
}

export const profileApi = {
  me: () => http.get<ProfileView>('/profile'),
  uploadAvatar: (file: File) => {
    const form = new FormData()
    form.append('file', file, file.name)
    return http.upload<ProfileView>('/profile/avatar', form)
  },
  removeAvatar: () => http.del<ProfileView>('/profile/avatar'),
  posts: (query: { page?: number; size?: number } = {}) =>
    http.get<PageResult<ProfilePostView>>('/profile/posts', { page: 1, size: 10, ...query }),
}

export const settingApi = {
  list: () => http.get<SettingView[]>('/settings'),
  update: (key: string, value: string) => http.put<SettingView>(`/settings/${key}`, { value }),
  leaveFlow: () => http.get<LeaveFlowView>('/settings/leave-flow'),
}

export const userApi = {
  list: (
    query: {
      page?: number
      size?: number
      keyword?: string
      status?: string
      roleCode?: string
      orgUnitId?: number | null
      includeSubOrgs?: boolean
    } = {},
  ) => http.get<PageResult<UserView>>('/users', { page: 1, size: 20, ...query }),
  detail: (id: number) => http.get<UserView>(`/users/${id}`),
  create: (body: {
    username: string
    displayName: string
    password: string
    email?: string | null
    phone?: string | null
    orgUnitId?: number | null
    roleCodes: string[]
  }) => http.post<UserView>('/users', body),
  update: (
    id: number,
    body: { displayName: string; email: string | null; phone: string | null; orgUnitId: number | null; version: number },
  ) => http.put<UserView>(`/users/${id}`, body),
  setStatus: (id: number, status: string, reason?: string) =>
    http.patch<UserView>(`/users/${id}/status`, { status, reason }),
  unlock: (id: number) => http.post<UserView>(`/users/${id}/unlock`),
  setRoles: (id: number, roleCodes: string[]) => http.put<UserView>(`/users/${id}/roles`, { roleCodes }),
  resetPassword: (id: number, newPassword: string) =>
    http.post<void>(`/users/${id}/password-reset`, { newPassword }),
}

export const orgApi = {
  tree: (includeDisabled = false) =>
    http.get<OrgTreeNode[]>('/org-units/tree', includeDisabled ? { includeDisabled: true } : {}),
  detail: (id: number) => http.get<OrgUnitView>(`/org-units/${id}`),
  create: (body: { parentId: number | null; code: string; name: string; type: string; sortOrder: number }) =>
    http.post<OrgUnitView>('/org-units', body),
  update: (
    id: number,
    body: { parentId: number | null; code: string; name: string; type: string; sortOrder: number },
  ) => http.put<OrgUnitView>(`/org-units/${id}`, body),
  setStatus: (id: number, status: string) => http.patch<OrgUnitView>(`/org-units/${id}/status`, { status }),
}

export const roleApi = {
  roles: () => http.get<RoleView[]>('/roles'),
  permissions: () => http.get<PermissionView[]>('/permissions'),
}

export const auditApi = {
  list: (
    query: {
      page?: number
      size?: number
      module?: string
      action?: string
      source?: string
      result?: string
      actorUserId?: number
      from?: string
      to?: string
    } = {},
  ) => http.get<PageResult<AuditLogView>>('/audit-logs', { page: 1, size: 20, ...query }),
}

export const aiApi = {
  chat: (body: AiChatRequest) => http.post<AiChatResponse>('/ai/chat', body),
  /** 自然语言办事：模型选工具并填参；写操作只返回 pending，需再 confirm */
  act: (message: string) => http.post<AgentActResponse>('/ai/act', { message }),
  /** 办事历史：本人记录倒序 */
  actHistory: (page = 1, size = 10) =>
    http.get<PageResult<ActHistoryView>>('/ai/acts', { page, size }),
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

// ---- 自动排课 ----

import type {
  ConstraintSetting,
  RuleTemplate,
  ScheduleCandidate,
  SchedulingCampus,
  SchedulingCourse,
  SchedulingRoom,
  SchedulingTeachingClass,
  SchedulingTermConfig,
  ScheduleEntry,
  ScheduleEvaluation,
  ScheduleJob,
  ScheduleJobLog,
  ScheduleVersion,
  SchedulingClass,
  SchedulingPrecheck,
  SchedulingTeacher,
  SchedulingTerm,
  TaskImportResult,
  TeacherAvailability,
  TeachingTask,
  TermCalendar,
  TermPeriod,
  TimeRule,
  TimetableView,
} from './types'

export const schedulingApi = {
  terms: () => http.get<SchedulingTerm[]>('/scheduling/terms'),

  versions: (semesterId?: number) =>
    http.get<ScheduleVersion[]>('/scheduling/versions', { semesterId }),

  version: (id: number) => http.get<ScheduleVersion>(`/scheduling/versions/${id}`),

  timetable: (id: number, scope: { teacherId?: number; classId?: number; roomId?: number } = {}) =>
    http.get<TimetableView>(`/scheduling/versions/${id}/timetable`, scope),

  entries: (id: number, query: Record<string, number | undefined> = {}) =>
    http.get<ScheduleEntry[]>(`/scheduling/versions/${id}/entries`, query),

  evaluation: (id: number) => http.get<ScheduleEvaluation>(`/scheduling/versions/${id}/evaluation`),

  check: (id: number) => http.post<ScheduleEvaluation>(`/scheduling/versions/${id}/check`),

  publish: (id: number, reason?: string) =>
    http.post<ScheduleVersion>(`/scheduling/versions/${id}/publish`, { reason: reason ?? null }),

  unpublish: (id: number, reason?: string) =>
    http.post<ScheduleVersion>(`/scheduling/versions/${id}/unpublish`, { reason: reason ?? null }),

  copy: (id: number, name?: string) =>
    http.post<ScheduleVersion>(`/scheduling/versions/${id}/copy`, { name: name ?? null }),

  candidates: (versionId: number, entryId: number) =>
    http.get<ScheduleCandidate[]>(`/scheduling/versions/${versionId}/entries/${entryId}/candidates`),

  move: (
    versionId: number,
    entryId: number,
    body: { dayOfWeek: number | null; startPeriod?: number; roomId?: number | null; reason?: string; force?: boolean },
  ) => http.put<ScheduleEntry>(`/scheduling/versions/${versionId}/entries/${entryId}`, body),

  lock: (versionId: number, entryId: number, locked: boolean, reason?: string) =>
    http.put<ScheduleEntry>(`/scheduling/versions/${versionId}/entries/${entryId}/lock`, { locked, reason }),

  jobs: (query: { semesterId?: number; status?: string; page?: number; size?: number } = {}) =>
    http.get<PageResult<ScheduleJob>>('/scheduling/jobs', { page: 1, size: 20, ...query }),

  job: (id: number) => http.get<ScheduleJob>(`/scheduling/jobs/${id}`),

  createJob: (body: {
    semesterId: number
    name: string
    mode?: string
    baseVersionId?: number | null
    timeLimitSeconds?: number
    workers?: number
    allowPartial?: boolean
    keepLocked?: boolean
    /** 求解范围：指定任务（不传表示全学期/按 orgUnitId） */
    taskIds?: number[]
    orgUnitId?: number
    randomSeed?: number
    /** 软约束权重覆盖，key 为 ConstraintCode */
    weightOverrides?: Record<string, number>
    replanTaskIds?: number[]
  }) => http.post<ScheduleJob>('/scheduling/jobs', body),

  runJob: (id: number) => http.post<unknown>(`/scheduling/jobs/${id}/run`),

  cancelJob: (id: number) => http.post<unknown>(`/scheduling/jobs/${id}/cancel`),

  jobLogs: (id: number) => http.get<ScheduleJobLog[]>(`/scheduling/jobs/${id}/logs`),

  teachingTasks: (query: { semesterId?: number; keyword?: string; page?: number; size?: number } = {}) =>
    http.get<PageResult<TeachingTask>>('/scheduling/teaching-tasks', { page: 1, size: 50, ...query }),

  precheck: (semesterId: number) =>
    http.post<SchedulingPrecheck>(`/scheduling/terms/${semesterId}/precheck`),

  /** 本人已发布课表：教师看自己任教的，学生看所在行政班 */
  myTimetable: (semesterId?: number) =>
    http.get<TimetableView>('/scheduling/timetables/me', { semesterId }),

  /** 按教室查已发布课表（教师与管理员，学生无权限） */
  roomTimetable: (roomId: number, semesterId?: number) =>
    http.get<TimetableView>('/scheduling/timetables/room', { roomId, semesterId }),

  teachers: (keyword?: string) => http.get<SchedulingTeacher[]>('/scheduling/teachers', { keyword }),

  classes: (keyword?: string) => http.get<SchedulingClass[]>('/scheduling/classes', { keyword }),

  // ---- 基础数据维护（教室/教师/课程/教学班/任务） ----

  campuses: () => http.get<SchedulingCampus[]>('/scheduling/campuses'),

  createCampus: (body: { code: string; name: string; status?: string }) =>
    http.post<SchedulingCampus>('/scheduling/campuses', body),

  updateCampus: (id: number, body: { code: string; name: string; status?: string }) =>
    http.put<SchedulingCampus>(`/scheduling/campuses/${id}`, body),

  rooms: (query: { campusId?: number; roomType?: string; keyword?: string; page?: number; size?: number } = {}) =>
    http.get<PageResult<SchedulingRoom>>('/scheduling/classrooms', { page: 1, size: 100, ...query }),

  createRoom: (body: Record<string, unknown>) =>
    http.post<SchedulingRoom>('/scheduling/classrooms', body),

  updateRoom: (id: number, body: Record<string, unknown>) =>
    http.put<SchedulingRoom>(`/scheduling/classrooms/${id}`, body),

  updateTeacher: (id: number, body: Record<string, unknown>) =>
    http.put<SchedulingTeacher>(`/scheduling/teachers/${id}`, body),

  courses: (query: { keyword?: string; status?: string } = {}) =>
    http.get<SchedulingCourse[]>('/scheduling/courses', query),

  createCourse: (body: Record<string, unknown>) =>
    http.post<SchedulingCourse>('/scheduling/courses', body),

  updateCourse: (id: number, body: Record<string, unknown>) =>
    http.put<SchedulingCourse>(`/scheduling/courses/${id}`, body),

  disableCourse: (id: number) => http.del<void>(`/scheduling/courses/${id}`),

  teachingClasses: (query: { semesterId?: number; keyword?: string } = {}) =>
    http.get<SchedulingTeachingClass[]>('/scheduling/teaching-classes', query),

  createTeachingClass: (body: Record<string, unknown>) =>
    http.post<SchedulingTeachingClass>('/scheduling/teaching-classes', body),

  updateTeachingClass: (id: number, body: Record<string, unknown>) =>
    http.put<SchedulingTeachingClass>(`/scheduling/teaching-classes/${id}`, body),

  teachingTask: (id: number) => http.get<TeachingTask>(`/scheduling/teaching-tasks/${id}`),

  createTeachingTask: (body: Record<string, unknown>) =>
    http.post<TeachingTask>('/scheduling/teaching-tasks', body),

  updateTeachingTask: (id: number, body: Record<string, unknown>) =>
    http.put<TeachingTask>(`/scheduling/teaching-tasks/${id}`, body),

  deleteTeachingTask: (id: number) => http.del<void>(`/scheduling/teaching-tasks/${id}`),

  importTasks: (semesterId: number, csv: string) =>
    http.postText<TaskImportResult>(`/scheduling/teaching-tasks/import?semesterId=${semesterId}`, csv),

  exportTasks: (semesterId: number) =>
    http.blob('/scheduling/teaching-tasks/export', { semesterId }),

  // ---- 学期设置：配置 / 作息 / 日历 / 约束权重 ----

  term: (id: number) => http.get<SchedulingTerm>(`/scheduling/terms/${id}`),

  putTermConfig: (id: number, body: {
    teachingWeeks: number
    firstWeekStart: string
    teachingDays: number[]
    periodsPerDay: number
    defaultTravelMinutes?: number | null
    maxRoomCandidates?: number | null
  }) => http.put<SchedulingTermConfig>(`/scheduling/terms/${id}/config`, body),

  periods: (id: number) => http.get<TermPeriod[]>(`/scheduling/terms/${id}/periods`),

  putPeriods: (id: number, periods: TermPeriod[]) =>
    http.put<TermPeriod[]>(`/scheduling/terms/${id}/periods`, { periods }),

  calendar: (id: number) => http.get<TermCalendar>(`/scheduling/terms/${id}/calendar`),

  putCalendar: (id: number, body: TermCalendar) =>
    http.put<TermCalendar>(`/scheduling/terms/${id}/calendar`, body),

  constraints: (id: number) => http.get<ConstraintSetting[]>(`/scheduling/terms/${id}/constraints`),

  putConstraints: (id: number, items: { code: string; enabled: boolean; weight: number }[]) =>
    http.put<ConstraintSetting[]>(`/scheduling/terms/${id}/constraints`, { items }),

  /** 教师时间要求（可来时间窗）：读取 */
  teacherAvailability: (semesterId: number, teacherId: number) =>
    http.get<TeacherAvailability>(`/scheduling/terms/${semesterId}/teacher-availability/${teacherId}`),

  /** 教师时间要求：设置，仅管理员（schedule:manage）可用 */
  putTeacherAvailability: (semesterId: number, teacherId: number, body: { days: number[]; sections: string[] }) =>
    http.put<TeacherAvailability>(
      `/scheduling/terms/${semesterId}/teacher-availability/${teacherId}`,
      body,
    ),

  // ---- 时间规则 ----

  rules: (query: { semesterId: number; targetType?: string; targetId?: number }) =>
    http.get<TimeRule[]>('/scheduling/rules', query),

  ruleTemplates: () => http.get<RuleTemplate[]>('/scheduling/rules/templates'),

  createRule: (body: Record<string, unknown>) => http.post<TimeRule>('/scheduling/rules', body),

  updateRule: (id: number, body: Record<string, unknown>) =>
    http.put<TimeRule>(`/scheduling/rules/${id}`, body),

  deleteRule: (id: number) => http.del<void>(`/scheduling/rules/${id}`),
}
