<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { aiApi } from '@/api/endpoints'
import type { ActHistoryView, AgentActResponse, AgentStep, AiToolView, ToolCallStatus } from '@/api/types'
import UiButton from '@/components/ui/UiButton.vue'
import UiPageHeader from '@/components/ui/UiPageHeader.vue'
import UiTag from '@/components/ui/UiTag.vue'
import { errorMessage } from '@/lib/errors'
import { renderMarkdown } from '@/lib/markdown'
import { formatDateTime, formatShort } from '@/lib/time'

/**
 * 办事窗口：一句话 -> 模型选工具填参 -> 平台执行。
 * 写操作一律先出确认单，点“确认执行”才落库；页面上不提供任何绕过确认的入口。
 * <p>
 * 已合并进「问答」页：embedded=true 时不渲染自己的页头，由问答页的视图切换统一提供上下文。
 */
const props = withDefaults(defineProps<{ embedded?: boolean }>(), { embedded: false })
void props

type Tone = 'neutral' | 'teal' | 'ochre' | 'warning' | 'success' | 'muted'

const input = ref('')
const inputEl = ref<HTMLTextAreaElement | null>(null)
const busy = ref(false)
const result = ref<AgentActResponse | null>(null)
const error = ref<string | null>(null)

/** 确认单的状态，与后端 pending.status 同步 */
const pendingStatus = ref<ToolCallStatus | null>(null)
const pendingSummary = ref<string | null>(null)
const confirming = ref(false)
const rejecting = ref(false)

const tools = ref<AiToolView[]>([])
const history = ref<ActHistoryView[]>([])
const historyLoading = ref(false)
/** 点历史条目时回放那一笔；不参与执行，也不显示确认按钮 */
const replay = ref<ActHistoryView | null>(null)

/** 页面展示的“这一笔”：本次新办的结果优先，其次是回放的历史 */
const view = computed(() => replay.value ?? result.value)

const canSubmit = computed(() => input.value.trim().length > 0 && !busy.value)

/** 模型答复可能带 **、列表等轻量 markdown，走同一套渲染与净化 */
const replyHtml = computed(() => renderMarkdown(view.value?.reply ?? ''))

/** 确认单已处理（执行/放弃）后，“还没生效”这句提示就该消失 */
const showOutcomeNote = computed(() => {
  if (replay.value) return false
  const note = outcomeNote(result.value?.outcome)
  if (!note) return false
  if (result.value?.outcome === 'WAITING_CONFIRM') return pendingStatus.value === 'PENDING_CONFIRM'
  return true
})

// ---------------- 猜你想问 ----------------

/**
 * 例子按工具生成，而不是写死一串：账号没有的工具就不出现对应的句子，
 * 学生不会看到“帮我排课”，教师也不会看到“帮我交请假条”。
 */
const EXAMPLE_BY_TOOL: Array<[string, string]> = [
  ['getMySchedule', '我这学期有哪些课？'],
  ['searchAvailableCourses', '这学期还有什么课可以选？'],
  ['getCourseDetails', '计算机导论现在多少人选了？'],
  ['searchKnowledge', '图书馆几点开门？'],
  ['listKnowledgeDocuments', '选课手册里都有哪些文档？'],
  ['getKnowledgeDocument', '选课规则里写了什么？'],
  ['searchForumPosts', '论坛里有人讨论补退选吗？'],
  ['createForumPost', '帮我发一条帖子，问问自习室插座'],
  ['createForumComment', '帮我给补退选那条帖子回一句'],
  ['updateForumPost', '帮我改一下我那条帖子的标题'],
  ['deleteForumPost', '把我那条重复的帖子删掉'],
  ['createCourse', '帮我新建一门课：CS301 编译原理，3 学分，60 人'],
  ['updateCourse', '把编译原理的容量改成 80'],
  ['updateCourseSchedule', '帮我把大学物理排到周四 7-8 节，教室 B201'],
]

const FALLBACK_EXAMPLES = [
  '我这学期有哪些课？',
  '图书馆几点开门？',
  '论坛里有人讨论补退选吗？',
  '帮我发一条帖子，问问自习室插座',
]

const guesses = computed(() => {
  const names = new Set(tools.value.map((t) => t.name))
  const picked = EXAMPLE_BY_TOOL.filter(([name]) => names.has(name)).map(([, text]) => text)
  return picked.length ? picked.slice(0, 6) : FALLBACK_EXAMPLES
})

