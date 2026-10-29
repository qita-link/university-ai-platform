<script setup lang="ts">
import { ref } from 'vue'
import CampusRoomPanel from './CampusRoomPanel.vue'
import TeacherPanel from './TeacherPanel.vue'
import CoursePanel from './CoursePanel.vue'
import TeachingClassPanel from './TeachingClassPanel.vue'
import TaskPanel from './TaskPanel.vue'

/**
 * 基础数据：排课的前置输入。顺序按「先有场地与课程、再有教学班、最后组教学任务」排列，
 * 与业务建库顺序一致，减少“建到一半发现缺前置数据”。
 */
defineProps<{ semesterId: number; canManage: boolean }>()

type Section = 'rooms' | 'teachers' | 'courses' | 'classes' | 'tasks'

const section = ref<Section>('courses')

const sections: { key: Section; label: string; latin: string }[] = [
  { key: 'courses', label: '课程库', latin: 'Courses' },
  { key: 'rooms', label: '校区与教室', latin: 'Rooms' },
  { key: 'teachers', label: '教师档案', latin: 'Teachers' },
  { key: 'classes', label: '教学班', latin: 'Classes' },
  { key: 'tasks', label: '教学任务', latin: 'Tasks' },
]
</script>

<template>
  <section class="bd">
    <nav class="bd__tabs" aria-label="基础数据分类">
      <button
        v-for="s in sections"
        :key="s.key"
        type="button"
        :class="{ 'is-on': section === s.key }"
        @click="section = s.key"
      >
        <span class="bd__label">{{ s.label }}</span>
        <span class="bd__latin">{{ s.latin }}</span>
      </button>
    </nav>

    <CoursePanel v-if="section === 'courses'" :can-manage="canManage" />
    <CampusRoomPanel v-else-if="section === 'rooms'" :can-manage="canManage" />
    <TeacherPanel v-else-if="section === 'teachers'" :can-manage="canManage" />
    <TeachingClassPanel v-else-if="section === 'classes'" :semester-id="semesterId" :can-manage="canManage" />
    <TaskPanel v-else :semester-id="semesterId" :can-manage="canManage" />
  </section>
</template>

<style scoped>
.bd {
  display: grid;
  gap: var(--s-4);
}

.bd__tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 0;
  border-bottom: 1px solid var(--ink);
}

.bd__tabs button {
  display: grid;
  gap: 1px;
  padding: 8px var(--s-4) 9px;
  border: 0;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  background: none;
  color: var(--ink-2);
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: color var(--t-fast) var(--ease), border-color var(--t-fast) var(--ease);
}

.bd__tabs button:hover {
  color: var(--ink);
}

.bd__tabs button.is-on {
  border-bottom-color: var(--teal);
  color: var(--ink);
}

.bd__label {
  font-size: 15px;
  font-weight: 600;
}

.bd__latin {
  font-size: 10px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--ink-2);
}

@media (max-width: 760px) {
  /* 窄屏改为换行而不是横向滚动：5 个分类横排必然被裁掉一半，滚动条又不明显 */
  .bd__tabs {
    flex-wrap: wrap;
    row-gap: 0;
  }

  .bd__tabs button {
    flex: 1 1 auto;
    min-width: 30%;
    padding: 7px 10px 8px;
    border-bottom-width: 2px;
  }

  .bd__label {
    font-size: 14px;
  }
}
</style>
