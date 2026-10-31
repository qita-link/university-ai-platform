<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { forumApi, profileApi } from '@/api/endpoints'
import type { ProfilePostView, ProfileView } from '@/api/types'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useAvatarStore } from '@/stores/avatar'
import { useToastStore } from '@/stores/toast'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { formatDateTime } from '@/lib/time'
import { ROLE_LABEL } from '@/lib/labels'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiEmpty from '@/components/ui/UiEmpty.vue'
import UiField from '@/components/ui/UiField.vue'
import UiPageHeader from '@/components/ui/UiPageHeader.vue'
import UiPager from '@/components/ui/UiPager.vue'
import UiTag from '@/components/ui/UiTag.vue'
import UiTextarea from '@/components/ui/UiTextarea.vue'

/**
 * 个人资料：上方是身份卡，下面用横向页签切换「基本信息 / 修改密码 / 我发过的帖子」，
 * 与其他模块的页签一致。发过的帖子可以自己编辑、删除，编辑过会显示「谁（角色）修改于何时」。
 */
const router = useRouter()
const auth = useAuthStore()
const avatar = useAvatarStore()
const toast = useToastStore()

type TabKey = 'info' | 'password' | 'posts'
const tab = ref<TabKey>('info')

const profile = ref<ProfileView | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)

const selfAvatar = computed(() => avatar.urlFor(profile.value?.userId, profile.value?.avatarVersion))

const roleText = computed(() => {
  const roles = profile.value?.roles ?? []
  return roles.map((r) => ROLE_LABEL[r] ?? r).join(' · ') || '未分配'
})

watch(
  () => [profile.value?.userId, profile.value?.avatarVersion] as const,
  () => void avatar.ensure(profile.value?.userId, profile.value?.avatarVersion),
)

