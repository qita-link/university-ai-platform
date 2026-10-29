<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { forumApi } from '@/api/endpoints'
import type { ForumComment, ForumCommentThread, ForumPostDetail } from '@/api/types'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { renderMarkdown } from '@/lib/markdown'
import { formatDateTime } from '@/lib/time'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiEmpty from '@/components/ui/UiEmpty.vue'
import UiField from '@/components/ui/UiField.vue'
import UiPager from '@/components/ui/UiPager.vue'
import UiTag from '@/components/ui/UiTag.vue'
import UiTextarea from '@/components/ui/UiTextarea.vue'

/**
 * 帖子详情：正文 + 两级评论。
 * 权限：作者可改删自己的内容，版主（forum:moderate）可管理全部。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const toast = useToastStore()

const postId = computed(() => Number(route.params.id))
const post = ref<ForumPostDetail | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)

const threads = ref<ForumCommentThread[]>([])
const total = ref(0)
const pages = ref(0)
const page = ref(1)
const commentsLoading = ref(false)

const me = computed(() => auth.user?.userId ?? null)
const isAuthor = computed(() => !!post.value && post.value.authorId === me.value)
const canModerate = computed(() => auth.can('forum:moderate'))
const canWrite = computed(() => auth.can('forum:post:write'))
const canComment = computed(() => auth.can('forum:comment:write'))

const html = computed(() => renderMarkdown(post.value?.content))

async function loadPost(id: number) {
  loading.value = true
  error.value = null
  try {
    post.value = await forumApi.post(id)
  } catch (e) {
    post.value = null
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

async function loadComments(id: number) {
  commentsLoading.value = true
  try {
    const res = await forumApi.comments(id, page.value, 20)
    threads.value = res.items
    total.value = res.total
    pages.value = res.pages
  } catch (e) {
    threads.value = []
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    commentsLoading.value = false
  }
}

watch(
  () => Number(route.params.id),
  (id) => {
    if (!Number.isInteger(id) || id <= 0) return
    page.value = 1
    void loadPost(id)
    void loadComments(id)
  },
)

watch(page, () => {
  if (post.value) void loadComments(post.value.id)
})

onMounted(() => {
  const id = postId.value
  if (Number.isInteger(id) && id > 0) {
    void loadPost(id)
    void loadComments(id)
  }
})

/* ---------------- 回复 ---------------- */
const replyTo = ref<ForumComment | null>(null)
const replyText = ref('')
const replyBusy = ref(false)
/** 行内回复框（同一时刻只会渲染一个）。用回调 ref：v-for 里的普通 ref 会变成数组。 */
const replyBox = ref<InstanceType<typeof UiTextarea> | null>(null)

function setReplyBox(el: unknown) {
  replyBox.value = (el as InstanceType<typeof UiTextarea> | null) ?? null
}

async function startReply(c: ForumComment | null) {
  replyTo.value = c
  replyText.value = ''
  if (c) {
    await nextTick()
    replyBox.value?.focus()
  }
}

async function submitReply() {
  if (!post.value) return
  if (!replyText.value.trim()) {
    toast.push('回复内容不能为空', 'danger')
    return
  }
  replyBusy.value = true
  try {
    await forumApi.addComment(post.value.id, replyText.value.trim(), replyTo.value?.id ?? null)
    replyText.value = ''
    replyTo.value = null
    page.value = 1
    await loadComments(post.value.id)
    post.value = await forumApi.post(post.value.id)
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    replyBusy.value = false
  }
}

const deletingComment = ref<ForumComment | null>(null)
const deleteCommentBusy = ref(false)

async function confirmDeleteComment() {
  if (!deletingComment.value || !post.value) return
  deleteCommentBusy.value = true
  try {
    await forumApi.deleteComment(deletingComment.value.id)
    deletingComment.value = null
    toast.push('评论已删除', 'success')
    await loadComments(post.value.id)
    post.value = await forumApi.post(post.value.id)
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    deleteCommentBusy.value = false
  }
}

function canDeleteComment(c: ForumComment): boolean {
  return canModerate.value || c.authorId === me.value
}

/* ---------------- 帖子操作 ---------------- */
const editOpen = ref(false)
const editBusy = ref(false)
const draft = ref({ title: '', content: '' })
const draftError = ref<{ title?: string | null; content?: string | null; general?: string | null }>({})

function openEdit() {
  if (!post.value) return
  draft.value = { title: post.value.title, content: post.value.content }
  draftError.value = {}
  editOpen.value = true
}

