/**
 * 枚举到中文的映射。集中放置，避免各页面各写一套，改文案时只改一处。
 * 只负责「怎么读」，不参与权限或状态机判断。
 */

export const VISIBILITY_LABEL: Record<string, string> = {
  PUBLIC: '公开',
  ORG: '组织内',
  PRIVATE: '私有',
  COURSE: '课程',
}

export const ACCESS_LABEL: Record<string, string> = {
  ADMIN: '管理员',
  OWNER: '所有者',
  MANAGER: '管理',
  EDITOR: '可编辑',
  VIEWER: '可阅读',
  PUBLIC: '公开可读',
  ORG: '组织内可读',
  COURSE: '课程成员',
  NONE: '无权限',
}

export const PROCESS_LABEL: Record<string, string> = {
  PENDING: '待处理',
  PROCESSING: '处理中',
  INDEXED: '已索引',
  FAILED: '处理失败',
}

export type Tone = 'neutral' | 'teal' | 'ochre' | 'success' | 'warning' | 'muted'

export function processTone(status: string): Tone {
  switch (status) {
    case 'INDEXED':
      return 'success'
    case 'PROCESSING':
      return 'teal'
    case 'FAILED':
      return 'ochre'
    default:
      return 'muted'
  }
}

export const POST_STATUS_LABEL: Record<string, string> = {
  PUBLISHED: '已发布',
  HIDDEN: '已隐藏',
  DELETED: '已删除',
}

/** 文档来源：上传 / 在线撰写 / 批量导入 */
export const SOURCE_LABEL: Record<string, string> = {
  UPLOAD: '上传',
  EDITOR: '在线撰写',
  IMPORT: '导入',
}

export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

// ---- 课程与请假 ----

export const SEMESTER_STATUS_LABEL: Record<string, string> = {
  DRAFT: '未启用',
  ACTIVE: '进行中',
  CLOSED: '已归档',
}

export const COURSE_STATUS_LABEL: Record<string, string> = {
  DRAFT: '待开放',
  OPEN: '开放选课',
  CLOSED: '已关闭',
  CANCELLED: '已停开',
}

export function courseTone(status: string): Tone {
  switch (status) {
    case 'OPEN':
      return 'success'
    case 'CLOSED':
      return 'muted'
    case 'CANCELLED':
      return 'ochre'
    default:
      return 'teal'
  }
}

/** 不可选原因：标签里用短词，详情页再补充说明 */
export const BLOCK_REASON_LABEL: Record<string, string> = {
  WINDOW_CLOSED: '选课窗口关闭',
  COURSE_NOT_OPEN: '课程未开放',
  ALREADY_SELECTED: '已选',
  FULL: '名额已满',
  TIME_CONFLICT: '时间冲突',
}

export const LEAVE_STATUS_LABEL: Record<string, string> = {
  PENDING: '待审批',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  CANCELLED: '已撤销',
}

export function leaveTone(status: string): Tone {
  switch (status) {
    case 'APPROVED':
      return 'success'
    case 'REJECTED':
      return 'ochre'
    case 'PENDING':
      return 'warning'
    default:
      return 'muted'
  }
}

// ---- 管理 ----

export const USER_STATUS_LABEL: Record<string, string> = {
  ACTIVE: '正常',
  DISABLED: '已停用',
  LOCKED: '已锁定',
}

export function userTone(status: string): Tone {
  switch (status) {
    case 'ACTIVE':
      return 'success'
    case 'DISABLED':
      return 'muted'
    default:
      return 'ochre'
  }
}

export const ROLE_LABEL: Record<string, string> = {
  ADMIN: '管理员',
  TEACHER: '教师',
  STUDENT: '学生',
  STUDENT_UNION: '学生会',
}

export const ORG_TYPE_LABEL: Record<string, string> = {
  COLLEGE: '学院',
  DEPARTMENT: '系',
  MAJOR: '专业',
  CLASS: '班级',
}

export const AUDIT_RESULT_LABEL: Record<string, string> = {
  SUCCESS: '成功',
  FAILURE: '失败',
  DENIED: '拒绝',
}

export function auditTone(result: string): Tone {
  switch (result) {
    case 'SUCCESS':
      return 'success'
    case 'DENIED':
      return 'ochre'
    default:
      return 'warning'
  }
}

