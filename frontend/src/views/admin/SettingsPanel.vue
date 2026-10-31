<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useToastStore } from '@/stores/toast'
import { leaveApi, settingApi, userApi } from '@/api/endpoints'
import type { ClassCounselorView, UserView } from '@/api/types'
import { errorMessage, traceIdOf } from '@/lib/errors'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiField from '@/components/ui/UiField.vue'
import UiSelect from '@/components/ui/UiSelect.vue'
import UiTag from '@/components/ui/UiTag.vue'

/**
 * 设置：请假审批流程（是否双审批、谁先谁后）与班级导员。
 * 这两项直接决定学生提交后「谁会收到」，因此放在管理端而不是个人偏好里。
 */
const toast = useToastStore()

const busy = ref(false)
const teacherRequired = ref(true)
const counselorRequired = ref(true)
const order = ref('TEACHER,COUNSELOR')

const warningThreshold = ref('3')
const appealWindow = ref('7')
const emailEnabled = ref(false)
const wechatEnabled = ref(false)
const miscBusy = ref(false)

const forumNotifyComment = ref(true)
const forumNotifyReply = ref(true)
const forumNotifyModerate = ref(true)
const announcementPopup = ref(true)
const forumBusy = ref(false)

const orderOptions = [
  { value: 'TEACHER,COUNSELOR', label: '任课教师 → 导员' },
  { value: 'COUNSELOR,TEACHER', label: '导员 → 任课教师' },
]

const flowText = computed(() => {
  const roles: string[] = []
  for (const role of order.value.split(',')) {
    if (role === 'TEACHER' && teacherRequired.value) roles.push('任课教师')
    if (role === 'COUNSELOR' && counselorRequired.value) roles.push('导员')
  }
  return roles.length ? roles.join(' → ') : '至少保留一级审批'
})

async function loadFlow() {
  const list = await settingApi.list()
  const map = new Map(list.map((s) => [s.key, s.value]))
  teacherRequired.value = (map.get('leave.approval.teacher') ?? 'true') === 'true'
  counselorRequired.value = (map.get('leave.approval.counselor') ?? 'true') === 'true'
  order.value = map.get('leave.approval.order') ?? 'TEACHER,COUNSELOR'
  warningThreshold.value = map.get('attendance.warning.threshold') ?? '3'
  appealWindow.value = map.get('attendance.appeal.window.days') ?? '7'
  emailEnabled.value = (map.get('notify.channel.email.enabled') ?? 'false') === 'true'
  wechatEnabled.value = (map.get('notify.channel.wechat.enabled') ?? 'false') === 'true'
  forumNotifyComment.value = (map.get('forum.notify.comment') ?? 'true') === 'true'
  forumNotifyReply.value = (map.get('forum.notify.reply') ?? 'true') === 'true'
  forumNotifyModerate.value = (map.get('forum.notify.moderate') ?? 'true') === 'true'
  announcementPopup.value = (map.get('announcement.popup') ?? 'true') === 'true'
}

async function saveForum() {
  forumBusy.value = true
  try {
    await settingApi.update('forum.notify.comment', String(forumNotifyComment.value))
    await settingApi.update('forum.notify.reply', String(forumNotifyReply.value))
    await settingApi.update('forum.notify.moderate', String(forumNotifyModerate.value))
    await settingApi.update('announcement.popup', String(announcementPopup.value))
    toast.push('论坛通知与公告设置已保存', 'success')
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    forumBusy.value = false
  }
}

async function saveMisc() {
  const threshold = Number(warningThreshold.value)
  const days = Number(appealWindow.value)
  if (!Number.isInteger(threshold) || threshold < 1 || threshold > 20) {
    toast.push('缺勤预警阈值应为 1~20 的整数', 'danger')
    return
  }
  if (!Number.isInteger(days) || days < 0 || days > 60) {
    toast.push('申诉期限应为 0~60 天', 'danger')
    return
  }
  miscBusy.value = true
  try {
    await settingApi.update('attendance.warning.threshold', String(threshold))
    await settingApi.update('attendance.appeal.window.days', String(days))
    await settingApi.update('notify.channel.email.enabled', String(emailEnabled.value))
    await settingApi.update('notify.channel.wechat.enabled', String(wechatEnabled.value))
    toast.push('考勤与通知设置已保存', 'success')
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    miscBusy.value = false
  }
}

async function saveFlow() {
  if (!teacherRequired.value && !counselorRequired.value) {
    toast.push('任课教师与导员审批不能同时关闭', 'danger')
    return
  }
  busy.value = true
  try {
    await settingApi.update('leave.approval.teacher', String(teacherRequired.value))
    await settingApi.update('leave.approval.counselor', String(counselorRequired.value))
    await settingApi.update('leave.approval.order', order.value)
    toast.push('审批流程已更新，对之后提交的请假生效', 'success')
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    busy.value = false
  }
}

