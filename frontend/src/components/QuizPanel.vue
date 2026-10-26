<script setup>
import { computed, ref, watch } from 'vue'
import MathText from './MathText.vue'
import { apiFetch } from '../api'

const props = defineProps({
  open: { type: Boolean, default: false }
})

const emit = defineEmits(['close', 'submitted'])

const letters = ['A', 'B', 'C', 'D']

const points = ref([])
const point = ref('')
const loading = ref(false)
const error = ref('')
const quiz = ref(null)
const answers = ref([])
const submitting = ref(false)
const result = ref(null)

const answeredAll = computed(
  () => quiz.value && answers.value.length === quiz.value.questions.length && answers.value.every((a) => a)
)

async function loadPoints() {
  try {
    const res = await apiFetch('/api/points')
    if (res.ok) {
      const data = await res.json()
      points.value = Array.isArray(data) ? data : []
    }
  } catch {
    // 后端未启动时下面会给出提示
  }

  // 默认选最薄弱的知识点
  try {
    const res = await apiFetch('/api/stats')
    if (res.ok) {
      const stats = await res.json()
      const top = Object.keys(stats || {})[0]
      if (top) point.value = top
    }
  } catch {
    // 忽略
  }

  if (!point.value && points.value.length) point.value = points.value[0]
}

async function generate() {
  if (loading.value || !point.value) return
  loading.value = true
  error.value = ''
  result.value = null
  quiz.value = null

  try {
    const res = await apiFetch('/api/quiz', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ point: point.value })
    })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) throw new Error(data.error || `出题失败（HTTP ${res.status}）`)
    quiz.value = data
    answers.value = (data.questions || []).map(() => '')
  } catch (err) {
    error.value = err?.message || '出题失败，请确认后端已启动'
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!quiz.value || !answeredAll.value || submitting.value) return
  submitting.value = true
  error.value = ''

  try {
    const res = await apiFetch('/api/quiz/submit', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ quizId: quiz.value.quizId, answers: answers.value })
    })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) throw new Error(data.error || `提交失败（HTTP ${res.status}）`)
    result.value = data
    emit('submitted')
  } catch (err) {
    error.value = err?.message || '提交失败，请重试'
  } finally {
    submitting.value = false
  }
}

function resetQuiz() {
  quiz.value = null
  result.value = null
  answers.value = []
  error.value = ''
}

function optionClass(qIndex, optIndex) {
  const letter = letters[optIndex]
  const chosen = answers.value[qIndex] === letter

  if (!result.value) {
    return chosen
      ? 'border-blue-300 bg-blue-50 text-blue-700'
      : 'border-slate-200 bg-white text-slate-700 hover:border-blue-300 hover:bg-blue-50/60'
  }

  const item = result.value.results?.[qIndex]
  if (item && item.answer === letter) {
    return 'border-emerald-300 bg-emerald-50 text-emerald-700'
  }
  if (chosen) {
    return 'border-rose-300 bg-rose-50 text-rose-600'
  }
  return 'border-slate-200 bg-white text-slate-400'
}

function choose(qIndex, optIndex) {
  if (result.value) return
  answers.value[qIndex] = letters[optIndex]
}

