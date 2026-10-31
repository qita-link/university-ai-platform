<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AnswerBlock from '@/components/ai/AnswerBlock.vue'
import ActResultBlock from '@/components/ai/ActResultBlock.vue'
import Composer from '@/components/ai/Composer.vue'
import ScopePicker from '@/components/ai/ScopePicker.vue'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import { fromChatResponse, fromMessage, type ChatItem } from '@/components/ai/types'
import { aiApi, evaluationApi, feedbackApi, gradeApi, knowledgeApi, leaveApi } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import type { AgentActResponse, ConversationView, SpaceView, TranscriptView } from '@/api/types'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { formatShort } from '@/lib/time'
import { useAuthStore } from '@/stores/auth'
import { useClockStore } from '@/stores/clock'
import { useToastStore } from '@/stores/toast'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const clock = useClockStore()
const toast = useToastStore()

const canKb = computed(() => auth.can('kb:space:read'))
const canChat = computed(() => auth.can('ai:chat'))
const canAct = computed(() => auth.can('ai:tool:use'))

// ---------------- 会话目录 ----------------
const conversations = ref<ConversationView[]>([])
const convLoading = ref(true)
const convError = ref<string | null>(null)
const tocOpen = ref(false)

async function loadConversations() {
  convError.value = null
  try {
    conversations.value = (await aiApi.conversations(1, 50)).items
  } catch (e) {
    convError.value = errorMessage(e)
  } finally {
    convLoading.value = false
  }
}

// ---------------- 当前会话 ----------------
const conversationId = ref<number | null>(null)
const items = ref<ChatItem[]>([])
const loadingConv = ref(false)
const loadConvError = ref<string | null>(null)
const draft = ref('')
const sending = ref(false)
const scope = ref<number[]>([])
const composerRef = ref<InstanceType<typeof Composer> | null>(null)

const currentTitle = computed(() => conversations.value.find((c) => c.id === conversationId.value)?.title ?? null)

function routeId(): number | null {
  const raw = route.params.conversationId
  const n = Number(Array.isArray(raw) ? raw[0] : raw)
  return Number.isInteger(n) && n > 0 ? n : null
}

async function openConversation(id: number | null) {
  conversationId.value = id
  loadConvError.value = null
  if (id === null) {
    items.value = []
    await nextTick()
    composerRef.value?.focus()
    return
  }
  loadingConv.value = true
  try {
    const detail = await aiApi.conversation(id)
    items.value = detail.messages.map(fromMessage).filter((x): x is ChatItem => x !== null)
    await nextTick()
    scrollToBottom('auto')
  } catch (e) {
    items.value = []
    loadConvError.value = errorMessage(e)
  } finally {
    loadingConv.value = false
  }
}

watch(
  () => route.params.conversationId,
  () => {
    tocOpen.value = false
    const id = routeId()
    if (id !== conversationId.value) void openConversation(id)
  },
)

function scrollToBottom(behavior: ScrollBehavior = 'smooth') {
  window.scrollTo({ top: document.documentElement.scrollHeight, behavior })
}

/** 办事结果装进同一条消息流：答复、经过与确认单都在这里渲染 */
function actItem(act: AgentActResponse): ChatItem {
  return {
    key: `a${Date.now()}`,
    role: 'ASSISTANT',
    content: act.reply ?? '',
    status: 'SUCCESS',
    citations: [],
    model: act.model,
    totalTokens: null,
    tokenSource: null,
    errorCode: null,
    errorText: null,
    traceId: null,
    createdAt: Date.now(),
    act,
  }
}

