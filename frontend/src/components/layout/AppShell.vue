<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { NAV } from '@/router/nav'

const auth = useAuthStore()
const router = useRouter()

const items = computed(() => NAV.filter((n) => auth.can(...n.anyOf)))

const ROLE_LABEL: Record<string, string> = { ADMIN: '管理员', TEACHER: '教师', STUDENT: '学生' }
const roleText = computed(() => (auth.user?.roles ?? []).map((r) => ROLE_LABEL[r] ?? r).join(' · ') || '未分配角色')
const initial = computed(() => (auth.user?.displayName ?? '?').slice(-1))

const menuOpen = ref(false)
const loggingOut = ref(false)

async function logout() {
  loggingOut.value = true
  await auth.logout()
  loggingOut.value = false
  menuOpen.value = false
  await router.replace({ name: 'login' })
}
</script>

<template>
  <div class="shell">
    <a class="skip-link" href="#main">跳到正文</a>
    <header class="masthead">
      <div class="masthead__inner">
        <RouterLink to="/" class="wordmark" aria-label="校园 AI 服务平台 首页">
          <!-- [logo] 学校校徽待提供；暂用字标 -->
          <span class="wordmark__cn">校园 AI 服务平台</span>
          <span class="wordmark__latin latin-italic">Campus Press</span>
        </RouterLink>

        <nav class="nav" aria-label="主导航">
          <RouterLink v-for="n in items" :key="n.to" :to="n.to" class="nav__link">
            <span>{{ n.label }}</span>
            <span v-if="!n.ready" class="nav__soon" aria-label="即将上线">·</span>
          </RouterLink>
        </nav>

        <div class="user" @keydown.esc="menuOpen = false">
          <button
            type="button"
            class="user__trigger"
            :aria-expanded="menuOpen"
            aria-controls="user-menu"
            @click="menuOpen = !menuOpen"
          >
            <span class="user__avatar" aria-hidden="true">{{ initial }}</span>
            <span class="user__name">{{ auth.user?.displayName }}</span>
          </button>
          <div v-if="menuOpen" id="user-menu" class="user__menu fade-in">
            <p class="user__who">
              <strong>{{ auth.user?.displayName }}</strong>
              <span class="mono">{{ auth.user?.username }}</span>
            </p>
            <p class="user__roles">{{ roleText }}</p>
            <hr />
            <button type="button" class="user__item" :disabled="loggingOut" @click="logout">
              {{ loggingOut ? '正在退出…' : '退出登录' }}
            </button>
            <p class="user__note">退出会让你在所有设备上的登录同时失效。</p>
          </div>
        </div>
      </div>
    </header>

    <div v-if="menuOpen" class="scrim" aria-hidden="true" @click="menuOpen = false" />

    <main id="main" class="main" tabindex="-1">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.shell {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
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

/* 报头：底部一粗一细两道线，像书刊的页眉 */
.masthead {
  position: sticky;
  top: 0;
  z-index: 50;
  background: color-mix(in oklch, var(--paper) 94%, transparent);
  backdrop-filter: blur(6px);
  border-bottom: 1px solid var(--ink);
  box-shadow: 0 3px 0 -2px var(--paper), 0 4px 0 -2px var(--rule);
}

.masthead__inner {
  display: flex;
  align-items: center;
  gap: var(--s-5);
  height: var(--masthead-h);
  padding: 0 var(--s-5);
}

.wordmark {
  display: flex;
  align-items: baseline;
  gap: var(--s-2);
  color: var(--ink);
  text-decoration: none;
  white-space: nowrap;
}

.wordmark__cn {
  font-family: var(--font-serif-cjk);
  font-size: 17px;
  font-weight: 600;
  letter-spacing: 0.06em;
}

.wordmark__latin {
  font-size: 15px;
  color: var(--ink-2);
}

.nav {
  display: flex;
  align-items: stretch;
  gap: var(--s-1);
  height: 100%;
  overflow-x: auto;
  scrollbar-width: none;
}

.nav__link {
  position: relative;
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 0 var(--s-3);
  color: var(--ink-2);
  font-size: var(--fs-ui-lg);
  text-decoration: none;
  white-space: nowrap;
  transition: color var(--t-fast) var(--ease);
}

.nav__link::after {
  content: '';
  position: absolute;
  left: var(--s-3);
  right: var(--s-3);
  bottom: -1px;
  height: 2px;
  background: var(--teal);
  transform: scaleX(0);
  transform-origin: left;
  transition: transform var(--t-mid) var(--ease);
}

.nav__link:hover {
  color: var(--ink);
}

.nav__link.router-link-active {
  color: var(--ink);
}

.nav__link.router-link-active::after {
  transform: scaleX(1);
}

.nav__soon {
  color: var(--rule-strong);
}

.user {
  position: relative;
  margin-left: auto;
}

.user__trigger {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  min-height: 40px;
  padding: 0 var(--s-1);
  border: 0;
  background: none;
  cursor: pointer;
}

.user__avatar {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--teal);
  color: var(--paper);
  font-family: var(--font-serif-cjk);
  font-size: 14px;
}

.user__name {
  font-size: var(--fs-ui);
}

.user__menu {
  position: absolute;
  right: 0;
  top: calc(100% + var(--s-2));
  z-index: 60;
  width: 248px;
  padding: var(--s-3);
  border: 1px solid var(--ink);
  background: var(--paper-lift);
}

.user__menu hr {
  margin: var(--s-3) 0 var(--s-2);
  border: 0;
  border-top: var(--hairline);
}

.user__who {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin: 0;
}

.user__who .mono {
  color: var(--ink-2);
}

.user__roles {
  margin: var(--s-1) 0 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.user__item {
  width: 100%;
  min-height: 36px;
  padding: 0;
  border: 0;
  background: none;
  text-align: left;
  color: var(--ochre);
  cursor: pointer;
}

.user__item:hover:not(:disabled) {
  text-decoration: underline;
  text-underline-offset: 3px;
}

.user__note {
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.scrim {
  position: fixed;
  inset: 0;
  z-index: 40;
}

.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.main:focus {
  outline: none;
}

@media (max-width: 760px) {
  .masthead__inner {
    gap: var(--s-3);
    padding: 0 var(--s-3);
  }

  .wordmark__latin,
  .user__name {
    display: none;
  }

  .wordmark__cn {
    font-size: 15px;
  }

  .nav__link {
    padding: 0 var(--s-2);
  }

  .nav__link::after {
    left: var(--s-2);
    right: var(--s-2);
  }
}
</style>
