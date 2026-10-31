<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'
import { classroomApi, roomBookingApi } from '@/api/endpoints'
import type { FreeDaySummary, FreeSlotView, RoomBookingView, RoomView } from '@/api/types'
import { errorMessage, traceIdOf } from '@/lib/errors'
import {
  ROOM_BOOKING_STATUS_LABEL,
  ROOM_TYPE_LABEL,
  WEEKDAY_LABEL,
  roomBookingTone,
} from '@/lib/labels'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiEmpty from '@/components/ui/UiEmpty.vue'
import UiField from '@/components/ui/UiField.vue'
import UiPageHeader from '@/components/ui/UiPageHeader.vue'
import UiSelect from '@/components/ui/UiSelect.vue'
import UiTag from '@/components/ui/UiTag.vue'
import UiTextarea from '@/components/ui/UiTextarea.vue'

/**
 * 教室：空教室查询、我的借用与借用审批。
 * 投诉建议已独立到「校长信箱」，这里只保留教室相关功能。
 */
const auth = useAuthStore()
const toast = useToastStore()
const route = useRoute()
const router = useRouter()

type TabKey = 'rooms' | 'bookings' | 'booking-review'
const tabs = computed(() => {
  const list: { key: TabKey; label: string }[] = []
  if (auth.can('classroom:read')) list.push({ key: 'rooms', label: '空教室' })
  if (auth.can('room:book')) list.push({ key: 'bookings', label: '我的借用' })
  if (auth.can('room:approve')) list.push({ key: 'booking-review', label: '借用审批' })
  return list
})

const active = ref<TabKey>('rooms')

function pickTab() {
  const q = String(route.query.tab ?? '') as TabKey
  active.value = tabs.value.some((t) => t.key === q) ? q : (tabs.value[0]?.key ?? 'rooms')
}

function go(key: TabKey) {
  active.value = key
  void router.replace({ query: { ...route.query, tab: key } })
}

/* ---------------- 空教室 ---------------- */

