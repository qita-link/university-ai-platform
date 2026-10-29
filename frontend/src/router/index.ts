import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { NAV } from './nav'

declare module 'vue-router' {
  interface RouteMeta {
    /** 公开页面（无需登录） */
    public?: boolean
    /** 满足任意一个权限点即可进入 */
    anyOf?: string[]
    title?: string
  }
}

const pendingRoutes: RouteRecordRaw[] = NAV.filter((n) => !n.ready).map((n) => ({
  path: n.to + '/:rest(.*)*',
  component: () => import('@/views/PendingModuleView.vue'),
  props: { label: n.label, latin: n.latin },
  meta: { anyOf: n.anyOf, title: n.label },
}))

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/',
    component: () => import('@/components/layout/AppShell.vue'),
    children: [
      { path: '', redirect: '/ai' },
      {
        path: 'ai/:conversationId(\\d+)?',
        name: 'ai',
        component: () => import('@/views/AiChatView.vue'),
        meta: { anyOf: ['ai:chat', 'ai:tool:use'], title: '问答' },
      },
      // 旧链接仍可用：办事已并入问答页的视图切换
      { path: 'act', redirect: { name: 'ai', query: { mode: 'act' } } },
      {
        path: 'knowledge',
        name: 'knowledge',
        component: () => import('@/views/knowledge/LibraryView.vue'),
        meta: { anyOf: ['kb:space:read'], title: '知识库' },
      },
      {
        path: 'knowledge/spaces/:id(\\d+)',
        name: 'space',
        component: () => import('@/views/knowledge/SpaceView.vue'),
        meta: { anyOf: ['kb:space:read'], title: '空间' },
      },
      {
        path: 'knowledge/documents/:id(\\d+)',
        name: 'document',
        component: () => import('@/views/DocumentView.vue'),
        meta: { anyOf: ['kb:space:read'], title: '文档' },
      },
      {
        path: 'forum',
        name: 'forum',
        component: () => import('@/views/forum/ForumView.vue'),
        meta: { title: '论坛' },
      },
      {
        path: 'forum/posts/:id(\\d+)',
        name: 'post',
        component: () => import('@/views/forum/PostView.vue'),
        meta: { title: '帖子' },
      },
      {
        path: 'courses',
        name: 'courses',
        component: () => import('@/views/course/CourseView.vue'),
        meta: { anyOf: ['course:read'], title: '课程' },
      },
      {
        path: 'courses/:id(\\d+)',
        name: 'course',
        component: () => import('@/views/course/CourseDetailView.vue'),
        meta: { anyOf: ['course:read'], title: '课程详情' },
      },
      {
        path: 'leave',
        name: 'leave',
        component: () => import('@/views/LeaveView.vue'),
        meta: { anyOf: ['leave:submit', 'leave:review', 'leave:manage_all'], title: '请假' },
      },
      {
        path: 'scheduling',
        name: 'scheduling',
        component: () => import('@/views/scheduling/SchedulingView.vue'),
        // 与 NAV 保持一致：学生没有 schedule:read / schedule:preference:self，直接进不来
        meta: { anyOf: ['schedule:read', 'schedule:preference:self'], title: '排课' },
      },
      {
        path: 'admin',
        name: 'admin',
        component: () => import('@/views/admin/AdminView.vue'),
        meta: { anyOf: ['user:read', 'role:manage', 'org:manage', 'audit:read'], title: '管理' },
      },
      ...pendingRoutes,
      {
        path: 'forbidden',
        name: 'forbidden',
        component: () => import('@/views/NotFoundView.vue'),
        props: { forbidden: true },
        meta: { title: '无权访问' },
      },
      {
        path: ':pathMatch(.*)*',
        name: 'not-found',
        component: () => import('@/views/NotFoundView.vue'),
        meta: { title: '页面不存在' },
      },
    ],
  },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(to, _from, saved) {
    if (saved) return saved
    if (to.hash) return { el: to.hash, top: 80 }
    return { top: 0 }
  },
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  if (to.meta.public) {
    if (to.name === 'login' && auth.isAuthenticated) return { path: '/' }
    return true
  }

  if (!auth.isAuthenticated) {
    return { name: 'login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : {} }
  }

  // 每个标签页首次进入时用 /auth/me 刷新权限（角色可能已被管理员调整）
  if (!auth.verified) {
    try {
      await auth.refreshMe()
    } catch {
      if (!auth.isAuthenticated) return { name: 'login', query: { redirect: to.fullPath } }
    }
  }

  const anyOf = to.meta.anyOf
  if (anyOf && anyOf.length > 0 && !auth.can(...anyOf)) {
    return { name: 'forbidden' }
  }
  return true
})

router.afterEach((to) => {
  const base = '校园 AI 服务平台'
  document.title = to.meta.title ? `${to.meta.title} · ${base}` : base
})

export { safeRedirect } from './redirect'