const guessIndex = ref(0)
const currentGuess = computed(() => guesses.value[guessIndex.value % guesses.value.length] ?? '')

let guessTimer: number | undefined

/** 5 秒换一条；正在办、或用户已经在输入时不打扰 */
function scheduleRotate() {
  guessTimer = window.setInterval(() => {
    if (busy.value || input.value.trim() || guesses.value.length <= 1) return
    guessIndex.value = (guessIndex.value + 1) % guesses.value.length
  }, 5000)
}

watch(guesses, () => {
  guessIndex.value = 0
})

function useGuess(text: string) {
  input.value = text
  void nextTick(() => inputEl.value?.focus())
}

// ---------------- 提交与确认 ----------------

async function submit() {
  if (!canSubmit.value) return
  busy.value = true
  error.value = null
  result.value = null
  replay.value = null
  pendingStatus.value = null
  pendingSummary.value = null
  try {
    const response = await aiApi.act(input.value.trim())
    result.value = response
    pendingStatus.value = response.pending?.status ?? null
    await loadHistory()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}

async function confirmPending() {
  const pending = result.value?.pending
  if (!pending) return
  confirming.value = true
  error.value = null
  try {
    const updated = await aiApi.confirmTool(pending.id)
    pendingStatus.value = updated.status
    pendingSummary.value = updated.resultSummary
    await loadHistory()
  } catch (e) {
    error.value = errorMessage(e)
    // 过期/已被处理：重新取一次真实状态，避免界面停在旧状态
    try {
      const fresh = await aiApi.toolCall(pending.id)
      pendingStatus.value = fresh.status
      pendingSummary.value = fresh.resultSummary
    } catch {
      /* 保持原状 */
    }
    await loadHistory()
  } finally {
    confirming.value = false
  }
}

async function rejectPending() {
  const pending = result.value?.pending
  if (!pending) return
  rejecting.value = true
  error.value = null
  try {
    const updated = await aiApi.rejectTool(pending.id, '用户放弃执行')
    pendingStatus.value = updated.status
    await loadHistory()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    rejecting.value = false
  }
}

// ---------------- 历史 ----------------

async function loadHistory() {
  historyLoading.value = true
  try {
    history.value = (await aiApi.actHistory(1, 8)).items
  } catch {
    // 历史只是参考，取不到不影响办事
  } finally {
    historyLoading.value = false
  }
}

function openHistory(item: ActHistoryView) {
  replay.value = item
  busy.value = false
  error.value = null
}

function againFromHistory() {
  if (!replay.value) return
  input.value = replay.value.message
  replay.value = null
  result.value = null
  void nextTick(() => inputEl.value?.focus())
}

function historyTone(item: ActHistoryView): Tone {
  const status = item.pendingStatus
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'PENDING_CONFIRM') return 'warning'
  if (status === 'REJECTED' || status === 'EXPIRED') return 'muted'
  if (item.outcome === 'MAX_ROUNDS') return 'ochre'
  if (item.outcome === 'RAW_TEXT') return 'muted'
  return 'teal'
}

function historyLabel(item: ActHistoryView): string {
  const status = item.pendingStatus
  if (status === 'SUCCEEDED') return '已执行'
  if (status === 'PENDING_CONFIRM') return '待确认'
  if (status === 'REJECTED') return '已放弃'
  if (status === 'EXPIRED') return '已过期'
  if (item.outcome === 'MAX_ROUNDS') return '未完成'
  if (item.outcome === 'RAW_TEXT') return '未执行'
  return '已答复'
}

function historyTime(item: ActHistoryView): string {
  return formatShort(item.createdAt, Date.now())
}

// ---------------- 展示辅助 ----------------

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

/** 参数按 key=value 平铺，长值截断；确认单里用同样的数据，保证“看到的”就是“要执行的” */
function argsText(args: Record<string, unknown> | null): string {
  if (!args) return ''
  return Object.entries(args)
    .filter(([, v]) => v !== null && v !== undefined && v !== '')
    .map(([k, v]) => {
      const text = typeof v === 'object' ? JSON.stringify(v) : String(v)
      return `${k}=${text.length > 32 ? text.slice(0, 32) + '…' : text}`
    })
    .join('  ')
}

function stepTone(step: AgentStep): Tone {
  if (step.status === 'FAILED') return 'ochre'
  if (step.status === 'PENDING_CONFIRM') return 'warning'
  return step.sideEffect ? 'warning' : 'teal'
}

