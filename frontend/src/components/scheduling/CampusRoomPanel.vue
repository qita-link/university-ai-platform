<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { schedulingApi } from '@/api/endpoints'
import type { SchedulingCampus, SchedulingRoom } from '@/api/types'
import { ApiError } from '@/api/http'
import { errorMessage, traceIdOf } from '@/lib/errors'
import { useToastStore } from '@/stores/toast'
import UiButton from '@/components/ui/UiButton.vue'
import UiDialog from '@/components/ui/UiDialog.vue'
import UiEmpty from '@/components/ui/UiEmpty.vue'
import UiField from '@/components/ui/UiField.vue'
import UiSelect from '@/components/ui/UiSelect.vue'
import UiTag from '@/components/ui/UiTag.vue'

/**
 * 校区与教室：教室必须挂在某个校区下，所以校区录入放在同一屏，避免“建教室时发现没有校区”。
 * 教室可用时间（维修/不开放）在 Swagger 的 availability 接口里维护，这里只管理基本信息。
 */
const props = defineProps<{ canManage: boolean }>()

const toast = useToastStore()

const ROOM_TYPES = [
  { value: 'NORMAL', label: '普通教室' },
  { value: 'MULTIMEDIA', label: '多媒体教室' },
  { value: 'COMPUTER_LAB', label: '计算机机房' },
  { value: 'LANGUAGE_LAB', label: '语音教室' },
  { value: 'LAB', label: '实验室' },
  { value: 'GYM', label: '体育场馆' },
  { value: 'OTHER', label: '其他' },
]

const campuses = ref<SchedulingCampus[]>([])
const rooms = ref<SchedulingRoom[]>([])
const loading = ref(true)
const loadError = ref<string | null>(null)
const filterCampus = ref<string>('')

const campusOptions = computed(() => campuses.value.map((c) => ({ value: String(c.id), label: c.name })))

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const [cs, page] = await Promise.all([
      schedulingApi.campuses(),
      schedulingApi.rooms({ size: 100 }),
    ])
    campuses.value = cs
    rooms.value = page.items
  } catch (e) {
    loadError.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)

const shownRooms = computed(() =>
  filterCampus.value ? rooms.value.filter((r) => String(r.campusId) === filterCampus.value) : rooms.value,
)

function roomTypeLabel(type: string) {
  return ROOM_TYPES.find((t) => t.value === type)?.label ?? type
}

/* ---------------- 校区 ---------------- */
const campusOpen = ref(false)
const campusBusy = ref(false)
const campusEditing = ref<SchedulingCampus | null>(null)
const campusForm = ref({ code: '', name: '' })
const campusError = ref<string | null>(null)

function openCampusCreate() {
  campusEditing.value = null
  campusForm.value = { code: '', name: '' }
  campusError.value = null
  campusOpen.value = true
}

function openCampusEdit(c: SchedulingCampus) {
  campusEditing.value = c
  campusForm.value = { code: c.code, name: c.name }
  campusError.value = null
  campusOpen.value = true
}

async function submitCampus() {
  campusError.value = null
  if (!campusForm.value.code.trim() || !campusForm.value.name.trim()) {
    campusError.value = '校区编码和名称都要填写'
    return
  }
  campusBusy.value = true
  try {
    const body = { code: campusForm.value.code.trim(), name: campusForm.value.name.trim(), status: 'ACTIVE' }
    if (campusEditing.value) await schedulingApi.updateCampus(campusEditing.value.id, body)
    else await schedulingApi.createCampus(body)
    campusOpen.value = false
    toast.push(campusEditing.value ? '校区已更新' : '校区已创建', 'success')
    await load()
  } catch (e) {
    campusError.value = errorMessage(e)
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    campusBusy.value = false
  }
}

/* ---------------- 教室 ---------------- */
const roomOpen = ref(false)
const roomBusy = ref(false)
const roomEditing = ref<SchedulingRoom | null>(null)
const roomForm = ref({
  code: '',
  name: '',
  campusId: '',
  building: '',
  floorNo: '',
  capacity: '40',
  roomType: 'NORMAL',
  features: '',
  note: '',
})
const roomErrors = ref<Record<string, string | null>>({})

function openRoomCreate() {
  roomEditing.value = null
  roomForm.value = {
    code: '',
    name: '',
    campusId: filterCampus.value || (campuses.value[0] ? String(campuses.value[0].id) : ''),
    building: '',
    floorNo: '',
    capacity: '40',
    roomType: 'NORMAL',
    features: '',
    note: '',
  }
  roomErrors.value = {}
  roomOpen.value = true
}

function openRoomEdit(r: SchedulingRoom) {
  roomEditing.value = r
  roomForm.value = {
    code: r.code,
    name: r.name,
    campusId: String(r.campusId),
    building: r.building ?? '',
    floorNo: r.floorNo == null ? '' : String(r.floorNo),
    capacity: String(r.capacity),
    roomType: r.roomType,
    features: (r.features ?? []).join(', '),
    note: r.note ?? '',
  }
  roomErrors.value = {}
  roomOpen.value = true
}