watch(
  () => props.open,
  (open) => {
    if (open) {
      loadPoints()
    } else {
      resetQuiz()
    }
  }
)
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="fixed inset-0 z-50 flex items-start justify-center px-4 py-10 sm:py-14">
      <div class="absolute inset-0 bg-slate-900/40 backdrop-blur-sm" @click="emit('close')"></div>

      <div
        class="relative flex max-h-[82vh] w-full max-w-[760px] flex-col overflow-hidden rounded-2xl border border-slate-100 bg-white shadow-xl"
      >
        <header class="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-3.5">
          <div>
            <p class="text-[13.5px] font-medium text-slate-900">知识点自测</p>
            <p class="mt-1 text-[11px] text-slate-500">
              每个知识点 5 道选择题 · AI 出题，提交后给逐题解析
            </p>
          </div>
          <button
            type="button"
            class="flex h-8 w-8 items-center justify-center rounded-lg border border-slate-200 text-[16px] leading-none text-slate-400 transition hover:border-rose-200 hover:text-rose-500"
            title="关闭"
            @click="emit('close')"
          >
            ×
          </button>
        </header>

        <!-- 选题 + 生成 -->
        <div class="flex flex-wrap items-center gap-2 border-b border-slate-100 px-5 py-3">
          <label class="text-[11.5px] text-slate-500">知识点</label>
          <select
            v-model="point"
            class="min-w-[160px] rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-[12px] text-slate-800 focus:border-blue-400 focus:outline-none"
          >
            <option v-for="item in points" :key="item" :value="item">{{ item }}</option>
          </select>
          <button
            type="button"
            class="rounded-lg bg-blue-600 px-3 py-1.5 text-[12px] font-medium text-white transition hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-45"
            :disabled="loading || !point"
            @click="generate"
          >
            {{ loading ? 'AI 正在出题…' : quiz ? '换一组题' : '开始自测' }}
          </button>
          <button
            v-if="quiz && !result"
            type="button"
            class="rounded-lg border border-slate-200 px-3 py-1.5 text-[12px] text-slate-500 transition hover:border-slate-300 hover:text-slate-700"
            @click="resetQuiz"
          >
            清空重来
          </button>
          <span v-if="result" class="text-[11.5px] text-emerald-600">
            得分 {{ result.score }} / {{ result.total }}
          </span>
        </div>

        <div class="thin-scroll min-h-0 flex-1 overflow-y-auto px-5 py-4">
          <p v-if="error" class="mb-3 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-[11.5px] text-rose-600">
            {{ error }}
          </p>

          <div v-if="!quiz && !loading" class="flex flex-col items-center justify-center gap-1.5 py-14 text-center">
            <p class="text-[13px] text-slate-500">选好知识点，点「开始自测」</p>
            <p class="text-[11px] text-slate-400">答完提交后，系统会记录掌握度，答错的题自动进错题本</p>
          </div>

          <p v-if="loading" class="py-14 text-center text-[12px] text-slate-500">
            正在生成 5 道题，大约需要 2 分钟…
          </p>

          <div v-if="quiz && !loading" class="space-y-4">
            <div
              v-for="(q, qIndex) in quiz.questions"
              :key="q.id"
              class="rounded-xl border border-slate-100 bg-slate-50/70 px-4 py-3"
            >
              <div class="mb-2 flex items-start gap-2">
                <span
                  class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-md bg-blue-50 text-[11px] font-semibold text-blue-600"
                >
                  {{ qIndex + 1 }}
                </span>
                <div class="min-w-0 flex-1 text-[15px] leading-relaxed text-slate-900">
                  <MathText :text="q.stem" compact />
                </div>
              </div>

              <div class="grid gap-1.5 sm:grid-cols-2">
                <button
                  v-for="(opt, optIndex) in q.options"
                  :key="optIndex"
                  type="button"
                  class="flex items-start gap-2 rounded-lg border px-3 py-2.5 text-left text-[13.5px] leading-relaxed transition"
                  :class="optionClass(qIndex, optIndex)"
                  @click="choose(qIndex, optIndex)"
                >
                  <span class="font-semibold">{{ letters[optIndex] }}.</span>
                  <div class="min-w-0 flex-1"><MathText :text="opt" compact /></div>
                </button>
              </div>

              <div
                v-if="result && result.results?.[qIndex]"
                class="mt-2.5 rounded-lg border border-slate-100 bg-white px-3 py-2"
              >
                <p class="mb-1 text-[11px] text-slate-500">
                  你的答案
                  <b class="text-slate-700">{{ result.results[qIndex].yourAnswer || '未作答' }}</b>
                  · 正确答案
                  <b class="text-emerald-600">{{ result.results[qIndex].answer }}</b>
                </p>
                <div class="text-[13px] leading-relaxed text-slate-700">
                  <MathText :text="result.results[qIndex].explain" compact />
                </div>
              </div>
            </div>

            <div v-if="!result" class="flex items-center justify-between gap-3 pt-1">
              <p class="text-[11px] text-slate-400">
                已作答 {{ answers.filter(Boolean).length }} / {{ quiz.questions.length }}
              </p>
              <button
                type="button"
                class="rounded-lg bg-blue-600 px-4 py-1.5 text-[12.5px] font-medium text-white transition hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-45"
                :disabled="!answeredAll || submitting"
                @click="submit"
              >
                {{ submitting ? '判分中…' : '提交答案' }}
              </button>
            </div>

            <div v-if="result" class="flex items-center justify-between gap-3 pt-1">
              <p class="text-[11.5px] text-slate-500">
                本轮得分 <b class="text-blue-600">{{ result.score }}</b> / {{ result.total }}
                ，答错的题已自动加入错题本
              </p>
              <button
                type="button"
                class="rounded-lg border border-blue-200 bg-blue-50 px-3.5 py-1.5 text-[12px] text-blue-600 transition hover:bg-blue-100"
                @click="generate"
              >
                再来一组
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>
