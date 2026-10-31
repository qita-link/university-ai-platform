<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch, watchEffect } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useNotifyStore } from '@/stores/notify'
import { NAV, isNavGroup, type NavGroup, type NavItem } from '@/router/nav'
import { useAvatarStore } from '@/stores/avatar'
import UserMenu from '@/components/layout/UserMenu.vue'
import AnnouncementPopup from '@/components/layout/AnnouncementPopup.vue'
import NotificationDrawer from '@/components/layout/NotificationDrawer.vue'

/**
 * 外壳：主导航固定在左侧（手机收成抽屉），正文在右侧。
 * <p>
 * 桌面放得下常驻侧栏；手机放不下就变成「汉堡 → 左侧滑出」，铃铛与账号放在顶部条上，
 * 保证通知抽屉与切换账号始终一键可达。
 */
const auth = useAuthStore()
const notify = useNotifyStore()
const avatar = useAvatarStore()
const route = useRoute()

/** 登录态里带头像版本，版本变了（换头像）就重新取一次图片 */
watchEffect(() => {
  void avatar.load(auth.user)
})

function canSee(item: NavItem): boolean {
  const roleAllowed = !item.roles?.length || item.roles.some((role) => auth.user?.roles.includes(role))
  return roleAllowed && auth.can(...item.anyOf)
}

const items = computed<(NavItem | NavGroup)[]>(() =>
  NAV.flatMap((entry): (NavItem | NavGroup)[] => {
    if (!isNavGroup(entry)) return canSee(entry) ? [entry] : []
    const children = entry.children.filter(canSee)
    return children.length ? [{ ...entry, children }] : []
  }),
)

/** 大模块展开状态；当前路由所在分组会自动展开。 */
const expanded = ref<Record<string, boolean>>({})

function toggleGroup(key: string) {
  expanded.value[key] = !expanded.value[key]
}

/** 手机端左侧导航抽屉 */
const navOpen = ref(false)
/** 通知抽屉：点铃铛从右侧滑出，不再整页跳到通知中心 */
const notifyOpen = ref(false)

function openNotify() {
  navOpen.value = false
  notifyOpen.value = true
}

/** 路径按前缀匹配；入口带 tab 时还要匹配页签，避免两个信箱子项同时高亮。 */
function isActive(to: string): boolean {
  const [path, query] = to.split('?')
  const pathActive = path === '/' ? route.path === '/' : route.path === path || route.path.startsWith(path + '/')
  if (!pathActive || !query) return pathActive
  const params = new URLSearchParams(query)
  return [...params.entries()].every(([key, value]) => String(route.query[key] ?? '') === value)
}

function isGroupActive(key: string): boolean {
  const group = items.value.find((entry) => isNavGroup(entry) && entry.key === key)
  return !!group && isNavGroup(group) && group.children.some((item) => isActive(item.to))
}

watch(
  () => route.path,
  () => {
    navOpen.value = false
    notifyOpen.value = false
    for (const entry of items.value) {
      if (isNavGroup(entry) && isGroupActive(entry.key)) expanded.value[entry.key] = true
    }
  },
  { immediate: true },
)

/** 手机抽屉：锁滚动 + Esc 关闭 */
function onNavKey(e: KeyboardEvent) {
  if (e.key === 'Escape') navOpen.value = false
}

watch(navOpen, (v) => {
  if (v) {
    document.body.style.overflow = 'hidden'
    document.addEventListener('keydown', onNavKey)
  } else {
    document.body.style.overflow = ''
    document.removeEventListener('keydown', onNavKey)
  }
})

onMounted(() => notify.start())
onBeforeUnmount(() => {
  notify.stop()
  document.body.style.overflow = ''
  document.removeEventListener('keydown', onNavKey)
})
</script>