async function submitEdit() {
  if (!post.value) return
  draftError.value = {}
  if (!draft.value.title.trim()) {
    draftError.value = { title: '请填写标题' }
    return
  }
  if (!draft.value.content.trim()) {
    draftError.value = { content: '正文不能为空' }
    return
  }
  editBusy.value = true
  try {
    post.value = await forumApi.updatePost(post.value.id, {
      categoryId: post.value.categoryId,
      title: draft.value.title.trim(),
      content: draft.value.content,
      version: post.value.version,
    })
    editOpen.value = false
    toast.push('已保存', 'success')
  } catch (e) {
    draftError.value = {
      title: e instanceof ApiError ? e.fieldError('title') : null,
      content: e instanceof ApiError ? e.fieldError('content') : null,
      general:
        e instanceof ApiError && e.status === 409 ? '这封帖子刚被修改过，请刷新后再编辑' : errorMessage(e),
    }
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    editBusy.value = false
  }
}

const deleteOpen = ref(false)
const deleteBusy = ref(false)

async function confirmDeletePost() {
  if (!post.value) return
  deleteBusy.value = true
  try {
    await forumApi.deletePost(post.value.id)
    deleteOpen.value = false
    toast.push('帖子已删除', 'success')
    await router.push({ name: 'forum' })
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    deleteBusy.value = false
  }
}

const moderating = ref(false)

async function togglePin() {
  if (!post.value) return
  moderating.value = true
  try {
    post.value = await forumApi.setPostStatus(post.value.id, { pinned: !post.value.pinned })
    toast.push(post.value.pinned ? '已置顶' : '已取消置顶', 'success')
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    moderating.value = false
  }
}

async function toggleHide() {
  if (!post.value) return
  moderating.value = true
  try {
    const next = post.value.status === 'HIDDEN' ? 'PUBLISHED' : 'HIDDEN'
    post.value = await forumApi.setPostStatus(post.value.id, { status: next })
    toast.push(next === 'HIDDEN' ? '已隐藏，普通用户不可见' : '已恢复', 'success')
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    moderating.value = false
  }
}
</script>