async function send() {
  const message = draft.value.trim()
  if (!message || sending.value) return
  sending.value = true
  draft.value = ''

  const stamp = Date.now()
  const userItem: ChatItem = {
    key: `u${stamp}`,
    role: 'USER',
    content: message,
    status: 'SUCCESS',
    citations: [],
    model: null,
    totalTokens: null,
    tokenSource: null,
    errorCode: null,
    errorText: null,
    traceId: null,
    createdAt: stamp,
  }
  const pending: ChatItem = { ...userItem, key: `p${stamp}`, role: 'ASSISTANT', content: '', status: 'PENDING' }
  items.value.push(userItem, pending)
  await nextTick()
  scrollToBottom()

  try {
    // 有问答权限时先查知识库：有依据就直接回答（带出处），不必惊动工具
    if (canChat.value) {
      const chat = await aiApi.chat({
        message,
        conversationId: conversationId.value,
        spaceIds: scope.value.length ? scope.value : null,
      })
      if (conversationId.value !== chat.conversationId) {
        conversationId.value = chat.conversationId
        await router.replace({ name: 'ai', params: { conversationId: chat.conversationId } })
      }
      void loadConversations()
      if (chat.sufficientEvidence || !canAct.value) {
        replaceItem(pending.key, fromChatResponse(chat))
        return
      }
      // 知识库答不上来，交给办事：查询类工具同样能补充信息，写操作会出确认单
      setPendingText(pending.key, '资料里没有直接答案，正在看看能不能帮你办…')
      const act = await aiApi.act(message)
      if (act.steps.length || act.pending || act.outcome === 'WAITING_CONFIRM') {
        replaceItem(pending.key, actItem(act))
        return
      }
      replaceItem(pending.key, fromChatResponse(chat))
      return
    }

    // 只有办事权限的账号：直接交给工具
    const act = await aiApi.act(message)
    replaceItem(pending.key, actItem(act))
  } catch (e) {
    const code = e instanceof ApiError ? e.code : null
    replaceItem(pending.key, {
      ...pending,
      status: 'FAILED',
      errorCode: code,
      errorText: errorMessage(e),
      traceId: traceIdOf(e),
    })
    if (!draft.value) draft.value = message
  } finally {
    sending.value = false
  }
}

function replaceItem(key: string, next: ChatItem) {
  const i = items.value.findIndex((x) => x.key === key)
  if (i >= 0) items.value.splice(i, 1, next)
}

/** 等待提示换一句话：替换成新对象，确保响应式更新 */
function setPendingText(key: string, text: string) {
  const i = items.value.findIndex((x) => x.key === key)
  const current = i >= 0 ? items.value[i] : undefined
  if (!current) return
  items.value.splice(i, 1, { ...current, content: text, status: 'PENDING' })
}

function quickAsk(text: string) {
  if (sending.value) return
  draft.value = text
  void send()
}

// 点击正文角标时，把对应旁注滚入视野（不使用 scrollIntoView）
function reveal(el: HTMLElement) {
  const r = el.getBoundingClientRect()
  const pad = 96
  if (r.top < pad || r.bottom > window.innerHeight - 200) {
    window.scrollTo({ top: window.scrollY + r.top - pad, behavior: 'smooth' })
  }
}

// ---------------- 起步面板：精简版“校务速览” ----------------
const transcript = ref<TranscriptView | null>(null)
const pendingLeaves = ref(0)
const pendingEvaluation = ref(0)
const pendingFeedback = ref(0)

const canGrades = computed(() => auth.can('grade:read'))
const canReview = computed(() => auth.can('leave:review', 'leave:manage_all'))
const canEvaluate = computed(() => auth.can('evaluation:submit'))
const canHandle = computed(() => !!auth.user?.roles.includes('ADMIN') && auth.can('feedback:handle'))

const gpaText = computed(() => {
  const overall = transcript.value?.overall
  if (!overall || overall.courseCount === 0) return null
  return overall
})

const todo = computed(() =>
  [
    { to: '/leave?tab=review', label: '待审批请假', count: pendingLeaves.value, show: canReview.value },
    { to: '/grades?tab=evaluate', label: '待评教', count: pendingEvaluation.value, show: canEvaluate.value },
    { to: '/mailbox?tab=handle', label: '待处理反馈', count: pendingFeedback.value, show: canHandle.value },
  ].filter((t) => t.show),
)

