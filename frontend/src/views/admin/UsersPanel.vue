<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { orgApi, roleApi, userApi } from '@/api/endpoints'
import type { OrgTreeNode, RoleView, UserView } from '@/api/types'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { ROLE_LABEL, USER_STATUS_LABEL, userTone } from '@/lib/labels'
import { formatDateTime } from '@/lib/time'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiEmpty from '@/components/ui/UiEmpty.vue'
import UiField from '@/components/ui/UiField.vue'
import UiPager from '@/components/ui/UiPager.vue'
import UiSelect from '@/components/ui/UiSelect.vue'
import UiTag from '@/components/ui/UiTag.vue'

/**
 * 用户管理：查询、建档、改资料、启停锁、分配角色、重置密码。
 * 所有修改都带 version（乐观锁），被他人改过时后端 409，前端提示刷新。
 */
const auth = useAuthStore()
const toast = useToastStore()

const users = ref<UserView[]>([])
const total = ref(0)
const pages = ref(0)
const page = ref(1)
const keyword = ref('')
const statusFilter = ref('')
const roleFilter = ref('')
const loading = ref(true)
const loadError = ref<string | null>(null)

const roles = ref<RoleView[]>([])
const orgFlat = ref<{ id: number; name: string; depth: number }[]>([])

const canManage = computed(() => auth.can('user:write', 'user:manage'))

const statusOptions = [
  { value: '', label: '全部状态' },
  { value: 'ACTIVE', label: '正常' },
  { value: 'DISABLED', label: '已停用' },
  { value: 'LOCKED', label: '已锁定' },
]

const roleOptions = computed(() => [
  { value: '', label: '全部角色' },
  ...roles.value.map((r) => ({ value: r.code, label: r.name })),
])

const orgOptions = computed(() => [
  { value: '', label: '不归属组织' },
  ...orgFlat.value.map((o) => ({ value: String(o.id), label: '　'.repeat(o.depth) + o.name })),
])

function flatten(nodes: OrgTreeNode[], depth = 0, out: { id: number; name: string; depth: number }[] = []) {
  for (const n of nodes) {
    out.push({ id: n.id, name: n.name, depth })
    if (n.children?.length) flatten(n.children, depth + 1, out)
  }
  return out
}

async function loadLookups() {
  try {
    roles.value = await roleApi.roles()
  } catch {
    roles.value = []
  }
  try {
    orgFlat.value = flatten(await orgApi.tree())
  } catch {
    orgFlat.value = []
  }
}

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const res = await userApi.list({
      page: page.value,
      size: 20,
      keyword: keyword.value.trim() || undefined,
      status: statusFilter.value || undefined,
      roleCode: roleFilter.value || undefined,
    })
    users.value = res.items
    total.value = res.total
    pages.value = res.pages
  } catch (e) {
    users.value = []
    loadError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  void load()
}

watch(page, () => void load())

onMounted(async () => {
  await loadLookups()
  await load()
})

/* ---------------- 新建 ---------------- */
const createOpen = ref(false)
const createBusy = ref(false)
const createForm = ref({ username: '', displayName: '', password: '', roleCodes: [] as string[], orgUnitId: '' })
const createError = ref<Record<string, string | null>>({})

function openCreate() {
  createForm.value = { username: '', displayName: '', password: '', roleCodes: ['STUDENT'], orgUnitId: '' }
  createError.value = {}
  createOpen.value = true
}

