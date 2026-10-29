<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import UiPageHeader from '@/components/ui/UiPageHeader.vue'
import UsersPanel from './UsersPanel.vue'
import OrgPanel from './OrgPanel.vue'
import RolesPanel from './RolesPanel.vue'
import AuditPanel from './AuditPanel.vue'
import SemestersPanel from './SemestersPanel.vue'

/**
 * 管理工作台：用户、组织、角色与审计四件事放在同一页的四个抽屉里。
 * 每个抽屉自己判断权限、自己取数，切走即卸载，避免一次拉四个模块的数据。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

interface TabDef {
  key: 'users' | 'org' | 'semesters' | 'roles' | 'audit'
  label: string
  anyOf: string[]
}

const TABS: TabDef[] = [
  { key: 'users', label: '用户', anyOf: ['user:read'] },
  { key: 'org', label: '组织', anyOf: ['org:manage'] },
  { key: 'semesters', label: '学期', anyOf: ['course:manage_all'] },
  { key: 'roles', label: '角色与权限', anyOf: ['role:manage'] },
  { key: 'audit', label: '审计日志', anyOf: ['audit:read'] },
]

const visible = computed(() => TABS.filter((t) => auth.can(...t.anyOf)))
const current = computed(() => {
  const q = String(route.query.tab ?? '')
  const hit = visible.value.find((t) => t.key === q)
  return hit?.key ?? visible.value[0]?.key ?? 'users'
})

function go(key: string) {
  void router.replace({ query: { ...route.query, tab: key } })
}
</script>

<template>
  <div class="admin">
    <UiPageHeader
      label="管理"
      latin="Office"
      title="校务办公室"
      lede="账号与组织是权限的底座，学期是课程的容器。这里的每次写操作都会记入审计日志。"
    />

    <div class="bar">
      <div class="tabs" role="tablist" aria-label="管理分区">
        <button
          v-for="t in visible"
          :key="t.key"
          type="button"
          class="tabs__btn"
          role="tab"
          :aria-selected="current === t.key"
          :class="{ 'is-active': current === t.key }"
          @click="go(t.key)"
        >
          {{ t.label }}
        </button>
      </div>
    </div>

    <UsersPanel v-if="current === 'users'" />
    <OrgPanel v-else-if="current === 'org'" />
    <SemestersPanel v-else-if="current === 'semesters'" />
    <RolesPanel v-else-if="current === 'roles'" />
    <AuditPanel v-else-if="current === 'audit'" />
  </div>
</template>

<style scoped>
.admin {
  width: 100%;
  max-width: 1180px;
  margin: 0 auto;
  padding: var(--s-5) clamp(var(--s-4), 4vw, var(--s-6)) var(--s-7);
}

.bar {
  padding-bottom: var(--s-2);
  margin-bottom: var(--s-4);
  border-bottom: 1px solid var(--ink);
}

.tabs {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-4);
}

.tabs__btn {
  position: relative;
  padding: 0 0 var(--s-2);
  border: 0;
  background: none;
  color: var(--ink-2);
  font-family: var(--font-serif-cjk);
  font-size: 17px;
  cursor: pointer;
  transition: color var(--t-fast) var(--ease);
}

.tabs__btn:hover {
  color: var(--ink);
}

.tabs__btn.is-active {
  color: var(--ink);
  font-weight: 600;
}

.tabs__btn.is-active::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  background: var(--teal);
}
</style>