const shortcuts = computed(() =>
  [
    { to: '/services?tab=rooms', label: '空教室', show: auth.can('classroom:read') },
    { to: '/leave', label: '请假', show: auth.can('leave:submit', 'leave:review', 'leave:manage_all') },
    { to: '/grades', label: '成绩', show: auth.can('grade:read', 'grade:manage', 'evaluation:submit') },
    { to: '/mailbox?tab=feedback', label: '意见', show: auth.can('feedback:submit') },
    { to: '/courses', label: '课表', show: auth.can('course:read') },
  ].filter((s) => s.show),
)

const guesses = computed(() => {
  const list: string[] = []
  if (auth.can('grade:read')) list.push('我的绩点是多少？')
  if (auth.can('timetable:read')) list.push('我这学期有哪些课？')
  if (auth.can('classroom:read')) list.push('今天下午哪里有空教室？')
  if (auth.can('leave:submit')) list.push('我要请假，帮我写一张')
  list.push('图书馆几点开门？')
  if (auth.can('forum:post:write')) list.push('论坛里有人讨论补退选吗？')
  return list.slice(0, 6)
})

async function loadGlance() {
  const jobs: Promise<void>[] = []
  if (canGrades.value) {
    jobs.push(
      gradeApi
        .me()
        .then((t) => {
          transcript.value = t
        })
        .catch(() => undefined),
    )
  }
  if (canReview.value) {
    jobs.push(
      leaveApi
        .toReview({ status: 'PENDING', size: 1 })
        .then((r) => {
          pendingLeaves.value = r.total
        })
        .catch(() => undefined),
    )
  }
  if (canEvaluate.value) {
    jobs.push(
      evaluationApi
        .tasks()
        .then((r) => {
          pendingEvaluation.value = r.filter((t) => !t.evaluated).length
        })
        .catch(() => undefined),
    )
  }
  if (canHandle.value) {
    jobs.push(
      feedbackApi
        .list({ status: 'OPEN', size: 1 })
        .then((r) => {
          pendingFeedback.value = r.total
        })
        .catch(() => undefined),
    )
  }
  await Promise.allSettled(jobs)
}

// ---------------- 删除会话 ----------------
const deleting = ref<ConversationView | null>(null)
const deleteBusy = ref(false)

async function confirmDelete() {
  const c = deleting.value
  if (!c) return
  deleteBusy.value = true
  try {
    await aiApi.deleteConversation(c.id)
    conversations.value = conversations.value.filter((x) => x.id !== c.id)
    if (conversationId.value === c.id) await router.push({ name: 'ai' })
    toast.push('会话已删除', 'success')
    deleting.value = null
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    deleteBusy.value = false
  }
}

// ---------------- 检索范围 ----------------
const spaces = ref<SpaceView[]>([])
const spacesLoading = ref(false)
const spacesError = ref<string | null>(null)

async function loadSpaces() {
  if (!canKb.value) return
  spacesLoading.value = true
  try {
    spaces.value = (await knowledgeApi.spaces({ size: 100 })).items
  } catch (e) {
    spacesError.value = errorMessage(e)
  } finally {
    spacesLoading.value = false
  }
}

onMounted(() => {
  void loadConversations()
  void openConversation(routeId())
  void loadSpaces()
  void loadGlance()
})
</script>

