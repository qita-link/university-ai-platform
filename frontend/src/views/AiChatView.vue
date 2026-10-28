<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AnswerBlock from '@/components/ai/AnswerBlock.vue'
import Composer from '@/components/ai/Composer.vue'
import ScopePicker from '@/components/ai/ScopePicker.vue'
import ToolCallCard from '@/components/ai/ToolCallCard.vue'
import ToolDialog from '@/components/ai/ToolDialog.vue'
import ToolReceipt from '@/components/ai/ToolReceipt.vue'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import { fromChatResponse, fromMessage, type ChatItem } from '@/components/ai/types'
import { aiApi, knowledgeApi } from '@/api/endpoints'
import { ApiError } from '@/api/http'
import type { AiToolCallView, AiToolView, ConversationView, SpaceView } from '@/api/types'
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

const canTools = computed(() => auth.can('ai:tool:use'))
const canKb = computed(() => auth.can('kb:space:read'))

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
  receipts.value = []
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

// 路由变化（点目录、浏览器前进后退）时切换会话；本页新建会话后 replace 路由不重复加载
watch(
  () => route.params.conversationId,
  () => {
    const id = routeId()
    if (id !== conversationId.value) void openConversation(id)
    tocOpen.value = false
  },
)

function scrollToBottom(behavior: ScrollBehavior = 'smooth') {
  window.scrollTo({ top: document.documentElement.scrollHeight, behavior })
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
    const res = await aiApi.chat({
      message,
      conversationId: conversationId.value,
      spaceIds: scope.value.length ? scope.value : null,
    })
    replaceItem(pending.key, fromChatResponse(res))
    if (conversationId.value !== res.conversationId) {
      conversationId.value = res.conversationId
      await router.replace({ name: 'ai', params: { conversationId: res.conversationId } })
    }
    void loadConversations()
  } catch (e) {
    const code = e instanceof ApiError ? e.code : null
    replaceItem(pending.key, {
      ...pending,
      status: 'FAILED',
      errorCode: code,
      errorText: errorMessage(e),
      traceId: traceIdOf(e),
    })
    if (!draft.value) draft.value = message // 失败时把问题放回输入框，方便重试
  } finally {
    sending.value = false
  }
}

function replaceItem(key: string, next: ChatItem) {
  const i = items.value.findIndex((x) => x.key === key)
  if (i >= 0) items.value.splice(i, 1, next)
}

