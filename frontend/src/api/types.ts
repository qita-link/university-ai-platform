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
  /** 头像版本：0 表示没有头像 */
  avatarVersion: number
  orgUnitId: number | null
  roles: string[]
  permissions: string[]
}

export interface LoginResult {
  accessToken: string
  /** 访问令牌过期后用它换新令牌；只存本地，不放进请求头 */
  refreshToken: string
  tokenType: 'Bearer'
  expiresIn: number
  expiresAt: string
  refreshExpiresAt: string
  user: CurrentUser
}

// ---- 知识库 ----
export type SpaceVisibility = 'PUBLIC' | 'ORG' | 'PRIVATE' | 'COURSE'
export type ProcessStatus = 'PENDING' | 'PROCESSING' | 'INDEXED' | 'FAILED'
export type DocumentStatus = 'ACTIVE' | 'DELETED'

export interface SpaceView {
  id: number
  categoryId: number | null
  categoryName: string | null
  name: string
  description: string | null
  visibility: SpaceVisibility
  scopeOrgUnitId: number | null
  scopeOrgName: string | null
  scopeCourseId: number | null
  scopeCourseName: string | null
  ownerUserId: number | null
  ownerName: string | null
  status: string
  accessLevel: string
  memberCount: number
  version: number
  createdAt: string
  updatedAt: string
}

export interface KnowledgeCategory {
  id: number
  parentId: number | null
  name: string
  description: string | null
  sortOrder: number
  children: KnowledgeCategory[]
}

