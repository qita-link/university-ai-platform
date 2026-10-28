<script setup lang="ts">
import { computed, ref } from 'vue'
import type { SpaceView } from '@/api/types'

/** 检索范围：不选表示“全部可读空间”。后端上限 50 个。 */
const props = defineProps<{ spaces: SpaceView[]; loading: boolean; error: string | null }>()
const selected = defineModel<number[]>({ required: true })

const open = ref(false)
const MAX = 50

const label = computed(() => {
  if (selected.value.length === 0) return '全部可读空间'
  if (selected.value.length === 1) {
    return props.spaces.find((s) => s.id === selected.value[0])?.name ?? '1 个空间'
  }
  return `${selected.value.length} 个空间`
})

function toggle(id: number) {
  const set = new Set(selected.value)
  if (set.has(id)) set.delete(id)
  else if (set.size < MAX) set.add(id)
  selected.value = [...set]
}
</script>

<template>
  <div class="scope" @keydown.esc.stop="open = false">
    <button type="button" class="scope__trigger" :aria-expanded="open" aria-controls="scope-panel" @click="open = !open">
      <span class="scope__k">检索范围</span>
      <span class="scope__v">{{ label }}</span>
      <span aria-hidden="true" class="scope__caret">{{ open ? '▴' : '▾' }}</span>
    </button>

    <div v-if="open" id="scope-panel" class="scope__panel fade-in">
      <p v-if="loading" class="scope__msg">正在载入空间…</p>
      <p v-else-if="error" class="scope__msg scope__msg--err">{{ error }}</p>
      <p v-else-if="spaces.length === 0" class="scope__msg">你目前没有可读的知识库空间。</p>
      <template v-else>
        <button type="button" class="scope__all" :class="{ on: selected.length === 0 }" @click="selected = []">
          全部可读空间
        </button>
        <ul class="scope__list">
          <li v-for="s in spaces" :key="s.id">
            <label class="scope__opt">
              <input type="checkbox" :checked="selected.includes(s.id)" @change="toggle(s.id)" />
              <span class="scope__name">{{ s.name }}</span>
              <span v-if="s.categoryName" class="scope__cat">{{ s.categoryName }}</span>
            </label>
          </li>
        </ul>
      </template>
      <div class="scope__foot">
        <button type="button" class="scope__done" @click="open = false">完成</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.scope {
  position: relative;
}

.scope__trigger {
  display: inline-flex;
  align-items: baseline;
  gap: var(--s-2);
  min-height: 32px;
  padding: 0 var(--s-2);
  border: 1px solid transparent;
  background: none;
  cursor: pointer;
}

.scope__trigger:hover,
.scope__trigger[aria-expanded='true'] {
  border-color: var(--rule-strong);
}

.scope__k {
  font-size: var(--fs-small);
  color: var(--ink-2);
}

.scope__v {
  max-width: 14em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--fs-ui);
}

.scope__caret {
  font-size: 10px;
  color: var(--ink-2);
}

.scope__panel {
  position: absolute;
  bottom: calc(100% + var(--s-2));
  left: 0;
  z-index: 30;
  width: 300px;
  border: 1px solid var(--ink);
  background: var(--paper-lift);
  box-shadow: 4px 4px 0 var(--paper-deep);
}

.scope__msg {
  margin: 0;
  padding: var(--s-3);
  color: var(--ink-2);
}

.scope__msg--err {
  color: var(--ochre);
}

.scope__all {
  width: 100%;
  min-height: 40px;
  padding: 0 var(--s-3);
  border: 0;
  border-bottom: var(--hairline);
  background: none;
  text-align: left;
  cursor: pointer;
}

.scope__all.on {
  color: var(--teal);
  font-weight: 600;
}

.scope__list {
  max-height: 260px;
  margin: 0;
  padding: var(--s-1) 0;
  overflow-y: auto;
  list-style: none;
}

.scope__opt {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: var(--s-2);
  min-height: 36px;
  padding: 0 var(--s-3);
  cursor: pointer;
}

.scope__opt:hover {
  background: var(--paper);
}

.scope__opt input {
  accent-color: var(--teal);
}

.scope__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.scope__cat {
  font-size: 11px;
  color: var(--ink-2);
}

.scope__foot {
  display: flex;
  justify-content: flex-end;
  padding: var(--s-2) var(--s-3);
  border-top: var(--hairline);
}

.scope__done {
  border: 0;
  background: none;
  color: var(--teal);
  cursor: pointer;
}
</style>
