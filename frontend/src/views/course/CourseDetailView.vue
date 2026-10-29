<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { courseApi, selectionApi } from '@/api/endpoints'
import type { CourseDetail, RosterItem, SelectionView } from '@/api/types'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { BLOCK_REASON_LABEL, COURSE_STATUS_LABEL, courseTone } from '@/lib/labels'
import { formatSchedule } from '@/lib/schedule'
import { formatDateTime } from '@/lib/time'
import UiButton from '@/components/ui/UiButton.vue'
import UiEmpty from '@/components/ui/UiEmpty.vue'
import UiPager from '@/components/ui/UiPager.vue'
import UiTag from '@/components/ui/UiTag.vue'
import CourseAdmin from '@/components/course/CourseAdmin.vue'

/**
 * 课程详情：先看清「上什么、谁上、什么时候上」，再决定选或退。
 * 名单只有任课教师与管理员能读，读不到就整块不显示，不做无意义的占位。
 */
const route = useRoute()

const auth = useAuthStore()
const toast = useToastStore()

const courseId = computed(() => Number(route.params.id))
const course = ref<CourseDetail | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)

const mySelection = ref<SelectionView | null>(null)
const busy = ref(false)

const roster = ref<RosterItem[]>([])
const rosterTotal = ref(0)
const rosterPages = ref(0)
const rosterPage = ref(1)
const rosterVisible = ref(false)
const rosterLoading = ref(false)

const canSelect = computed(() => auth.can('selection:select'))

/** 名单只对任课教师与管理员开放；先在本地判断，避免必然 403 的请求 */
const canViewRoster = computed(() => {
  const c = course.value
  if (!c) return false
  if (auth.can('course:manage_all')) return true
  return c.teachers.some((t) => t.teacherId === auth.user?.userId)
})

/** 本人是否任教这门课：任教者不该看到「选这门课」 */
const isTeacherOfCourse = computed(() =>
  !!course.value?.teachers.some((t) => t.teacherId === auth.user?.userId),
)

/** 只有主讲或管理员能改课：助教没有管理权 */
const canManage = computed(() => {
  if (auth.can('course:manage_all')) return true
  return !!course.value?.teachers.some(
    (t) => t.teacherId === auth.user?.userId && t.teacherRole === 'PRIMARY',
  )
})
const seatRatio = computed(() => {
  if (!course.value || course.value.capacity <= 0) return 0
  return Math.min(1, course.value.selectedCount / course.value.capacity)
})