export interface DocumentSummaryView {
  id: number
  spaceId: number
  batchId: number | null
  title: string
  originalFilename: string | null
  sourceType: 'UPLOAD' | 'EDITOR' | 'IMPORT'
  sourcePath: string | null
  status: DocumentStatus
  processStatus: ProcessStatus
  processError: string | null
  contentHash: string | null
  currentVersionId: number | null
  versionNo: number | null
  sizeBytes: number | null
  chunkCount: number
  uploadedBy: number | null
  uploadedByName: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface VersionView {
  id: number
  versionNo: number
  contentHash: string
  sizeBytes: number
  changeNote: string | null
  createdBy: number | null
  createdByName: string | null
  createdAt: string
}

export interface VersionDetailView {
  id: number
  documentId: number
  versionNo: number
  content: string
  contentHash: string
  sizeBytes: number
  changeNote: string | null
  createdBy: number | null
  createdByName: string | null
  createdAt: string
}

export interface ChunkView {
  id: number
  versionId: number
  chunkIndex: number
  headingPath: string | null
  content: string
  charCount: number
  tokenEstimate: number
  status: 'ACTIVE' | 'STALE'
  embeddingStatus: string | null
  vectorId: string | null
}

export interface UploadBatchItem {
  filename: string
  success: boolean
  documentId: number | null
  title: string | null
  error: string | null
}

export interface UploadBatchView {
  batchId: number
  spaceId: number
  totalCount: number
  successCount: number
  failedCount: number
  status: 'PROCESSING' | 'COMPLETED' | 'PARTIAL' | 'FAILED'
  items: UploadBatchItem[]
}

// ---- 论坛 ----
export type ForumPostStatus = 'PUBLISHED' | 'HIDDEN' | 'DELETED'

export interface ForumCategory {
  id: number
  name: string
  description: string | null
  sortOrder: number
  status: 'ACTIVE' | 'DISABLED'
  /** 公告分类：只有持 forum:announce 的账号能发帖，发布后广播全员并弹窗 */
  announcement: boolean
  postCount: number
  createdAt: string
  updatedAt: string
}

export interface ForumPostSummary {
  id: number
  categoryId: number
  categoryName: string
  authorId: number
  authorName: string
  /** 作者头像版本，0 表示没有头像 */
  authorAvatarVersion: number
  title: string
  excerpt: string | null
  status: ForumPostStatus
  pinned: boolean
  /** 置顶到期时间；null 表示长期置顶（pinned 为 false 时无意义） */
  pinnedUntil: string | null
  commentCount: number
  lastActivityAt: string
  createdAt: string
  updatedAt: string
  version: number
}

export interface ForumPostDetail extends ForumPostSummary {
  content: string
}

export interface ForumComment {
  id: number
  postId: number
  parentId: number | null
  rootId: number | null
  authorId: number
  authorName: string
  authorAvatarVersion: number
  replyToUserId: number | null
  replyToUserName: string | null
  replyToAvatarVersion: number
  content: string
  status: 'PUBLISHED' | 'DELETED'
  createdAt: string
}

export interface ForumCommentThread {
  comment: ForumComment
  replies: ForumComment[]
}

export interface ForumCommentPage {
  items: ForumCommentThread[]
  total: number
  page: number
  size: number
  pages: number
}

export interface DocumentDetailView {
  id: number
  spaceId: number
  batchId: number | null
  title: string
  originalFilename: string | null
  sourceType: 'UPLOAD' | 'EDITOR' | 'IMPORT'
  sourcePath: string | null
  status: DocumentStatus
  processStatus: ProcessStatus
  processError: string | null
  contentHash: string | null
  currentVersionId: number | null
  versionNo: number | null
  sizeBytes: number | null
  chunkCount: number
  uploadedBy: number | null
  uploadedByName: string | null
  /** 乐观锁版本号：编辑时必须回传 */
  version: number
  content: string
  effectiveFrom: string | null
  effectiveTo: string | null
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

// ---- 学期与课程 ----
export type SemesterStatus = 'DRAFT' | 'ACTIVE' | 'CLOSED'
export type CourseStatus = 'DRAFT' | 'OPEN' | 'CLOSED' | 'CANCELLED'
export type TeacherRole = 'PRIMARY' | 'ASSISTANT'
export type WeekParity = 'ALL' | 'ODD' | 'EVEN'
export type SelectionStatus = 'SELECTED' | 'DROPPED'
export type CourseBlockReason = 'WINDOW_CLOSED' | 'COURSE_NOT_OPEN' | 'ALREADY_SELECTED' | 'FULL' | 'TIME_CONFLICT'

export interface SemesterView {
  id: number
  code: string
  name: string
  startDate: string
  endDate: string
  selectionStartAt: string | null
  selectionEndAt: string | null
  status: SemesterStatus
  /** 学期 ACTIVE 且当前时间在选课窗口内才为 true */
  selectionOpen: boolean
  courseCount: number
  createdAt: string
  updatedAt: string
}

export interface Schedule {
  id: number
  courseId: number
  dayOfWeek: number
  startPeriod: number
  endPeriod: number
  startWeek: number
  endWeek: number
  weekParity: WeekParity
  classroom: string | null
}

export interface CourseTeacher {
  teacherId: number
  username: string
  displayName: string
  teacherRole: TeacherRole
}

export interface CourseSummary {
  id: number
  semesterId: number
  semesterName: string
  code: string
  name: string
  credit: number
  description: string | null
  orgUnitId: number | null
  orgUnitName: string | null
  capacity: number
  selectedCount: number
  status: CourseStatus
  /** 主讲在前 */
  teacherNames: string[]
  createdAt: string
  updatedAt: string
  version: number
}

export interface CourseDetail extends Omit<CourseSummary, 'teacherNames'> {
  teachers: CourseTeacher[]
  schedules: Schedule[]
  /** 本人选课状态；教师/管理员为 null */
  mySelectionStatus: SelectionStatus | null
  selectable: boolean
  blockedReason: CourseBlockReason | null
  selectionOpen: boolean
}

export interface AvailableCourse {
  courseId: number
  semesterId: number
  code: string
  name: string
  credit: number
  capacity: number
  selectedCount: number
  status: CourseStatus
  teacherNames: string[]
  schedules: Schedule[]
  selectable: boolean
  blockedReason: CourseBlockReason | null
  conflictCourseName: string | null
}

export interface MyScheduleItem {
  semesterId: number
  semesterName: string
  courseId: number
  courseCode: string
  courseName: string
  teacherNames: string[]
  /** 手工排课记录 ID；来自自动排课时为 null */
  scheduleId: number | null
  /** 自动排课分配 ID；来自手工排课时为 null（旧接口不返回该字段） */
  assignmentId?: number | null
  /** SCHEDULING=自动排课已发布，COURSE=课程手工排课 */
  source?: 'SCHEDULING' | 'COURSE'
  dayOfWeek: number
  startPeriod: number
  endPeriod: number
  startWeek: number
  endWeek: number
  weekParity: WeekParity
  classroom: string | null
}

export interface SelectionView {
  id: number
  courseId: number
  courseCode: string
  courseName: string
  credit: number
  semesterId: number
  semesterName: string
  status: SelectionStatus
  teacherNames: string[]
  selectedAt: string
  droppedAt: string | null
}

export interface RosterItem {
  selectionId: number
  studentId: number
  username: string
  displayName: string
  orgUnitId: number | null
  orgUnitName: string | null
  selectedAt: string
}

// ---- 请假 ----
export type LeaveStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED'

export interface LeaveApprovalView {
  stage: number
  approverRole: 'TEACHER' | 'COUNSELOR' | 'ADMIN'
  approverId: number
  approverName: string | null
  decision: 'APPROVED' | 'REJECTED'
  comment: string | null
  decidedAt: string
}

export interface LeaveView {
  id: number
  studentId: number
  studentUsername: string
  studentName: string
  classId: number | null
  className: string | null
  courseId: number
  courseCode: string
  courseName: string
  scheduleId: number | null
  startAt: string
  endAt: string
  reason: string
  status: LeaveStatus
  /** 审批顺序快照：TEACHER = 任课教师，COUNSELOR = 导员 */
  approvalOrder: string[]
  totalStages: number
  currentStage: number
  currentStageRole: string
  approvals: LeaveApprovalView[]
  reviewerId: number | null
  reviewerName: string | null
  reviewComment: string | null
  reviewedAt: string | null
  revokedAt: string | null
  revokeNote: string | null
  /** 当前用户是否可以审批 / 撤销 / 销假，由后端按请求者实时计算 */
  canReview: boolean
  canCancel: boolean
  canRevoke: boolean
  createdAt: string
  version: number
}

export interface ClassCounselorView {
  classId: number
  classCode: string
  className: string
  counselorIds: number[]
  counselorNames: string[]
}

// ---- 管理（用户 / 组织 / 角色 / 审计） ----
export type UserStatus = 'ACTIVE' | 'DISABLED' | 'LOCKED'
export type OrgType = 'COLLEGE' | 'DEPARTMENT' | 'MAJOR' | 'CLASS'
export type OrgStatus = 'ACTIVE' | 'DISABLED'

export interface UserView {
  id: number
  username: string
  displayName: string
  email: string | null
  phone: string | null
  orgUnitId: number | null
  orgUnitName: string | null
  status: UserStatus
  /** 连续登录失败触发的临时锁定，未锁定为空 */
  lockedUntil: string | null
  failedLoginCount: number
  roles: string[]
  lastLoginAt: string | null
  passwordChangedAt: string | null
  /** 修改资料时回传，用于乐观锁 */
  version: number
  createdAt: string
  updatedAt: string
}

export interface OrgTreeNode {
  id: number
  parentId: number | null
  code: string
  name: string
  type: OrgType
  status: OrgStatus
  sortOrder: number
  children: OrgTreeNode[]
}

export interface OrgUnitView {
  id: number
  parentId: number | null
  code: string
  name: string
  type: OrgType
  status: OrgStatus
  sortOrder: number
  createdAt: string
  updatedAt: string
}

export interface RoleView {
  id: number
  code: string
  name: string
  description: string | null
  builtIn: boolean
  permissions: string[]
}

export interface PermissionView {
  id: number
  code: string
  name: string
  module: string
  description: string | null
}

export type AuditResult = 'SUCCESS' | 'FAILURE' | 'DENIED'
export type AuditSource = 'API' | 'AI_TOOL' | 'SYSTEM'

export interface AuditLogView {
  id: number
  traceId: string
  actorUserId: number | null
  actorUsername: string | null
  source: AuditSource
  module: string
  action: string
  targetType: string | null
  targetId: string | null
  result: AuditResult
  errorCode: string | null
  /** 已脱敏的 JSON 摘要 */
  detail: string | null
  clientIp: string | null
  createdAt: string
}

// ---- 知识库成员 ----
export type MemberRole = 'MANAGER' | 'EDITOR' | 'VIEWER'

export interface SpaceMemberView {
  userId: number
  username: string
  displayName: string
  memberRole: MemberRole
  grantedBy: number | null
  createdAt: string
}

// ---- 自然语言办事 ----
export interface AgentStep {
  roundNo: number
  toolName: string
  title: string
  sideEffect: boolean
  arguments: Record<string, unknown> | null
  status: ToolCallStatus
  summary: string | null
  errorCode: string | null
  errorMessage: string | null
  callId: number | null
}

export type AgentOutcome = 'DONE' | 'WAITING_CONFIRM' | 'MAX_ROUNDS' | 'RAW_TEXT'

export interface AgentActResponse {
  reply: string
  steps: AgentStep[]
  /** 写操作的待确认记录；确认后才真正执行 */
  pending: AiToolCallView | null
  outcome: AgentOutcome
  model: string | null
}

export interface ActHistoryView {
  id: number
  message: string
  reply: string | null
  outcome: AgentOutcome
  stepCount: number
  steps: AgentStep[]
  pendingId: number | null
  /** 确认单的当前状态（实时），没有确认单时为空 */
  pendingStatus: ToolCallStatus | null
  model: string | null
  createdAt: string
}

// ---- 自动排课（scheduling） ----

export type SolverStatus =
  | 'DRAFT'
  | 'QUEUED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'PARTIAL'
  | 'INFEASIBLE'
  | 'UNKNOWN'
  | 'FAILED'
  | 'CANCELLED'

export type VersionStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'

export interface SchedulingTermConfig {
  teachingWeeks: number
  firstWeekStart: string
  teachingDays: number[]
  periodsPerDay: number
  defaultTravelMinutes: number | null
  maxRoomCandidates: number | null
  periodCount: number
  configured: boolean
}

export interface SchedulingTerm {
  id: number
  code: string
  name: string
  startDate: string
  endDate: string
  status: string
  config: SchedulingTermConfig | null
}

export interface ScheduleEntry {
  id: number
  taskId: number
  taskCode: string | null
  courseName: string | null
  teachingClassName: string | null
  classNames: string[]
  teacherNames: string[]
  roomId: number | null
  roomCode: string | null
  campusName: string | null
  dayOfWeek: number | null
  startPeriod: number | null
  endPeriod: number | null
  locked: boolean
  origin: string
  weeks: number[]
  weekText: string
  timeText: string
}

export interface TimetableCell {
  dayOfWeek: number
  entries: ScheduleEntry[]
}

export interface TimetableRow {
  periodNo: number
  startTime: string
  endTime: string
  cells: TimetableCell[]
}

export interface TimetableView {
  versionId: number
  versionName: string
  scope: string
  ownerName: string
  days: number[]
  rows: TimetableRow[]
}

export interface ScheduleVersion {
  id: number
  semesterId: number
  semesterName: string
  versionNo: number
  name: string
  source: string
  jobId: number | null
  baseVersionId: number | null
  status: VersionStatus
  totalSessions: number
  placedSessions: number
  hardViolations: number
  score: number | null
  softPenalty: number | null
  publishedAt: string | null
  publishedBy: number | null
  createdAt: string
  version: number
  note: string | null
}

export interface SoftScoreView {
  code: string
  label: string
  weight: number
  applicable: number
  violated: number
  penalty: number
  satisfaction: number
}

export interface ScheduleConflict {
  code: string
  label: string
  severity: string
  taskId: number | null
  taskCode: string | null
  otherTaskId: number | null
  dayOfWeek: number | null
  startPeriod: number | null
  endPeriod: number | null
  teacherNames: string[]
  classNames: string[]
  roomCodes: string[]
  message: string
  suggestion: string
}

export interface ScheduleEvaluation {
  score: number
  placedSessions: number
  totalSessions: number
  hardViolations: number
  softPenalty: number
  details: SoftScoreView[]
  conflicts: ScheduleConflict[]
}

export interface ScheduleCandidate {
  dayOfWeek: number
  startPeriod: number
  endPeriod: number
  roomId: number | null
  roomCode: string | null
  available: boolean
  conflicts: string[]
}

export interface ScheduleJob {
  id: number
  semesterId: number
  semesterName: string
  name: string
  mode: string
  baseVersionId: number | null
  status: SolverStatus
  solverStatus: string | null
  progress: number
  phase: string | null
  objective: number | null
  bestBound: number | null
  resultVersionId: number | null
  errorCode: string | null
  message: string | null
  startedAt: string | null
  finishedAt: string | null
  elapsedMillis: number
  taskCount: number
  totalSessions: number
  statsJson: string | null
}

export interface ScheduleJobLog {
  level: string
  message: string
  createdAt: string
}

export interface TeachingTask {
  id: number
  semesterId: number
  code: string
  courseId: number
  courseCode: string | null
  courseName: string | null
  teachingClassId: number
  teachingClassName: string | null
  classNames: string[]
  startWeek: number
  endWeek: number
  weekParity: string
  weeks: number[]
  sessionsPerWeek: number
  periodsPerSession: number
  totalHours: number
  providedHours: number
  hoursConsistent: boolean
  roomRequired: boolean
  roomType: string | null
  requiredFeatures?: string[]
  priority: number | null
  allowSameDay?: boolean
  uniformStart?: boolean
  status: string
  note?: string | null
  teachers: { teacherId: number; teacherName: string; role: string }[]
  rooms?: { roomId: number; roomCode: string | null; mode: string }[]
  version: number
}

export interface SchedulingIssue {
  severity: 'ERROR' | 'WARNING' | 'INFO'
  code: string
  taskId: number | null
  message: string
}

export interface SchedulingPrecheck {
  errorCount: number
  warningCount: number
  solvable: boolean
  summary: string
  issues: SchedulingIssue[]
}

export interface SchedulingTeacher {
  id: number
  username: string
  displayName: string
  orgUnitId: number | null
  orgUnitName: string | null
  title: string | null
  homeCampusId: number | null
  maxWeeklyPeriods: number | null
  maxDailyPeriods: number | null
  maxDailySessions: number | null
  maxConsecutivePeriods: number | null
  courseIds: number[]
}

export interface SchedulingClass {
  id: number
  code: string
  name: string
  grade: string | null
  studentCount: number | null
}

// ---- 排课基础数据（校区/教室/课程/教学班/任务录入） ----

export interface SchedulingCampus {
  id: number
  code: string
  name: string
  status: string
}

export interface SchedulingRoom {
  id: number
  code: string
  name: string
  campusId: number
  campusName: string | null
  building: string | null
  floorNo: number | null
  capacity: number
  roomType: string
  features: string[]
  status: string
  note: string | null
  version: number
}

export interface SchedulingCourse {
  id: number
  code: string
  name: string
  courseType: string
  credit: number
  totalHours: number
  orgUnitId: number | null
  defaultRoomType: string | null
  requiredFeatures: string[]
  priority: number | null
  status: string
  description: string | null
  version: number
}

export interface TeachingClassMember {
  classId: number
  className: string
  fullClass: boolean
  studentCount: number | null
}

export interface SchedulingTeachingClass {
  id: number
  semesterId: number
  code: string
  name: string
  studentCount: number | null
  computedStudentCount: number
  members: TeachingClassMember[]
}

export interface TaskImportRowError {
  line: number
  code: string | null
  message: string
}

export interface TaskImportResult {
  total: number
  created: number
  updated: number
  failed: number
  errors: TaskImportRowError[]
}

// ---- 排课设置：作息、日历、约束权重、时间规则 ----

export interface TermPeriod {
  periodNo: number
  startTime: string
  endTime: string
  section: 'MORNING' | 'AFTERNOON' | 'EVENING' | string
}

export interface CalendarWeek {
  weekNo: number
  weekType: string
  teachingAllowed: boolean
  note: string | null
}

export interface CalendarDate {
  eventDate: string
  dateType: 'HOLIDAY' | 'SUSPENDED' | 'WORKDAY' | 'MAKEUP' | string
  followDayOfWeek: number | null
  followDate: string | null
  name: string
}

export interface TermCalendar {
  weeks: CalendarWeek[]
  dates: CalendarDate[]
}

export interface ConstraintSetting {
  code: string
  label: string
  hard: boolean
  enabled: boolean
  weight: number
  defaultWeight: number
}

/** 教师时间要求：允许来校的星期与半天（都为空表示不作限制） */
export interface TeacherAvailability {
  semesterId: number
  teacherId: number
  teacherName: string
  days: number[]
  sections: string[]
}

export interface TimeRule {
  id: number
  semesterId: number
  targetType: string
  targetId: number | null
  targetName: string | null
  kind: string
  dayOfWeek: number | null
  startPeriod: number
  endPeriod: number
  startWeek: number | null
  endWeek: number | null
  weight: number | null
  note: string | null
  weeks: number[]
  timeText: string
}

export interface RuleTemplate {
  code: string
  name: string
  kind: string
  kindLabel: string
  dayOfWeek: number | null
  startPeriod: number
  endPeriod: number
  /** 模板说明（后端字段名是 description，不是 note） */
  description: string
}

// ---- 成绩与绩点 ----
export type GradeStatus = 'DRAFT' | 'PUBLISHED'

export interface GradeView {
  id: number
  courseId: number
  courseCode: string
  courseName: string
  credit: number
  semesterId: number
  semesterCode: string | null
  semesterName: string | null
  score: number | null
  gradePoint: number | null
  gradeLevel: string | null
  status: GradeStatus
  remark: string | null
  publishedAt: string | null
}

export interface GpaSummary {
  gpa: number
  weightedAverage: number
  totalCredits: number
  earnedCredits: number
  courseCount: number
}

export interface SemesterGradeView {
  semesterId: number
  semesterCode: string | null
  semesterName: string | null
  summary: GpaSummary
  grades: GradeView[]
}

export interface TranscriptView {
  overall: GpaSummary
  latest: SemesterGradeView | null
  semesters: SemesterGradeView[]
}

export interface GradeRosterItem {
  studentId: number
  username: string | null
  displayName: string | null
  orgUnitName: string | null
  score: number | null
  gradePoint: number | null
  gradeLevel: string | null
  status: GradeStatus
  remark: string | null
}

export interface GradeBookView {
  courseId: number
  courseCode: string
  courseName: string
  credit: number
  semesterId: number
  semesterName: string | null
  published: boolean
  items: GradeRosterItem[]
}

export interface GradeRuleView {
  id: number
  name: string
  minScore: number
  maxScore: number
  gradePoint: number
  gradeLevel: string
}

// ---- 评教 ----
export interface EvaluationTaskView {
  courseId: number
  courseCode: string
  courseName: string
  semesterId: number
  semesterName: string | null
  teacherId: number
  teacherName: string | null
  evaluated: boolean
  myScore: number | null
}

export interface EvaluationCommentView {
  comment: string
  totalScore: number
  createdAt: string
}

export interface EvaluationCourseSummary {
  courseId: number
  courseCode: string
  courseName: string
  semesterId: number
  semesterName: string | null
  count: number
  avgTeaching: number
  avgContent: number
  avgInteraction: number
  avgWorkload: number
  avgTotal: number
  comments: EvaluationCommentView[]
}

export interface EvaluationOverview {
  totalCount: number
  avgTotal: number
  courses: EvaluationCourseSummary[]
}

// ---- 投诉与建议 ----
export type FeedbackCategory = 'COMPLAINT' | 'SUGGESTION' | 'REPORT' | 'OTHER'
export type FeedbackStatus = 'OPEN' | 'PROCESSING' | 'RESOLVED' | 'CLOSED'

export interface FeedbackView {
  id: number
  category: FeedbackCategory
  title: string
  content: string
  anonymous: boolean
  submitterId: number | null
  submitterName: string | null
  status: FeedbackStatus
  handlerName: string | null
  reply: string | null
  repliedAt: string | null
  canHandle: boolean
  createdAt: string
}

// ---- 空教室 ----
export interface RoomView {
  id: number | null
  code: string
  name: string
  building: string | null
  floorNo: number | null
  capacity: number | null
  roomType: string | null
  features: string | null
}

export interface PeriodTimeView {
  periodNo: number
  startTime: string
  endTime: string
  section: string
}

export interface FreeSlotView {
  date: string
  dayOfWeek: number
  weekNo: number
  teachingDay: boolean
  startPeriod: number
  endPeriod: number
  startTime: string
  endTime: string
  totalRooms: number
  rooms: RoomView[]
  periods: PeriodTimeView[]
  semesterName: string | null
}

export interface FreeDaySummary {
  date: string
  dayOfWeek: number
  weekNo: number
  teachingDay: boolean
  freeCount: number
  totalRooms: number
}

export interface FreeWeekView {
  from: string
  to: string
  startPeriod: number
  endPeriod: number
  startTime: string
  endTime: string
  days: FreeDaySummary[]
}

// ---- 考勤 ----
export type AttendanceStatus = 'PRESENT' | 'ABSENT' | 'LATE' | 'LEAVE'
export type AttendanceSessionStatus = 'DRAFT' | 'FINAL'

export interface AttendanceRecordView {
  studentId: number
  username: string | null
  displayName: string | null
  orgUnitName: string | null
  status: AttendanceStatus
  leaveId: number | null
  remark: string | null
}

export interface AttendanceSessionView {
  id: number
  courseId: number
  courseCode: string | null
  courseName: string | null
  classId: number
  className: string | null
  sessionDate: string
  startPeriod: number
  endPeriod: number
  status: AttendanceSessionStatus
  note: string | null
  presentCount: number
  absentCount: number
  lateCount: number
  leaveCount: number
  updatedAt: string
}

export interface AttendanceSessionDetailView {
  session: AttendanceSessionView
  records: AttendanceRecordView[]
}

export interface ClassTimetableSlot {
  scheduleId: number
  dayOfWeek: number
  startPeriod: number
  endPeriod: number
  startWeek: number
  endWeek: number
  weekParity: string
  classroom: string | null
  sessionCount: number
  absentCount: number
}

export interface ClassTimetableCourse {
  courseId: number
  courseCode: string
  courseName: string
  teacherNames: string[]
  slots: ClassTimetableSlot[]
  sessionCount: number
  absentCount: number
}

export interface ClassTimetableView {
  classId: number
  classCode: string
  className: string
  semesterId: number | null
  semesterName: string | null
  sessionCount: number
  absentCount: number
  courses: ClassTimetableCourse[]
}

export interface ClassAttendanceStat {
  studentId: number
  username: string
  displayName: string
  total: number
  present: number
  absent: number
  late: number
  leave: number
  absenceRate: number
}

export interface ClassAttendanceStatsView {
  classId: number
  className: string
  semesterId: number | null
  semesterName: string | null
  sessionCount: number
  students: ClassAttendanceStat[]
}

export interface ClassOptionView {
  id: number
  code: string
  name: string
}

// ---- 设置 ----
export interface SettingView {
  key: string
  value: string
  description: string | null
}

export interface LeaveFlowView {
  teacherRequired: boolean
  counselorRequired: boolean
  stages: string[]
  totalStages: number
}

// ---- 通知 ----
export type NotificationCategory =
  | 'LEAVE'
  | 'GRADE'
  | 'FEEDBACK'
  | 'ATTENDANCE'
  | 'ROOM'
  | 'FORUM'
  | 'ANNOUNCEMENT'
  | 'SYSTEM'
export type NotificationLevel = 'INFO' | 'SUCCESS' | 'WARNING'

export interface NotificationView {
  id: number
  category: NotificationCategory
  level: NotificationLevel
  title: string
  body: string | null
  linkUrl: string | null
  bizType: string | null
  bizId: number | null
  /** 官方公告：登录后弹窗提示，关闭弹窗即视为已读 */
  popupRequired: boolean
  readAt: string | null
  createdAt: string
}

// ---- 考勤申诉 ----
export type AppealStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export interface MyAttendanceView {
  recordId: number
  courseId: number
  courseCode: string
  courseName: string
  sessionDate: string
  startPeriod: number
  endPeriod: number
  status: AttendanceStatus
  remark: string | null
  appealId: number | null
  appealStatus: AppealStatus | null
  canAppeal: boolean
}

export interface AppealView {
  id: number
  recordId: number
  studentId: number
  studentName: string | null
  studentUsername: string | null
  courseId: number | null
  courseName: string | null
  sessionDate: string | null
  startPeriod: number
  endPeriod: number
  originalStatus: AttendanceStatus | null
  targetStatus: AttendanceStatus
  reason: string
  status: AppealStatus
  reviewerName: string | null
  reviewComment: string | null
  reviewedAt: string | null
  canReview: boolean
  createdAt: string
}

// ---- 教室借用 ----
export type RoomBookingStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED'

export interface RoomBookingView {
  id: number
  roomId: number
  roomCode: string | null
  roomName: string | null
  building: string | null
  applicantId: number
  applicantName: string | null
  bookingDate: string
  startPeriod: number
  endPeriod: number
  purpose: string
  attendees: number | null
  contact: string | null
  status: RoomBookingStatus
  reviewerName: string | null
  reviewComment: string | null
  reviewedAt: string | null
  canCancel: boolean
  canReview: boolean
  createdAt: string
}

// ---- 个人资料 ----
export interface ProfileView {
  userId: number
  username: string
  displayName: string
  email: string | null
  phone: string | null
  orgUnitId: number | null
  orgUnitName: string | null
  roles: string[]
  avatarVersion: number
  lastLoginAt: string | null
  createdAt: string | null
}

export interface ProfilePostView {
  id: number
  title: string
  categoryName: string | null
  status: ForumPostStatus
  pinned: boolean
  commentCount: number
  lastActivityAt: string | null
  createdAt: string
  /** 内容被编辑过才有值 */
  editedAt: string | null
  editorName: string | null
  editorRole: string | null
}

// ---- 论坛举报 ----
export type ReportTargetType = 'POST' | 'COMMENT'
export type ReportStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED'

export interface ForumReportView {
  id: number
  targetType: ReportTargetType
  targetId: number
  /** 被举报内容的标题或摘要 */
  targetTitle: string | null
  targetVisible: boolean
  reporterId: number
  reporterName: string | null
  reason: string
  detail: string | null
  status: ReportStatus
  handledBy: number | null
  handlerName: string | null
  handledAt: string | null
  handleNote: string | null
  createdAt: string
}