function isoDate(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

const todayIso = isoDate(new Date())
const roomsDate = ref(todayIso)
const startPeriod = ref('1')
const endPeriod = ref('2')
const minCapacity = ref('')

const periodOptions = Array.from({ length: 12 }, (_, i) => ({
  value: String(i + 1),
  label: `第 ${i + 1} 节`,
}))

const capacityOptions = [
  { value: '', label: '不限容量' },
  { value: '30', label: '≥ 30 人' },
  { value: '60', label: '≥ 60 人' },
  { value: '100', label: '≥ 100 人' },
  { value: '200', label: '≥ 200 人' },
]

const slot = ref<FreeSlotView | null>(null)
const week = ref<FreeDaySummary[]>([])
const roomsBusy = ref(false)
const roomsError = ref<string | null>(null)

const roomsByBuilding = computed(() => {
  const groups: { building: string; rooms: FreeSlotView['rooms'] }[] = []
  for (const room of slot.value?.rooms ?? []) {
    const building = room.building || '未标注楼栋'
    let group = groups.find((g) => g.building === building)
    if (!group) {
      group = { building, rooms: [] }
      groups.push(group)
    }
    group.rooms.push(room)
  }
  return groups
})

async function loadRooms() {
  roomsBusy.value = true
  roomsError.value = null
  const params = {
    date: roomsDate.value,
    startPeriod: Number(startPeriod.value),
    endPeriod: Number(endPeriod.value),
    minCapacity: minCapacity.value ? Number(minCapacity.value) : undefined,
  }
  const { date: _ignored, ...weekParams } = params
  try {
    const [detail, weekRes] = await Promise.all([
      classroomApi.free(params),
      classroomApi.week({ from: todayIso, ...weekParams }).catch(() => null),
    ])
    slot.value = detail
    if (weekRes) week.value = weekRes.days
    if (Number(startPeriod.value) > Number(endPeriod.value)) {
      endPeriod.value = startPeriod.value
    }
  } catch (e) {
    roomsError.value = errorMessage(e)
  } finally {
    roomsBusy.value = false
  }
}

function chooseDay(day: FreeDaySummary) {
  // 日期变化由 watch 触发一次加载，避免重复请求
  roomsDate.value = day.date
}

/* ---------------- 教室借用 ---------------- */

const canBook = computed(() => auth.can('room:book'))

const bookingTarget = ref<RoomView | null>(null)
const bookingDate = ref(todayIso)
const bookingStart = ref('1')
const bookingEnd = ref('2')
const bookingPurpose = ref('')
const bookingAttendees = ref('')
const bookingContact = ref('')
const bookingBusy = ref(false)

const myBookings = ref<RoomBookingView[]>([])
const myBookingsBusy = ref(false)
const bookingReview = ref<RoomBookingView[]>([])
const bookingReviewBusy = ref(false)
const decisionTarget = ref<RoomBookingView | null>(null)
const decisionApprove = ref(true)
const decisionComment = ref('')
const decisionBusy = ref(false)

function openBooking(room: RoomView) {
  bookingTarget.value = room
  bookingDate.value = roomsDate.value
  bookingStart.value = startPeriod.value
  bookingEnd.value = endPeriod.value
  bookingPurpose.value = ''
  bookingAttendees.value = ''
  bookingContact.value = ''
}

async function submitBooking() {
  if (!bookingTarget.value || bookingTarget.value.id === null) return
  if (!bookingPurpose.value.trim()) {
    toast.push('请填写借用用途', 'danger')
    return
  }
  if (Number(bookingEnd.value) < Number(bookingStart.value)) {
    toast.push('结束节次不能早于开始节次', 'danger')
    return
  }
  bookingBusy.value = true
  try {
    await roomBookingApi.create({
      roomId: bookingTarget.value.id,
      bookingDate: bookingDate.value,
      startPeriod: Number(bookingStart.value),
      endPeriod: Number(bookingEnd.value),
      purpose: bookingPurpose.value.trim(),
      attendees: bookingAttendees.value ? Number(bookingAttendees.value) : null,
      contact: bookingContact.value.trim() || null,
    })
    bookingTarget.value = null
    toast.push('已提交借用申请，等待管理员审批', 'success')
    if (active.value !== 'bookings') {
      active.value = 'bookings'
      await router.replace({ query: { ...route.query, tab: 'bookings' } })
    }
    await loadMyBookings()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    bookingBusy.value = false
  }
}

async function loadMyBookings() {
  myBookingsBusy.value = true
  try {
    myBookings.value = (await roomBookingApi.mine({ size: 50 })).items
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    myBookingsBusy.value = false
  }
}

async function cancelBooking(item: RoomBookingView) {
  try {
    await roomBookingApi.cancel(item.id)
    toast.push('已取消借用', 'success')
    await loadMyBookings()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  }
}

async function loadBookingReview() {
  bookingReviewBusy.value = true
  try {
    bookingReview.value = (await roomBookingApi.review({ status: 'PENDING', size: 50 })).items
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    bookingReviewBusy.value = false
  }
}

function openDecision(item: RoomBookingView, approve: boolean) {
  decisionTarget.value = item
  decisionApprove.value = approve
  decisionComment.value = ''
}

async function submitDecision() {
  if (!decisionTarget.value) return
  if (!decisionApprove.value && !decisionComment.value.trim()) {
    toast.push('驳回时请写明理由', 'danger')
    return
  }
  decisionBusy.value = true
  try {
    await roomBookingApi.decide(decisionTarget.value.id, {
      approve: decisionApprove.value,
      comment: decisionComment.value.trim() || null,
    })
    decisionTarget.value = null
    toast.push(decisionApprove.value ? '已通过借用' : '已驳回借用', 'success')
    await loadBookingReview()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    decisionBusy.value = false
  }
}

/* ---------------- 生命周期 ---------------- */

async function load() {
  if (active.value === 'rooms') await loadRooms()
  else if (active.value === 'bookings') await loadMyBookings()
  else await loadBookingReview()
}

onMounted(() => {
  pickTab()
  void load()
})

watch(
  () => route.query.tab,
  () => pickTab(),
)

watch(active, () => {
  void load()
})

watch([startPeriod, endPeriod, minCapacity, roomsDate], () => {
  if (active.value === 'rooms') void loadRooms()
})

</script>

<template>
  <div class="services">
    <UiPageHeader
      label="教室"
      latin="Rooms"
      title="教室"
      lede="查当前或一周内的空教室，找到合适的场地后可直接提交借用申请。"
    />

    <div class="bar">
      <div class="tabs" role="tablist" aria-label="教室分区">
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
    </div>

    <!-- 空教室 -->
    <template v-if="active === 'rooms'">
      <div class="filters">
        <UiField v-model="roomsDate" label="日期" type="date" class="filters__date" />
        <UiSelect v-model="startPeriod" label="开始节次" :options="periodOptions" />
        <UiSelect v-model="endPeriod" label="结束节次" :options="periodOptions" />
        <UiSelect v-model="minCapacity" label="容量" :options="capacityOptions" />
      </div>

      <div v-if="week.length" class="week" role="tablist" aria-label="一周空教室">
        <button
          v-for="d in week"
          :key="d.date"
          type="button"
          class="week__day"
          role="tab"
          :aria-selected="roomsDate === d.date"
          :class="{ 'is-active': roomsDate === d.date, 'is-closed': !d.teachingDay }"
          @click="chooseDay(d)"
        >
          <span class="week__label">{{ WEEKDAY_LABEL[d.dayOfWeek] }}</span>
          <span class="week__date mono">{{ d.date.slice(5) }}</span>
          <span class="week__count mono">{{ d.freeCount }} 间</span>
        </button>
      </div>

      <p v-if="roomsBusy" class="state">正在核对课表…</p>
      <p v-else-if="roomsError" class="state state--err" role="alert">{{ roomsError }}</p>

      <template v-else-if="slot">
        <div class="slot-head">
          <p class="slot-head__title">
            {{ slot.date }} · {{ WEEKDAY_LABEL[slot.dayOfWeek] }} · 第 {{ slot.startPeriod }}–{{ slot.endPeriod }} 节
            <span class="mono">{{ slot.startTime }}–{{ slot.endTime }}</span>
          </p>
          <p class="slot-head__meta mono">
            第 {{ slot.weekNo }} 教学周 · 空闲 {{ slot.rooms.length }} / {{ slot.totalRooms }} 间
            <span v-if="slot.semesterName"> · {{ slot.semesterName }}</span>
          </p>
        </div>

        <UiEmpty
          v-if="slot.rooms.length === 0"
          title="这个时段没有空教室"
          hint="换个节次或日期看看；也可能是教室档案还没登记（由排课模块维护）。"
        />

        <div v-else class="buildings">
          <section v-for="g in roomsByBuilding" :key="g.building" class="building">
            <h2 class="building__name">{{ g.building }}</h2>
            <ul class="rooms">
              <li v-for="r in g.rooms" :key="r.id ?? r.code" class="room">
                <span class="room__name">{{ r.name }}</span>
                <span class="room__meta mono">
                  {{ r.code }}
                  <template v-if="r.capacity"> · {{ r.capacity }} 座</template>
                </span>
                <UiTag tone="muted">{{ ROOM_TYPE_LABEL[r.roomType ?? 'UNKNOWN'] ?? r.roomType }}</UiTag>
                <button v-if="canBook && r.id" type="button" class="room__book" @click="openBooking(r)">
                  借用
                </button>
              </li>
            </ul>
          </section>
        </div>
      </template>
    </template>

    <!-- 我的借用 -->
    <template v-else-if="active === 'bookings'">
      <p class="att-summary muted">借用需管理员审批；通过后该时段会从空教室中扣除。</p>
      <p v-if="myBookingsBusy" class="state">正在取借用记录…</p>
      <UiEmpty
        v-else-if="myBookings.length === 0"
        title="还没有借用过教室"
        hint="在「空教室」里找到合适的教室，点「借用」提交申请。"
      >
        <UiButton size="sm" variant="primary" @click="go('rooms')">去找空教室</UiButton>
      </UiEmpty>
      <ul v-else class="bookings">
        <li v-for="b in myBookings" :key="b.id" class="booking">
          <div class="booking__main">
            <p class="booking__title">
              {{ b.roomName ?? '教室' }}<span class="booking__code mono">{{ b.roomCode }}</span>
            </p>
            <p class="booking__meta mono">
              {{ b.bookingDate }} · 第 {{ b.startPeriod }}–{{ b.endPeriod }} 节
              <template v-if="b.attendees"> · {{ b.attendees }} 人</template>
            </p>
            <p class="booking__purpose">{{ b.purpose }}</p>
            <p v-if="b.reviewComment" class="booking__review">审批意见：{{ b.reviewComment }}</p>
          </div>
          <div class="booking__side">
            <UiTag :tone="roomBookingTone(b.status)">{{ ROOM_BOOKING_STATUS_LABEL[b.status] }}</UiTag>
            <UiButton v-if="b.canCancel" size="sm" @click="cancelBooking(b)">取消</UiButton>
          </div>
        </li>
      </ul>
    </template>

    <!-- 借用审批 -->
    <template v-else-if="active === 'booking-review'">
      <p v-if="bookingReviewBusy" class="state">正在取待审批借用…</p>
      <UiEmpty
        v-else-if="bookingReview.length === 0"
        title="没有待审批的借用"
        hint="学生或教师提交后会出现在这里。"
      />
      <ul v-else class="bookings">
        <li v-for="b in bookingReview" :key="b.id" class="booking">
          <div class="booking__main">
            <p class="booking__title">
              {{ b.roomName ?? '教室' }}<span class="booking__code mono">{{ b.roomCode }}</span>
              <span class="booking__applicant">{{ b.applicantName }}</span>
            </p>
            <p class="booking__meta mono">
              {{ b.bookingDate }} · 第 {{ b.startPeriod }}–{{ b.endPeriod }} 节
              <template v-if="b.attendees"> · {{ b.attendees }} 人</template>
            </p>
            <p class="booking__purpose">{{ b.purpose }}</p>
            <p v-if="b.contact" class="booking__meta mono">联系方式：{{ b.contact }}</p>
          </div>
          <div class="booking__side booking__side--row">
            <UiButton size="sm" variant="primary" @click="openDecision(b, true)">通过</UiButton>
            <UiButton size="sm" variant="danger" @click="openDecision(b, false)">驳回</UiButton>
          </div>
        </li>
      </ul>
    </template>

    <!-- 申请借用 -->
    <UiDialog
      :open="!!bookingTarget"
      title="借用教室"
      :eyebrow="bookingTarget?.name ?? ''"
      width="md"
      :dismissible="!bookingBusy"
      @close="bookingTarget = null"
    >
      <div class="form">
        <p class="form__note">
          {{ bookingTarget?.building || '' }} {{ bookingTarget?.name }}
          <template v-if="bookingTarget?.capacity"> · {{ bookingTarget.capacity }} 座</template>
        </p>
        <UiField v-model="bookingDate" label="日期" type="date" required />
        <div class="form-row">
          <UiSelect v-model="bookingStart" label="开始节次" :options="periodOptions" />
          <UiSelect v-model="bookingEnd" label="结束节次" :options="periodOptions" />
        </div>
        <UiTextarea
          v-model="bookingPurpose"
          label="用途"
          :rows="3"
          :maxlength="200"
          hint="例如：社团例会、补课、答辩预演。"
          required
        />
        <div class="form-row">
          <UiField v-model="bookingAttendees" label="预计人数" hint="可空" />
          <UiField v-model="bookingContact" label="联系方式" :maxlength="64" />
        </div>
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="bookingBusy" @click="bookingTarget = null">取消</UiButton>
        <UiButton variant="primary" :loading="bookingBusy" @click="submitBooking">提交申请</UiButton>
      </template>
    </UiDialog>

    <!-- 审批借用 -->
    <UiDialog
      :open="!!decisionTarget"
      :title="decisionApprove ? '通过这次借用？' : '驳回这次借用？'"
      :eyebrow="decisionTarget?.roomName ?? ''"
      width="sm"
      :dismissible="!decisionBusy"
      @close="decisionTarget = null"
    >
      <div class="form">
        <p class="form__note">
          {{ decisionTarget?.applicantName }} · {{ decisionTarget?.bookingDate }} · 第
          {{ decisionTarget?.startPeriod }}–{{ decisionTarget?.endPeriod }} 节
        </p>
        <p class="form__note">{{ decisionTarget?.purpose }}</p>
        <UiTextarea
          v-model="decisionComment"
          :label="decisionApprove ? '意见（可空）' : '驳回理由'"
          :rows="3"
          :maxlength="500"
        />
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="decisionBusy" @click="decisionTarget = null">取消</UiButton>
        <UiButton
          :variant="decisionApprove ? 'primary' : 'danger'"
          :loading="decisionBusy"
          @click="submitDecision"
        >
          {{ decisionApprove ? '通过' : '驳回' }}
        </UiButton>
      </template>
    </UiDialog>
  </div>
</template>

<style scoped>
.services {
  width: 100%;
  max-width: 980px;
  margin: 0 auto;
  padding: var(--s-5) clamp(var(--s-4), 4vw, var(--s-6)) var(--s-7);
}

.bar {
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

.filters {
  display: grid;
  grid-template-columns: 160px repeat(3, minmax(120px, 1fr));
  gap: var(--s-3);
  align-items: end;
}

.week {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 1px;
  margin: var(--s-4) 0;
  background: var(--rule);
  border: 1px solid var(--rule);
}

.week__day {
  display: grid;
  gap: 2px;
  justify-items: center;
  padding: var(--s-2) 4px;
  border: 0;
  background: var(--paper-lift);
  color: var(--ink);
  cursor: pointer;
  transition: background var(--t-fast) var(--ease);
}

.week__day:hover {
  background: var(--paper-deep);
}

.week__day.is-active {
  background: var(--teal);
  color: var(--paper);
}

.week__day.is-closed {
  background: var(--paper-deep);
  color: var(--ink-2);
}

.week__label {
  font-family: var(--font-serif-cjk);
  font-size: 14px;
}

.week__date,
.week__count {
  font-size: 10px;
}

.slot-head {
  padding: var(--s-3) 0;
  border-top: var(--hairline);
  border-bottom: var(--hairline);
}

.slot-head__title {
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-size: 19px;
}

.slot-head__meta {
  margin: var(--s-1) 0 0;
  color: var(--ink-2);
  font-size: 11px;
}

.buildings {
  margin-top: var(--s-4);
  display: grid;
  gap: var(--s-5);
}

.building__name {
  margin: 0 0 var(--s-2);
  padding-bottom: var(--s-1);
  border-bottom: 1px solid var(--ink);
  font-family: var(--font-serif-cjk);
  font-size: 17px;
  font-weight: 600;
}

.rooms {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 1px;
  margin: 0;
  padding: 0;
  list-style: none;
  background: var(--rule);
  border: 1px solid var(--rule);
}

.room {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--s-2);
  padding: var(--s-2) var(--s-3);
  background: var(--paper-lift);
}

.room__name {
  font-family: var(--font-serif-cjk);
  font-size: 15px;
}

.room__meta {
  color: var(--ink-2);
  font-size: 11px;
  margin-left: auto;
}

.room__book {
  padding: 0;
  border: 0;
  background: none;
  color: var(--teal);
  font-size: 11px;
  text-decoration: underline;
  text-underline-offset: 3px;
  cursor: pointer;
}

.bookings {
  margin: 0;
  padding: 0;
  list-style: none;
  border-top: var(--hairline);
}

.booking {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--s-3);
  padding: var(--s-3) 0;
  border-bottom: var(--hairline);
}