/* ---------------- 班级导员 ---------------- */

const classes = ref<ClassCounselorView[]>([])
const classesBusy = ref(false)
const editTarget = ref<ClassCounselorView | null>(null)
const selected = ref<number[]>([])
const teachers = ref<UserView[]>([])
const editBusy = ref(false)
const keyword = ref('')

const filteredTeachers = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return teachers.value
  return teachers.value.filter(
    (t) => t.displayName.toLowerCase().includes(q) || t.username.toLowerCase().includes(q),
  )
})

async function loadClasses() {
  classesBusy.value = true
  try {
    classes.value = await leaveApi.counselors()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    classesBusy.value = false
  }
}

async function openEdit(item: ClassCounselorView) {
  editTarget.value = item
  selected.value = [...item.counselorIds]
  keyword.value = ''
  if (!teachers.value.length) {
    try {
      const res = await userApi.list({ roleCode: 'TEACHER', size: 100 })
      teachers.value = res.items
    } catch (e) {
      toast.push(errorMessage(e), 'danger', traceIdOf(e))
    }
  }
}

function toggle(id: number) {
  const index = selected.value.indexOf(id)
  if (index >= 0) selected.value.splice(index, 1)
  else selected.value.push(id)
}

async function saveCounselors() {
  if (!editTarget.value) return
  editBusy.value = true
  try {
    await leaveApi.setCounselors(editTarget.value.classId, selected.value)
    editTarget.value = null
    toast.push('导员配置已保存', 'success')
    await loadClasses()
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    editBusy.value = false
  }
}

onMounted(async () => {
  busy.value = true
  try {
    await Promise.all([loadFlow(), loadClasses()])
  } catch (e) {
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    busy.value = false
  }
})
</script>

<template>
  <section class="settings">
    <div class="block">
      <h2 class="block__title">请假审批流程</h2>
      <p class="block__lede">
        决定学生提交请假后由谁审批、按什么顺序。已提交的请假保留提交时的流程快照，修改只影响之后的新申请。
      </p>

      <div class="checks">
        <label class="check">
          <input v-model="teacherRequired" type="checkbox" />
          <span>任课教师审批</span>
        </label>
        <label class="check">
          <input v-model="counselorRequired" type="checkbox" />
          <span>班级导员审批</span>
        </label>
      </div>

      <UiSelect v-model="order" label="审批顺序" :options="orderOptions" class="block__select" />
      <p class="flow">当前流程：<strong>{{ flowText }}</strong></p>

      <div class="block__actions">
        <UiButton variant="primary" :loading="busy" @click="saveFlow">保存流程</UiButton>
      </div>
    </div>

    <div class="block">
      <h2 class="block__title">考勤与通知</h2>
      <p class="block__lede">
        缺勤预警在考勤提交后触发；通知先写入站内信，邮件/微信渠道开启后会生成投递记录。
        两个渠道的发送器尚未接入，开启后投递状态为 SKIPPED，不会影响站内信。
      </p>
      <div class="two">
        <UiField v-model="warningThreshold" label="缺勤预警阈值（次）" hint="同一课程累计旷课达到该次数时提醒" />
        <UiField v-model="appealWindow" label="申诉期限（天）" hint="自上课日起，0 表示不限制" />
      </div>
      <div class="checks">
        <label class="check">
          <input v-model="emailEnabled" type="checkbox" />
          <span>邮件推送（服务号/邮件渠道待接入）</span>
        </label>
        <label class="check">
          <input v-model="wechatEnabled" type="checkbox" />
          <span>微信服务号推送（待接入）</span>
        </label>
      </div>
      <div class="block__actions">
        <UiButton variant="primary" :loading="miscBusy" @click="saveMisc">保存考勤与通知设置</UiButton>
      </div>
    </div>

    <div class="block">
      <h2 class="block__title">论坛与公告</h2>
      <p class="block__lede">
        论坛的评论、回复与版主操作会写进站内信；公告分类的帖子发布后，会给全校写一条站内信，
        并在用户下一次打开页面时弹窗提示（关闭弹窗即视为已读）。
      </p>
      <div class="checks">
        <label class="check">
          <input v-model="forumNotifyComment" type="checkbox" />
          <span>帖子有新评论时通知作者</span>
        </label>
        <label class="check">
          <input v-model="forumNotifyReply" type="checkbox" />
          <span>评论被回复时通知被回复人</span>
        </label>
        <label class="check">
          <input v-model="forumNotifyModerate" type="checkbox" />
          <span>帖子/评论被版主隐藏、恢复或删除时通知作者</span>
        </label>
        <label class="check">
          <input v-model="announcementPopup" type="checkbox" />
          <span>官方公告发布后弹窗提示（关闭后只进站内信）</span>
        </label>
      </div>
      <div class="block__actions">
        <UiButton variant="primary" :loading="forumBusy" @click="saveForum">保存论坛与公告设置</UiButton>
      </div>
    </div>

    <div class="block">
      <h2 class="block__title">班级导员</h2>
      <p class="block__lede">
        一个班可以有多名导员，任一导员都能审批本班学生的请假。未配置导员的班级会自动跳过导员环节。
        学生会成员请在「用户 → 角色」中分配 <span class="mono">STUDENT_UNION</span> 角色，
        即可只读查询请假情况与班级考勤。
      </p>

      <p v-if="classesBusy" class="state">正在取班级…</p>
      <div v-else class="table-scroll">
        <table class="table">
        <thead>
          <tr>
            <th scope="col">班级</th>
            <th scope="col">导员</th>
            <th scope="col" class="num">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in classes" :key="c.classId">
            <td>
              <span class="cell-name">{{ c.className }}</span>
              <span class="cell-code mono">{{ c.classCode }}</span>
            </td>
            <td>
              <template v-if="c.counselorNames.length">
                <UiTag v-for="n in c.counselorNames" :key="n" tone="teal" class="tag-gap">{{ n }}</UiTag>
              </template>
              <span v-else class="muted small">未配置</span>
            </td>
            <td class="num">
              <UiButton size="sm" @click="openEdit(c)">编辑</UiButton>
            </td>
          </tr>
          <tr v-if="classes.length === 0">
            <td colspan="3" class="muted small">还没有班级；先在「组织」中建立班级。</td>
          </tr>
        </tbody>
      </table>
      </div>
    </div>

    <UiDialog
      :open="!!editTarget"
      title="配置班级导员"
      :eyebrow="editTarget?.className ?? ''"
      width="md"
      :dismissible="!editBusy"
      @close="editTarget = null"
    >
      <div class="picker">
        <input v-model="keyword" class="picker__search" type="search" placeholder="搜索教师姓名或工号" />
        <p v-if="teachers.length === 0" class="muted small">还没有教师账号。</p>
        <ul v-else class="picker__list">
          <li v-for="t in filteredTeachers" :key="t.id" class="picker__item">
            <label class="check">
              <input type="checkbox" :checked="selected.includes(t.id)" @change="toggle(t.id)" />
              <span>{{ t.displayName }}<span class="mono cell-code">{{ t.username }}</span></span>
            </label>
          </li>
        </ul>
        <p class="muted small">已选 {{ selected.length }} 人</p>
      </div>
      <template #footer>
        <UiButton variant="link" :disabled="editBusy" @click="editTarget = null">取消</UiButton>
        <UiButton variant="primary" :loading="editBusy" @click="saveCounselors">保存</UiButton>
      </template>
    </UiDialog>
  </section>