<template>
  <div class="post-page">
    <p class="crumbs">
      <RouterLink :to="{ name: 'forum' }">来信</RouterLink>
      <span aria-hidden="true">/</span>
      <span>{{ post?.categoryName ?? '帖子' }}</span>
    </p>

    <p v-if="loading" class="state">正在展开这封信…</p>

    <div v-else-if="error" class="state state--err" role="alert">
      <p>{{ error }}</p>
      <RouterLink :to="{ name: 'forum' }" class="state__link">回到列表</RouterLink>
    </div>

    <template v-else-if="post">
      <article class="letter">
        <header class="letter__head">
          <p class="letter__tags">
            <UiTag v-if="post.pinned" tone="ochre">置顶</UiTag>
            <span class="letter__cat">{{ post.categoryName }}</span>
            <UiTag v-if="post.status === 'HIDDEN'" tone="ochre">已隐藏</UiTag>
          </p>
          <h1 class="letter__title">{{ post.title }}</h1>
          <p class="letter__meta">
            <span>{{ post.authorName }}</span>
            <span class="mono">{{ formatDateTime(post.createdAt) }}</span>
            <span class="mono">{{ post.commentCount }} 回复</span>
          </p>

          <div v-if="isAuthor || canModerate" class="letter__actions">
            <UiButton v-if="isAuthor && canWrite" size="sm" @click="openEdit">编辑</UiButton>
            <template v-if="canModerate">
              <UiButton size="sm" :loading="moderating" @click="togglePin">
                {{ post.pinned ? '取消置顶' : '置顶' }}
              </UiButton>
              <UiButton size="sm" :loading="moderating" @click="toggleHide">
                {{ post.status === 'HIDDEN' ? '恢复显示' : '隐藏' }}
              </UiButton>
            </template>
            <UiButton size="sm" variant="danger" @click="deleteOpen = true">删除</UiButton>
          </div>
        </header>

        <div class="prose letter__body" v-html="html" />
      </article>

      <section class="comments" aria-label="回复">
        <div class="comments__head">
          <p class="eyebrow">回复</p>
          <p class="comments__count mono">{{ total }}</p>
        </div>

        <p v-if="commentsLoading" class="state">正在取回复…</p>

        <UiEmpty
          v-else-if="threads.length === 0"
          title="还没有回复"
          :hint="canComment ? '写下你的看法，或补充一条有用的信息。' : '当前账号没有回复权限。'"
        />

        <ol v-else class="threads">
          <li v-for="t in threads" :key="t.comment.id" class="thread">
            <div class="comment">
              <p class="comment__head">
                <span class="comment__author">{{ t.comment.authorName }}</span>
                <span class="comment__time mono">{{ formatDateTime(t.comment.createdAt) }}</span>
              </p>
              <p class="comment__body">{{ t.comment.content }}</p>
              <p class="comment__tools">
                <button v-if="canComment" type="button" class="link-btn" @click="startReply(t.comment)">回复</button>
                <button
                  v-if="canDeleteComment(t.comment)"
                  type="button"
                  class="link-btn link-btn--danger"
                  @click="deletingComment = t.comment"
                >
                  删除
                </button>
              </p>

              <div v-if="replyTo?.id === t.comment.id" class="reply-box">
                <UiTextarea
                  :ref="setReplyBox"
                  v-model="replyText"
                  :label="`回复 ${t.comment.authorName}`"
                  :rows="3"
                  :maxlength="2000"
                  placeholder="回应这一条…"
                />
                <div class="reply-box__actions">
                  <UiButton size="sm" variant="link" @click="startReply(null)">取消</UiButton>
                  <UiButton size="sm" variant="primary" :loading="replyBusy" @click="submitReply">发送</UiButton>
                </div>
              </div>
            </div>

            <ol v-if="t.replies.length" class="replies">
              <li v-for="r in t.replies" :key="r.id" class="comment comment--reply">
                <p class="comment__head">
                  <span class="comment__author">{{ r.authorName }}</span>
                  <span v-if="r.replyToUserName" class="comment__to">回复 {{ r.replyToUserName }}</span>
                  <span class="comment__time mono">{{ formatDateTime(r.createdAt) }}</span>
                </p>
                <p class="comment__body">{{ r.content }}</p>
                <p class="comment__tools">
                  <button v-if="canComment" type="button" class="link-btn" @click="startReply(r)">回复</button>
                  <button
                    v-if="canDeleteComment(r)"
                    type="button"
                    class="link-btn link-btn--danger"
                    @click="deletingComment = r"
                  >
                    删除
                  </button>
                </p>

                <div v-if="replyTo?.id === r.id" class="reply-box">
                  <UiTextarea
                    :ref="setReplyBox"
                    v-model="replyText"
                    :label="`回复 ${r.authorName}`"
                    :rows="3"
                    :maxlength="2000"
                    placeholder="回应这一条…"
                  />
                  <div class="reply-box__actions">
                    <UiButton size="sm" variant="link" @click="startReply(null)">取消</UiButton>
                    <UiButton size="sm" variant="primary" :loading="replyBusy" @click="submitReply">发送</UiButton>
                  </div>
                </div>
              </li>
            </ol>
          </li>
        </ol>

        <UiPager v-if="pages > 1" :page="page" :pages="pages" :total="total" @change="page = $event" />

        <!-- 新回复 -->
        <div v-if="canComment && post.status === 'PUBLISHED'" class="new-reply">
          <UiTextarea
            v-model="replyText"
            label="写下回复"
            :rows="4"
            :maxlength="2000"
            hint="对事不对人。涉及个人信息的内容请私聊或联系老师。"
            placeholder="补充信息、追问或回答……"
          />
          <div class="new-reply__actions">
            <UiButton variant="primary" :loading="replyBusy" @click="submitReply">发送回复</UiButton>
          </div>
        </div>
        <p v-else-if="post.status !== 'PUBLISHED'" class="closed">这封帖子已隐藏，暂停回复。</p>
      </section>
    </template>

    <!-- 编辑 -->
    <UiDialog
      :open="editOpen"
      title="编辑帖子"
      eyebrow="修改"
      width="lg"
      :dismissible="!editBusy"
      @close="editOpen = false"
    >
      <div class="form">
        <UiField v-model="draft.title" label="标题" :error="draftError.title" :maxlength="120" required />
        <UiTextarea v-model="draft.content" label="正文" :rows="14" :error="draftError.content" />
        <p v-if="draftError.general" class="form__err" role="alert">{{ draftError.general }}</p>
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="editBusy" @click="editOpen = false">取消</UiButton>
        <UiButton variant="primary" :loading="editBusy" @click="submitEdit">保存</UiButton>
      </template>
    </UiDialog>

    <!-- 删除帖子 -->
    <UiDialog
      :open="deleteOpen"
      title="删除这封帖子？"
      eyebrow="软删除"
      width="sm"
      :dismissible="!deleteBusy"
      @close="deleteOpen = false"
    >
      <p class="del-text">删除后普通用户不可见，其下的回复会一并隐藏。版主仍可追溯。</p>
      <template #footer>
        <UiButton variant="link" :disabled="deleteBusy" @click="deleteOpen = false">取消</UiButton>
        <UiButton variant="danger" :loading="deleteBusy" @click="confirmDeletePost">确认删除</UiButton>
      </template>
    </UiDialog>

    <!-- 删除评论 -->
    <UiDialog
      :open="!!deletingComment"
      title="删除这条回复？"
      eyebrow="不可撤销"
      width="sm"
      :dismissible="!deleteCommentBusy"
      @close="deletingComment = null"
    >
      <p class="del-text">删除顶级回复会同时隐藏它下面的全部追评。</p>
      <template #footer>
        <UiButton variant="link" :disabled="deleteCommentBusy" @click="deletingComment = null">取消</UiButton>
        <UiButton variant="danger" :loading="deleteCommentBusy" @click="confirmDeleteComment">确认删除</UiButton>
      </template>
    </UiDialog>
  </div>
