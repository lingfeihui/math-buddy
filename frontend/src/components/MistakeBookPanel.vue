<script setup>
import { computed, ref, watch } from 'vue'
import { fetchEventSource } from '@microsoft/fetch-event-source'
import MathText from './MathText.vue'
import { apiFetch, authHeaders, notifyUnauthorized } from '../api'

const props = defineProps({
  open: { type: Boolean, default: false }
})

const emit = defineEmits(['close', 'guide'])

const items = ref([])
const loading = ref(false)
const error = ref('')
const filter = ref('all')
const retryingId = ref('')
const retryTexts = ref({})
const retryErrors = ref({})
let retryController = null
const askingId = ref('')
const askTexts = ref({})
const askErrors = ref({})
let askController = null

const counts = computed(() => {
  const total = items.value.length
  const mastered = items.value.filter((m) => m.mastered).length
  return { total, mastered, pending: total - mastered }
})

const filtered = computed(() => {
  if (filter.value === 'pending') return items.value.filter((m) => !m.mastered)
  if (filter.value === 'mastered') return items.value.filter((m) => m.mastered)
  return items.value
})

function pad(n) {
  return String(n).padStart(2, '0')
}

function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await apiFetch('/api/mistakes')
    if (!res.ok) throw new Error(`加载失败（HTTP ${res.status}）`)
    const data = await res.json()
    items.value = Array.isArray(data) ? data : []
  } catch (err) {
    error.value = err?.message || '错题本加载失败，请确认后端已启动'
  } finally {
    loading.value = false
  }
}