.booking__title {
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-size: 16px;
}

.booking__code {
  margin-left: var(--s-2);
  color: var(--ink-2);
  font-size: 11px;
}

.booking__applicant {
  margin-left: var(--s-2);
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.booking__meta {
  margin: 2px 0 0;
  color: var(--ink-2);
  font-size: 11px;
}

.booking__purpose {
  margin: var(--s-1) 0 0;
  font-family: var(--font-serif);
  font-size: 15px;
  line-height: 1.7;
}

.booking__review {
  margin: var(--s-1) 0 0;
  color: var(--ink-2);
  font-size: 11px;
}

.booking__side {
  display: grid;
  justify-items: end;
  align-content: start;
  gap: var(--s-2);
}

.booking__side--row {
  grid-auto-flow: column;
  align-items: center;
}

.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--s-3);
}

.section-title {
  margin: 0 0 var(--s-3);
  font-family: var(--font-serif-cjk);
  font-size: var(--fs-h3);
  font-weight: 600;
}

.section-title--split {
  margin-top: var(--s-6);
  padding-top: var(--s-4);
  border-top: 1px solid var(--ink);
}

.compose {
  display: grid;
  gap: var(--s-3);
}

.compose__grid {
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr);
  gap: var(--s-3);
}

.compose__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
}

