<script setup lang="ts">
import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import UserAvatar from '@/components/ui/UserAvatar.vue'

/**
 * 账号入口：头像 + 姓名，点击进入个人资料页。
 * 桌面在左侧导航底部（variant=side），手机在顶部条右侧（variant=bar）。
 * 改密码、退出登录都收在个人资料页，这里只负责「去我的资料」。
 */
const props = withDefaults(defineProps<{ variant?: 'side' | 'bar' }>(), { variant: 'bar' })

const auth = useAuthStore()

const ROLE_LABEL: Record<string, string> = {
  ADMIN: '管理员',
  TEACHER: '教师',
  STUDENT: '学生',
  STUDENT_UNION: '学生会',
}
const roleText = computed(() => (auth.user?.roles ?? []).map((r) => ROLE_LABEL[r] ?? r).join(' · ') || '未分配角色')
</script>

<template>
  <RouterLink
    to="/profile"
    class="user"
    :class="`user--${props.variant}`"
    :aria-label="`个人资料：${auth.user?.displayName ?? ''}`"
  >
    <UserAvatar
      :user-id="auth.user?.userId"
      :version="auth.user?.avatarVersion"
      :name="auth.user?.displayName"
      :size="30"
    />
    <span class="user__text">
      <span class="user__name">{{ auth.user?.displayName }}</span>
      <span v-if="variant === 'side'" class="user__role">{{ roleText }}</span>
    </span>
  </RouterLink>
</template>

<style scoped>
.user {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  min-width: 0;
  padding: 4px;
  color: var(--ink);
  text-decoration: none;
}

.user__text {
  display: grid;
  min-width: 0;
  line-height: 1.25;
}

.user__name {
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user__role {
  overflow: hidden;
  color: var(--ink-2);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user--side {
  flex: 1 1 auto;
  border-radius: 2px;
}

.user--side:hover .user__name {
  color: var(--teal);
}

@media (max-width: 900px) {
  .user--bar .user__text {
    display: none;
  }
}
</style>
