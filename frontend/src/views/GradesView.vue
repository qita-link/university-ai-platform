<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import { courseApi, evaluationApi, gradeApi } from '@/api/endpoints'
import type {
  CourseSummary,
  EvaluationOverview,
  EvaluationTaskView,
  GradeBookView,
  GradeRuleView,
  TranscriptView,
} from '@/api/types'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { formatDateTime } from '@/lib/time'
import { GRADE_STATUS_LABEL, scoreWord } from '@/lib/labels'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiEmpty from '@/components/ui/UiEmpty.vue'
import UiPageHeader from '@/components/ui/UiPageHeader.vue'
import UiSelect from '@/components/ui/UiSelect.vue'
import UiTag from '@/components/ui/UiTag.vue'
import UiTextarea from '@/components/ui/UiTextarea.vue'

/**
 * 成绩与评教：
 * 学生看成绩单并给老师打分；教师录成绩、看本人课程的评教结果。
 * 所有「能不能看/能不能写」都由后端判定，前端只决定展示哪一块。
 */
const auth = useAuthStore()
const toast = useToastStore()
const route = useRoute()
const router = useRouter()

type TabKey = 'mine' | 'evaluate' | 'reviews' | 'record'

const isStudentRole = computed(() => (auth.user?.roles ?? []).some((r) => r === 'STUDENT' || r === 'STUDENT_UNION'))
const tabs = computed(() => {
  const list: { key: TabKey; label: string }[] = []
  if (auth.can('grade:read') && isStudentRole.value) list.push({ key: 'mine', label: '我的成绩' })
  if (auth.can('evaluation:submit')) list.push({ key: 'evaluate', label: '评教' })
  if (auth.can('evaluation:read')) list.push({ key: 'reviews', label: '评教结果' })
  if (auth.can('grade:manage', 'grade:manage_all')) list.push({ key: 'record', label: '成绩录入' })
  if (auth.can('grade:manage_all') && !list.some((t) => t.key === 'mine')) {
    list.unshift({ key: 'mine', label: '我的成绩' })
  }
  return list
})

const active = ref<TabKey>('mine')
const loading = ref(false)
const loadError = ref<string | null>(null)

function pickTab() {
  const q = String(route.query.tab ?? '') as TabKey
  active.value = tabs.value.some((t) => t.key === q) ? q : (tabs.value[0]?.key ?? 'mine')
}

function go(key: TabKey) {
  active.value = key
  void router.replace({ query: { ...route.query, tab: key } })
}

/* ---------------- 我的成绩 ---------------- */

const transcript = ref<TranscriptView | null>(null)
const rules = ref<GradeRuleView[]>([])
const showRules = ref(false)

async function loadMine() {
  const [t, r] = await Promise.all([gradeApi.me(), gradeApi.rules().catch(() => [])])
  transcript.value = t
  rules.value = r
}

/* ---------------- 评教 ---------------- */

const tasks = ref<EvaluationTaskView[]>([])
const evaluateTarget = ref<EvaluationTaskView | null>(null)
const evaluateBusy = ref(false)
const scores = ref({ teaching: 5, content: 5, interaction: 5, workload: 5 })
const evalComment = ref('')
const anonymous = ref(true)

async function loadTasks() {
  tasks.value = await evaluationApi.tasks()
}

function openEvaluate(task: EvaluationTaskView) {
  evaluateTarget.value = task
  scores.value = { teaching: 5, content: 5, interaction: 5, workload: 5 }
  evalComment.value = ''
  anonymous.value = true
}

const ratingRows = computed(() => [
  { key: 'teaching' as const, label: '教学态度与方法', hint: '讲解是否清楚、备课是否充分' },
  { key: 'content' as const, label: '内容与收获', hint: '这门课是否让你真正学到东西' },
  { key: 'interaction' as const, label: '互动与答疑', hint: '提问、讨论与课后答疑' },
  { key: 'workload' as const, label: '作业与考核', hint: '作业量与考核方式是否合理' },
])