<template>
  <div class="ask">
    <!-- 目录：历史会话 -->
    <aside class="toc" :class="{ 'is-open': tocOpen }" aria-label="历史会话">
      <div class="toc__head">
        <p class="toc__title"><span class="latin-italic">Contents</span> 目录</p>
        <RouterLink :to="{ name: 'ai' }" class="toc__new">新的提问</RouterLink>
      </div>

      <p v-if="convLoading" class="toc__msg">载入中…</p>
      <p v-else-if="convError" class="toc__msg toc__msg--err">
        {{ convError }}
        <button type="button" class="toc__retry" @click="loadConversations">重试</button>
      </p>
      <p v-else-if="conversations.length === 0" class="toc__msg">还没有会话。提出第一个问题后会出现在这里。</p>

      <ol v-else class="toc__list">
        <li v-for="(c, i) in conversations" :key="c.id" class="toc__item" :class="{ 'is-current': c.id === conversationId }">
          <RouterLink :to="{ name: 'ai', params: { conversationId: c.id } }" class="toc__link">
            <span class="toc__no latin-italic">{{ i + 1 }}</span>
            <span class="toc__name">{{ c.title || '未命名会话' }}</span>
            <span class="toc__time mono">{{ formatShort(c.lastMessageAt ?? c.createdAt, clock.now) }}</span>
          </RouterLink>
          <button type="button" class="toc__del" :aria-label="`删除会话：${c.title || '未命名会话'}`" @click="deleting = c">
            删除
          </button>
        </li>
      </ol>
    </aside>
    <div v-if="tocOpen" class="toc-scrim" aria-hidden="true" @click="tocOpen = false" />

    <!-- 正文 -->
    <section class="column">
      <div class="column__inner">
        <header class="running-head">
          <button type="button" class="running-head__toc" :aria-expanded="tocOpen" @click="tocOpen = !tocOpen">
            目录
          </button>
          <p class="running-head__title">{{ conversationId ? currentTitle || '会话' : '问答与办事' }}</p>
          <p class="running-head__folio latin-italic">Ask · Act</p>
        </header>

        <p v-if="loadingConv" class="state">正在翻到这一页…</p>
        <div v-else-if="loadConvError" class="state state--err" role="alert">
          <p>{{ loadConvError }}</p>
          <RouterLink :to="{ name: 'ai' }">开始新的提问</RouterLink>
        </div>

        <!-- 起步：一句话入口 + 精简校务速览 -->
        <div v-else-if="items.length === 0" class="start fade-in">
          <p class="start__latin latin-italic">Ask · Act</p>
          <h1 class="start__title">有什么想查？</h1>
          <p class="start__sub">查资料、课表、空教室；也可以直接说要办的事，比如请假、发帖。</p>

          <section
            v-if="gpaText || todo.length || shortcuts.length"
            class="glance"
            :class="{ 'glance--slim': !gpaText }"
            aria-label="校务速览"
          >
            <RouterLink v-if="gpaText" to="/grades" class="glance__gpa">
              <span class="glance__gpa-value mono">{{ gpaText.gpa.toFixed(2) }}</span>
              <span class="glance__gpa-label">
                平均学分绩点 · 已修 {{ gpaText.earnedCredits.toFixed(1) }} 学分
              </span>
            </RouterLink>
            <div class="glance__side">
              <p v-if="todo.length" class="glance__todo">
                <RouterLink v-for="t in todo" :key="t.to" :to="t.to" class="glance__todo-item">
                  {{ t.label }}<span class="mono">{{ t.count }}</span>
                </RouterLink>
              </p>
              <p v-if="shortcuts.length" class="glance__links">
                <RouterLink v-for="s in shortcuts" :key="s.to" :to="s.to" class="glance__link">{{ s.label }}</RouterLink>
              </p>
            </div>
          </section>

          <div class="chips" aria-label="猜你想问">
            <button v-for="g in guesses" :key="g" type="button" class="chip" :disabled="sending" @click="quickAsk(g)">
              {{ g }}
            </button>
          </div>
          <p class="start__note">回答只依据你有权阅读的资料；改数据的操作会先出确认单，确认后才生效。</p>
        </div>

        <!-- 对话流 -->
        <div v-else class="stream">
          <template v-for="m in items" :key="m.key">
            <div v-if="m.role === 'USER'" class="question fade-in">
              <p class="question__label eyebrow" aria-hidden="true">问</p>
              <p class="question__text">{{ m.content }}</p>
            </div>
            <ActResultBlock v-else-if="m.act" :response="m.act" />
            <AnswerBlock v-else :item="m" @reveal="reveal" />
          </template>
        </div>
      </div>

      <!-- 底部输入 -->
      <div class="dock">
        <div class="dock__inner">
          <Composer ref="composerRef" v-model="draft" :busy="sending" @send="send">
            <template #tools>
              <ScopePicker v-if="canKb" v-model="scope" :spaces="spaces" :loading="spacesLoading" :error="spacesError" />
            </template>
          </Composer>
          <p class="dock__note">回答可能有误，重要事项以原文与学校通知为准；办事会先让你确认。</p>
        </div>
      </div>
    </section>

    <UiDialog :open="!!deleting" title="删除这段会话？" eyebrow="删除" width="sm" :dismissible="!deleteBusy" @close="deleting = null">
      <p class="del-text">“{{ deleting?.title || '未命名会话' }}”将从目录中移除。</p>
      <template #footer>
        <UiButton variant="link" :disabled="deleteBusy" @click="deleting = null">取消</UiButton>
        <UiButton variant="danger" :loading="deleteBusy" @click="confirmDelete">删除</UiButton>
      </template>
    </UiDialog>
  </div>