function stepLabel(step: AgentStep): string {
  if (step.status === 'FAILED') return '失败'
  if (step.status === 'PENDING_CONFIRM') return '待确认'
  return '已执行'
}

function outcomeNote(outcome: AgentActResponse['outcome'] | undefined): string {
  if (outcome === 'WAITING_CONFIRM') return '下面这张确认单还没有生效，确认后才会真正写入。'
  if (outcome === 'MAX_ROUNDS') return '这件事需要多步操作，已经停下；建议拆成更具体的一句一句来。'
  if (outcome === 'RAW_TEXT') return '模型这次没有按约定给出指令，下面只是它的原话，没有任何动作被执行。'
  return ''
}

const writableTools = computed(() => tools.value.filter((t) => t.sideEffect))
const readableTools = computed(() => tools.value.filter((t) => !t.sideEffect))

onMounted(async () => {
  scheduleRotate()
  void loadHistory()
  try {
    tools.value = await aiApi.tools()
  } catch {
    // 目录只用于展示能力，取不到不影响办事
  }
})

onBeforeUnmount(() => {
  if (guessTimer) window.clearInterval(guessTimer)
})
</script>

<template>
  <div class="act" :class="{ 'act--embedded': embedded }">
    <UiPageHeader
      v-if="!embedded"
      label="办事"
      latin="Desk"
      title="说一句，把事办掉"
      lede="用日常说法讲清要办什么，它自己挑工具、填参数。凡是会改数据的动作，都会先出一张确认单——你点确认才生效。"
    />

    <div class="act__grid">
      <div class="act__main">
        <section class="desk">
          <label class="desk__label eyebrow" for="ask">要办的事</label>
          <textarea
            id="ask"
            ref="inputEl"
            v-model="input"
            class="desk__input"
            rows="2"
            maxlength="1000"
            placeholder="例如：把大学物理（上）加一条排课，周二第 9-10 节，1 到 16 周，教室 B105"
            :disabled="busy"
            @keydown.ctrl.enter.prevent="submit"
            @keydown.meta.enter.prevent="submit"
          />
          <div class="desk__foot">
            <p class="desk__tip">
              它能查能改，但不猜：缺课程 ID 会先去查，查不到就问你。<span class="mono">Ctrl</span> + 回车提交。
            </p>
            <UiButton variant="primary" :disabled="!canSubmit" :loading="busy" @click="submit">
              交给它办
            </UiButton>
          </div>

          <p v-if="currentGuess" class="guess">
            <span class="guess__label">猜你想问：</span>
            <Transition name="guess" mode="out-in">
              <button
                :key="currentGuess"
                type="button"
                class="guess__text"
                :disabled="busy"
                @click="useGuess(currentGuess)"
              >
                {{ currentGuess }}
              </button>
            </Transition>
          </p>
        </section>

        <p v-if="busy" class="working">正在判断该调用哪个工具，可能需要十几秒…</p>
        <p v-if="error" class="alert">{{ error }}</p>

        <template v-if="view">
          <p v-if="replay" class="replay">
            <span class="replay__label">历史记录</span>
            <span class="replay__time">{{ formatDateTime(replay.createdAt) }}</span>
            <button type="button" class="replay__again" @click="againFromHistory">按原话再办一次</button>
          </p>

          <section v-if="view.steps.length" class="trail">
            <p class="trail__title eyebrow">经过</p>
            <ol class="steps">
              <li v-for="step in view.steps" :key="`${step.roundNo}-${step.toolName}`" class="step">
                <span class="step__no mono">{{ pad(step.roundNo) }}</span>
                <div class="step__body">
                  <p class="step__head">
                    <span class="step__title">{{ step.title }}</span>
                    <span class="step__tool mono">{{ step.toolName }}</span>
                  </p>
                  <p v-if="argsText(step.arguments)" class="step__args mono">{{ argsText(step.arguments) }}</p>
                  <p v-if="step.summary" class="step__note">{{ step.summary }}</p>
                  <p v-else-if="step.errorMessage" class="step__error">{{ step.errorMessage }}</p>
                </div>
                <UiTag :tone="stepTone(step)">{{ stepLabel(step) }}</UiTag>
              </li>
            </ol>
          </section>

          <section class="answer">
            <p class="answer__label eyebrow">答复</p>
            <div class="answer__text prose" v-html="replyHtml" />
            <p v-if="showOutcomeNote" class="answer__aside">{{ outcomeNote(result?.outcome) }}</p>
          </section>

          <section
            v-if="result?.pending"
            class="chit"
            :class="{ 'is-settled': pendingStatus !== 'PENDING_CONFIRM' }"
          >
            <div class="chit__head">
              <p class="chit__label eyebrow">确认单 · {{ result.pending.toolName }}</p>
              <UiTag :tone="pendingStatus === 'PENDING_CONFIRM' ? 'warning' : 'success'">
                {{
                  pendingStatus === 'PENDING_CONFIRM'
                    ? '待你确认'
                    : pendingStatus === 'REJECTED'
                      ? '已放弃'
                      : pendingStatus === 'SUCCEEDED'
                        ? '已执行'
                        : '未生效'
                }}
              </UiTag>
            </div>
            <p class="chit__preview">{{ result.pending.preview }}</p>

            <dl class="chit__args">
              <template v-for="(value, key) in result.pending.arguments ?? {}" :key="key">
                <dt class="mono">{{ key }}</dt>
                <dd class="mono">{{ value }}</dd>
              </template>
            </dl>

            <p
              v-if="pendingStatus === 'PENDING_CONFIRM' && result.pending.confirmExpiresAt"
              class="chit__ttl"
            >
              请在 {{ formatDateTime(result.pending.confirmExpiresAt) }} 前确认，过期这张单作废。
            </p>

            <div v-if="pendingStatus === 'PENDING_CONFIRM'" class="chit__actions">
              <UiButton variant="primary" :loading="confirming" @click="confirmPending">确认执行</UiButton>
              <UiButton :disabled="confirming || rejecting" :loading="rejecting" @click="rejectPending">
                放弃
              </UiButton>
            </div>
            <p v-if="pendingStatus === 'SUCCEEDED'" class="chit__done">
              已执行{{ pendingSummary ? `：${pendingSummary}` : '。' }}
            </p>
            <p v-else-if="pendingStatus === 'REJECTED'" class="chit__done">已放弃，没有改动任何数据。</p>
          </section>
        </template>

        <section v-if="!view && tools.length" class="scope">
          <p class="scope__title eyebrow">这个账号现在能办的事</p>
          <div class="scope__cols">
            <div class="scope__col">
              <p class="scope__sub">可以查</p>
              <ul class="scope__list">
                <li v-for="t in readableTools" :key="t.name">{{ t.title }}</li>
              </ul>
              <p v-if="!readableTools.length" class="scope__none">没有可用的查询。</p>
            </div>
            <div class="scope__col">
              <p class="scope__sub">可以改（需确认）</p>
              <ul class="scope__list">
                <li v-for="t in writableTools" :key="t.name">{{ t.title }}</li>
              </ul>
              <p v-if="!writableTools.length" class="scope__none">
                当前账号没有写权限，只能查；需要开课或排课请用教师账号。
              </p>
            </div>
          </div>
        </section>
      </div>

      <aside class="rail" aria-label="办事历史">
        <div class="rail__head">
          <p class="rail__title eyebrow">最近办过的事</p>
          <span v-if="history.length" class="rail__count mono">{{ history.length }}</span>
        </div>
        <p v-if="historyLoading && !history.length" class="rail__msg">载入中…</p>
        <p v-else-if="!history.length" class="rail__msg">还没有办过事。左边说一句，就是第一件。</p>
        <ol v-else class="rail__list">
          <li v-for="item in history" :key="item.id">
            <button
              type="button"
              class="rail__item"
              :class="{ 'is-current': replay?.id === item.id }"
              :title="item.message"
              @click="openHistory(item)"
            >
              <span class="rail__text">{{ item.message }}</span>
              <span class="rail__meta">
                <span class="rail__time mono">{{ historyTime(item) }}</span>
                <UiTag :tone="historyTone(item)">{{ historyLabel(item) }}</UiTag>
              </span>
            </button>
          </li>
        </ol>
        <p v-if="history.length" class="rail__note">点一条可回看当时的经过；回看不会再次执行。</p>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.act {
  width: 100%;
  max-width: 1140px;
  margin: 0 auto;
  padding: var(--s-5) clamp(var(--s-4), 4vw, var(--s-6)) var(--s-7);
}