async function submitRoom() {
  roomErrors.value = {}
  const f = roomForm.value
  if (!f.code.trim()) roomErrors.value.code = '请填写教室编号'
  if (!f.name.trim()) roomErrors.value.name = '请填写教室名称'
  if (!f.campusId) roomErrors.value.campusId = '请选择校区'
  const capacity = Number(f.capacity)
  if (!Number.isFinite(capacity) || capacity < 1) roomErrors.value.capacity = '容量必须大于 0'
  if (Object.keys(roomErrors.value).length) return

  roomBusy.value = true
  const floorNo = f.floorNo.trim() === '' ? null : Number(f.floorNo)
  const body: Record<string, unknown> = {
    code: f.code.trim(),
    name: f.name.trim(),
    campusId: Number(f.campusId),
    building: f.building.trim() || null,
    floorNo: Number.isFinite(floorNo as number) ? floorNo : null,
    capacity,
    roomType: f.roomType,
    features: f.features
      .split(/[,，\s]+/)
      .map((x) => x.trim())
      .filter(Boolean),
    status: roomEditing.value?.status ?? 'ACTIVE',
    note: f.note.trim() || null,
  }
  if (roomEditing.value) body.version = roomEditing.value.version
  try {
    if (roomEditing.value) await schedulingApi.updateRoom(roomEditing.value.id, body)
    else await schedulingApi.createRoom(body)
    roomOpen.value = false
    toast.push(roomEditing.value ? '教室已更新' : '教室已创建', 'success')
    await load()
  } catch (e) {
    const apiErr = e instanceof ApiError ? e : null
    roomErrors.value = {
      code: apiErr?.fieldError('code') ?? null,
      name: apiErr?.fieldError('name') ?? null,
      capacity: apiErr?.fieldError('capacity') ?? null,
      general: errorMessage(e),
    }
    toast.push(errorMessage(e), 'danger', traceIdOf(e))
  } finally {
    roomBusy.value = false
  }
}
</script>