async function toggleMastered(item) {
  error.value = ''
  try {
    const res = await apiFetch(`/api/mistakes/${item.id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mastered: !item.mastered })
    })
    if (!res.ok) throw new Error(`操作失败（HTTP ${res.status}）`)
    const updated = await res.json()
    const index = items.value.findIndex((m) => m.id === item.id)
    if (index >= 0) items.value[index] = updated
  } catch (err) {
    error.value = err?.message || '标记失败，请重试'
  }
}

async function removeItem(item) {
  if (!window.confirm('确定要从错题本里删除这道题吗？')) return
  error.value = ''
  try {
    const res = await apiFetch(`/api/mistakes/${item.id}`, { method: 'DELETE' })
    if (!res.ok && res.status !== 404) throw new Error(`删除失败（HTTP ${res.status}）`)
    await load()
  } catch (err) {
    error.value = err?.message || '删除失败，请重试'
  }
}

/** 针对这道错题再出一道同类变式题 */
async function retry(item) {
  retryController?.abort()
  retryController = new AbortController()
  retryingId.value = item.id
  retryTexts.value = { ...retryTexts.value, [item.id]: '' }
  retryErrors.value = { ...retryErrors.value, [item.id]: '' }

  try {
    await fetchEventSource(`/api/mistakes/${item.id}/retry`, {
      method: 'POST',
      headers: authHeaders(),
      signal: retryController.signal,
      openWhenHidden: true,
      async onopen(res) {
        if (res.status === 401) notifyUnauthorized()
        if (!res.ok) {
          const detail = await res.text().catch(() => '')
          throw new Error(detail || `请求失败（HTTP ${res.status}）`)
        }
      },
      onmessage(ev) {
        if (!ev.data) return
        retryTexts.value = { ...retryTexts.value, [item.id]: (retryTexts.value[item.id] || '') + ev.data }
      },
      onclose() {},
      onerror(err) {
        throw err
      }
    })
  } catch (err) {
    if (retryingId.value === item.id) {
      retryErrors.value = { ...retryErrors.value, [item.id]: err?.message || '变式题生成失败' }
    }
  } finally {
    retryingId.value = ''
    load()
  }
}

function closeRetry(item) {
  retryController?.abort()
  retryController = null
  const id = item?.id || retryingId.value
  const texts = { ...retryTexts.value }
  delete texts[id]
  retryTexts.value = texts
  const errs = { ...retryErrors.value }
  delete errs[id]
  retryErrors.value = errs
  retryingId.value = ''
}

/** 针对这道错题向 AI 提问，直接要讲解和答案 */
async function ask(item) {
  askController?.abort()
  askController = new AbortController()
  askingId.value = item.id
  askTexts.value = { ...askTexts.value, [item.id]: '' }
  askErrors.value = { ...askErrors.value, [item.id]: '' }

  try {
    await fetchEventSource(`/api/mistakes/${item.id}/ask`, {
      method: 'POST',
      headers: authHeaders(),
      signal: askController.signal,
      openWhenHidden: true,
      async onopen(res) {
        if (res.status === 401) notifyUnauthorized()
        if (!res.ok) {
          const detail = await res.text().catch(() => '')
          throw new Error(detail || `请求失败（HTTP ${res.status}）`)
        }
      },
      onmessage(ev) {
        if (!ev.data) return
        askTexts.value = { ...askTexts.value, [item.id]: (askTexts.value[item.id] || '') + ev.data }
      },
      onclose() {},
      onerror(err) {
        throw err
      }
    })
  } catch (err) {
    if (askingId.value === item.id) {
      askErrors.value = { ...askErrors.value, [item.id]: err?.message || '答疑失败' }
    }
  } finally {
    askingId.value = ''
  }
}

function closeAsk(item) {
  askController?.abort()
  askController = null
  const id = item?.id || askingId.value
  const texts = { ...askTexts.value }
  delete texts[id]
  askTexts.value = texts
  const errs = { ...askErrors.value }
  delete errs[id]
  askErrors.value = errs
  askingId.value = ''
}

function closeAll() {
  retryController?.abort()
  askController?.abort()
  retryController = null
  askController = null
  retryTexts.value = {}
  retryErrors.value = {}
  askTexts.value = {}
  askErrors.value = {}
  retryingId.value = ''
  askingId.value = ''
  emit('close')
}

watch(
  () => props.open,
  (open) => {
    if (open) {
      load()
    } else {
      retryController?.abort()
      askController?.abort()
      retryController = null
      askController = null
      retryingId.value = ''
      askingId.value = ''
    }
  }
)
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="fixed inset-0 z-50 flex items-start justify-center px-4 py-10 sm:py-16">
      <div class="absolute inset-0 bg-slate-900/40 backdrop-blur-sm" @click="closeAll"></div>

      <div
        class="relative flex max-h-[80vh] w-full max-w-[720px] flex-col overflow-hidden rounded-2xl border border-slate-100 bg-white shadow-xl"
      >
        <header class="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-3.5">
          <div>
            <p class="text-[13.5px] font-medium text-slate-900">智能错题本</p>
            <p class="mt-1 text-[11px] text-slate-500">
              提问过的题自动收录 · 共 {{ counts.total }} 道，待复习 {{ counts.pending }} 道
            </p>
          </div>
          <button
            type="button"
            class="flex h-8 w-8 items-center justify-center rounded-lg border border-slate-200 text-[16px] leading-none text-slate-400 transition hover:border-rose-200 hover:text-rose-500"
            title="关闭"
            @click="closeAll"
          >
            ×
          </button>
        </header>

        <div class="flex flex-wrap items-center gap-2 border-b border-slate-100 px-5 py-2.5">
          <button
            v-for="tab in [
              { key: 'all', label: `全部 ${counts.total}` },
              { key: 'pending', label: `待复习 ${counts.pending}` },
              { key: 'mastered', label: `已掌握 ${counts.mastered}` }
            ]"
            :key="tab.key"
            type="button"
            class="rounded-full border px-3 py-1 text-[11.5px] transition"
            :class="
              filter === tab.key
                ? 'border-blue-200 bg-blue-50 text-blue-700'
                : 'border-slate-200 text-slate-500 hover:border-blue-200 hover:text-blue-600'
            "
            @click="filter = tab.key"
          >
            {{ tab.label }}
          </button>
        </div>

        <div class="thin-scroll min-h-0 flex-1 space-y-3 overflow-y-auto px-5 py-4">
          <p v-if="error" class="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-[12px] text-rose-600">{{ error }}</p>
          <p v-if="loading" class="py-8 text-center text-[12px] text-slate-500">正在加载错题…</p>

          <div
            v-if="!loading && !filtered.length"
            class="flex flex-col items-center justify-center gap-1 py-12 text-center"
          >
            <p class="text-[13px] text-slate-500">这里还空着</p>
            <p class="text-[11px] text-slate-400">在左侧问一道题，它会自动收进错题本</p>
          </div>

          <article
            v-for="item in filtered"
            :key="item.id"
            class="rounded-xl border border-slate-100 bg-slate-50/70 px-4 py-3"
          >
            <div class="mb-2 flex flex-wrap items-center gap-2">
              <span
                class="rounded-md border border-blue-100 bg-blue-50 px-2 py-[1px] text-[10.5px] text-blue-700"
              >
                {{ item.point }}
              </span>
              <span
                v-if="item.mastered"
                class="rounded-md border border-emerald-100 bg-emerald-50 px-2 py-[1px] text-[10.5px] text-emerald-600"
              >
                已掌握
              </span>
              <span class="text-[10.5px] text-slate-400">
                {{ formatTime(item.createdAt) }} · 复习 {{ item.reviewCount }} 次
              </span>
            </div>

            <div class="text-[13px] leading-relaxed text-slate-800">
              <MathText :text="item.question" compact />
            </div>

            <div class="mt-2.5 flex flex-wrap items-center gap-2">
              <button
                type="button"
                class="rounded-lg border border-slate-200 bg-white px-2.5 py-1 text-[11.5px] text-slate-700 transition hover:border-blue-300 hover:bg-blue-50 hover:text-blue-600 disabled:cursor-not-allowed disabled:opacity-40"
                :disabled="!!retryingId || !!askingId"
                @click="retry(item)"
              >
                {{ retryingId === item.id ? '正在出题…' : '复习变式' }}
              </button>
              <button
                type="button"
                class="rounded-lg border border-blue-200 bg-blue-50 px-2.5 py-1 text-[11.5px] text-blue-600 transition hover:border-blue-300 hover:bg-blue-100 disabled:cursor-not-allowed disabled:opacity-40"
                :disabled="!!retryingId || !!askingId"
                @click="emit('guide', item.question)"
              >
                引导解析
              </button>
              <button
                type="button"
                class="rounded-lg border border-violet-200 bg-violet-50 px-2.5 py-1 text-[11.5px] text-violet-600 transition hover:border-violet-300 hover:bg-violet-100 disabled:cursor-not-allowed disabled:opacity-40"
                :disabled="!!retryingId || !!askingId"
                @click="ask(item)"
              >
                {{ askingId === item.id ? '正在解答…' : 'AI答疑' }}
              </button>
              <button
                type="button"
                class="rounded-lg border border-slate-200 px-2.5 py-1 text-[11.5px] transition hover:border-emerald-300 hover:text-emerald-600"
                :class="item.mastered ? 'text-emerald-600' : 'text-slate-500'"
                @click="toggleMastered(item)"
              >
                {{ item.mastered ? '取消已掌握' : '标记已掌握' }}
              </button>
              <button
                type="button"
                class="rounded-lg border border-slate-200 px-2.5 py-1 text-[11.5px] text-slate-400 transition hover:border-rose-200 hover:text-rose-500"
                @click="removeItem(item)"
              >
                删除
              </button>
            </div>

            <div
              v-if="askingId === item.id || askTexts[item.id]"
              class="mt-3 rounded-xl border border-violet-100 bg-violet-50/60 px-3.5 py-2.5 text-[12.5px]"
            >
              <p class="mb-1 text-[10.5px] text-violet-500">AI 答疑</p>
              <MathText :text="askTexts[item.id] || ''" :streaming="askingId === item.id" compact />
              <p v-if="askingId === item.id && !askTexts[item.id]" class="text-[11.5px] text-slate-400">
                正在思考…
              </p>
              <p v-if="askErrors[item.id]" class="text-[11.5px] text-rose-500">{{ askErrors[item.id] }}</p>
              <button
                v-if="askingId !== item.id"
                type="button"
                class="mt-2 text-[11px] text-slate-400 transition hover:text-slate-600"
                @click="closeAsk(item)"
              >
                收起
              </button>
            </div>

            <div
              v-if="retryingId === item.id || retryTexts[item.id]"
              class="mt-3 rounded-xl border border-blue-100 bg-blue-50/60 px-3.5 py-2.5 text-[12.5px]"
            >
              <p class="mb-1 text-[10.5px] text-blue-600">同类变式题</p>
              <MathText :text="retryTexts[item.id] || ''" :streaming="retryingId === item.id" compact />
              <p v-if="retryingId === item.id && !retryTexts[item.id]" class="text-[11.5px] text-slate-400">
                正在思考…
              </p>
              <p v-if="retryErrors[item.id]" class="text-[11.5px] text-rose-500">{{ retryErrors[item.id] }}</p>
              <button
                v-if="retryingId !== item.id"
                type="button"
                class="mt-2 text-[11px] text-slate-400 transition hover:text-slate-600"
                @click="closeRetry(item)"
              >
                收起
              </button>
            </div>
          </article>
        </div>
      </div>
    </div>
  </Teleport>
</template>