/* 嵌入到问答页时，外层已经有内边距，这里不再叠加 */
.act--embedded {
  max-width: none;
  padding: 0;
}

.act__grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 240px;
  gap: clamp(var(--s-4), 4vw, var(--s-6));
  align-items: start;
}

.act__main {
  min-width: 0;
}

/* ---------- 输入 ---------- */
.desk {
  padding-bottom: var(--s-4);
  border-bottom: var(--hairline);
}

.desk__label {
  display: block;
  margin: 0 0 var(--s-2);
  color: var(--ink-2);
}

.desk__input {
  display: block;
  width: 100%;
  padding: var(--s-2) 0;
  border: 0;
  border-bottom: 1px solid var(--ink);
  background: transparent;
  color: var(--ink);
  font-family: var(--font-serif-cjk);
  font-size: var(--fs-read);
  line-height: 1.6;
  resize: vertical;
}

.desk__input:focus {
  outline: none;
  border-bottom-color: var(--teal);
}

.desk__input::placeholder {
  color: var(--ink-2);
  font-size: 15px;
}

.desk__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  margin-top: var(--s-3);
}

.desk__tip {
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.6;
}

/* ---------- 猜你想问 ---------- */
.guess {
  display: flex;
  align-items: baseline;
  gap: var(--s-1);
  margin: var(--s-3) 0 0;
  min-height: 20px;
  font-size: var(--fs-small);
  line-height: 1.6;
}

