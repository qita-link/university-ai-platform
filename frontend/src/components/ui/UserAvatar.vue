<script setup lang="ts">
import { computed, watch } from 'vue'
import { useAvatarStore } from '@/stores/avatar'

/**
 * 用户头像：有图片显示图片，没有则显示姓名末字（与侧栏一致）。
 * <p>
 * 头像接口需要令牌，这里交给 avatar store 取 blob；同一用户同一版本只请求一次。
 */
const props = withDefaults(
  defineProps<{
    userId: number | null | undefined
    version: number | null | undefined
    name?: string | null
    size?: number
  }>(),
  { name: '', size: 28 },
)

const avatar = useAvatarStore()

const src = computed(() => avatar.urlFor(props.userId, props.version))
const initial = computed(() => (props.name || '?').slice(-1))

watch(
  () => [props.userId, props.version] as const,
  () => void avatar.ensure(props.userId, props.version),
  { immediate: true },
)
</script>

<template>
  <span class="ua" :style="{ width: `${size}px`, height: `${size}px`, fontSize: `${Math.round(size * 0.44)}px` }" aria-hidden="true">
    <img v-if="src" :src="src" alt="" />
    <template v-else>{{ initial }}</template>
  </span>
</template>

<style scoped>
.ua {
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  overflow: hidden;
  border-radius: 50%;
  background: var(--ink);
  color: var(--paper);
  font-family: var(--font-serif-cjk);
  line-height: 1;
}
</style>