async function submitEvaluation() {
  if (!evaluateTarget.value) return
  evaluateBusy.value = true
  try {
    await evaluationApi.submit({
      courseId: evaluateTarget.value.courseId,
      teacherId: evaluateTarget.value.teacherId,
      scoreTeaching: scores.value.teaching,
      scoreContent: scores.value.content,
      scoreInteraction: scores.value.interaction,
      scoreWorkload: scores.value.workload,
      comment: evalComment.value.trim() || null,
      anonymous: anonymous.value,
    })
    evaluateTarget.value = null
    toast.push('评教已提交，谢谢你的认真', 'success')
    await loadTasks()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    evaluateBusy.value = false
  }
}

/* ---------------- 评教结果（教师） ---------------- */

const overview = ref<EvaluationOverview | null>(null)

async function loadOverview() {
  overview.value = await evaluationApi.mine()
}

/* ---------------- 成绩录入 ---------------- */

const courses = ref<CourseSummary[]>([])
const courseId = ref('')
const book = ref<GradeBookView | null>(null)
const bookBusy = ref(false)
const draft = ref<Record<number, { score: string; remark: string }>>({})
const publishOpen = ref(false)
const publishBusy = ref(false)

const courseOptions = computed(() =>
  courses.value.map((c) => ({
    value: String(c.id),
    label: `${c.semesterName} · ${c.name}（${c.code}）`,
  })),
)

async function loadCourses() {
  const query = auth.can('grade:manage_all') ? {} : { teacherId: auth.user?.userId }
  const res = await courseApi.list({ ...query, size: 100 })
  courses.value = res.items
  const first = courses.value[0]
  if (!courseId.value && first) {
    courseId.value = String(first.id)
  }
}

async function loadBook() {
  if (!courseId.value) return
  bookBusy.value = true
  try {
    const res = await gradeApi.gradeBook(Number(courseId.value))
    book.value = res
    const next: Record<number, { score: string; remark: string }> = {}
    for (const item of res.items) {
      next[item.studentId] = {
        score: item.score === null ? '' : String(item.score),
        remark: item.remark ?? '',
      }
    }
    draft.value = next
  } finally {
    bookBusy.value = false
  }
}

/** 表格输入用：确保每一行都有可绑定的对象 */
function cell(studentId: number): { score: string; remark: string } {
  if (!draft.value[studentId]) draft.value[studentId] = { score: '', remark: '' }
  return draft.value[studentId]
}

function pointOf(scoreText: string): string {
  const score = Number(scoreText)
  if (!scoreText || Number.isNaN(score)) return '—'
  const hit = rules.value.find((r) => score >= r.minScore && (score < r.maxScore || score === 100))
  return hit ? hit.gradePoint.toFixed(1) : '—'
}

const filledCount = computed(
  () => Object.values(draft.value).filter((d) => d.score !== '' && !Number.isNaN(Number(d.score))).length,
)