<template>
  <div class="shell">
    <a class="skip-link" href="#main">跳到正文</a>

    <!-- 手机顶部条：菜单、字标、通知、账号 -->
    <header class="topbar">
      <button type="button" class="topbar__menu" aria-label="打开菜单" @click="navOpen = true">
        <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
          <path d="M4 7h16M4 12h16M4 17h16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
        </svg>
      </button>
      <RouterLink to="/ai" class="wordmark" aria-label="校园 AI 服务平台">
        <span class="wordmark__cn">校园 AI 服务平台</span>
      </RouterLink>

      <button
        type="button"
        class="bell"
        aria-label="通知"
        aria-haspopup="dialog"
        :aria-expanded="notifyOpen"
        @click="openNotify"
      >
        <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
          <path
            d="M12 3a5 5 0 0 0-5 5v3.2l-1.6 3.2A1 1 0 0 0 6.3 16h11.4a1 1 0 0 0 .9-1.6L17 11.2V8a5 5 0 0 0-5-5Zm0 18a2.5 2.5 0 0 0 2.4-2H9.6A2.5 2.5 0 0 0 12 21Z"
            fill="none"
            stroke="currentColor"
            stroke-width="1.4"
            stroke-linejoin="round"
          />
        </svg>
        <span v-if="notify.unread" class="bell__badge mono">{{ notify.unread > 99 ? '99+' : notify.unread }}</span>
      </button>

      <UserMenu variant="bar" />
    </header>

    <!-- 左侧主导航：桌面常驻，手机自左滑出 -->
    <aside class="sidenav" :class="{ 'is-open': navOpen }" aria-label="主导航">
      <RouterLink to="/ai" class="sidenav__brand">
        <span class="sidenav__cn">校园 AI 服务平台</span>
        <span class="sidenav__latin latin-italic">Campus Press</span>
      </RouterLink>

      <nav class="sidenav__list">
        <template v-for="n in items" :key="isNavGroup(n) ? n.key : n.to">
          <div v-if="isNavGroup(n)" class="nav-group">
            <button
              type="button"
              class="nav-link nav-group__toggle"
              :class="{ 'is-active': isGroupActive(n.key) }"
              :aria-expanded="!!expanded[n.key]"
              :aria-controls="`nav-group-${n.key}`"
              @click="toggleGroup(n.key)"
            >
              <span class="nav-link__label">{{ n.label }}</span>
              <span class="nav-link__latin">{{ n.latin }}</span>
              <svg class="nav-group__chevron" :class="{ 'is-open': expanded[n.key] }" viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
                <path d="m4 6 4 4 4-4" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </button>
            <Transition name="nav-drop">
              <div v-if="expanded[n.key]" :id="`nav-group-${n.key}`" class="nav-group__children">
                <RouterLink
                  v-for="child in n.children"
                  :key="child.to"
                  :to="child.to"
                  class="nav-link nav-link--child"
                  :class="{ 'is-active': isActive(child.to) }"
                  :aria-current="isActive(child.to) ? 'page' : undefined"
                >
                  <span class="nav-link__label">{{ child.label }}</span>
                  <span class="nav-link__latin">{{ child.latin }}</span>
                </RouterLink>
              </div>
            </Transition>
          </div>
          <RouterLink
            v-else
            :to="n.to"
            class="nav-link"
            :class="{ 'is-active': isActive(n.to) }"
            :aria-current="isActive(n.to) ? 'page' : undefined"
          >
            <span class="nav-link__label">{{ n.label }}</span>
            <span class="nav-link__latin">{{ n.latin }}</span>
            <span v-if="!n.ready" class="nav-link__soon" aria-label="即将上线">·</span>
          </RouterLink>
        </template>
      </nav>

      <div class="sidenav__foot">
        <UserMenu variant="side" class="sidenav__user" />
        <button type="button" class="bell" aria-label="通知" aria-haspopup="dialog" @click="openNotify">
          <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
            <path
              d="M12 3a5 5 0 0 0-5 5v3.2l-1.6 3.2A1 1 0 0 0 6.3 16h11.4a1 1 0 0 0 .9-1.6L17 11.2V8a5 5 0 0 0-5-5Zm0 18a2.5 2.5 0 0 0 2.4-2H9.6A2.5 2.5 0 0 0 12 21Z"
              fill="none"
              stroke="currentColor"
              stroke-width="1.4"
              stroke-linejoin="round"
            />
          </svg>
          <span v-if="notify.unread" class="bell__badge mono">{{ notify.unread > 99 ? '99+' : notify.unread }}</span>
        </button>
      </div>
    </aside>

    <div v-if="navOpen" class="navscrim" aria-hidden="true" @click="navOpen = false" />

    <main id="main" class="main" tabindex="-1">
      <RouterView />
    </main>

    <!-- 通知抽屉：点铃铛从右侧拉出，占页面一部分 -->
    <NotificationDrawer :open="notifyOpen" @close="notifyOpen = false" />

    <!-- 官方公告：未读即弹窗，关闭后进通知中心 -->
    <AnnouncementPopup />
  </div>
</template>

<style scoped>
.shell {
  min-height: 100vh;
}

.skip-link {
  position: absolute;
  left: var(--s-3);
  top: -40px;
  z-index: 200;
  padding: var(--s-2) var(--s-3);
  background: var(--ink);
  color: var(--paper);
}

.skip-link:focus {
  top: var(--s-2);
}

/* ---------- 左侧主导航 ---------- */
.sidenav {
  position: fixed;
  top: 0;
  bottom: 0;
  left: 0;
  z-index: 80;
  display: flex;
  flex-direction: column;
  width: var(--nav-w);
  padding: var(--s-4) 0 var(--s-3);
  border-right: 1px solid var(--ink);
  background: var(--paper);
  overflow-y: auto;
}

.sidenav__brand {
  display: grid;
  gap: 2px;
  margin: 0 var(--s-4) var(--s-4);
  padding-bottom: var(--s-3);
  border-bottom: 1px solid var(--ink);
  color: var(--ink);
  text-decoration: none;
}

