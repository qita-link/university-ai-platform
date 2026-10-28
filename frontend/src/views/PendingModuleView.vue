<script setup lang="ts">
defineProps<{ label: string; latin: string }>()
</script>

<template>
  <section class="pending">
    <p class="pending__latin latin-italic">{{ latin }}</p>
    <h1 class="pending__title">{{ label }}</h1>
    <p class="pending__body">这一章还在排版中。v0 先确认整体方向，本模块会在下一轮按同一套设计系统实现，并接入真实接口。</p>
    <RouterLink to="/ai" class="pending__back">回到问答</RouterLink>
  </section>
</template>

<style scoped>
.pending {
  width: min(100% - 2 * var(--s-4), 640px);
  margin: var(--s-7) auto;
}

.pending__latin {
  margin: 0;
  color: var(--ink-2);
  font-size: 22px;
}

.pending__title {
  margin: var(--s-1) 0 var(--s-4);
  font-size: var(--fs-h1);
  line-height: 1.05;
}

.pending__body {
  max-width: 28em;
  margin: 0 0 var(--s-5);
  font-family: var(--font-serif);
  font-size: var(--fs-read);
  line-height: var(--lh-read);
  color: var(--ink-2);
}
</style>