async function submitCreate() {
  createError.value = {}
  if (!createForm.value.username.trim()) createError.value.username = '请填写登录名（学号/工号）'
  if (!createForm.value.displayName.trim()) createError.value.displayName = '请填写显示名'
  if (createForm.value.password.length < 8) createError.value.password = '初始密码至少 8 位'
  if (createForm.value.roleCodes.length === 0) createError.value.roleCodes = '至少分配一个角色'
  if (Object.keys(createError.value).length) return

  createBusy.value = true
  try {
    await userApi.create({
      username: createForm.value.username.trim(),
      displayName: createForm.value.displayName.trim(),
      password: createForm.value.password,
      orgUnitId: createForm.value.orgUnitId ? Number(createForm.value.orgUnitId) : null,
      roleCodes: createForm.value.roleCodes,
    })
    createOpen.value = false
    toast.push('账号已创建，请把初始密码线下告知本人', 'success')
    await load()
  } catch (e) {
    const apiErr = e instanceof ApiError ? e : null
    createError.value = {
      username: apiErr?.fieldError('username') ?? null,
      displayName: apiErr?.fieldError('displayName') ?? null,
      password: apiErr?.fieldError('password') ?? null,
      general: apiErr?.fieldError('username') ? null : errorMessage(e),
    }
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    createBusy.value = false
  }
}

/* ---------------- 改资料 ---------------- */
const editTarget = ref<UserView | null>(null)
const editBusy = ref(false)
const editForm = ref({ displayName: '', email: '', phone: '', orgUnitId: '' })
const editError = ref<Record<string, string | null>>({})

function openEdit(u: UserView) {
  editTarget.value = u
  editForm.value = {
    displayName: u.displayName,
    email: u.email ?? '',
    phone: u.phone ?? '',
    orgUnitId: u.orgUnitId ? String(u.orgUnitId) : '',
  }
  editError.value = {}
}

async function submitEdit() {
  if (!editTarget.value) return
  editError.value = {}
  if (!editForm.value.displayName.trim()) {
    editError.value.displayName = '显示名不能为空'
    return
  }
  editBusy.value = true
  try {
    await userApi.update(editTarget.value.id, {
      displayName: editForm.value.displayName.trim(),
      email: editForm.value.email.trim() || null,
      phone: editForm.value.phone.trim() || null,
      orgUnitId: editForm.value.orgUnitId ? Number(editForm.value.orgUnitId) : null,
      version: editTarget.value.version,
    })
    editTarget.value = null
    toast.push('资料已更新', 'success')
    await load()
  } catch (e) {
    const apiErr = e instanceof ApiError ? e : null
    editError.value = {
      email: apiErr?.fieldError('email') ?? null,
      phone: apiErr?.fieldError('phone') ?? null,
      general: apiErr?.status === 409 ? '这条记录刚被别人改过，请关闭后重新打开' : errorMessage(e),
    }
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    editBusy.value = false
  }
}

/* ---------------- 分配角色 ---------------- */
const roleTarget = ref<UserView | null>(null)
const roleBusy = ref(false)
const rolePicked = ref<string[]>([])

function openRoles(u: UserView) {
  roleTarget.value = u
  rolePicked.value = [...u.roles]
}

async function submitRoles() {
  if (!roleTarget.value) return
  roleBusy.value = true
  try {
    await userApi.setRoles(roleTarget.value.id, rolePicked.value)
    roleTarget.value = null
    toast.push('角色已更新', 'success')
    await load()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    roleBusy.value = false
  }
}

/* ---------------- 状态 / 解锁 ---------------- */
const statusTarget = ref<{ user: UserView; next: 'ACTIVE' | 'DISABLED' | 'LOCKED' } | null>(null)
const statusReason = ref('')
const statusBusy = ref(false)

const STATUS_ACTION: Record<string, { label: string; hint: string }> = {
  ACTIVE: { label: '启用账号', hint: '启用后立即可登录；若处于临时锁定，计数会清零。' },
  DISABLED: { label: '停用账号', hint: '停用后该账号无法登录，已签发的令牌会失效。' },
  LOCKED: { label: '锁定账号', hint: '锁定需要管理员手动解除，适用于可疑账号。' },
}

function statusAction(next: string) {
  return STATUS_ACTION[next] ?? { label: '修改状态', hint: '' }
}

function openStatus(u: UserView, next: 'ACTIVE' | 'DISABLED' | 'LOCKED') {
  statusTarget.value = { user: u, next }
  statusReason.value = ''
}