async function loadProfile() {
  loading.value = true
  error.value = null
  try {
    profile.value = await profileApi.me()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

/* ---------------- 头像 ---------------- */
const fileInput = ref<HTMLInputElement | null>(null)
const avatarBusy = ref(false)
const MAX_AVATAR = 1.5 * 1024 * 1024

function pickAvatar() {
  fileInput.value?.click()
}

async function onAvatarPicked(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!/^image\/(png|jpeg|webp)$/.test(file.type)) {
    toast.push('只支持 PNG、JPEG、WebP 图片', 'danger')
    return
  }
  if (file.size > MAX_AVATAR) {
    toast.push('头像不能超过 1.5MB', 'danger')
    return
  }
  avatarBusy.value = true
  try {
    profile.value = await profileApi.uploadAvatar(file)
    await auth.refreshMe()
    toast.push('头像已更新', 'success')
  } catch (err) {
    toast.push(errorMessage(err), 'danger', traceIdOf(err))
  } finally {
    avatarBusy.value = false
  }
}

async function removeAvatar() {
  avatarBusy.value = true
  try {
    profile.value = await profileApi.removeAvatar()
    await auth.refreshMe()
    toast.push('已恢复为姓名首字头像', 'success')
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    avatarBusy.value = false
  }
}

/* ---------------- 修改密码 ---------------- */
const pwdOld = ref('')
const pwdNew = ref('')
const pwdAgain = ref('')
const pwdBusy = ref(false)
const pwdError = ref<{ old?: string | null; fresh?: string | null; again?: string | null }>({})

async function submitPassword() {
  pwdError.value = {}
  if (!pwdOld.value) {
    pwdError.value = { old: '请输入原密码' }
    return
  }
  if (pwdNew.value.length < 8) {
    pwdError.value = { fresh: '新密码至少 8 位' }
    return
  }
  if (pwdNew.value === pwdOld.value) {
    pwdError.value = { fresh: '新密码不能与原密码相同' }
    return
  }
  if (pwdNew.value !== pwdAgain.value) {
    pwdError.value = { again: '两次输入的新密码不一致' }
    return
  }
  pwdBusy.value = true
  try {
    await auth.changePassword(pwdOld.value, pwdNew.value)
    pwdOld.value = ''
    pwdNew.value = ''
    pwdAgain.value = ''
    toast.push('密码已更新，其他设备上的登录已失效', 'success')
  } catch (e) {
    const apiErr = e instanceof ApiError ? e : null
    pwdError.value = {
      old: apiErr?.fieldError('oldPassword') ?? null,
      fresh: apiErr?.fieldError('newPassword') ?? null,
    }
    if (!apiErr?.fieldError('oldPassword') && !apiErr?.fieldError('newPassword')) {
      pwdError.value = { old: errorMessage(e) }
    }
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    pwdBusy.value = false
  }
}

/* ---------------- 我发过的帖子 ---------------- */
const posts = ref<ProfilePostView[]>([])
const postsLoading = ref(false)
const postTotal = ref(0)
const postPages = ref(0)
const postPage = ref(1)
const deleting = ref<ProfilePostView | null>(null)
const deleteBusy = ref(false)

const STATUS_LABEL: Record<string, string> = { PUBLISHED: '公开', HIDDEN: '已隐藏', DELETED: '已删除' }

async function loadPosts() {
  postsLoading.value = true
  try {
    const res = await profileApi.posts({ page: postPage.value, size: 10 })
    posts.value = res.items
    postTotal.value = res.total
    postPages.value = res.pages
  } catch (e) {
    posts.value = []
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    postsLoading.value = false
  }
}

function editorLine(p: ProfilePostView): string {
  if (!p.editedAt) return ''
  const role = p.editorRole ? (ROLE_LABEL[p.editorRole] ?? p.editorRole) : ''
  const who = p.editorName ?? '管理员'
  return `${who}${role ? `（${role}）` : ''}修改于 ${formatDateTime(p.editedAt)}`
}

async function confirmDelete() {
  if (!deleting.value) return
  deleteBusy.value = true
  try {
    await forumApi.deletePost(deleting.value.id)
    toast.push('帖子已删除', 'success')
    deleting.value = null
    await loadPosts()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    deleteBusy.value = false
  }
}

function changePage(p: number) {
  postPage.value = p
  void loadPosts()
}

/* ---------------- 编辑自己的帖子 ---------------- */
const editing = ref<ProfilePostView | null>(null)
const editBusy = ref(false)
const editLoading = ref(false)
const editError = ref<string | null>(null)
const editForm = ref({ title: '', content: '', categoryId: 0, version: 0 })

async function openEdit(p: ProfilePostView) {
  editing.value = p
  editLoading.value = true
  editError.value = null
  editForm.value = { title: '', content: '', categoryId: 0, version: 0 }
  try {
    const detail = await forumApi.post(p.id)
    editForm.value = {
      title: detail.title,
      content: detail.content,
      categoryId: detail.categoryId,
      version: detail.version,
    }
  } catch (e) {
    editError.value = errorMessage(e)
  } finally {
    editLoading.value = false
  }
}

async function submitEdit() {
  if (!editing.value) return
  if (!editForm.value.title.trim()) {
    editError.value = '标题不能为空'
    return
  }
  if (!editForm.value.content.trim()) {
    editError.value = '正文不能为空'
    return
  }
  editBusy.value = true
  editError.value = null
  try {
    await forumApi.updatePost(editing.value.id, {
      categoryId: editForm.value.categoryId,
      title: editForm.value.title.trim(),
      content: editForm.value.content,
      version: editForm.value.version,
    })
    toast.push('帖子已更新，列表会显示修改时间', 'success')
    editing.value = null
    await loadPosts()
  } catch (e) {
    editError.value = errorMessage(e)
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    editBusy.value = false
  }
}

/* ---------------- 退出 ---------------- */
const loggingOut = ref(false)

async function logout() {
  loggingOut.value = true
  await auth.logout()
  avatar.clear()
  loggingOut.value = false
  await router.replace({ name: 'login' })
}

onMounted(async () => {
  await Promise.all([loadProfile(), loadPosts()])
})
</script>

<template>
  <div class="profile">
    <UiPageHeader label="个人资料" latin="Profile" title="我的资料" compact>
      <template #actions>
        <UiButton size="sm" :loading="loggingOut" @click="logout">退出登录</UiButton>
      </template>
    </UiPageHeader>

    <p v-if="loading" class="state">正在取资料…</p>
    <div v-else-if="error" class="state state--err" role="alert">
      <p>{{ error }}</p>
      <UiButton size="sm" @click="loadProfile">重试</UiButton>
    </div>

    <template v-else-if="profile">
      <!-- 身份 -->
      <section class="ident">
        <button type="button" class="ident__avatar" aria-label="更换头像" @click="pickAvatar">
          <img v-if="selfAvatar" :src="selfAvatar" alt="我的头像" />
          <span v-else aria-hidden="true">{{ profile.displayName.slice(-1) }}</span>
        </button>
        <div class="ident__main">
          <h1 class="ident__name">{{ profile.displayName }}</h1>
          <p class="ident__meta">
            <span class="mono">{{ profile.username }}</span>
            <template v-if="profile.orgUnitName">
              <span class="ident__dot" aria-hidden="true">·</span>
              <span>{{ profile.orgUnitName }}</span>
            </template>
            <template v-if="profile.lastLoginAt">
              <span class="mono ident__last">最近登录 {{ formatDateTime(profile.lastLoginAt) }}</span>
            </template>
          </p>
          <p class="ident__tags">
            <UiTag v-for="r in profile.roles" :key="r" tone="teal">{{ ROLE_LABEL[r] ?? r }}</UiTag>
            <UiTag v-if="profile.roles.length === 0" tone="muted">未分配角色</UiTag>
          </p>
        </div>
      </section>

      <!-- 页签 -->
      <div class="bar">
        <div class="tabs" role="tablist" aria-label="资料分区">
          <button type="button" class="tabs__btn" :class="{ 'is-active': tab === 'info' }" @click="tab = 'info'">
            基本信息
          </button>
          <button
            type="button"
            class="tabs__btn"
            :class="{ 'is-active': tab === 'password' }"
            @click="tab = 'password'"
          >
            修改密码
          </button>
          <button type="button" class="tabs__btn" :class="{ 'is-active': tab === 'posts' }" @click="tab = 'posts'">
            我发过的帖子<span class="tabs__count mono">{{ postTotal }}</span>
          </button>
        </div>
      </div>

      <!-- 基本信息 -->
      <section v-if="tab === 'info'" class="pane">
        <div class="cols">
          <div class="block">
            <dl class="facts">
              <div class="facts__row">
                <dt>学号 / 工号</dt>
                <dd class="mono">{{ profile.username }}</dd>
              </div>
              <div class="facts__row">
                <dt>姓名</dt>
                <dd>{{ profile.displayName }}</dd>
              </div>
              <div class="facts__row">
                <dt>学院 / 班级</dt>
                <dd>{{ profile.orgUnitName ?? '未归属' }}</dd>
              </div>
              <div class="facts__row">
                <dt>角色</dt>
                <dd>{{ roleText }}</dd>
              </div>
              <div class="facts__row">
                <dt>邮箱</dt>
                <dd>{{ profile.email ?? '—' }}</dd>
              </div>
              <div class="facts__row">
                <dt>手机</dt>
                <dd>{{ profile.phone ?? '—' }}</dd>
              </div>
              <div class="facts__row">
                <dt>账号创建</dt>
                <dd class="mono">{{ profile.createdAt ? formatDateTime(profile.createdAt) : '—' }}</dd>
              </div>
            </dl>
            <p class="block__note">学号、姓名与班级由学校统一维护，有误请联系管理员修改。</p>
          </div>

          <div class="block">
            <h2 class="block__title">头像</h2>
            <div class="avatar-box">
              <div class="avatar-box__preview">
                <img v-if="selfAvatar" :src="selfAvatar" alt="当前头像" />
                <span v-else aria-hidden="true">{{ profile.displayName.slice(-1) }}</span>
              </div>
              <div class="avatar-box__side">
                <p class="block__note">支持 PNG、JPEG、WebP，不超过 1.5MB。没有头像时显示姓名末字。</p>
                <div class="avatar-box__btns">
                  <UiButton size="sm" variant="primary" :loading="avatarBusy" @click="pickAvatar">选择图片</UiButton>
                  <UiButton
                    v-if="profile.avatarVersion > 0"
                    size="sm"
                    variant="link"
                    :disabled="avatarBusy"
                    @click="removeAvatar"
                  >
                    移除头像
                  </UiButton>
                </div>
                <input
                  ref="fileInput"
                  class="visually-hidden"
                  type="file"
                  accept="image/png,image/jpeg,image/webp"
                  @change="onAvatarPicked"
                />
              </div>
            </div>
          </div>
        </div>
      </section>

      <!-- 修改密码 -->
      <section v-else-if="tab === 'password'" class="pane">
        <form class="pwd" @submit.prevent="submitPassword">
          <UiField
            v-model="pwdOld"
            label="原密码"
            type="password"
            autocomplete="current-password"
            :error="pwdError.old"
            :disabled="pwdBusy"
            required
          />
          <UiField
            v-model="pwdNew"
            label="新密码"
            type="password"
            autocomplete="new-password"
            :error="pwdError.fresh"
            :disabled="pwdBusy"
            hint="至少 8 位，包含大写字母、小写字母、数字、符号中的三类，不能包含用户名。"
            required
          />
          <UiField
            v-model="pwdAgain"
            label="再输入一次新密码"
            type="password"
            autocomplete="new-password"
            :error="pwdError.again"
            :disabled="pwdBusy"
            required
          />
          <div class="pwd__actions">
            <p class="block__note">修改成功后你在其他设备上的登录会立即失效。</p>
            <UiButton variant="primary" type="submit" :loading="pwdBusy">保存新密码</UiButton>
          </div>
        </form>
      </section>

      <!-- 我发过的帖子 -->
      <section v-else class="pane">
        <p v-if="postsLoading" class="state">正在取帖子…</p>
        <UiEmpty
          v-else-if="posts.length === 0"
          title="还没有发过帖子"
          hint="论坛里的提问与经验都会记在这里，方便回看与修改。"
        >
          <RouterLink to="/forum"><UiButton size="sm" variant="primary">去论坛</UiButton></RouterLink>
        </UiEmpty>

        <ul v-else class="plist">
          <li v-for="p in posts" :key="p.id" class="pitem">
            <div class="pitem__main">
              <p class="pitem__tags">
                <UiTag v-if="p.pinned" tone="ochre">置顶</UiTag>
                <UiTag :tone="p.status === 'HIDDEN' ? 'ochre' : 'neutral'">{{ STATUS_LABEL[p.status] }}</UiTag>
                <span class="pitem__cat">{{ p.categoryName ?? '未分类' }}</span>
              </p>
              <RouterLink class="pitem__title" :to="`/forum/posts/${p.id}`">{{ p.title }}</RouterLink>
              <p class="pitem__meta mono">
                发布于 {{ formatDateTime(p.createdAt) }} · {{ p.commentCount }} 回复
              </p>
              <p v-if="p.editedAt" class="pitem__edited">〔{{ editorLine(p) }}〕</p>
            </div>
            <div class="pitem__actions">
              <UiButton size="sm" variant="link" @click="openEdit(p)">编辑</UiButton>
              <RouterLink :to="`/forum/posts/${p.id}`"><UiButton size="sm" variant="link">查看</UiButton></RouterLink>
              <UiButton size="sm" variant="link" @click="deleting = p">删除</UiButton>
            </div>
          </li>
        </ul>

        <UiPager
          v-if="postPages > 1"
          :page="postPage"
          :pages="postPages"
          :total="postTotal"
          :disabled="postsLoading"
          @change="changePage"
        />
      </section>
    </template>

    <!-- 编辑帖子 -->
    <UiDialog
      :open="!!editing"
      title="编辑帖子"
      eyebrow="论坛"
      width="lg"
      :dismissible="!editBusy"
      @close="editing = null"
    >
      <p v-if="editLoading" class="state">正在打开…</p>
      <div v-else class="edit">
        <UiField v-model="editForm.title" label="标题" :maxlength="120" required />
        <UiTextarea v-model="editForm.content" label="正文" :rows="12" hint="支持 Markdown。" />
        <p v-if="editError" class="edit__err" role="alert">{{ editError }}</p>
        <p class="block__note">保存后会记一次修改时间，在列表里显示「谁（角色）修改于何时」。</p>
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="editBusy" @click="editing = null">取消</UiButton>
        <UiButton variant="primary" :loading="editBusy" :disabled="editLoading" @click="submitEdit">保存</UiButton>
      </template>
    </UiDialog>

    <UiDialog
      :open="!!deleting"
      title="删除这封帖子？"
      eyebrow="不可撤销"
      width="sm"
      :dismissible="!deleteBusy"
      @close="deleting = null"
    >
      <p class="del-text">「{{ deleting?.title }}」删除后普通用户不可见，回复也会一并隐藏，版主仍可追溯。</p>
      <template #footer>
        <UiButton variant="link" :disabled="deleteBusy" @click="deleting = null">取消</UiButton>
        <UiButton variant="danger" :loading="deleteBusy" @click="confirmDelete">确认删除</UiButton>
      </template>
    </UiDialog>
  </div>
</template>

<style scoped>
.profile {
  width: 100%;
  max-width: 1040px;
  margin: 0 auto;
  padding: var(--s-5) clamp(var(--s-4), 4vw, var(--s-6)) var(--s-7);
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

/* ---------- 身份 ---------- */
.ident {
  display: flex;
  align-items: center;
  gap: var(--s-4);
  padding: var(--s-4) 0;
}

.ident__avatar {
  display: grid;
  place-items: center;
  width: 68px;
  height: 68px;
  padding: 0;
  overflow: hidden;
  border: 0;
  border-radius: 50%;
  background: var(--ink);
  color: var(--paper);
  font-family: var(--font-serif-cjk);
  font-size: 26px;
  cursor: pointer;
}

.ident__avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.ident__name {
  margin: 0 0 4px;
  font-family: var(--font-serif-cjk);
  font-size: 24px;
  line-height: 1.2;
}

.ident__meta {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
  flex-wrap: wrap;
}

.ident__dot {
  color: var(--rule-strong);
}

.ident__last {
  color: var(--ink-2);
}

.ident__last {
  color: var(--ink-2);
}

.ident__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: var(--s-2) 0 0;
}

/* ---------- 页签 ---------- */
.bar {
  display: flex;
  padding-bottom: var(--s-2);
  border-bottom: 1px solid var(--ink);
}

.tabs {
  display: flex;
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

.tabs__count {
  margin-left: 4px;
  color: var(--ochre);
  font-size: 11px;
}

.pane {
  padding-top: var(--s-4);
}

/* ---------- 内容 ---------- */
.cols {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 320px);
  gap: clamp(var(--s-4), 3vw, var(--s-5));
}

.block {
  min-width: 0;
}

.block__title {
  margin: 0 0 var(--s-3);
  font-family: var(--font-serif-cjk);
  font-size: 18px;
  font-weight: 600;
}

.block__note {
  margin: var(--s-2) 0 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.7;
}

.facts {
  display: grid;
  gap: 1px;
  margin: 0;
  background: var(--rule);
  border: 1px solid var(--rule);
}

.facts__row {
  display: grid;
  grid-template-columns: 100px minmax(0, 1fr);
  gap: var(--s-3);
  padding: 9px var(--s-3);
  background: var(--paper);
}

.facts dt {
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.facts dd {
  margin: 0;
  font-size: var(--fs-ui);
  overflow-wrap: anywhere;
}

/* ---------- 头像 ---------- */
.avatar-box {
  display: flex;
  gap: var(--s-3);
  align-items: flex-start;
}

.avatar-box__preview {
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  width: 76px;
  height: 76px;
  overflow: hidden;
  border-radius: 50%;
  background: var(--ink);
  color: var(--paper);
  font-family: var(--font-serif-cjk);
  font-size: 28px;
}

.avatar-box__preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-box__btns {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2);
  margin-top: var(--s-2);
}

/* ---------- 密码 ---------- */
.pwd {
  display: grid;
  gap: var(--s-3);
  max-width: 520px;
}

.pwd__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  flex-wrap: wrap;
}

