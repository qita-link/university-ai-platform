/**
 * 导航结构与所需权限（满足任意一个即显示）。权限点来自 docs/04-api-design.md。
 * 前端隐藏只是体验层面的；后端对每个接口都会再次校验。
 */
export interface NavItem {
  to: string
  label: string
  /** 页眉里的拉丁短题，作为书卷气的点缀 */
  latin: string
  anyOf: string[]
  ready: boolean
}

export const NAV: NavItem[] = [
  // 问答与办事合成一页：同一句人话进去，只是办事会挑工具、改数据
  { to: '/ai', label: '问答', latin: 'Ask', anyOf: ['ai:chat', 'ai:tool:use'], ready: true },
  { to: '/knowledge', label: '知识库', latin: 'Library', anyOf: ['kb:space:read'], ready: true },
  { to: '/forum', label: '论坛', latin: 'Letters', anyOf: [], ready: true },
  { to: '/courses', label: '课程', latin: 'Ledger', anyOf: ['course:read'], ready: true },
  {
    to: '/leave',
    label: '请假',
    latin: 'Leave',
    anyOf: ['leave:submit', 'leave:review', 'leave:manage_all'],
    ready: true,
  },
  {
    to: '/scheduling',
    label: '排课',
    latin: 'Timetable',
    // 学生不进排课：他们只在「课程」页看自己的课表（timetable:read 学生也有，不能作为入口条件）
    anyOf: ['schedule:read', 'schedule:preference:self'],
    ready: true,
  },
  {
    to: '/admin',
    label: '管理',
    latin: 'Office',
    anyOf: ['user:read', 'role:manage', 'org:manage', 'audit:read'],
    ready: true,
  },
]