<template>
  <section class="cr">
    <div class="cr__bar">
      <div class="cr__campuses">
        <span class="cr__label">校区</span>
        <button
          v-for="c in campuses"
          :key="c.id"
          type="button"
          class="cr__campus"
          :class="{ 'is-on': filterCampus === String(c.id) }"
          @click="filterCampus = filterCampus === String(c.id) ? '' : String(c.id)"
        >
          {{ c.name }}
          <span class="cr__campus-code mono">{{ c.code }}</span>
        </button>
        <UiButton v-if="props.canManage" size="sm" variant="link" @click="openCampusCreate">＋ 校区</UiButton>
        <UiButton
          v-if="props.canManage && filterCampus"
          size="sm"
          variant="link"
          @click="openCampusEdit(campuses.find((c) => String(c.id) === filterCampus)!)"
        >
          改名称
        </UiButton>
      </div>
      <UiButton v-if="props.canManage" size="sm" variant="primary" @click="openRoomCreate">新建教室</UiButton>
    </div>

    <p v-if="loadError" class="cr__error">{{ loadError }}</p>
    <UiEmpty
      v-else-if="!campuses.length && !loading"
      title="还没有校区"
      hint="先创建一个校区，教室必须挂在校区下"
    />
    <UiEmpty v-else-if="!shownRooms.length && !loading" title="这个范围内还没有教室" hint="排课前至少需要一间容量足够的教室" />

    <table v-else-if="shownRooms.length" class="cr__table">
      <thead>
        <tr>
          <th>编号</th>
          <th>名称</th>
          <th>位置</th>
          <th>容量</th>
          <th>类型</th>
          <th>设备</th>
          <th>状态</th>
          <th />
        </tr>
      </thead>
      <tbody>
        <tr v-for="r in shownRooms" :key="r.id">
          <td class="mono">{{ r.code }}</td>
          <td>{{ r.name }}</td>
          <td class="cr__sub">
            {{ r.campusName }}<span v-if="r.building"> · {{ r.building }}</span>
            <span v-if="r.floorNo != null"> · {{ r.floorNo }}F</span>
          </td>
          <td class="mono">{{ r.capacity }}</td>
          <td class="cr__sub">{{ roomTypeLabel(r.roomType) }}</td>
          <td class="cr__sub">{{ (r.features ?? []).join('、') || '—' }}</td>
          <td>
            <UiTag :tone="r.status === 'ACTIVE' ? 'teal' : 'muted'">
              {{ r.status === 'ACTIVE' ? '可用' : '停用' }}
            </UiTag>
          </td>
          <td class="cr__ops">
            <UiButton v-if="props.canManage" size="sm" variant="link" @click="openRoomEdit(r)">编辑</UiButton>
          </td>
        </tr>
      </tbody>
    </table>

    <UiDialog :open="campusOpen" :title="campusEditing ? '修改校区' : '新建校区'" width="sm" @close="campusOpen = false">
      <div class="cr__form">
        <UiField v-model="campusForm.code" label="校区编码" required placeholder="如 XH" :maxlength="32" />
        <UiField v-model="campusForm.name" label="校区名称" required placeholder="如 学海校区" :maxlength="64" />
      </div>
      <p v-if="campusError" class="cr__error">{{ campusError }}</p>
      <template #footer>
        <UiButton @click="campusOpen = false">取消</UiButton>
        <UiButton variant="primary" :loading="campusBusy" @click="submitCampus">保存</UiButton>
      </template>
    </UiDialog>

    <UiDialog
      :open="roomOpen"
      :title="roomEditing ? '修改教室' : '新建教室'"
      eyebrow="教学场地"
      width="lg"
      @close="roomOpen = false"
    >
      <div class="cr__grid">
        <UiField v-model="roomForm.code" label="教室编号" required placeholder="如 A301" :maxlength="32" :error="roomErrors.code" />
        <UiField v-model="roomForm.name" label="教室名称" required placeholder="如 第三教学楼 301" :maxlength="64" :error="roomErrors.name" />
        <UiSelect
          :model-value="roomForm.campusId"
          label="校区"
          :options="campusOptions"
          placeholder="选择校区"
          :error="roomErrors.campusId"
          @update:model-value="(v: string) => (roomForm.campusId = v)"
        />
        <UiField v-model="roomForm.building" label="楼栋" placeholder="如 第三教学楼" :maxlength="64" />
        <UiField v-model="roomForm.floorNo" label="楼层" placeholder="3" inputmode="numeric" />
        <UiField
          v-model="roomForm.capacity"
          label="容量（座位数）"
          required
          inputmode="numeric"
          hint="必须不小于班级人数"
          :error="roomErrors.capacity"
        />
        <UiSelect
          :model-value="roomForm.roomType"
          label="教室类型"
          :options="ROOM_TYPES"
          @update:model-value="(v: string) => (roomForm.roomType = v)"
        />
        <UiField v-model="roomForm.features" label="设备标签" placeholder="投影, 空调, 电脑" hint="逗号分隔" />
      </div>
      <UiField v-model="roomForm.note" class="cr__full" label="备注" :maxlength="200" />
      <p v-if="roomErrors.general" class="cr__error">{{ roomErrors.general }}</p>
      <template #footer>
        <UiButton @click="roomOpen = false">取消</UiButton>
        <UiButton variant="primary" :loading="roomBusy" @click="submitRoom">保存</UiButton>
      </template>
    </UiDialog>
  </section>
</template>

<style scoped>
.cr {
  display: grid;
  gap: var(--s-4);
}

.cr__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  flex-wrap: wrap;
}

.cr__campuses {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.cr__label {
  color: var(--ink-2);
  font-size: var(--fs-small);
  font-weight: 600;
  letter-spacing: 0.08em;
  margin-right: 4px;
}

.cr__campus {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  padding: 5px 10px;
  border: var(--hairline);
  background: var(--paper);
  color: var(--ink);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  transition: background-color var(--t-fast) var(--ease);
}

.cr__campus:hover {
  background: var(--paper-lift);
}

.cr__campus.is-on {
  border-color: var(--teal);
  color: var(--teal);
  font-weight: 600;
}

.cr__campus-code {
  color: var(--ink-2);
  font-size: 11px;
}

.cr__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.cr__table th {
  padding: 8px 10px;
  border-bottom: 1px solid var(--ink);
  color: var(--ink-2);
  font-size: var(--fs-small);
  font-weight: 600;
  text-align: left;
  white-space: nowrap;
}

.cr__table td {
  padding: 10px;
  border-bottom: var(--hairline);
  vertical-align: top;
}

.cr__sub {
  color: var(--ink-2);
  font-size: 12px;
}

.cr__ops {
  text-align: right;
  white-space: nowrap;
}

.cr__form {
  display: grid;
  gap: var(--s-4);
}

.cr__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  gap: var(--s-4);
  margin-bottom: var(--s-4);
}

.cr__full {
  margin-top: var(--s-2);
}

.cr__error {
  margin: var(--s-3) 0 0;
  color: var(--danger);
  font-size: var(--fs-small);
}

@media (max-width: 760px) {
  .cr__table thead {
    display: none;
  }

  .cr__table tr {
    display: grid;
    gap: 2px;
    padding: var(--s-2) 0;
    border-bottom: var(--hairline);
  }

  .cr__table td {
    padding: 2px 0;
    border: 0;
  }

  .cr__ops {
    text-align: left;
  }
}
</style>