.guess__label {
  flex: 0 0 auto;
  color: var(--ink-2);
}

.guess__text {
  padding: 0;
  border: 0;
  background: none;
  color: var(--teal);
  font-size: inherit;
  text-align: left;
  text-decoration: underline;
  text-decoration-color: color-mix(in srgb, var(--teal) 35%, transparent);
  text-underline-offset: 3px;
  cursor: pointer;
}

.guess__text:hover:not(:disabled) {
  text-decoration-color: var(--teal);
}

.guess-enter-active,
.guess-leave-active {
  transition: opacity var(--t-fast) var(--ease), transform var(--t-fast) var(--ease);
}

.guess-enter-from {
  opacity: 0;
  transform: translateY(4px);
}

.guess-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

/* ---------- 状态 ---------- */
.working,
.alert {
  margin: var(--s-4) 0 0;
  padding: var(--s-2) 0 var(--s-2) var(--s-3);
  border-left: 2px solid var(--rule-strong);
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.7;
}

.alert {
  border-left-color: var(--ochre);
  color: var(--ochre);
}

.replay {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: var(--s-2);
  margin: var(--s-4) 0 0;
  padding: var(--s-2) 0 var(--s-2) var(--s-3);
  border-left: 2px solid var(--rule-strong);
  font-size: var(--fs-small);
  color: var(--ink-2);
}

.replay__label {
  color: var(--ink);
}

.replay__again {
  padding: 0;
  border: 0;
  background: none;
  color: var(--teal);
  font-size: inherit;
  text-decoration: underline;
  text-underline-offset: 3px;
  cursor: pointer;
}

/* ---------- 经过 ---------- */
.trail {
  margin-top: var(--s-5);
}

.trail__title {
  margin: 0 0 var(--s-3);
  color: var(--ink-2);
}

.steps {
  margin: 0;
  padding: 0;
  list-style: none;
  border-top: 1px solid var(--ink);
}

.step {
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr) auto;
  gap: var(--s-3);
  align-items: start;
  padding: var(--s-3) 0;
  border-bottom: var(--hairline);
}

.step__no {
  color: var(--ink-2);
  font-size: 11px;
  letter-spacing: 0.08em;
  padding-top: 3px;
}

.step__head {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: var(--s-2);
  margin: 0;
}

.step__title {
  font-family: var(--font-serif-cjk);
  font-size: 16px;
}

.step__tool {
  color: var(--ink-2);
  font-size: 11px;
}

.step__args {
  margin: 2px 0 0;
  color: var(--ink-2);
  font-size: 11px;
  line-height: 1.6;
  word-break: break-all;
}

.step__note {
  margin: var(--s-1) 0 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.6;
}

.step__error {
  margin: var(--s-1) 0 0;
  color: var(--ochre);
  font-size: var(--fs-small);
  line-height: 1.6;
}

/* ---------- 答复 ---------- */
.answer {
  margin-top: var(--s-5);
}

.answer__label {
  margin: 0 0 var(--s-2);
  color: var(--ink-2);
}

.answer__text {
  font-family: var(--font-serif-cjk);
  font-size: var(--fs-read);
  line-height: var(--lh-read);
}

.answer__text :deep(> :first-child) {
  margin-top: 0;
}