async function saveDraft() {
  if (!book.value) return
  const items = Object.entries(draft.value).map(([id, d]) => {
    const text = d.score.trim()
    if (text === '') return { studentId: Number(id), score: null, remark: d.remark.trim() || null }
    const value = Number(text)
    if (Number.isNaN(value) || value < 0 || value > 100) {
      throw new Error('成绩需在 0~100 之间')
    }
    return { studentId: Number(id), score: value, remark: d.remark.trim() || null }
  })
  bookBusy.value = true
  try {
    const res = await gradeApi.save(book.value.courseId, items)
    book.value = res
    toast.push('已保存为草稿，学生暂时看不到', 'success')
  } catch (e) {
    toast.push(e instanceof Error && e.message.includes('0~100') ? e.message : errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    bookBusy.value = false
  }
}

async function publish() {
  if (!book.value) return
  publishBusy.value = true
  try {
    const res = await gradeApi.publish(book.value.courseId)
    book.value = res
    publishOpen.value = false
    toast.push('成绩已发布，学生现在可以看到', 'success')
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    publishBusy.value = false
  }
}

/* ---------------- 生命周期 ---------------- */

async function load() {
  loading.value = true
  loadError.value = null
  try {
    if (active.value === 'mine') await loadMine()
    else if (active.value === 'evaluate') await Promise.all([loadTasks(), loadMine().catch(() => undefined)])
    else if (active.value === 'reviews') {
      await Promise.all([loadOverview(), loadMine().catch(() => undefined)])
    } else if (active.value === 'record') {
      await Promise.all([loadCourses(), gradeApi.rules().then((r) => (rules.value = r)).catch(() => undefined)])
      await loadBook()
    }
  } catch (e) {
    loadError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  pickTab()
  void load()
})

watch(
  () => route.query.tab,
  () => {
    pickTab()
  },
)

watch(active, () => {
  void load()
})

watch(courseId, () => {
  if (active.value === 'record') void loadBook()
})
</script>

<template>
  <div class="grades">
    <UiPageHeader
      label="成绩"
      latin="Marks"
      title="成绩与评教"
      lede="成绩由任课教师录入并发布，绩点按学分加权；评教匿名，只把结果交给老师本人。"
    >
      <template #meta>
        <span v-if="transcript" class="mono">绩点 {{ transcript.overall.gpa.toFixed(2) }}</span>
        <span v-if="tasks.length" class="mono">待评 {{ tasks.filter((t) => !t.evaluated).length }} 门</span>
      </template>
    </UiPageHeader>

    <div class="bar">
      <div class="tabs" role="tablist" aria-label="成绩视图">
        <button
          v-for="t in tabs"
          :key="t.key"
          type="button"
          class="tabs__btn"
          role="tab"
          :aria-selected="active === t.key"
          :class="{ 'is-active': active === t.key }"
          @click="go(t.key)"
        >
          {{ t.label }}
        </button>
      </div>
      <UiButton v-if="active === 'mine' && rules.length" size="sm" variant="link" @click="showRules = !showRules">
        {{ showRules ? '收起绩点规则' : '绩点怎么算' }}
      </UiButton>
    </div>

    <p v-if="loading" class="state">正在取成绩…</p>
    <p v-else-if="loadError" class="state state--err" role="alert">{{ loadError }}</p>

    <template v-else>
      <!-- 我的成绩 -->
      <template v-if="active === 'mine'">
        <div v-if="showRules" class="rules">
          <p class="rules__title">绩点换算（{{ rules[0]?.name ?? '4.0 制' }}）</p>
          <div class="rules__grid">
            <span v-for="r in rules" :key="r.id" class="rules__item mono">
              {{ r.minScore }}–{{ r.maxScore }} → {{ r.gradePoint.toFixed(1) }} {{ r.gradeLevel }}
            </span>
          </div>
        </div>

        <UiEmpty
          v-if="!transcript || transcript.semesters.length === 0"
          title="还没有已发布的成绩"
          hint="教师发布后，这里会按学期列出成绩、绩点与加权平均分。"
        />

        <template v-else>
          <div class="summary">
            <div class="summary__cell">
              <p class="summary__label">总平均学分绩点</p>
              <p class="summary__value">{{ transcript.overall.gpa.toFixed(2) }}</p>
            </div>
            <div class="summary__cell">
              <p class="summary__label">加权平均分</p>
              <p class="summary__value">{{ transcript.overall.weightedAverage.toFixed(1) }}</p>
            </div>
            <div class="summary__cell">
              <p class="summary__label">已获学分</p>
              <p class="summary__value">{{ transcript.overall.earnedCredits.toFixed(1) }}</p>
            </div>
            <div class="summary__cell">
              <p class="summary__label">课程门数</p>
              <p class="summary__value">{{ transcript.overall.courseCount }}</p>
            </div>
          </div>

          <section v-for="sem in transcript.semesters" :key="sem.semesterId" class="semester">
            <header class="semester__head">
              <h2 class="semester__name">{{ sem.semesterName ?? sem.semesterCode ?? '学期' }}</h2>
              <p class="semester__meta mono">
                绩点 {{ sem.summary.gpa.toFixed(2) }} · 均分 {{ sem.summary.weightedAverage.toFixed(1) }} ·
                {{ sem.grades.length }} 门
              </p>
            </header>
            <div class="table-scroll">
              <table class="table">
              <thead>
                <tr>
                  <th scope="col">课程</th>
                  <th scope="col" class="num">学分</th>
                  <th scope="col" class="num">成绩</th>
                  <th scope="col" class="num">绩点</th>
                  <th scope="col" class="col-optional">等级</th>
                  <th scope="col" class="col-optional">备注</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="g in sem.grades" :key="g.id">
                  <td>
                    <span class="cell-name">{{ g.courseName }}</span>
                    <span class="cell-code mono">{{ g.courseCode }}</span>
                  </td>
                  <td class="num mono">{{ g.credit.toFixed(1) }}</td>
                  <td class="num score mono">{{ g.score ?? '—' }}</td>
                  <td class="num mono">{{ g.gradePoint?.toFixed(1) ?? '—' }}</td>
                  <td class="mono col-optional">{{ g.gradeLevel ?? '—' }}</td>
                  <td class="muted col-optional">{{ g.remark || '—' }}</td>
                </tr>
              </tbody>
            </table>
            </div>
          </section>
        </template>
      </template>

      <!-- 评教 -->
      <template v-else-if="active === 'evaluate'">
        <UiEmpty
          v-if="tasks.length === 0"
          title="没有可评的课程"
          hint="只有本人已选课程才出现在这里；选课后再来。"
        />
        <ul v-else class="list">
          <li v-for="t in tasks" :key="`${t.courseId}-${t.teacherId}`" class="item">
            <div>
              <p class="item__title">
                {{ t.courseName }}<span class="item__code mono">{{ t.courseCode }}</span>
              </p>
              <p class="item__meta">
                {{ t.teacherName ?? '教师' }} · {{ t.semesterName ?? '本学期' }}
                <span v-if="t.evaluated" class="item__done mono">已评 {{ t.myScore?.toFixed(1) }}</span>
              </p>
            </div>
            <UiButton v-if="!t.evaluated" size="sm" variant="primary" @click="openEvaluate(t)">去评教</UiButton>
            <UiTag v-else tone="success">已完成</UiTag>
          </li>
        </ul>
      </template>

      <!-- 评教结果 -->
      <template v-else-if="active === 'reviews'">
        <UiEmpty
          v-if="!overview || overview.courses.length === 0"
          title="还没有评教数据"
          hint="学生提交后，这里会显示每门课的平均分与匿名留言。"
        />
        <template v-else>
          <p class="overview-line">
            共 <span class="mono">{{ overview.totalCount }}</span> 份评教，总平均
            <span class="overview-score mono">{{ overview.avgTotal.toFixed(2) }}</span> / 5
          </p>
          <section v-for="c in overview.courses" :key="c.courseId" class="review">
            <header class="review__head">
              <h2 class="review__name">{{ c.courseName }}</h2>
              <p class="mono review__total">{{ c.avgTotal.toFixed(2) }} 分 · {{ c.count }} 份</p>
            </header>
            <div class="dims">
              <div class="dim">
                <span class="dim__label">教学</span>
                <span class="dim__bar"><i :style="{ width: `${(c.avgTeaching / 5) * 100}%` }" /></span>
                <span class="dim__value mono">{{ c.avgTeaching.toFixed(1) }}</span>
              </div>
              <div class="dim">
                <span class="dim__label">内容</span>
                <span class="dim__bar"><i :style="{ width: `${(c.avgContent / 5) * 100}%` }" /></span>
                <span class="dim__value mono">{{ c.avgContent.toFixed(1) }}</span>
              </div>
              <div class="dim">
                <span class="dim__label">互动</span>
                <span class="dim__bar"><i :style="{ width: `${(c.avgInteraction / 5) * 100}%` }" /></span>
                <span class="dim__value mono">{{ c.avgInteraction.toFixed(1) }}</span>
              </div>
              <div class="dim">
                <span class="dim__label">考核</span>
                <span class="dim__bar"><i :style="{ width: `${(c.avgWorkload / 5) * 100}%` }" /></span>
                <span class="dim__value mono">{{ c.avgWorkload.toFixed(1) }}</span>
              </div>
            </div>
            <ul v-if="c.comments.length" class="comments">
              <li v-for="(cm, i) in c.comments" :key="i" class="comment">
                <p class="comment__text">{{ cm.comment }}</p>
                <p class="comment__meta mono">{{ cm.totalScore.toFixed(1) }} 分 · {{ formatDateTime(cm.createdAt) }}</p>
              </li>
            </ul>
            <p v-else class="muted small">这一门还没有留言。</p>
          </section>
        </template>
      </template>

      <!-- 成绩录入 -->
      <template v-else>
        <div class="record-bar">
          <UiSelect v-model="courseId" label="课程" :options="courseOptions" class="record-bar__select" />
          <UiTag v-if="book?.published" tone="success">已发布</UiTag>
        </div>

        <UiEmpty
          v-if="!book || book.items.length === 0"
          title="没有可录入的学生"
          hint="课程还没有学生选课，或你选的课程不属于你任教的课程。"
        />

        <template v-else>
          <p class="record-hint muted">
            已填 <span class="mono">{{ filledCount }}</span> / {{ book.items.length }} 人。
            成绩保存为草稿时学生看不到；发布后学生即可查看并计入绩点。
          </p>
          <div class="table-scroll">
            <table class="table table--entry">
            <thead>
              <tr>
                <th scope="col">学生</th>
                <th scope="col" class="col-optional">班级</th>
                <th scope="col" class="num">成绩</th>
                <th scope="col" class="num">绩点</th>
                <th scope="col" class="col-optional">备注</th>
                <th scope="col">状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in book.items" :key="item.studentId">
                <td>
                  <span class="cell-name">{{ item.displayName }}</span>
                  <span class="cell-code mono">{{ item.username }}</span>
                </td>
                <td class="muted col-optional">{{ item.orgUnitName ?? '—' }}</td>
                <td class="num">
                  <input
                    v-model="cell(item.studentId).score"
                    class="cell-input mono"
                    type="number"
                    min="0"
                    max="100"
                    step="0.5"
                    inputmode="decimal"
                    :aria-label="`${item.displayName} 的成绩`"
                  />
                </td>
                <td class="num mono">{{ pointOf(cell(item.studentId).score) }}</td>
                <td class="col-optional">
                  <input
                    v-model="cell(item.studentId).remark"
                    class="cell-input cell-input--wide"
                    maxlength="200"
                    placeholder="可空"
                    :aria-label="`${item.displayName} 的备注`"
                  />
                </td>
                <td>
                  <UiTag :tone="item.status === 'PUBLISHED' ? 'success' : 'muted'">
                    {{ GRADE_STATUS_LABEL[item.status] }}
                  </UiTag>
                </td>
              </tr>
            </tbody>
          </table>
          </div>

          <div class="record-actions">
            <UiButton :loading="bookBusy" @click="saveDraft">保存草稿</UiButton>
            <UiButton variant="primary" :disabled="filledCount === 0" @click="publishOpen = true">
              发布成绩
            </UiButton>
          </div>
        </template>
      </template>
    </template>

    <!-- 评教对话框 -->
    <UiDialog
      :open="!!evaluateTarget"
      :title="`评价 ${evaluateTarget?.teacherName ?? '任课教师'}`"
      :eyebrow="evaluateTarget?.courseName ?? ''"
      width="md"
      :dismissible="!evaluateBusy"
      @close="evaluateTarget = null"
    >
      <div class="eval">
        <div v-for="row in ratingRows" :key="row.key" class="eval__row">
          <div class="eval__text">
            <p class="eval__label">{{ row.label }}</p>
            <p class="eval__hint">{{ row.hint }}</p>
          </div>
          <div class="rating" role="group" :aria-label="row.label">
            <button
              v-for="n in 5"
              :key="n"
              type="button"
              class="rating__dot"
              :class="{ 'is-on': scores[row.key] === n }"
              :aria-label="`${n} 分`"
              :aria-pressed="scores[row.key] === n"
              @click="scores[row.key] = n"
            >
              {{ n }}
            </button>
            <span class="rating__word">{{ scoreWord(scores[row.key]) }}</span>
          </div>
        </div>
        <UiTextarea
          v-model="evalComment"
          label="想对老师说的话（可空）"
          :rows="3"
          :maxlength="500"
          hint="留言不会显示你的姓名。"
        />
        <label class="check">
          <input v-model="anonymous" type="checkbox" />
          <span>匿名提交（推荐）</span>
        </label>
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="evaluateBusy" @click="evaluateTarget = null">取消</UiButton>
        <UiButton variant="primary" :loading="evaluateBusy" @click="submitEvaluation">提交评教</UiButton>
      </template>
    </UiDialog>

    <!-- 发布确认 -->
    <UiDialog
      :open="publishOpen"
      title="发布这门课的成绩？"
      :eyebrow="book?.courseName ?? ''"
      width="sm"
      :dismissible="!publishBusy"
      @close="publishOpen = false"
    >
      <p class="publish-note">
        发布后 {{ filledCount }} 名学生可以看到成绩与绩点，教师本人不能再修改；如需更正请联系管理员。
      </p>
      <template #footer>
        <UiButton variant="link" :disabled="publishBusy" @click="publishOpen = false">再想想</UiButton>
        <UiButton variant="primary" :loading="publishBusy" @click="publish">确认发布</UiButton>
      </template>
    </UiDialog>
  </div>
</template>

<style scoped>
.grades {
  width: 100%;
  max-width: 1020px;
  margin: 0 auto;
  padding: var(--s-5) clamp(var(--s-4), 4vw, var(--s-6)) var(--s-7);
}

.bar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--s-4);
  padding-bottom: var(--s-2);
  margin-bottom: var(--s-4);
  border-bottom: 1px solid var(--ink);
}