</template>

<style scoped>
.ask {
  --toc-w: 272px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) var(--toc-w);
  flex: 1;
}

/* ---------- 目录 ---------- */
.toc {
  position: sticky;
  top: var(--masthead-h);
  grid-column: 2;
  grid-row: 1;
  align-self: start;
  height: calc(100vh - var(--masthead-h));
  overflow-y: auto;
  padding: var(--s-5) var(--s-5) var(--s-5) var(--s-4);
  border-left: var(--hairline);
  background: var(--paper-deep);
}

.toc__head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  padding-bottom: var(--s-2);
  border-bottom: 1px solid var(--ink);
}

.toc__title {
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-weight: 600;
}

.toc__title .latin-italic {
  margin-right: var(--s-1);
  color: var(--ink-2);
}

.toc__new {
  font-size: var(--fs-small);
}

.toc__msg {
  margin: var(--s-3) 0;
  color: var(--ink-2);
  font-size: var(--fs-ui);
}

.toc__msg--err {
  color: var(--ink);
}

.toc__retry {
  padding: 0;
  border: 0;
  background: none;
  color: var(--teal);
  text-decoration: underline;
  cursor: pointer;
}

.toc__list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.toc__item {
  position: relative;
  border-bottom: var(--hairline);
}

.toc__link {
  display: grid;
  grid-template-columns: 1.6em 1fr auto;
  align-items: baseline;
  gap: var(--s-2);
  padding: var(--s-2) 0;
  color: var(--ink);
  text-decoration: none;
}

.toc__no {
  color: var(--ink-2);
  font-size: 14px;
}

.toc__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: var(--font-serif-cjk);
  font-size: var(--fs-ui);
}

.toc__time {
  font-size: 11px;
  color: var(--ink-2);
}

.toc__item.is-current .toc__name {
  font-weight: 600;
  color: var(--teal);
}

.toc__item.is-current::before {
  content: '';
  position: absolute;
  left: -12px;
  top: 10px;
  bottom: 10px;
  width: 2px;
  background: var(--teal);
}

.toc__link:hover .toc__name {
  text-decoration: underline;
  text-underline-offset: 3px;
}

.toc__del {
  position: absolute;
  right: 0;
  top: 50%;
  padding: 2px var(--s-1);
  border: 0;
  background: var(--paper-deep);
  color: var(--ochre);
  font-size: var(--fs-small);
  opacity: 0;
  transform: translateY(-50%);
  cursor: pointer;
  transition: opacity var(--t-fast) var(--ease);
}

.toc__item:hover .toc__del,
.toc__del:focus-visible {
  opacity: 1;
}

.toc-scrim {
  display: none;
}

/* ---------- 正文栏 ---------- */
.column {
  display: flex;
  flex-direction: column;
  grid-column: 1;
  grid-row: 1;
  min-width: 0;
  container: reading / inline-size;
}

