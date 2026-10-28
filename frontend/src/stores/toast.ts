import { defineStore } from 'pinia'
import { ref } from 'vue'

export type ToastTone = 'info' | 'success' | 'danger'

export interface Toast {
  id: number
  tone: ToastTone
  text: string
  traceId?: string | null
}

export const useToastStore = defineStore('toast', () => {
  const items = ref<Toast[]>([])
  let seq = 0

  function push(text: string, tone: ToastTone = 'info', traceId?: string | null, ttl = 5000) {
    const id = ++seq
    items.value.push({ id, tone, text, traceId })
    if (items.value.length > 3) items.value.shift()
    window.setTimeout(() => dismiss(id), tone === 'danger' ? ttl * 2 : ttl)
  }

  function dismiss(id: number) {
    items.value = items.value.filter((t) => t.id !== id)
  }

  return { items, push, dismiss }
})
