<script setup>
const props = defineProps({
  open: { type: Boolean, default: false },
  sessions: { type: Array, default: () => [] },
  activeId: { type: String, default: '' }
})

const emit = defineEmits(['close', 'select', 'new'])

function pad(n) {
  return String(n).padStart(2, '0')
}

function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function userTurns(item) {
  return (item.messages || []).filter((m) => m.role === 'user').length
}

function preview(item) {
  const first = (item.messages || []).find((m) => m.role === 'user')
  return (first?.content || '（图片/文档答疑）').replace(/\s+/g, ' ')
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="fixed inset-0 z-50 flex items-start justify-center px-4 py-10 sm:py-16">
      <div class="absolute inset-0 bg-slate-900/40 backdrop-blur-sm" @click="emit('close')"></div>

      <div
        class="relative flex max-h-[75vh] w-full max-w-[620px] flex-col overflow-hidden rounded-2xl border border-slate-100 bg-white shadow-xl"
      >
        <header class="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-3.5">
          <div>
            <p class="text-[13.5px] font-medium text-slate-900">历史对话</p>
            <p class="mt-1 text-[11px] text-slate-500">保存在本机浏览器，最多保留 20 段</p>
          </div>
          <div class="flex items-center gap-2">
            <button
              type="button"
              class="rounded-lg bg-blue-600 px-3 py-1.5 text-[12px] font-medium text-white shadow-sm transition hover:bg-blue-700"
              @click="emit('new')"
            >
              新对话
            </button>
            <button
              type="button"
              class="flex h-8 w-8 items-center justify-center rounded-lg border border-slate-200 text-[16px] leading-none text-slate-400 transition hover:border-rose-200 hover:text-rose-500"
              title="关闭"
              @click="emit('close')"
            >
              ×
            </button>
          </div>
        </header>

        <div class="thin-scroll min-h-0 flex-1 overflow-y-auto p-3">
          <div v-if="!sessions.length" class="flex flex-col items-center justify-center gap-1 py-12 text-center">
            <p class="text-[13px] text-slate-500">还没有历史对话</p>
            <p class="text-[11px] text-slate-400">这次聊完会自动保存到这里</p>
          </div>

          <button
            v-for="item in sessions"
            :key="item.id"
            type="button"
            class="mb-2 w-full rounded-xl border px-3.5 py-2.5 text-left transition"
            :class="
              item.id === activeId
                ? 'border-blue-200 bg-blue-50'
                : 'border-slate-100 bg-white hover:border-blue-200 hover:bg-blue-50/50'
            "
            @click="emit('select', item)"
          >
            <div class="flex items-center justify-between gap-3">
              <span class="truncate text-[12.5px] text-slate-800">{{ item.title }}</span>
              <span class="shrink-0 text-[10px] text-slate-400">{{ formatTime(item.updatedAt) }}</span>
            </div>
            <p class="mt-1 truncate text-[11px] text-slate-500">{{ preview(item) }}</p>
            <p class="mt-1 text-[10px] text-slate-400">
              {{ userTurns(item) }} 轮提问 · {{ (item.messages || []).length }} 条消息
            </p>
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>