async function submitStatus() {
  if (!statusTarget.value) return
  statusBusy.value = true
  try {
    await userApi.setStatus(statusTarget.value.user.id, statusTarget.value.next, statusReason.value.trim() || undefined)
    statusTarget.value = null
    toast.push('账号状态已更新', 'success')
    await load()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    statusBusy.value = false
  }
}

const unlockBusy = ref<number | null>(null)
async function unlock(u: UserView) {
  unlockBusy.value = u.id
  try {
    await userApi.unlock(u.id)
    toast.push('已解除临时锁定', 'success')
    await load()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    unlockBusy.value = null
  }
}

/* ---------------- 重置密码 ---------------- */
const resetTarget = ref<UserView | null>(null)
const resetPwd = ref('')
const resetBusy = ref(false)
const resetError = ref<string | null>(null)

function openReset(u: UserView) {
  resetTarget.value = u
  resetPwd.value = ''
  resetError.value = null
}

async function submitReset() {
  if (!resetTarget.value) return
  if (resetPwd.value.length < 8) {
    resetError.value = '新密码至少 8 位，且需包含三类字符'
    return
  }
  resetBusy.value = true
  try {
    await userApi.resetPassword(resetTarget.value.id, resetPwd.value)
    resetTarget.value = null
    toast.push('密码已重置，该用户全部令牌失效', 'success')
  } catch (e) {
    resetError.value = errorMessage(e)
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    resetBusy.value = false
  }
}

function roleText(u: UserView) {
  return u.roles.map((r) => ROLE_LABEL[r] ?? r).join(' · ') || '未分配'
}
</script>

