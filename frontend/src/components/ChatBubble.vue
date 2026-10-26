<script setup>
import { nextTick, onMounted, ref, watch } from 'vue'
import MarkdownIt from 'markdown-it'
import renderMathInElement from 'katex/dist/contrib/auto-render.mjs'
import { normalizeLatex, renderMarkdown } from '../utils/latex'

const props = defineProps({
  message: { type: Object, required: true },
  canVariant: { type: Boolean, default: false }
})

const emit = defineEmits(['variant'])

const bodyRef = ref(null)
const userBodyRef = ref(null)
const md = new MarkdownIt({ breaks: true, linkify: true, html: false })
let timer = null

function render() {
  const el = bodyRef.value
  if (el) {
    const raw = normalizeLatex(props.message.content || '')
    el.innerHTML = renderMarkdown(md, raw)

    // 流式输出时，避免把还没闭合的 $ 交给 KaTeX 造成半截公式报错
    const dollarCount = (raw.match(/\$/g) || []).length
    if (!props.message.streaming || dollarCount % 2 === 0) {
      renderMathInElement(el, {
        delimiters: [
          { left: '$$', right: '$$', display: true },
          { left: '$', right: '$', display: false },
          { left: '\\[', right: '\\]', display: true },
          { left: '\\(', right: '\\)', display: false }
        ],
        throwOnError: false,
        ignoredTags: ['script', 'noscript', 'style', 'textarea', 'pre', 'code']
      })
    }
  }

  // 用户消息可能是从错题本带过来的题目，同样要渲染公式
  const userEl = userBodyRef.value
  if (userEl) {
    userEl.textContent = normalizeLatex(props.message.content || '')
    renderMathInElement(userEl, {
      delimiters: [
        { left: '$$', right: '$$', display: true },
        { left: '$', right: '$', display: false },
        { left: '\\[', right: '\\]', display: true },
        { left: '\\(', right: '\\)', display: false }
      ],
      throwOnError: false,
      ignoredTags: ['script', 'noscript', 'style', 'textarea', 'pre', 'code']
    })
  }
}

function scheduleRender() {
  clearTimeout(timer)
  timer = setTimeout(() => {
    nextTick(render)
  }, 45)
}

watch(() => props.message.content, scheduleRender)
watch(() => props.message.streaming, scheduleRender)
onMounted(render)
</script>

<template>
  <div v-if="message.role === 'user'" class="flex justify-end">
    <div
      class="max-w-[82%] rounded-2xl rounded-br-md border border-blue-200 bg-blue-600 px-4 py-2.5 text-[14.5px] leading-relaxed text-white shadow-sm"
    >
      <img
        v-if="message.image"
        :src="message.image"
        alt="题目图片"
        class="mb-2 max-h-52 w-auto rounded-xl border border-blue-400/40"
      />
      <div
        v-if="message.docName"
        class="mb-1.5 inline-flex max-w-full items-center gap-1.5 rounded-lg border border-blue-400/40 bg-blue-500/30 px-2 py-0.5 text-[11.5px] text-blue-50"
      >
        <svg viewBox="0 0 24 24" class="h-3.5 w-3.5 shrink-0" fill="none" stroke="currentColor" stroke-width="1.8">
          <path d="M6 3.5h8l4 4v13H6z" stroke-linejoin="round" />
          <path d="M14 3.5v4h4" stroke-linejoin="round" />
        </svg>
        <span class="truncate">{{ message.docName }}</span>
      </div>
      <div ref="userBodyRef" class="whitespace-pre-wrap"></div>
    </div>
  </div>

  <div v-else class="flex items-start gap-3">
    <div
      class="mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-blue-100 text-blue-600"
    >
      <svg viewBox="0 0 24 24" class="h-[18px] w-[18px]" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
        <rect x="6" y="8" width="12" height="9" rx="3" />
        <circle cx="9.5" cy="12.5" r="1" fill="currentColor" stroke="none" />
        <circle cx="14.5" cy="12.5" r="1" fill="currentColor" stroke="none" />
        <path d="M10 15.5h4" />
        <path d="M12 8V6" />
        <circle cx="12" cy="5" r="1.2" fill="currentColor" stroke="none" />
      </svg>
    </div>

    <div class="min-w-0 flex-1">
      <div class="mb-1 flex items-center gap-2">
        <span class="text-[11px] tracking-wide text-slate-500">数思引航</span>
        <span
          v-if="message.variant"
          class="rounded-full border border-blue-100 bg-blue-50 px-2 py-[1px] text-[10px] text-blue-700"
        >
          变式训练
        </span>
        <span
          v-if="message.recommend"
          class="rounded-full border border-blue-100 bg-blue-50 px-2 py-[1px] text-[10px] text-blue-700"
        >
          薄弱点推荐
        </span>
      </div>

      <div
        class="rounded-2xl rounded-tl-md border border-slate-100 bg-white px-4 py-3 shadow-sm"
      >
        <div ref="bodyRef" class="md-body"></div>
        <span v-if="message.streaming" class="caret"></span>
        <span
          v-if="!message.content && message.streaming"
          class="text-[13px] text-slate-500"
        >
          正在思考…
        </span>
      </div>

      <div v-if="canVariant && !message.streaming && message.content" class="mt-2 flex items-center gap-2">
        <button
          type="button"
          class="group inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-2.5 py-1 text-[12px] text-slate-700 transition hover:border-blue-300 hover:bg-blue-50 hover:text-blue-600"
          @click="emit('variant')"
        >
          <svg viewBox="0 0 24 24" class="h-3.5 w-3.5" fill="none" stroke="currentColor" stroke-width="1.8">
            <path d="M4 12a8 8 0 0 1 13.7-5.6M20 12a8 8 0 0 1-13.7 5.6" stroke-linecap="round" />
            <path d="M18 3v4h-4M6 21v-4h4" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          举一反三
        </button>
        <span class="text-[11px] text-slate-400">换个条件，再练一道同类题</span>
      </div>
    </div>
  </div>
</template>