.check {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.feedback-list {
  margin: 0;
  padding: 0;
  list-style: none;
  border-top: var(--hairline);
}

.feedback {
  padding: var(--s-3) 0;
  border-bottom: var(--hairline);
}

.feedback__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-3);
}

.feedback__title {
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-size: 17px;
  font-weight: 600;
}

.feedback__tags {
  display: flex;
  align-items: center;
  gap: var(--s-2);
}

.feedback__meta {
  margin: 2px 0 0;
  color: var(--ink-2);
  font-size: 11px;
}

.feedback__content {
  margin: var(--s-2) 0 0;
  max-width: 62em;
  font-family: var(--font-serif);
  font-size: 15px;
  line-height: 1.75;
  white-space: pre-wrap;
}

.reply {
  margin-top: var(--s-3);
  padding: var(--s-2) var(--s-3);
  border-left: 2px solid var(--teal);
  background: var(--teal-tint);
}

.reply__label {
  margin: 0;
  color: var(--teal);
  font-size: 11px;
  letter-spacing: 0.08em;
}

.reply__text {
  margin: var(--s-1) 0 0;
  font-family: var(--font-serif);
  font-size: 15px;
  line-height: 1.7;
  white-space: pre-wrap;
}

.handle-bar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--s-3);
  margin-bottom: var(--s-3);
}

.handle-bar__select {
  max-width: 220px;
}

.reply-form {
  display: grid;
  gap: var(--s-3);
}

.reply-form__title {
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-size: 17px;
}

.reply-form__content {
  margin: 0;
  padding: var(--s-2) var(--s-3);
  border-left: 2px solid var(--rule-strong);
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.7;
  white-space: pre-wrap;
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
  .services {
    padding: var(--s-4) 16px var(--s-6);
  }

  .bar {
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

  .filters {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 8px;
  }

  .filters__date {
    grid-column: 1 / -1;
  }

  .week {
    display: flex;
    overflow-x: auto;
    scrollbar-width: none;
  }

  .week::-webkit-scrollbar {
    display: none;
  }

  .week__day {
    flex: 0 0 72px;
  }

  .slot-head {
    padding: 8px 0;
  }

  .slot-head__title {
    font-size: 16px;
  }

  .compose__grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