// 点击正文角标时，把对应旁注滚入视野（不使用 scrollIntoView）
function reveal(el: HTMLElement) {
  const r = el.getBoundingClientRect()
  const pad = 96
  if (r.top < pad || r.bottom > window.innerHeight - 200) {
    window.scrollTo({ top: window.scrollY + r.top - pad, behavior: 'smooth' })
  }
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

// ---------------- 工具（办事） ----------------
const tools = ref<AiToolView[]>([])
const toolsLoading = ref(false)
const toolsError = ref<string | null>(null)
const toolDialog = ref(false)
/** 待确认的写操作：跨会话显示，刷新后从后端重新拉取 */
const pendingCalls = ref<AiToolCallView[]>([])
/** 本次浏览中已结束的调用，以回执形式附在对话末尾 */
const receipts = ref<AiToolCallView[]>([])

const toolTitle = (name: string) => tools.value.find((t) => t.name === name)?.title ?? name

async function loadTools() {
  if (!canTools.value) return
  toolsLoading.value = true
  try {
    const [catalog, pending] = await Promise.all([
      aiApi.tools(),
      aiApi.toolCalls({ status: 'PENDING_CONFIRM', size: 20 }),
    ])
    tools.value = catalog
    pendingCalls.value = pending.items
  } catch (e) {
    toolsError.value = errorMessage(e)
  } finally {
    toolsLoading.value = false
  }
}

function onToolCreated(call: AiToolCallView) {
  if (call.status === 'PENDING_CONFIRM') {
    pendingCalls.value = [call, ...pendingCalls.value.filter((c) => c.id !== call.id)]
  } else {
    receipts.value.push(call)
    void nextTick(() => scrollToBottom())
  }
}

function onToolUpdate(call: AiToolCallView) {
  if (call.status === 'PENDING_CONFIRM') {
    pendingCalls.value = pendingCalls.value.map((c) => (c.id === call.id ? call : c))
    return
  }
  pendingCalls.value = pendingCalls.value.filter((c) => c.id !== call.id)
  receipts.value = [...receipts.value.filter((c) => c.id !== call.id), call]
  if (call.status === 'SUCCEEDED') toast.push(`已完成：${toolTitle(call.toolName)}`, 'success')
  void nextTick(() => scrollToBottom())
}

function dismissPending(id: number) {
  const call = pendingCalls.value.find((c) => c.id === id)
  pendingCalls.value = pendingCalls.value.filter((c) => c.id !== id)
  if (call) receipts.value.push({ ...call, status: 'EXPIRED' })
}

onMounted(() => {
  void loadConversations()
  void openConversation(routeId())
  void loadSpaces()
  void loadTools()
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
          <button type="button" class="running-head__toc" :aria-expanded="tocOpen" @click="tocOpen = !tocOpen">目录</button>
          <p class="running-head__title">{{ conversationId ? currentTitle || '会话' : '新的提问' }}</p>
          <p class="running-head__folio latin-italic">Ask</p>
        </header>

        <p v-if="loadingConv" class="state">正在翻到这一页…</p>
        <div v-else-if="loadConvError" class="state state--err" role="alert">
          <p>{{ loadConvError }}</p>
          <RouterLink :to="{ name: 'ai' }">开始新的提问</RouterLink>
        </div>

        <!-- 空白页：说明这里如何作答，不放示例数据 -->
        <div v-else-if="items.length === 0 && receipts.length === 0" class="blank fade-in">
          <p class="blank__latin latin-italic">Ask the library</p>
          <h1 class="blank__title">有什么想查的？</h1>
          <div class="blank__rules">
            <p><span class="latin-italic">i.</span> 回答只依据你有权阅读的知识库，出处标在页边，点开可读原文。</p>
            <p><span class="latin-italic">ii.</span> 依据不足时，会以“编者按”直接告诉你没找到，而不是编一个答案。</p>
            <p v-if="canTools">
              <span class="latin-italic">iii.</span> 需要办事（查课表、选课、请假）时用“办事”。写操作先给你看预览，确认后才执行。
            </p>
          </div>
        </div>

        <!-- 对话流 -->
        <div v-else class="stream">
          <template v-for="m in items" :key="m.key">
            <div v-if="m.role === 'USER'" class="question fade-in">
              <p class="question__label eyebrow" aria-hidden="true">问</p>
              <p class="question__text">{{ m.content }}</p>
            </div>
            <AnswerBlock v-else :item="m" @reveal="reveal" />
          </template>
          <ToolReceipt v-for="c in receipts" :key="'r' + c.id" :call="c" :title="toolTitle(c.toolName)" />
        </div>
      </div>

      <!-- 底部：待确认 + 输入 -->
      <div class="dock">
        <div class="dock__inner">
          <div v-if="pendingCalls.length" class="tray" aria-label="待确认的操作">
            <p class="tray__label eyebrow">待确认 {{ pendingCalls.length }}</p>
            <div class="tray__cards">
              <ToolCallCard
                v-for="c in pendingCalls"
                :key="c.id"
                :call="c"
                :title="toolTitle(c.toolName)"
                @update="onToolUpdate"
                @dismiss="dismissPending"
              />
            </div>
          </div>

          <Composer ref="composerRef" v-model="draft" :busy="sending" @send="send">
            <template #tools>
              <ScopePicker v-if="canKb" v-model="scope" :spaces="spaces" :loading="spacesLoading" :error="spacesError" />
              <button v-if="canTools" type="button" class="dock__tool" @click="toolDialog = true">办事</button>
            </template>
          </Composer>
          <p class="dock__note">回答由模型依据知识库生成，可能有误，重要事项请以原文和学校通知为准。</p>
        </div>
      </div>
    </section>

    <ToolDialog
      v-if="canTools"
      :open="toolDialog"
      :tools="tools"
      :loading="toolsLoading"
      :load-error="toolsError"
      :conversation-id="conversationId"
      @close="toolDialog = false"
      @created="onToolCreated"
    />

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
  grid-template-columns: var(--toc-w) minmax(0, 1fr);
  flex: 1;
}

/* ---------- 目录 ---------- */
.toc {
  position: sticky;
  top: var(--masthead-h);
  align-self: start;
  height: calc(100vh - var(--masthead-h));
  overflow-y: auto;
  padding: var(--s-5) var(--s-4) var(--s-5) var(--s-5);
  border-right: var(--hairline);
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
  min-width: 0;
  container: reading / inline-size;
}

.column__inner {
  flex: 1;
  width: 100%;
  max-width: 1080px;
  padding: 0 clamp(var(--s-4), 5vw, var(--s-7)) var(--s-6);
}

/* 书眉 */
.running-head {
  display: flex;
  align-items: baseline;
  gap: var(--s-3);
  padding: var(--s-4) 0 var(--s-2);
  margin-bottom: var(--s-6);
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

/* 空白页 */
.blank {
  max-width: 40em;
  padding: var(--s-6) 0 var(--s-5);
}

.blank__latin {
  margin: 0;
  color: var(--ink-2);
  font-size: 22px;
}

.blank__title {
  margin: var(--s-1) 0 var(--s-6);
  font-family: var(--font-serif-cjk);
  font-size: var(--fs-h1);
  line-height: 1.05;
  letter-spacing: 0.02em;
}

.blank__rules {
  display: grid;
  gap: var(--s-3);
  max-width: var(--measure);
  padding-top: var(--s-3);
  border-top: 1px solid var(--ink);
  font-family: var(--font-serif);
  font-size: var(--fs-read);
  line-height: var(--lh-read);
}

.blank__rules p {
  display: grid;
  grid-template-columns: 2em 1fr;
  margin: 0;
}

.blank__rules .latin-italic {
  color: var(--teal);
}

/* 对话流 */
.stream {
  display: grid;
  gap: var(--s-6);
  padding-left: 40px; /* 给页边“问/答”标记留位 */
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
  font-size: 24px;
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
  max-width: 1080px;
  padding: 0 clamp(var(--s-4), 5vw, var(--s-7));
}

/* 输入框与正文栏左右对齐（正文前留 40px 页边标记） */
.dock__inner > * {
  max-width: var(--measure);
  margin-left: 40px;
}

.dock__tool {
  min-height: 32px;
  padding: 0 var(--s-2);
  border: 1px solid transparent;
  background: none;
  color: var(--teal);
  cursor: pointer;
}

.dock__tool:hover {
  border-color: var(--rule-strong);
}

.dock__note {
  margin: var(--s-2) 0 0;
  font-size: 11px;
  color: var(--ink-2);
}

.tray {
  margin-bottom: var(--s-3);
}

.tray__label {
  margin: 0 0 var(--s-2);
  color: var(--teal);
}

.tray__cards {
  display: grid;
  gap: var(--s-2);
  max-height: 46vh;
  overflow-y: auto;
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
    left: 0;
    top: var(--masthead-h);
    bottom: 0;
    z-index: 45;
    width: min(86vw, 320px);
    height: auto;
    border-right: 1px solid var(--ink);
    transform: translateX(-100%);
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

@container reading (max-width: 600px) {
  .stream {
    padding-left: 0;
  }

  .question__label {
    position: static;
    margin-bottom: var(--s-1);
  }

  .question__text {
    font-size: 20px;
  }

  .dock__inner > * {
    margin-left: 0;
  }

  .running-head {
    margin-bottom: var(--s-4);
  }
}
</style>