<template>
  <section class="panel">
    <div class="toolbar">
      <form class="search" role="search" @submit.prevent="search">
        <label class="search__label" for="user-search">检索</label>
        <input
          id="user-search"
          v-model="keyword"
          class="search__input"
          type="search"
          placeholder="姓名、账号、邮箱"
          autocomplete="off"
        />
        <UiButton size="sm" type="submit">查找</UiButton>
      </form>

      <div class="filters">
        <UiSelect v-model="statusFilter" label="状态" :options="statusOptions" @update:model-value="search" />
        <UiSelect v-model="roleFilter" label="角色" :options="roleOptions" @update:model-value="search" />
        <UiButton v-if="canManage" variant="primary" @click="openCreate">新建账号</UiButton>
      </div>
    </div>

    <p v-if="loading" class="state">正在取用户…</p>

    <div v-else-if="loadError" class="state state--err" role="alert">
      <p>{{ loadError }}</p>
      <UiButton size="sm" @click="load">重试</UiButton>
    </div>

    <UiEmpty
      v-else-if="users.length === 0"
      title="没有匹配的账号"
      hint="换个关键词，或清除状态下拉里的筛选。"
    />

    <template v-else>
      <ol class="rows">
        <li v-for="u in users" :key="u.id" class="row">
          <div class="row__who">
            <p class="row__name">
              {{ u.displayName }}
              <span class="row__username mono">{{ u.username }}</span>
            </p>
            <p class="row__meta">
              <span>{{ roleText(u) }}</span>
              <span v-if="u.orgUnitName">{{ u.orgUnitName }}</span>
              <span v-if="u.email">{{ u.email }}</span>
            </p>
          </div>

          <div class="row__state">
            <UiTag :tone="userTone(u.status)">{{ USER_STATUS_LABEL[u.status] ?? u.status }}</UiTag>
            <span class="row__login mono">
              {{ u.lastLoginAt ? `上次登录 ${formatDateTime(u.lastLoginAt)}` : '从未登录' }}
            </span>
          </div>

          <div v-if="canManage" class="row__actions">
            <UiButton size="sm" variant="link" @click="openEdit(u)">编辑</UiButton>
            <UiButton size="sm" variant="link" @click="openRoles(u)">角色</UiButton>
            <UiButton size="sm" variant="link" @click="openReset(u)">重置密码</UiButton>
            <template v-if="u.status === 'ACTIVE'">
              <UiButton size="sm" variant="link" @click="openStatus(u, 'DISABLED')">停用</UiButton>
              <UiButton size="sm" variant="link" @click="openStatus(u, 'LOCKED')">锁定</UiButton>
            </template>
            <template v-else>
              <UiButton size="sm" variant="link" @click="openStatus(u, 'ACTIVE')">启用</UiButton>
              <UiButton v-if="u.lockedUntil" size="sm" variant="link" :loading="unlockBusy === u.id" @click="unlock(u)">
                解除锁定
              </UiButton>
            </template>
          </div>
        </li>
      </ol>

      <UiPager v-if="pages > 1" :page="page" :pages="pages" :total="total" @change="page = $event" />
    </template>

    <!-- 新建 -->
    <UiDialog
      :open="createOpen"
      title="新建账号"
      eyebrow="用户"
      width="md"
      :dismissible="!createBusy"
      @close="createOpen = false"
    >
      <div class="form">
        <UiField v-model="createForm.username" label="登录名" :error="createError.username" hint="学号或工号，创建后不可修改。" />
        <UiField v-model="createForm.displayName" label="显示名" :error="createError.displayName" />
        <UiField
          v-model="createForm.password"
          label="初始密码"
          type="password"
          autocomplete="new-password"
          :error="createError.password"
          hint="至少 8 位，含大小写字母、数字、符号中的三类。"
        />
        <UiSelect v-model="createForm.orgUnitId" label="所属组织" :options="orgOptions" />
        <div class="checks">
          <p class="checks__label">角色</p>
          <label v-for="r in roles" :key="r.code" class="check">
            <input v-model="createForm.roleCodes" type="checkbox" :value="r.code" />
            <span>{{ r.name }}</span>
          </label>
          <p v-if="createError.roleCodes" class="form__err">{{ createError.roleCodes }}</p>
        </div>
        <p v-if="createError.general" class="form__err" role="alert">{{ createError.general }}</p>
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="createBusy" @click="createOpen = false">取消</UiButton>
        <UiButton variant="primary" :loading="createBusy" @click="submitCreate">创建</UiButton>
      </template>
    </UiDialog>

    <!-- 编辑 -->
    <UiDialog
      :open="!!editTarget"
      title="编辑资料"
      :eyebrow="editTarget?.username ?? ''"
      width="md"
      :dismissible="!editBusy"
      @close="editTarget = null"
    >
      <div class="form">
        <UiField v-model="editForm.displayName" label="显示名" :error="editError.displayName" />
        <UiField v-model="editForm.email" label="邮箱" :error="editError.email" />
        <UiField v-model="editForm.phone" label="手机号" :error="editError.phone" hint="11 位手机号，可留空。" />
        <UiSelect v-model="editForm.orgUnitId" label="所属组织" :options="orgOptions" />
        <p v-if="editError.general" class="form__err" role="alert">{{ editError.general }}</p>
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="editBusy" @click="editTarget = null">取消</UiButton>
        <UiButton variant="primary" :loading="editBusy" @click="submitEdit">保存</UiButton>
      </template>
    </UiDialog>

    <!-- 角色 -->
    <UiDialog
      :open="!!roleTarget"
      title="分配角色"
      :eyebrow="roleTarget?.displayName ?? ''"
      width="sm"
      :dismissible="!roleBusy"
      @close="roleTarget = null"
    >
      <div class="checks">
        <label v-for="r in roles" :key="r.code" class="check">
          <input v-model="rolePicked" type="checkbox" :value="r.code" />
          <span>{{ r.name }}</span>
          <span class="check__code mono">{{ r.code }}</span>
        </label>
      </div>
      <p class="hint">清空角色后，该账号只能访问基础接口；权限点由角色决定，不能单独授予。</p>
      <template #footer>
        <UiButton variant="link" :disabled="roleBusy" @click="roleTarget = null">取消</UiButton>
        <UiButton variant="primary" :loading="roleBusy" @click="submitRoles">保存角色</UiButton>
      </template>
    </UiDialog>

    <!-- 状态 -->
    <UiDialog
      :open="!!statusTarget"
      :title="statusTarget ? statusAction(statusTarget.next).label : ''"
      :eyebrow="statusTarget?.user.displayName ?? ''"
      width="sm"
      :dismissible="!statusBusy"
      @close="statusTarget = null"
    >
      <div class="form">
        <p class="hint">{{ statusTarget ? statusAction(statusTarget.next).hint : '' }}</p>
        <UiField v-model="statusReason" label="原因" hint="写入审计日志，建议写清依据。" :maxlength="200" />
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="statusBusy" @click="statusTarget = null">取消</UiButton>
        <UiButton
          :variant="statusTarget?.next === 'ACTIVE' ? 'primary' : 'danger'"
          :loading="statusBusy"
          @click="submitStatus"
        >
          确认
        </UiButton>
      </template>
    </UiDialog>

    <!-- 重置密码 -->
    <UiDialog
      :open="!!resetTarget"
      title="重置密码"
      :eyebrow="resetTarget?.displayName ?? ''"
      width="sm"
      :dismissible="!resetBusy"
      @close="resetTarget = null"
    >
      <div class="form">
        <p class="hint">重置后该用户全部令牌立即失效，需要用新密码重新登录。请通过可靠渠道告知本人。</p>
        <UiField
          v-model="resetPwd"
          label="新密码"
          type="password"
          autocomplete="new-password"
          :error="resetError"
          hint="至少 8 位，含大小写字母、数字、符号中的三类。"
        />
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="resetBusy" @click="resetTarget = null">取消</UiButton>
        <UiButton variant="danger" :loading="resetBusy" @click="submitReset">重置</UiButton>
      </template>
    </UiDialog>
  </section>