.tabs {
  display: flex;
  gap: var(--s-4);
  flex-wrap: wrap;
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

.rules {
  padding: var(--s-3) var(--s-4);
  margin-bottom: var(--s-4);
  border: 1px solid var(--rule);
  background: var(--paper-lift);
}

.rules__title {
  margin: 0 0 var(--s-2);
  font-size: var(--fs-small);
  letter-spacing: 0.08em;
}

.rules__grid {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2) var(--s-4);
  font-size: 12px;
  color: var(--ink-2);
}

.summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1px;
  background: var(--rule);
  border: 1px solid var(--rule);
  margin-bottom: var(--s-5);
}

.summary__cell {
  padding: var(--s-3) var(--s-4) var(--s-4);
  background: var(--paper-lift);
}

.summary__label {
  margin: 0;
  color: var(--ink-2);
  font-size: 11px;
  letter-spacing: 0.1em;
}

.summary__value {
  margin: var(--s-2) 0 0;
  font-family: var(--font-serif);
  font-size: 38px;
  line-height: 1;
}

.semester {
  margin-bottom: var(--s-5);
}

.semester__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-3);
  padding-bottom: var(--s-2);
  border-bottom: 1px solid var(--ink);
}

.semester__name {
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-size: 20px;
  font-weight: 600;
}