.answer__text :deep(> :last-child) {
  margin-bottom: 0;
}

.answer__aside {
  margin: var(--s-2) 0 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.7;
}

/* ---------- 确认单 ---------- */
.chit {
  margin-top: var(--s-5);
  padding: var(--s-4);
  border: 1px solid var(--ink);
  background: var(--paper-lift);
}

.chit.is-settled {
  border-color: var(--rule);
}

.chit__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-3);
}

.chit__label {
  margin: 0;
  color: var(--teal);
}

.chit__preview {
  margin: var(--s-3) 0 0;
  font-family: var(--font-serif-cjk);
  font-size: 16px;
  line-height: 1.7;
}

.chit__args {
  display: grid;
  grid-template-columns: max-content max-content;
  gap: 2px var(--s-3);
  margin: var(--s-3) 0 0;
  padding: var(--s-2) 0;
  border-top: var(--hairline);
  font-size: 11px;
}

.chit__args dt {
  color: var(--ink-2);
}

.chit__args dd {
  margin: 0;
  max-width: 320px;
  word-break: break-all;
}

.chit__ttl {
  margin: var(--s-2) 0 0;
  color: var(--ink-2);
  font-size: 11px;
}

.chit__actions {
  display: flex;
  gap: var(--s-2);
  margin-top: var(--s-3);
}

.chit__done {
  margin: var(--s-3) 0 0;
  color: var(--success);
  font-size: var(--fs-small);
}

/* ---------- 能力清单 ---------- */
/* 分隔线只留输入区底部那一条，这里不再画第二条 */
.scope {
  margin-top: var(--s-5);
}

.scope__title {
  margin: 0 0 var(--s-3);
  color: var(--ink-2);
}

.scope__cols {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--s-5);
}

.scope__sub {
  margin: 0;
  color: var(--ink);
  font-family: var(--font-serif-cjk);
  font-size: 16px;
}

.scope__list {
  margin: var(--s-2) 0 0;
  padding: 0;
  list-style: none;
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 2;
}

.scope__none {
  margin: var(--s-2) 0 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.7;
}

/* ---------- 历史（页边栏） ---------- */
.rail {
  position: sticky;
  top: calc(var(--masthead-h) + var(--s-4));
  padding-left: var(--s-3);
  border-left: var(--hairline);
}

.rail__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-2);
  padding-bottom: var(--s-2);
  border-bottom: 1px solid var(--ink);
}

.rail__title {
  margin: 0;
  color: var(--ink-2);
}

.rail__count {
  color: var(--ink-2);
  font-size: 11px;
}

.rail__msg {
  margin: var(--s-3) 0 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.7;
}

.rail__list {
  margin: 0;
  padding: 0;
  list-style: none;
  max-height: calc(100vh - var(--masthead-h) - 220px);
  overflow-y: auto;
}

.rail__item {
  display: block;
  width: 100%;
  padding: var(--s-2) 0;
  border: 0;
  border-bottom: var(--hairline);
  background: none;
  text-align: left;
  cursor: pointer;
}

.rail__text {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  color: var(--ink);
  font-size: var(--fs-small);
  line-height: 1.6;
}

.rail__meta {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  margin-top: 2px;
}

.rail__time {
  color: var(--ink-2);
  font-size: 11px;
}

.rail__item:hover .rail__text,
.rail__item.is-current .rail__text {
  text-decoration: underline;
  text-underline-offset: 3px;
  text-decoration-color: var(--rule-strong);
}

.rail__item.is-current .rail__time {
  color: var(--teal);
}

.rail__note {
  margin: var(--s-3) 0 0;
  color: var(--ink-2);
  font-size: 11px;
  line-height: 1.7;
}

@media (max-width: 980px) {
  .act__grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .rail {
    position: static;
    padding-left: 0;
    padding-top: var(--s-4);
    border-left: 0;
    border-top: var(--hairline);
  }

  .rail__list {
    max-height: none;
  }
}

@media (max-width: 720px) {
  .desk__foot {
    flex-direction: column;
    align-items: stretch;
    gap: var(--s-2);
  }

  .scope__cols {
    grid-template-columns: minmax(0, 1fr);
    gap: var(--s-4);
  }

  .step {
    grid-template-columns: 22px minmax(0, 1fr);
    row-gap: var(--s-2);
  }

  .step :deep(.tag) {
    grid-column: 2;
    justify-self: start;
  }
}
</style>
