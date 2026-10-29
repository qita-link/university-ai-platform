<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { roleApi } from '@/api/endpoints'
import type { PermissionView, RoleView } from '@/api/types'
import { errorMessage } from '@/lib/errors'
import UiButton from '@/components/ui/UiButton.vue'
import UiTag from '@/components/ui/UiTag.vue'

/**
 * 角色与权限：只读。三种内置角色的权限点由后端固化，
 * 这里把「谁能做什么」摊开给人看，避免管理员靠猜。
 */
const roles = ref<RoleView[]>([])
const permissions = ref<PermissionView[]>([])
const activeCode = ref<string | null>(null)
const loading = ref(true)
const loadError = ref<string | null>(null)

const active = computed(() => roles.value.find((r) => r.code === activeCode.value) ?? null)

/** 权限点按模块分组，保持后端返回顺序 */
const groups = computed(() => {
  const byModule = new Map<string, PermissionView[]>()
  for (const p of permissions.value) {
    const list = byModule.get(p.module) ?? []
    list.push(p)
    byModule.set(p.module, list)
  }
  return [...byModule.entries()].map(([module, items]) => ({ module, items }))
})

const activeSet = computed(() => new Set(active.value?.permissions ?? []))

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const [r, p] = await Promise.all([roleApi.roles(), roleApi.permissions()])
    roles.value = r
    permissions.value = p
    activeCode.value = r[0]?.code ?? null
  } catch (e) {
    loadError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="panel">
    <p v-if="loading" class="state">正在取角色…</p>

    <div v-else-if="loadError" class="state state--err" role="alert">
      <p>{{ loadError }}</p>
      <UiButton size="sm" @click="load">重试</UiButton>
    </div>

    <div v-else class="grid">
      <aside class="roles">
        <p class="roles__title eyebrow">角色</p>
        <ul class="roles__list">
          <li v-for="r in roles" :key="r.code">
            <button
              type="button"
              class="role"
              :class="{ 'is-active': r.code === activeCode }"
              @click="activeCode = r.code"
            >
              <span class="role__name">{{ r.name }}</span>
              <span class="role__code mono">{{ r.code }}</span>
              <span class="role__count mono">{{ r.permissions.length }}</span>
            </button>
          </li>
        </ul>
        <p class="roles__note">内置角色不可新增或删除；用户的权限完全由所分配的角色决定。</p>
      </aside>

      <div class="detail">
        <header v-if="active" class="detail__head">
          <div>
            <h2 class="detail__name">{{ active.name }}</h2>
            <p class="detail__desc">{{ active.description || '无说明' }}</p>
          </div>
          <UiTag tone="neutral">{{ active.permissions.length }} 个权限点</UiTag>
        </header>

        <div v-for="g in groups" :key="g.module" class="group">
          <p class="group__title">
            {{ g.module }}
            <span class="group__count mono">{{ g.items.filter((i) => activeSet.has(i.code)).length }} / {{ g.items.length }}</span>
          </p>
          <ul class="perms">
            <li v-for="p in g.items" :key="p.code" class="perm" :class="{ 'is-on': activeSet.has(p.code) }">
              <span class="perm__mark" aria-hidden="true">{{ activeSet.has(p.code) ? '✓' : '·' }}</span>
              <span class="perm__name">{{ p.name }}</span>
              <span class="perm__code mono">{{ p.code }}</span>
            </li>
          </ul>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.panel {
  min-height: 240px;
}

.grid {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  gap: clamp(var(--s-4), 3vw, var(--s-6));
  align-items: start;
}

.roles {
  position: sticky;
  top: calc(var(--masthead-h) + var(--s-4));
}

.roles__title {
  margin: 0 0 var(--s-2);
  padding-bottom: var(--s-2);
  border-bottom: 1px solid var(--ink);
}

.roles__list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.role {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 0 var(--s-2);
  width: 100%;
  padding: var(--s-2);
  border: 0;
  border-left: 2px solid transparent;
  background: none;
  text-align: left;
  cursor: pointer;
  transition:
    border-color var(--t-fast) var(--ease),
    background-color var(--t-fast) var(--ease);
}

.role:hover {
  background: color-mix(in srgb, var(--paper-deep) 60%, transparent);
}

.role.is-active {
  border-left-color: var(--teal);
  background: color-mix(in srgb, var(--paper-lift) 70%, transparent);
}

.role__name {
  font-family: var(--font-serif-cjk);
  font-size: 15px;
}

.role.is-active .role__name {
  font-weight: 600;
}

.role__code {
  grid-row: 2;
  color: var(--ink-2);
  font-size: 10px;
}

.role__count {
  grid-row: 1 / span 2;
  align-self: center;
  color: var(--ink-2);
  font-size: 11px;
}

.roles__note {
  margin: var(--s-3) 0 0;
  color: var(--ink-2);
  font-size: 11px;
  line-height: 1.7;
}

.detail__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s-4);
  padding-bottom: var(--s-3);
  margin-bottom: var(--s-4);
  border-bottom: 1px solid var(--ink);
}

.detail__name {
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-size: 22px;
}

.detail__desc {
  margin: var(--s-1) 0 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.group {
  margin-bottom: var(--s-4);
}

.group__title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin: 0 0 var(--s-2);
  padding-bottom: var(--s-1);
  border-bottom: var(--hairline);
  font-family: var(--font-mono);
  font-size: 12px;
  letter-spacing: 0.06em;
  color: var(--ink-2);
}

.group__count {
  font-size: 11px;
}

.perms {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 0 var(--s-4);
  margin: 0;
  padding: 0;
  list-style: none;
}

.perm {
  display: flex;
  align-items: baseline;
  gap: var(--s-2);
  padding: 3px 0;
  color: var(--rule-strong);
}

.perm.is-on {
  color: var(--ink);
}

.perm__mark {
  width: 1em;
  font-family: var(--font-mono);
  font-size: 11px;
}

.perm.is-on .perm__mark {
  color: var(--teal);
}

.perm__name {
  font-size: var(--fs-small);
}

.perm__code {
  margin-left: auto;
  font-size: 10px;
  color: var(--ink-2);
  opacity: 0.7;
}

.state {
  margin: 0;
  padding: var(--s-5) 0;
  color: var(--ink-2);
  font-family: var(--font-serif);
  font-size: var(--fs-read);
}

.state--err {
  color: var(--ochre);
}

@media (max-width: 860px) {
  .grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .roles {
    position: static;
  }
}
</style>