</template>

<style scoped>
.post-page {
  width: 100%;
  max-width: 860px;
  margin: 0 auto;
  padding: var(--s-5) clamp(var(--s-4), 4vw, var(--s-6)) var(--s-7);
}

.crumbs {
  display: flex;
  gap: var(--s-2);
  margin: 0 0 var(--s-4);
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.crumbs a {
  color: var(--ink-2);
}

.crumbs a:hover {
  color: var(--teal);
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

.state__link {
  margin-left: var(--s-3);
}

/* ---------- 正文 ---------- */
.letter__head {
  padding-bottom: var(--s-4);
  margin-bottom: var(--s-5);
  border-bottom: 1px solid var(--ink);
}

.letter__tags {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  margin: 0;
}

.letter__cat {
  color: var(--ink-2);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.12em;
}

.letter__title {
  margin: var(--s-2) 0 var(--s-3);
  font-family: var(--font-serif-cjk);
  font-size: var(--fs-h2);
  line-height: 1.2;
}

.letter__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-3);
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.letter__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2);
  margin-top: var(--s-3);
}

.letter__body {
  margin-bottom: var(--s-6);
}

/* ---------- 评论 ---------- */
.comments {
  padding-top: var(--s-5);
  border-top: 1px solid var(--ink);
}

.comments__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding-bottom: var(--s-2);
  margin-bottom: var(--s-3);
  border-bottom: var(--hairline);
}

.comments__head p {
  margin: 0;
}

.comments__count {
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.threads,
.replies {
  margin: 0;
  padding: 0;
  list-style: none;
}

.thread {
  padding: var(--s-3) 0;
  border-bottom: var(--hairline);
}

.comment__head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--s-2);
  margin: 0;
}

.comment__author {
  font-family: var(--font-serif-cjk);
  font-size: 15px;
  font-weight: 600;
}

.comment__to {
  color: var(--ink-2);
  font-size: 11px;
}

.comment__time {
  margin-left: auto;
  color: var(--ink-2);
  font-size: 11px;
}

.comment__body {
  margin: var(--s-1) 0 0;
  font-family: var(--font-serif);
  font-size: 16px;
  line-height: 1.7;
  white-space: pre-wrap;
}

.comment__tools {
  display: flex;
  gap: var(--s-3);
  margin: var(--s-1) 0 0;
}

.link-btn {
  padding: 0;
  border: 0;
  background: none;
  color: var(--teal);
  font-size: var(--fs-small);
  cursor: pointer;
  text-decoration: underline;
  text-decoration-thickness: 1px;
  text-underline-offset: 3px;
}

.link-btn:hover {
  text-decoration-thickness: 2px;
}

.link-btn--danger {
  color: var(--ochre);
}

.replies {
  margin: var(--s-2) 0 0 var(--s-4);
  padding-left: var(--s-3);
  border-left: 1px solid var(--rule);
}

.comment--reply {
  padding: var(--s-2) 0;
}

.reply-box {
  margin-top: var(--s-3);
  padding: var(--s-3);
  border: 1px solid var(--rule);
  background: var(--paper-lift);
}

.reply-box__actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--s-2);
  margin-top: var(--s-2);
}

.new-reply {
  margin-top: var(--s-5);
  padding-top: var(--s-4);
  border-top: 1px solid var(--ink);
}

.new-reply__actions {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--s-3);
}

.closed {
  margin: var(--s-5) 0 0;
  padding: var(--s-3);
  border: 1px dashed var(--rule-strong);
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.form {
  display: grid;
  gap: var(--s-4);
}

.form__err {
  margin: 0;
  color: var(--ochre);
  font-size: var(--fs-small);
}

.del-text {
  margin: 0;
  line-height: 1.7;
}
</style>