.semester__meta {
  color: var(--ink-2);
  font-size: 11px;
}

.table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-ui);
}

.table th {
  padding: var(--s-2) var(--s-2);
  color: var(--ink-2);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-align: left;
  border-bottom: var(--hairline);
  white-space: nowrap;
}

.table td {
  padding: var(--s-2);
  border-bottom: var(--hairline);
  vertical-align: middle;
}

.table .num {
  text-align: right;
}

.cell-name {
  font-family: var(--font-serif-cjk);
}

.cell-code {
  display: block;
  color: var(--ink-2);
  font-size: 11px;
}

.score {
  font-size: 16px;
}

.cell-input {
  width: 72px;
  padding: 4px 6px;
  border: 1px solid var(--rule-strong);
  background: var(--paper-lift);
  color: var(--ink);
  font-size: var(--fs-ui);
  text-align: right;
}

.cell-input--wide {
  width: 100%;
  min-width: 120px;
  text-align: left;
}

.cell-input:focus {
  outline: 2px solid var(--teal);
  outline-offset: 1px;
}

.list {
  margin: 0;
  padding: 0;
  list-style: none;
  border-top: var(--hairline);
}

.item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-4);
  padding: var(--s-3) 0;
  border-bottom: var(--hairline);
}

.item__title {
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-size: 17px;
  font-weight: 600;
}