.column__inner {
  flex: 1;
  width: 100%;
  max-width: 1140px;
  padding: 0 clamp(var(--s-4), 5vw, var(--s-7)) var(--s-6);
}

.running-head {
  display: flex;
  align-items: baseline;
  gap: var(--s-3);
  padding: var(--s-4) 0 var(--s-2);
  margin-bottom: var(--s-4);
  border-bottom: var(--hairline);
}

.running-head__toc {
  display: none;
  padding: 0;
  border: 0;
  background: none;
  color: var(--teal);
  cursor: pointer;
}

.running-head__title {
  flex: 1;
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: var(--font-serif-cjk);
  font-size: var(--fs-ui);
  letter-spacing: 0.08em;
  color: var(--ink-2);
}

.running-head__folio {
  margin: 0;
  color: var(--ink-2);
}

/* ---------- 起步面板 ---------- */
.start {
  max-width: 46em;
  padding: var(--s-4) 0 var(--s-2);
}

.start__latin {
  margin: 0;
  color: var(--ink-2);
  font-size: 18px;
}

.start__title {
  margin: var(--s-1) 0 var(--s-2);
  font-family: var(--font-serif-cjk);
  font-size: clamp(34px, 5vw, 52px);
  line-height: 1.1;
  letter-spacing: 0.02em;
}

.start__sub {
  margin: 0 0 var(--s-4);
  color: var(--ink-2);
  font-family: var(--font-serif);
  font-size: 16px;
  line-height: 1.7;
}

.glance {
  display: grid;
  grid-template-columns: minmax(0, 0.9fr) minmax(0, 1.4fr);
  gap: 1px;
  margin-bottom: var(--s-4);
  border: 1px solid var(--rule);
  background: var(--rule);
}

.glance--slim {
  grid-template-columns: minmax(0, 1fr);
}

.glance--slim {
  grid-template-columns: minmax(0, 1fr);
}

.glance__gpa {
  display: grid;
  gap: 4px;
  padding: var(--s-3);
  background: var(--paper-lift);
  color: var(--ink);
  text-decoration: none;
}

.glance__gpa:hover {
  background: var(--paper-deep);
  text-decoration: none;
}

.glance__gpa-value {
  font-size: clamp(30px, 4vw, 40px);
  line-height: 1;
}

.glance__gpa-label {
  color: var(--ink-2);
  font-size: 11px;
  line-height: 1.5;
}

.glance__side {
  display: grid;
  gap: var(--s-2);
  padding: var(--s-3);
  background: var(--paper-lift);
}

.glance__todo {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2);
  margin: 0;
}

.glance__todo-item {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  padding: 3px 8px;
  border: 1px solid var(--warning);
  color: var(--ink);
  font-size: 12px;
  text-decoration: none;
}

.glance__todo-item .mono {
  color: var(--warning);
  font-size: 12px;
}

.glance__links {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-3);
  margin: 0;
}

.glance__link {
  font-size: var(--fs-small);
  color: var(--teal);
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2);
}

.chip {
  padding: 7px 11px;
  border: 1px solid var(--rule-strong);
  background: var(--paper-lift);
  color: var(--ink);
  font-size: var(--fs-small);
  text-align: left;
  cursor: pointer;
  transition: border-color var(--t-fast) var(--ease), background-color var(--t-fast) var(--ease);
}

.chip:hover:not(:disabled) {
  border-color: var(--teal);
  background: var(--teal-tint);
}

.chip:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.start__note {
  margin: var(--s-3) 0 0;
  color: var(--ink-2);
  font-size: 11px;
  line-height: 1.7;
}

.state {
  margin: var(--s-6) 0;
  color: var(--ink-2);
  font-family: var(--font-serif);
  font-size: var(--fs-read);
  font-style: italic;
}

.state--err p {
  margin: 0 0 var(--s-2);
  font-style: normal;
  color: var(--ink);
}

/* ---------- 对话流 ---------- */
.stream {
  display: grid;
  gap: var(--s-6);
  padding-left: 40px;
}