/* ---------- 帖子 ---------- */
.plist {
  margin: 0;
  padding: 0;
  list-style: none;
  border-top: var(--hairline);
}

.pitem {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--s-3);
  padding: var(--s-3) 0;
  border-bottom: var(--hairline);
}

.pitem__tags {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 0 2px;
}

.pitem__cat {
  color: var(--ink-2);
  font-size: 10px;
  letter-spacing: 0.1em;
}

.pitem__title {
  color: var(--ink);
  font-family: var(--font-serif-cjk);
  font-size: 17px;
  line-height: 1.4;
  text-decoration: none;
}

.pitem__title:hover {
  color: var(--teal);
}

.pitem__meta {
  margin: 3px 0 0;
  color: var(--ink-2);
  font-size: 11px;
}

.pitem__edited {
  margin: 2px 0 0;
  color: var(--ochre);
  font-size: 11px;
}

.pitem__actions {
  display: flex;
  align-items: center;
  gap: var(--s-1);
}

.edit {
  display: grid;
  gap: var(--s-3);
}

.edit__err {
  margin: 0;
  color: var(--ochre);
  font-size: var(--fs-small);
}

.del-text {
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.75;
}

@media (max-width: 900px) {
  .profile {
    padding: var(--s-4) 16px var(--s-6);
  }

  .ident {
    gap: 12px;
    padding-top: 0;
  }

  .ident__avatar {
    width: 54px;
    height: 54px;
    font-size: 20px;
  }

  .ident__name {
    font-size: 21px;
  }

  .ident__last {
    width: 100%;
  }

  .ident__last {
    width: 100%;
  }

  .cols {
    grid-template-columns: minmax(0, 1fr);
    gap: var(--s-4);
  }

  .facts__row {
    grid-template-columns: 84px minmax(0, 1fr);
    padding: 8px 10px;
  }

  .pwd {
    max-width: none;
  }

  .pwd__actions {
    flex-direction: column;
    align-items: stretch;
  }

  .pitem {
    grid-template-columns: minmax(0, 1fr);
    gap: 6px;
  }

  .pitem__actions {
    justify-content: flex-end;
  }
}
</style>