.item__code {
  margin-left: var(--s-2);
  color: var(--ink-2);
  font-size: 11px;
  font-weight: 400;
}

.item__meta {
  margin: var(--s-1) 0 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.item__done {
  margin-left: var(--s-2);
  color: var(--teal);
}

.overview-line {
  margin: 0 0 var(--s-4);
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.overview-score {
  color: var(--ink);
  font-size: 20px;
}

.review {
  padding: var(--s-4) 0;
  border-top: 1px solid var(--ink);
}

.review__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-3);
}

.review__name {
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-size: 19px;
  font-weight: 600;
}

.review__total {
  color: var(--teal);
  font-size: var(--fs-small);
}

.dims {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--s-3) var(--s-4);
  margin: var(--s-3) 0;
}

.dim {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--s-2);
}

.dim__label {
  color: var(--ink-2);
  font-size: 11px;
}

.dim__bar {
  height: 4px;
  background: var(--paper-deep);
}

.dim__bar i {
  display: block;
  height: 100%;
  background: var(--teal);
}

.dim__value {
  font-size: 12px;
}

.comments {
  margin: 0;
  padding: 0;
  list-style: none;
}

.comment {
  padding: var(--s-2) 0;
  border-top: var(--hairline);
}

.comment__text {
  margin: 0;
  font-family: var(--font-serif);
  font-size: 15px;
  line-height: 1.7;
}