</template>

<style scoped>
.panel {
  min-height: 240px;
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--s-4);
  padding-bottom: var(--s-3);
  margin-bottom: var(--s-3);
  border-bottom: var(--hairline);
}

.search {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex: 1 1 320px;
}

.search__label {
  font-size: var(--fs-small);
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--ink-2);
}

.search__input {
  flex: 1;
  min-width: 0;
  height: 32px;
  padding: 0 2px;
  border: 0;
  background: transparent;
  font-size: 15px;
}

.search__input:focus {
  outline: none;
  box-shadow: 0 1px 0 0 var(--teal);
}

.filters {
  display: flex;
  align-items: flex-end;
  gap: var(--s-3);
}

.filters :deep(.ui-select) {
  min-width: 120px;
}

.rows {
  margin: 0;
  padding: 0;
  list-style: none;
}

.row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 220px auto;
  align-items: center;
  gap: var(--s-4);
  padding: var(--s-3) 0;
  border-bottom: var(--hairline);
}

.row:hover {
  background: color-mix(in srgb, var(--paper-lift) 60%, transparent);
}

.row__name {
  display: flex;
  align-items: baseline;
  gap: var(--s-2);
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-size: 16px;
  font-weight: 600;
}

.row__username {
  color: var(--ink-2);
  font-size: 11px;
  font-weight: 400;
}

.row__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-3);
  margin: 2px 0 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.row__state {
  display: grid;
  justify-items: start;
  gap: var(--s-1);
}

.row__login {
  color: var(--ink-2);
  font-size: 11px;
}

.row__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: var(--s-3);
}

.form {
  display: grid;
  gap: var(--s-4);
}

.checks {
  display: grid;
  gap: var(--s-2);
}

.checks__label {
  margin: 0;
  font-size: var(--fs-small);
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--ink-2);
}

.check {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  min-height: 32px;
  cursor: pointer;
}

.check input {
  width: 16px;
  height: 16px;
  accent-color: var(--teal);
}

.check__code {
  color: var(--ink-2);
  font-size: 11px;
}

.hint {
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.7;
}

.form__err {
  margin: 0;
  color: var(--ochre);
  font-size: var(--fs-small);
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

@media (max-width: 900px) {
  .row {
    grid-template-columns: minmax(0, 1fr);
    gap: var(--s-2);
  }

  .row__actions {
    justify-content: flex-start;
  }
}
</style>
