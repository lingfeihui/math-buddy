<script setup>
import { onMounted, ref, watch } from 'vue'
import MarkdownIt from 'markdown-it'
import renderMathInElement from 'katex/dist/contrib/auto-render.mjs'
import { normalizeLatex, renderMarkdown } from '../utils/latex'

const props = defineProps({
  text: { type: String, default: '' },
  streaming: { type: Boolean, default: false },
  compact: { type: Boolean, default: false }
})

const el = ref(null)
const md = new MarkdownIt({ breaks: true, linkify: true, html: false })

function render() {
  if (!el.value) return
  const raw = normalizeLatex(props.text || '')
  el.value.innerHTML = renderMarkdown(md, raw)

  // 流式输出时，$ 还没闭合就先跳过 KaTeX，避免半截公式报错
  const dollarCount = (raw.match(/\$/g) || []).length
  if (!props.streaming || dollarCount % 2 === 0) {
    renderMathInElement(el.value, {
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

watch(() => props.text, render)
watch(() => props.streaming, render)
onMounted(render)
</script>

<template>
  <div ref="el" class="md-body" :class="{ 'md-body-compact': compact }"></div>
</template>