export const AUDIT_SOURCE_LABEL: Record<string, string> = {
  API: '接口',
  AI_TOOL: 'AI 工具',
  SYSTEM: '系统',
}

// ---- 成绩、评教、反馈、空教室、考勤 ----

export const GRADE_STATUS_LABEL: Record<string, string> = {
  DRAFT: '未发布',
  PUBLISHED: '已发布',
}

export const LEAVE_STAGE_LABEL: Record<string, string> = {
  TEACHER: '任课教师',
  COUNSELOR: '导员',
  ADMIN: '管理员',
}

export const FEEDBACK_CATEGORY_LABEL: Record<string, string> = {
  COMPLAINT: '投诉',
  SUGGESTION: '建议',
  REPORT: '反映情况',
  OTHER: '其他',
}

export const FEEDBACK_STATUS_LABEL: Record<string, string> = {
  OPEN: '待受理',
  PROCESSING: '处理中',
  RESOLVED: '已回复',
  CLOSED: '已关闭',
}

export function feedbackTone(status: string): Tone {
  switch (status) {
    case 'RESOLVED':
      return 'success'
    case 'PROCESSING':
      return 'teal'
    case 'OPEN':
      return 'warning'
    default:
      return 'muted'
  }
}

export const ATTENDANCE_STATUS_LABEL: Record<string, string> = {
  PRESENT: '出勤',
  ABSENT: '旷课',
  LATE: '迟到',
  LEAVE: '请假',
}

export function attendanceTone(status: string): Tone {
  switch (status) {
    case 'PRESENT':
      return 'success'
    case 'ABSENT':
      return 'ochre'
    case 'LATE':
      return 'warning'
    default:
      return 'teal'
  }
}

export const ATTENDANCE_SESSION_LABEL: Record<string, string> = {
  DRAFT: '待提交',
  FINAL: '已提交',
}

export const ROOM_TYPE_LABEL: Record<string, string> = {
  NORMAL: '普通教室',
  MULTIMEDIA: '多媒体',
  COMPUTER_LAB: '机房',
  LANGUAGE_LAB: '语音室',
  LAB: '实验室',
  GYM: '体育馆',
  OTHER: '其他',
  UNKNOWN: '未登记',
}

export const WEEKDAY_LABEL: Record<number, string> = {
  1: '周一',
  2: '周二',
  3: '周三',
  4: '周四',
  5: '周五',
  6: '周六',
  7: '周日',
}

/** 1~5 分制转文字，避免页面各写一套判断 */
export function scoreWord(score: number): string {
  if (score >= 4.5) return '非常好'
  if (score >= 4) return '好'
  if (score >= 3) return '一般'
  if (score >= 2) return '待改进'
  return '不满意'
}

// ---- 通知、申诉、借用 ----

export const NOTIFICATION_CATEGORY_LABEL: Record<string, string> = {
  LEAVE: '请假',
  GRADE: '成绩',
  FEEDBACK: '反馈',
  ATTENDANCE: '考勤',
  ROOM: '教室',
  FORUM: '论坛',
  ANNOUNCEMENT: '公告',
  SYSTEM: '系统',
}

export function notificationTone(category: string): Tone {
  switch (category) {
    case 'LEAVE':
      return 'teal'
    case 'GRADE':
      return 'success'
    case 'FEEDBACK':
      return 'teal'
    case 'ATTENDANCE':
      return 'warning'
    case 'ROOM':
      return 'ochre'
    case 'FORUM':
      return 'teal'
    case 'ANNOUNCEMENT':
      return 'ochre'
    default:
      return 'muted'
  }
}

export const APPEAL_STATUS_LABEL: Record<string, string> = {
  PENDING: '待复核',
  APPROVED: '已通过',
  REJECTED: '已驳回',
}

export function appealTone(status: string): Tone {
  if (status === 'APPROVED') return 'success'
  if (status === 'REJECTED') return 'ochre'
  return 'warning'
}

export const ROOM_BOOKING_STATUS_LABEL: Record<string, string> = {
  PENDING: '待审批',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  CANCELLED: '已取消',
}

export function roomBookingTone(status: string): Tone {
  if (status === 'APPROVED') return 'success'
  if (status === 'REJECTED') return 'ochre'
  if (status === 'CANCELLED') return 'muted'
  return 'warning'
}