.comment__meta {
  margin: 2px 0 0;
  color: var(--ink-2);
  font-size: 11px;
}

.record-bar {
  display: flex;
  align-items: flex-end;
  gap: var(--s-4);
  margin-bottom: var(--s-4);
}

.record-bar__select {
  flex: 1;
  max-width: 440px;
}

.record-hint {
  margin: 0 0 var(--s-2);
  font-size: var(--fs-small);
}

.record-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--s-3);
  margin-top: var(--s-4);
}

.eval {
  display: grid;
  gap: var(--s-4);
}

.eval__row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--s-4);
  align-items: center;
  padding-bottom: var(--s-3);
  border-bottom: var(--hairline);
}

.eval__label {
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-size: 16px;
}

.eval__hint {
  margin: 2px 0 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.rating {
  display: flex;
  align-items: center;
  gap: 4px;
}

.rating__dot {
  width: 34px;
  height: 34px;
  border: 1px solid var(--rule-strong);
  background: var(--paper-lift);
  color: var(--ink-2);
  font-family: var(--font-mono);
  font-size: 13px;
  cursor: pointer;
}

.rating__dot.is-on {
  border-color: var(--teal);
  background: var(--teal);
  color: var(--paper);
}

.rating__word {
  min-width: 4em;
  margin-left: var(--s-2);
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.check {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.publish-note {
  margin: 0;
  font-family: var(--font-serif);
  font-size: 16px;
  line-height: 1.8;
}

.small {
  font-size: var(--fs-small);
}

.muted {
  color: var(--ink-2);
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

@media (max-width: 760px) {
  .grades {
    padding: var(--s-4) 16px var(--s-6);
  }

  .bar {
    flex-wrap: wrap;
    padding-bottom: 6px;
    margin-bottom: 10px;
  }

  .tabs {
    flex-wrap: nowrap;
    overflow-x: auto;
    width: 100%;
    gap: 14px;
    scrollbar-width: none;
  }

  .tabs::-webkit-scrollbar {
    display: none;
  }

  .tabs__btn {
    flex: 0 0 auto;
    font-size: 15px;
  }

  .summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .summary__cell {
    padding: 10px 12px 12px;
  }

  .summary__value {
    font-size: 28px;
  }

  .record-bar {
    flex-wrap: wrap;
  }

  .record-actions {
    flex-direction: column-reverse;
  }

  .record-actions :deep(.ui-btn) {
    width: 100%;
  }

  .table-scroll > .table--entry {
    min-width: 0;
  }

  .table-scroll > .table {
    min-width: 0;
  }

  .col-optional {
    display: none;
  }

  .semester__head {
    flex-direction: column;
    align-items: flex-start;
    gap: 2px;
  }

  .dims {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .eval__row {
    grid-template-columns: minmax(0, 1fr);
  }

  .table--entry {
    font-size: 13px;
  }

  .cell-input {
    width: 60px;
  }
}
</style>