</template>

<style scoped>
.settings {
  display: grid;
  gap: var(--s-6);
  max-width: 860px;
  min-width: 0;
}

.block {
  border-top: 1px solid var(--ink);
  padding-top: var(--s-3);
  min-width: 0;
}

.table-scroll {
  max-width: 100%;
}

.block__title {
  margin: 0;
  font-family: var(--font-serif-cjk);
  font-size: var(--fs-h3);
  font-weight: 600;
}

.block__lede {
  margin: var(--s-2) 0 var(--s-3);
  max-width: 46em;
  color: var(--ink-2);
  font-size: var(--fs-small);
  line-height: 1.8;
}

.checks {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-4);
  margin-bottom: var(--s-3);
}

.check {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  font-size: var(--fs-ui);
}

.two {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--s-3);
  max-width: 520px;
  margin-bottom: var(--s-3);
}

.two {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--s-3);
  max-width: 520px;
  margin-bottom: var(--s-3);
}

.block__select {
  max-width: 320px;
}

.flow {
  margin: var(--s-3) 0 0;
  color: var(--ink-2);
  font-size: var(--fs-small);
}

.flow strong {
  color: var(--teal);
}

.block__actions {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--s-3);
}

.table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-ui);
}

.table th {
  padding: var(--s-2);
  color: var(--ink-2);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-align: left;
  border-bottom: var(--hairline);
}

.table td {
  padding: var(--s-2);
  border-bottom: var(--hairline);
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

.tag-gap {
  margin-right: 4px;
}

.picker {
  display: grid;
  gap: var(--s-3);
}

.picker__search {
  width: 100%;
  padding: 6px 8px;
  border: 1px solid var(--rule-strong);
  background: var(--paper-lift);
  color: var(--ink);
  font-size: var(--fs-ui);
}

.picker__list {
  margin: 0;
  padding: 0;
  list-style: none;
  max-height: 320px;
  overflow: auto;
  border-top: var(--hairline);
}

.picker__item {
  padding: var(--s-2) 0;
  border-bottom: var(--hairline);
}

.muted {
  color: var(--ink-2);
}

.small {
  font-size: var(--fs-small);
}

.state {
  margin: 0;
  padding: var(--s-4) 0;
  color: var(--ink-2);
  font-family: var(--font-serif);
}
</style>
