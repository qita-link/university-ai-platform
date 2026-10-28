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
  { to: '/ai', label: '问答', latin: 'Ask', anyOf: ['ai:chat'], ready: true },
  { to: '/knowledge', label: '知识库', latin: 'Library', anyOf: ['kb:space:read'], ready: false },
  { to: '/forum', label: '论坛', latin: 'Letters', anyOf: [], ready: false },
  { to: '/courses', label: '课程', latin: 'Ledger', anyOf: ['course:read'], ready: false },
  {
    to: '/leave',
    label: '请假',
    latin: 'Leave',
    anyOf: ['leave:submit', 'leave:review', 'leave:manage_all'],
    ready: false,
  },
  {
    to: '/admin',
    label: '管理',
    latin: 'Office',
    anyOf: ['user:read', 'role:manage', 'org:manage', 'audit:read'],
    ready: false,
  },
]