.question {
  position: relative;
  max-width: var(--measure);
}

.question__label {
  position: absolute;
  left: -40px;
  top: 8px;
  margin: 0;
}

.question__text {
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-size: 22px;
  font-weight: 600;
  line-height: 1.45;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

/* ---------- 底部输入 ---------- */
.dock {
  position: sticky;
  bottom: 0;
  z-index: 20;
  padding: var(--s-3) 0 var(--s-3);
  background: linear-gradient(to bottom, transparent, var(--paper) 24px);
}

.dock__inner {
  display: grid;
  max-width: 1140px;
  padding: 0 clamp(var(--s-4), 5vw, var(--s-7));
  padding-left: calc(clamp(var(--s-4), 5vw, var(--s-7)) + 40px);
  grid-template-columns: minmax(0, var(--measure));
  column-gap: clamp(24px, 2.6vw, 44px);
}

.dock__inner > * {
  grid-column: 1;
}

.dock__note {
  margin: var(--s-2) 0 0;
  font-size: 11px;
  color: var(--ink-2);
}

.del-text {
  margin: 0;
}

/* ---------- 窄屏 ---------- */
@media (max-width: 960px) {
  .ask {
    grid-template-columns: minmax(0, 1fr);
  }

  .toc {
    position: fixed;
    left: auto;
    right: 0;
    top: var(--masthead-h);
    bottom: 0;
    z-index: 45;
    width: min(86vw, 320px);
    /* 正文栏有 container-type，会让 fixed 的包含块变成它自己，这里必须写死高度 */
    height: calc(100dvh - var(--masthead-h));
    border-left: 1px solid var(--ink);
    transform: translateX(100%);
    transition: transform var(--t-mid) var(--ease);
  }

  .toc.is-open {
    transform: none;
  }

  .toc-scrim {
    display: block;
    position: fixed;
    inset: var(--masthead-h) 0 0;
    z-index: 44;
    background: rgba(26, 26, 24, 0.24);
  }

  .running-head__toc {
    display: inline;
  }

  .toc__del {
    opacity: 1;
  }
}

@media (max-width: 640px) {
  .column__inner {
    padding: 0 16px var(--s-5);
  }

  .running-head {
    padding: 8px 0 6px;
    margin-bottom: 8px;
  }

  .running-head__folio {
    display: none;
  }

  .start {
    padding: 8px 0 0;
  }

  .start__latin {
    display: none;
  }

  .start__title {
    font-size: 32px;
    margin: 0 0 6px;
  }

  .start__sub {
    font-size: 14px;
    margin-bottom: 12px;
    line-height: 1.6;
  }

  .glance {
    grid-template-columns: minmax(0, 1fr);
    margin-bottom: 12px;
  }

  .glance--slim {
  grid-template-columns: minmax(0, 1fr);
}

.glance--slim {
  grid-template-columns: minmax(0, 1fr);
}

.glance__gpa {
    display: flex;
    align-items: baseline;
    gap: 10px;
    padding: 10px 12px;
  }

  .glance__gpa-value {
    font-size: 28px;
  }

  .glance__side {
    padding: 10px 12px;
    gap: 8px;
  }

  .chips {
    gap: 6px;
  }

  .chip {
    padding: 8px 10px;
    font-size: 12px;
  }

  .start__note {
    display: none;
  }

  .stream {
    padding-left: 0;
    gap: var(--s-5);
  }

  .question__label {
    position: static;
    margin-bottom: 2px;
  }

  .question__text {
    font-size: 19px;
  }

  .dock {
    padding: 8px 0 calc(8px + env(safe-area-inset-bottom));
  }

  .dock__inner {
    padding: 0 16px;
  }

  .dock__note {
    display: none;
  }
}

@container reading (max-width: 780px) {
  .stream {
    padding-left: 0;
  }

  .question__label {
    position: static;
    margin-bottom: var(--s-1);
  }
}
</style>
