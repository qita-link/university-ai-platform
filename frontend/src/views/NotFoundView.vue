<script setup lang="ts">
withDefaults(defineProps<{ forbidden?: boolean }>(), { forbidden: false })
</script>

<template>
  <section class="nf">
    <p class="nf__code latin-italic">{{ forbidden ? '403' : '404' }}</p>
    <h1 class="nf__title">{{ forbidden ? '这一页不在你的书目里' : '没有找到这一页' }}</h1>
    <p class="nf__body">
      {{ forbidden ? '当前账号没有访问该页面的权限。若你认为这是误判，请联系管理员调整角色。' : '链接可能已失效，或页面已被移除。' }}
    </p>
    <RouterLink to="/">回到首页</RouterLink>
  </section>
</template>

<style scoped>
.nf {
  width: min(100% - 2 * var(--s-4), 640px);
  margin: var(--s-7) auto;
}

.nf__code {
  margin: 0;
  font-size: 64px;
  line-height: 1;
  color: var(--rule-strong);
}

.nf__title {
  margin: var(--s-3) 0;
  font-size: var(--fs-h2);
}

.nf__body {
  margin: 0 0 var(--s-4);
  color: var(--ink-2);
  font-size: var(--fs-ui-lg);
}
</style>