.sidenav__cn {
  font-family: var(--font-serif-cjk);
  font-size: 17px;
  font-weight: 600;
  letter-spacing: 0.06em;
  line-height: 1.3;
}

.sidenav__latin {
  color: var(--ink-2);
  font-size: 13px;
}

.sidenav__list {
  display: grid;
  gap: 1px;
}

.nav-link {
  display: flex;
  align-items: baseline;
  gap: var(--s-2);
  padding: 9px var(--s-4);
  border-left: 2px solid transparent;
  color: var(--ink-2);
  font-family: var(--font-serif-cjk);
  font-size: 16px;
  text-decoration: none;
  transition:
    color var(--t-fast) var(--ease),
    border-color var(--t-fast) var(--ease),
    background-color var(--t-fast) var(--ease);
}

.nav-link__label {
  white-space: nowrap;
}

.nav-link__latin {
  margin-left: auto;
  color: var(--ink-2);
  font-family: var(--font-serif);
  font-size: 11px;
  opacity: 0.55;
}

.nav-link:hover {
  color: var(--ink);
  background: color-mix(in srgb, var(--paper-deep) 55%, transparent);
}

.nav-link.is-active {
  border-left-color: var(--teal);
  background: color-mix(in srgb, var(--paper-deep) 80%, transparent);
  color: var(--ink);
  font-weight: 600;
}

.nav-link__soon {
  color: var(--rule-strong);
}

.nav-group__toggle {
  width: 100%;
  border-top: 0;
  border-right: 0;
  border-bottom: 0;
  background: none;
  text-align: left;
  cursor: pointer;
}

.nav-group__chevron {
  flex: 0 0 auto;
  transition: transform var(--t-fast) var(--ease);
}

.nav-group__chevron.is-open {
  transform: rotate(180deg);
}

.nav-group__children {
  display: grid;
}

.nav-link--child {
  padding-left: calc(var(--s-4) + 14px);
  font-size: 14px;
}

.nav-link--child .nav-link__latin {
  font-size: 10px;
}

.nav-drop-enter-active,
.nav-drop-leave-active {
  transition:
    opacity var(--t-fast) var(--ease),
    transform var(--t-fast) var(--ease);
  transform-origin: top;
}

.nav-drop-enter-from,
.nav-drop-leave-to {
  opacity: 0;
  transform: translateY(-5px);
}

.sidenav__foot {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: auto;
  padding: var(--s-2) var(--s-3) 0;
  border-top: var(--hairline);
}

.sidenav__user {
  flex: 1 1 auto;
  min-width: 0;
}

/* 让问答页这类 flex:1 的页面仍能撑满视口 */
.main {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  margin-left: var(--nav-w);
}

/* ---------- 通知铃铛 ---------- */
.bell {
  position: relative;
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  width: 36px;
  height: 36px;
  padding: 0;
  border: 0;
  background: none;
  color: var(--ink);
  cursor: pointer;
}

.bell:hover {
  color: var(--teal);
}

.bell__badge {
  position: absolute;
  top: 2px;
  right: 0;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  background: var(--ochre);
  color: var(--paper);
  font-size: 10px;
  line-height: 16px;
  text-align: center;
}

/* ---------- 手机：顶部条 + 抽屉 ---------- */
.topbar {
  display: none;
}

.navscrim {
  display: none;
}

@media (max-width: 900px) {
  .topbar {
    position: sticky;
    top: 0;
    z-index: 70;
    display: flex;
    align-items: center;
    gap: 6px;
    height: var(--masthead-h);
    padding: 0 10px;
    /* srgb 混合：oklch 与 transparent 混会丢色相 */
    background: color-mix(in srgb, var(--paper) 94%, transparent);
    backdrop-filter: blur(6px);
    border-bottom: 1px solid var(--ink);
  }

  .topbar__menu {
    display: grid;
    place-items: center;
    width: 34px;
    height: 34px;
    padding: 0;
    border: 0;
    background: none;
    color: var(--ink);
    cursor: pointer;
  }

  .wordmark {
    display: flex;
    align-items: baseline;
    min-width: 0;
    margin-right: auto;
    color: var(--ink);
    text-decoration: none;
  }

  .wordmark__cn {
    overflow: hidden;
    font-family: var(--font-serif-cjk);
    font-size: 15px;
    font-weight: 600;
    letter-spacing: 0.04em;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .sidenav {
    width: min(280px, 82vw);
    transform: translateX(-100%);
    transition: transform 220ms var(--ease);
  }

  .sidenav.is-open {
    transform: none;
  }

  /* 抽屉里不再重复铃铛与账号，顶部条已经有 */
  .sidenav__foot {
    display: none;
  }

  .nav-link {
    padding: 11px var(--s-4);
    font-size: 17px;
  }

  .navscrim {
    position: fixed;
    inset: 0;
    z-index: 75;
    display: block;
    background: color-mix(in srgb, var(--ink) 24%, transparent);
  }

  .main {
    margin-left: 0;
  }
}
</style>