async function load(id: number) {
  loading.value = true
  error.value = null
  rosterVisible.value = false
  try {
    course.value = await courseApi.detail(id)
    await loadMySelection()
    if (canViewRoster.value) await loadRoster(id)
  } catch (e) {
    course.value = null
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

async function loadMySelection() {
  mySelection.value = null
  if (!course.value || !canSelect.value) return
  try {
    const list = await selectionApi.mine(course.value.semesterId)
    mySelection.value = list.find((s) => s.courseId === course.value?.id && s.status === 'SELECTED') ?? null
  } catch {
    mySelection.value = null
  }
}

/** 名单接口对非任课教师返回 403；这种情况直接不展示这一块 */
async function loadRoster(id: number) {
  rosterLoading.value = true
  try {
    const res = await courseApi.roster(id, rosterPage.value, 50)
    roster.value = res.items
    rosterTotal.value = res.total
    rosterPages.value = res.pages
    rosterVisible.value = true
  } catch (e) {
    roster.value = []
    rosterVisible.value = false
    if (!(e instanceof ApiError && (e.status === 403 || e.status === 404))) {
      // 非权限原因的失败也静默：详情页主体不应因名单失败而不可用
    }
  } finally {
    rosterLoading.value = false
  }
}

watch(courseId, (id) => {
  if (Number.isInteger(id) && id > 0) {
    rosterPage.value = 1
    void load(id)
  }
})

watch(rosterPage, () => {
  if (course.value && rosterVisible.value) void loadRoster(course.value.id)
})

onMounted(() => {
  const id = courseId.value
  if (Number.isInteger(id) && id > 0) void load(id)
})

/* ---------------- 选课 / 退课 ---------------- */
async function select() {
  if (!course.value) return
  busy.value = true
  try {
    await selectionApi.create(course.value.id)
    toast.push(`已选上「${course.value.name}」`, 'success')
    await load(course.value.id)
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
    await load(course.value.id)
  } finally {
    busy.value = false
  }
}

async function drop() {
  if (!mySelection.value || !course.value) return
  busy.value = true
  try {
    await selectionApi.drop(mySelection.value.id)
    toast.push('已退课', 'success')
    await load(course.value.id)
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="course-page">
    <p class="crumbs">
      <RouterLink :to="{ name: 'courses' }">课表</RouterLink>
      <span aria-hidden="true">/</span>
      <span>{{ course?.code ?? '课程' }}</span>
    </p>

    <p v-if="loading" class="state">正在取课程…</p>

    <div v-else-if="error" class="state state--err" role="alert">
      <p>{{ error }}</p>
      <UiButton size="sm" @click="load(courseId)">重试</UiButton>
      <RouterLink :to="{ name: 'courses' }" class="state__link">回到课表</RouterLink>
    </div>

    <template v-else-if="course">
      <header class="head">
        <div class="head__row">
          <p class="head__eyebrow eyebrow">
            {{ course.semesterName }}<span class="head__code mono">{{ course.code }}</span>
          </p>
          <div class="head__actions">
            <template v-if="mySelection">
              <UiTag tone="success">已选</UiTag>
              <UiButton v-if="course.selectionOpen" size="sm" :loading="busy" @click="drop">退课</UiButton>
            </template>
            <template v-else-if="canSelect && !isTeacherOfCourse">
              <UiButton
                size="sm"
                variant="primary"
                :disabled="!course.selectable"
                :loading="busy"
                @click="select"
              >
                {{ course.selectable ? '选这门课' : '不可选' }}
              </UiButton>
            </template>
          </div>
        </div>

        <h1 class="head__title">{{ course.name }}</h1>

        <p class="head__meta">
          <UiTag :tone="courseTone(course.status)">{{ COURSE_STATUS_LABEL[course.status] ?? course.status }}</UiTag>
          <span class="mono">{{ course.credit }} 学分</span>
          <span v-if="course.orgUnitName">{{ course.orgUnitName }}</span>
          <span class="mono">{{ course.selectedCount }}/{{ course.capacity }} 人</span>
          <span v-if="!mySelection && course.blockedReason" class="head__block">
            {{ BLOCK_REASON_LABEL[course.blockedReason] ?? course.blockedReason }}
          </span>
        </p>

        <!-- 名额条：没人选时不画，避免看起来像多出一条分隔线 -->
        <div v-if="course.selectedCount > 0" class="cap" :aria-label="`名额 ${course.selectedCount}/${course.capacity}`">
          <span class="cap__fill" :style="{ width: `${seatRatio * 100}%` }" />
        </div>
      </header>

      <div class="sections">
        <!-- 有管理权限时，排课与教师在下方「课程管理」里统一维护，这里不重复渲染 -->
        <section v-if="!canManage" class="sect">
          <p class="sect__title eyebrow">排课</p>
          <ol v-if="course.schedules.length" class="scheds">
            <li v-for="s in course.schedules" :key="s.id" class="sched">
              <span class="sched__text">{{ formatSchedule(s) }}</span>
            </li>
          </ol>
          <p v-else class="muted">尚未排课。排课完成后教师才能把课程设为「开放选课」。</p>
        </section>

        <section v-if="!canManage" class="sect">
          <p class="sect__title eyebrow">授课教师</p>
          <ul v-if="course.teachers.length" class="teachers">
            <li v-for="t in course.teachers" :key="t.teacherId" class="teacher">
              <span class="teacher__name">{{ t.displayName }}</span>
              <UiTag :tone="t.teacherRole === 'PRIMARY' ? 'teal' : 'muted'">
                {{ t.teacherRole === 'PRIMARY' ? '主讲' : '助教' }}
              </UiTag>
              <span class="teacher__id mono">{{ t.username }}</span>
            </li>
          </ul>
          <p v-else class="muted">还没有安排授课教师。</p>
        </section>

        <section v-if="course.description" class="sect">
          <p class="sect__title eyebrow">简介</p>
          <p class="desc">{{ course.description }}</p>
        </section>

        <section v-if="rosterVisible" class="sect">
          <div class="sect__head">
            <p class="sect__title eyebrow">选课名单</p>
            <p class="sect__count mono">{{ rosterTotal }} 人</p>
          </div>

          <p v-if="rosterLoading" class="muted">正在取名单…</p>
          <UiEmpty v-else-if="roster.length === 0" compact title="还没有学生选课" />
          <ol v-else class="roster">
            <li v-for="(r, i) in roster" :key="r.selectionId" class="roster__item">
              <span class="roster__no latin-italic">{{ String((rosterPage - 1) * 50 + i + 1).padStart(2, '0') }}</span>
              <span class="roster__name">{{ r.displayName }}</span>
              <span class="roster__id mono">{{ r.username }}</span>
              <span class="roster__org">{{ r.orgUnitName || '—' }}</span>
              <span class="roster__time mono">{{ formatDateTime(r.selectedAt) }}</span>
            </li>
          </ol>

          <UiPager
            v-if="rosterPages > 1"
            :page="rosterPage"
            :pages="rosterPages"
            :total="rosterTotal"
            @change="rosterPage = $event"
          />
        </section>
      </div>

      <p class="foot">
        课程状态由任课教师或管理员调整；停开（CANCELLED）会级联退掉全部选课。
      </p>

      <CourseAdmin v-if="canManage" :course="course" @changed="load(course.id)" />
    </template>
  </div>
</template>

<style scoped>
.course-page {
  width: 100%;
  max-width: 880px;
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

.head {
  padding-bottom: var(--s-4);
  margin-bottom: var(--s-5);
  border-bottom: 1px solid var(--ink);
}

.head__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-4);
  min-height: 32px;
}

.head__eyebrow {
  margin: 0;
}

.head__code {
  margin-left: var(--s-2);
  color: var(--ink-2);
  font-size: 11px;
  letter-spacing: 0.08em;
}

.head__actions {
  display: flex;
  align-items: center;
  gap: var(--s-2);
}

.head__title {
  margin: var(--s-2) 0 var(--s-3);
  font-family: var(--font-serif-cjk);
  font-size: var(--fs-h2);
  line-height: 1.15;
}

.head__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--s-3);
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.head__block {
  color: var(--ochre);
}

/* 名额条：细线 + 深青填充，不做圆角；留出与标题的呼吸 */
.cap {
  position: relative;
  height: 3px;
  margin-top: var(--s-4);
  background: var(--paper-deep);
}

.cap__fill {
  position: absolute;
  inset: 0 auto 0 0;
  min-width: 2px;
  background: var(--teal);
  transition: width var(--t-slow) var(--ease);
}

.sections {
  display: grid;
  gap: var(--s-5);
}

.sect__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding-bottom: var(--s-2);
  margin-bottom: var(--s-3);
  border-bottom: 1px solid var(--ink);
}

.sect__title {
  margin: 0 0 var(--s-2);
  padding-bottom: var(--s-1);
  border-bottom: 1px solid var(--rule-strong);
}

.sect__head .sect__title {
  margin: 0;
  padding: 0;
  border: 0;
}

.sect__count {
  margin: 0;
  color: var(--ink-2);
  font-size: 11px;
}

.scheds {
  margin: 0;
  padding: 0;
  list-style: none;
}

.sched {
  padding: var(--s-2) 0;
  border-bottom: var(--hairline);
  font-family: var(--font-serif);
  font-size: 16px;
}

.teachers {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

.teacher {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  padding: var(--s-1) var(--s-3) var(--s-1) 0;
}

.teacher__name {
  font-family: var(--font-serif-cjk);
  font-size: 15px;
}

.teacher__id {
  color: var(--ink-2);
  font-size: 11px;
}

.desc {
  max-width: 40em;
  margin: 0;
  font-family: var(--font-serif);
  font-size: 17px;
  line-height: 1.75;
}

.roster {
  margin: 0;
  padding: 0;
  list-style: none;
}

.roster__item {
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr) 100px 130px 140px;
  align-items: baseline;
  gap: var(--s-2);
  padding: var(--s-2) 0;
  border-bottom: var(--hairline);
  font-size: var(--fs-ui);
}

.roster__no {
  color: var(--rule-strong);
  font-size: 13px;
  text-align: right;
}

.roster__name {
  font-family: var(--font-serif-cjk);
}

.roster__id,
.roster__org,
.roster__time {
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.roster__time {
  text-align: right;
}

.muted {
  margin: 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.7;
}

.foot {
  margin: var(--s-6) 0 0;
  padding-top: var(--s-3);
  border-top: var(--hairline);
  color: var(--ink-2);
  font-size: 11px;
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

@media (max-width: 640px) {
  .roster__item {
    grid-template-columns: 28px minmax(0, 1fr);
  }

  .roster__id,
  .roster__org,
  .roster__time {
    grid-column: 2;
  }

  .roster__time {
    text-align: left;
  }
}
</style>
