<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { knowledgeApi, userApi } from '@/api/endpoints'
import type { MemberRole, SpaceMemberView, SpaceView, UserView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import { errorMessage, traceIdOf } from '@/lib/errors'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiField from '@/components/ui/UiField.vue'
import UiSelect from '@/components/ui/UiSelect.vue'
import UiTag from '@/components/ui/UiTag.vue'

/**
 * 空间成员授权：成员身份不绕过功能权限——被加为 EDITOR 的人仍需具备 kb:document:write 才能写。
 * 所有者不在成员列表里，也不能被移除或改角色。
 */
const props = defineProps<{ open: boolean; space: SpaceView }>()
const emit = defineEmits<{ close: []; changed: [] }>()

const auth = useAuthStore()
const toast = useToastStore()

const members = ref<SpaceMemberView[]>([])
const loading = ref(false)
const loadError = ref<string | null>(null)
const canSearchUsers = computed(() => auth.can('user:read'))

const roleOptions = [
  { value: 'MANAGER', label: '管理' },
  { value: 'EDITOR', label: '编辑' },
  { value: 'VIEWER', label: '只读' },
]

async function load() {
  loading.value = true
  loadError.value = null
  try {
    members.value = await knowledgeApi.members(props.space.id)
  } catch (e) {
    loadError.value = errorMessage(e)
    members.value = []
  } finally {
    loading.value = false
  }
}

watch(
  () => props.open,
  (v) => {
    if (v) void load()
  },
  { immediate: true },
)

/* ---------------- 改角色 / 移除 ---------------- */
const busyUser = ref<number | null>(null)

async function changeRole(m: SpaceMemberView, role: string) {
  if (role === m.memberRole) return
  busyUser.value = m.userId
  try {
    await knowledgeApi.updateMember(props.space.id, m.userId, role)
    toast.push(`${m.displayName} 的角色已更新`, 'success')
    await load()
    emit('changed')
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    busyUser.value = null
  }
}

async function remove(m: SpaceMemberView) {
  busyUser.value = m.userId
  try {
    await knowledgeApi.removeMember(props.space.id, m.userId)
    toast.push('已移除该成员', 'success')
    await load()
    emit('changed')
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    busyUser.value = null
  }
}

/* ---------------- 添加 ---------------- */
const keyword = ref('')
const results = ref<UserView[]>([])
const searching = ref(false)
const picked = ref<number | null>(null)
const pickedRole = ref<MemberRole>('VIEWER')
const adding = ref(false)

async function search() {
  searching.value = true
  try {
    const res = await userApi.list({ keyword: keyword.value.trim() || undefined, size: 8 })
    // 已在成员里的不必再出现
    const exists = new Set([props.space.ownerUserId, ...members.value.map((m) => m.userId)])
    results.value = res.items.filter((u) => !exists.has(u.id))
    picked.value = results.value[0]?.id ?? null
  } catch (e) {
    results.value = []
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    searching.value = false
  }
}

watch(
  () => props.open,
  (v) => {
    if (!v) return
    keyword.value = ''
    results.value = []
    picked.value = null
    pickedRole.value = 'VIEWER'
    if (canSearchUsers.value) void search()
  },
)

async function add() {
  if (!picked.value) {
    toast.push('请先选择一位用户', 'danger')
    return
  }
  adding.value = true
  try {
    await knowledgeApi.addMember(props.space.id, { userId: picked.value, memberRole: pickedRole.value })
    toast.push('成员已添加', 'success')
    picked.value = null
    await load()
    if (canSearchUsers.value) await search()
    emit('changed')
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    adding.value = false
  }
}
</script>

<template>
  <UiDialog
    :open="open"
    title="空间成员"
    :eyebrow="space.name"
    width="md"
    :dismissible="!adding"
    @close="emit('close')"
  >
    <p class="note">
      所有者 <strong>{{ space.ownerName || '—' }}</strong> 不可被移除或改角色。角色含义：管理＝可管成员与空间设置，编辑＝可写文档，只读＝只能看。
      成员身份不会绕过功能权限：编辑者仍需拥有「知识库文档写入」权限才能真正改文档。
    </p>

    <p v-if="loading" class="muted">正在取成员…</p>
    <div v-else-if="loadError" class="muted" role="alert">
      {{ loadError }}
      <UiButton size="sm" variant="link" @click="load">重试</UiButton>
    </div>

    <template v-else>
      <ul v-if="members.length" class="members">
        <li v-for="m in members" :key="m.userId" class="member">
          <div class="member__who">
            <span class="member__name">{{ m.displayName }}</span>
            <span class="member__id mono">{{ m.username }}</span>
          </div>
          <template v-if="m.userId === space.ownerUserId">
            <UiTag tone="teal">所有者</UiTag>
            <span />
          </template>
          <template v-else>
            <UiSelect
              :model-value="m.memberRole"
              class="member__role"
              label="角色"
              :options="roleOptions"
              :disabled="busyUser === m.userId"
              @update:model-value="changeRole(m, $event)"
            />
            <UiButton size="sm" variant="link" :loading="busyUser === m.userId" @click="remove(m)">移除</UiButton>
          </template>
        </li>
      </ul>
      <p v-else class="muted">还没有成员。公开空间对全校可读，私有空间只有成员能看。</p>

      <div v-if="canSearchUsers" class="add">
        <p class="add__title eyebrow">添加成员</p>
        <div class="add__row">
          <UiField v-model="keyword" label="按姓名/账号搜索" />
          <UiButton size="sm" :loading="searching" @click="search">查找</UiButton>
        </div>
        <ul v-if="results.length" class="candidates">
          <li v-for="u in results" :key="u.id">
            <label class="candidate">
              <input v-model="picked" type="radio" :value="u.id" />
              <span>{{ u.displayName }}</span>
              <span class="candidate__id mono">{{ u.username }}</span>
            </label>
          </li>
        </ul>
        <p v-else class="muted">没有可添加的用户，换个关键词试试。</p>
        <UiSelect v-model="pickedRole" label="角色" :options="roleOptions" />
        <div class="add__foot">
          <UiButton variant="primary" :loading="adding" :disabled="!picked" @click="add">添加</UiButton>
        </div>
      </div>
      <p v-else class="muted">当前账号没有用户查询权限，只能调整现有成员；添加成员请联系管理员。</p>
    </template>

    <template #footer>
      <UiButton variant="link" @click="emit('close')">关闭</UiButton>
    </template>
  </UiDialog>
</template>

<style scoped>
.note {
  margin: 0 0 var(--s-4);
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.7;
}

.muted {
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.7;
}

.members {
  margin: 0 0 var(--s-4);
  padding: 0;
  list-style: none;
  border-top: var(--hairline);
}

.member {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 208px auto;
  align-items: center;
  gap: var(--s-3);
  padding: var(--s-2) 0;
  border-bottom: var(--hairline);
}

.member__who {
  display: flex;
  align-items: baseline;
  gap: var(--s-2);
  min-width: 0;
}

.member__name {
  font-family: var(--font-serif-cjk);
  font-size: 15px;
}

.member__id {
  color: var(--ink-2);
  font-size: 11px;
}

.add {
  padding-top: var(--s-3);
  border-top: 1px solid var(--ink);
}

.add__title {
  margin: 0 0 var(--s-2);
}

.add__row {
  display: flex;
  align-items: flex-end;
  gap: var(--s-3);
  margin-bottom: var(--s-2);
}

.add__row :deep(.ui-field) {
  flex: 1;
}

.candidates {
  margin: 0 0 var(--s-3);
  padding: 0;
  list-style: none;
}

.candidate {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  min-height: 32px;
  cursor: pointer;
}

.candidate input {
  accent-color: var(--teal);
}

.candidate__id {
  margin-left: auto;
  color: var(--ink-2);
  font-size: 11px;
}

.add__foot {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--s-3);
}

@media (max-width: 640px) {
  .member {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
