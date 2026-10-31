/**
 * 左侧主导航。分组只负责展开/收起，真正的页面入口在 children 中。
 * 权限点满足任意一个即显示；roles 用于“仅系统管理员”这类额外约束。
 * 前端隐藏只是体验层面的，后端对每个接口都会再次校验。
 */
export interface NavItem {
  to: string
  label: string
  /** 页眉里的拉丁短题，作为书卷气的点缀 */
  latin: string
  anyOf: string[]
  ready: boolean
  roles?: string[]
}

export interface NavGroup {
  type: 'group'
  key: string
  label: string
  latin: string
  children: NavItem[]
}

export type NavEntry = NavItem | NavGroup

export function isNavGroup(entry: NavEntry): entry is NavGroup {
  return 'children' in entry
}

export function flatNavItems(entries: NavEntry[]): NavItem[] {
  return entries.flatMap((entry) => (isNavGroup(entry) ? entry.children : [entry]))
}

export const NAV: NavEntry[] = [
  // 问答与办事合成一页：查资料与“帮我办事”共用一个输入框
  { to: '/ai', label: '问答', latin: 'Ask', anyOf: ['ai:chat', 'ai:tool:use'], ready: true },
  { to: '/knowledge', label: '知识库', latin: 'Library', anyOf: ['kb:space:read'], ready: true },
  { to: '/forum', label: '论坛', latin: 'Letters', anyOf: [], ready: true },
  {
    type: 'group',
    key: 'courses',
    label: '课程',
    latin: 'Courses',
    children: [
      { to: '/courses', label: '课表', latin: 'Timetable', anyOf: ['course:read'], ready: true },
      {
        to: '/scheduling',
        label: '排课',
        latin: 'Scheduling',
        // 学生只在「课表」看自己的安排；排课入口权限保持不变
        anyOf: ['schedule:read', 'schedule:preference:self'],
        ready: true,
      },
    ],
  },
  {
    to: '/grades',
    label: '成绩',
    latin: 'Marks',
    anyOf: ['grade:read', 'grade:manage', 'grade:manage_all', 'evaluation:submit', 'evaluation:read'],
    ready: true,
  },
  {
    to: '/leave',
    label: '请假',
    latin: 'Leave',
    anyOf: ['leave:submit', 'leave:review', 'leave:manage_all'],
    ready: true,
  },
  {
    to: '/services',
    label: '教室',
    latin: 'Rooms',
    anyOf: ['classroom:read', 'room:book', 'room:approve'],
    ready: true,
  },
  {
    type: 'group',
    key: 'mailbox',
    label: '校长信箱',
    latin: 'Mailbox',
    children: [
      { to: '/mailbox?tab=feedback', label: '投诉与建议', latin: 'Write', anyOf: ['feedback:submit'], ready: true },
      {
        to: '/mailbox?tab=handle',
        label: '处理反馈',
        latin: 'Reply',
        anyOf: ['feedback:handle'],
        roles: ['ADMIN'],
        ready: true,
      },
    ],
  },
  {
    to: '/admin',
    label: '管理',
    latin: 'Office',
    anyOf: ['user:read', 'role:manage', 'org:manage', 'audit:read'],
    ready: true,
  },
]
