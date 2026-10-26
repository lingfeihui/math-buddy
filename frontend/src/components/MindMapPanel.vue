<script setup>
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { fetchEventSource } from '@microsoft/fetch-event-source'
import MarkdownIt from 'markdown-it'
import renderMathInElement from 'katex/dist/contrib/auto-render.mjs'
import { authHeaders, notifyUnauthorized } from '../api'

const props = defineProps({
  open: { type: Boolean, default: false },
  point: { type: String, default: '' },
  text: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' }
})

const emit = defineEmits(['close'])

const chartEl = ref(null)
const detailBodyRef = ref(null)
const detailRef = ref(null)
const detailOpen = ref(false)
const selectedNode = ref('')
const explainText = ref('')
const explainLoading = ref(false)
const explainError = ref('')
const md = new MarkdownIt({ breaks: true, linkify: true, html: false })
let chart = null
let resizeObserver = null
let renderTimer = null
let detailTimer = null
let explainController = null
let explainSeq = 0

/** 去掉 markdown 记号，让节点文字干净一些 */
function cleanName(raw) {
  return raw
    .replace(/\*\*/g, '')
    .replace(/`/g, '')
    .replace(/\$/g, '')
    .replace(/\\[a-zA-Z]+/g, '')
    .replace(/[{}]/g, '')
    .trim()
}

/**
 * 把 AI 返回的缩进列表解析成 Echarts 树结构
 * 形如：
 * - 函数与导数
 *   - 核心概念
 *     - 导数的几何意义
 */
function parseOutline(text, point) {
  const root = { name: point || '知识点', children: [] }
  const stack = [{ level: -1, node: root }]

  for (const line of (text || '').split(/\r?\n/)) {
    const match = line.match(/^([ \t]*)[-*•]\s+(.+)$/)
    if (!match) continue

    const indent = match[1].replace(/\t/g, '  ').length
    const level = Math.floor(indent / 2)
    const name = cleanName(match[2])
    if (!name) continue

    while (stack.length > 1 && stack[stack.length - 1].level >= level) stack.pop()

    const node = { name, children: [] }
    stack[stack.length - 1].node.children.push(node)
    stack.push({ level, node })
  }

  // AI 通常会自己写一行根节点，这时去掉外层包装，避免顶层重复
  if (root.children.length === 1 && root.children[0].children.length) {
    return root.children[0]
  }
  return root
}

function paint(node, depth) {
  node.symbolSize = depth === 0 ? 15 : depth === 1 ? 10 : 7
  node.itemStyle = {
    color: depth === 0 ? '#2563EB' : depth === 1 ? '#3B82F6' : '#60A5FA',
    borderColor: '#ffffff',
    borderWidth: 1.5
  }
  node.children?.forEach((child) => paint(child, depth + 1))
}

/** 点击节点后，让 AI 讲解这个节点的概念和例题 */
async function explainNode(name) {
  const node = (name || '').trim()
  if (!node) return

  selectedNode.value = node
  detailOpen.value = true
  explainText.value = ''
  explainError.value = ''
  explainLoading.value = true

  const seq = ++explainSeq
  explainController?.abort()
  explainController = new AbortController()

  try {
    await fetchEventSource('/api/explain', {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ point: props.point, node }),
      signal: explainController.signal,
      openWhenHidden: true,
      async onopen(res) {
        if (res.status === 401) notifyUnauthorized()
        if (!res.ok) throw new Error(`请求失败（HTTP ${res.status}）`)
      },
      onmessage(ev) {
        if (seq === explainSeq && ev.data) explainText.value += ev.data
      },
      onerror(err) {
        throw err
      }
    })
  } catch (err) {
    if (seq === explainSeq && detailOpen.value) {
      explainError.value = err?.message || '讲解生成失败，请确认后端已启动'
    }
  } finally {
    if (seq === explainSeq) explainLoading.value = false
  }
}

function renderDetail() {
  const el = detailRef.value
  if (!el) return

  const raw = explainText.value || ''
  el.innerHTML = md.render(raw)

  // 流式输出时避免把未闭合的 $ 交给 KaTeX
  const dollarCount = (raw.match(/\$/g) || []).length
  if (!explainLoading.value || dollarCount % 2 === 0) {
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

  // 流式输出时让内容跟随滚动
  const body = detailBodyRef.value
  if (body) body.scrollTop = body.scrollHeight
}

function scheduleDetailRender() {
  clearTimeout(detailTimer)
  detailTimer = setTimeout(() => nextTick(renderDetail), 45)
}

watch([explainText, explainLoading, detailOpen], () => {
  if (!detailOpen.value) return
  scheduleDetailRender()
})

function closeDetail() {
  detailOpen.value = false
  explainController?.abort()
  explainController = null
}

/** ECharts 把 seriesIndex/dataIndex 挂在元素的 __ec_inner_ 属性上，逐个找出来 */
function ecDataOf(el) {
  for (const key in el) {
    if (!key.startsWith('__ec_inner_')) continue
    const info = el[key]
    if (info && typeof info.dataIndex === 'number' && typeof info.seriesIndex === 'number') return info
  }
  return null
}

/** 用 zrender 的命中测试取节点名，不依赖 ECharts 的 click 事件派发 */
function nodeNameAt(x, y) {
  if (!chart) return ''
  try {
    let el = chart.getZr().handler.findHover(x, y)?.target
    while (el) {
      const info = ecDataOf(el)
      if (info && info.dataIndex > 0) {
        const name = cleanName(chart.getModel().getSeriesByIndex(info.seriesIndex)?.getData()?.getName(info.dataIndex) || '')
        if (name) return name
      }
      el = el.parent
    }
  } catch (err) {
    // 命中测试失败时静默，避免影响图表交互
  }
  return ''
}

/** 节点上挂原生点击/悬停，保证点得动 */
function bindChartEvents(el) {
  const localPoint = (event) => {
    const rect = el.getBoundingClientRect()
    return [event.clientX - rect.left, event.clientY - rect.top]
  }
  el.addEventListener('click', (event) => {
    const [x, y] = localPoint(event)
    const name = nodeNameAt(x, y)
    if (name) explainNode(name)
  })
  el.addEventListener('mousemove', (event) => {
    const [x, y] = localPoint(event)
    el.style.cursor = nodeNameAt(x, y) ? 'pointer' : 'default'
  })
}

function render() {
  if (!props.open || !props.text || !chartEl.value) return
  if (!chart) {
    chart = echarts.init(chartEl.value, null, { renderer: 'canvas' })
    // 开发环境暴露实例，便于排查点击问题
    if (import.meta.env.DEV) window.__mindmapChart = chart
    bindChartEvents(chartEl.value)
  }

  const tree = parseOutline(props.text, props.point)
  paint(tree, 0)

  chart.setOption(
    {
      backgroundColor: 'transparent',
      tooltip: {
        trigger: 'item',
        backgroundColor: 'rgba(255,255,255,0.96)',
        borderColor: '#E2E8F0',
        borderWidth: 1,
        textStyle: { color: '#0F172A', fontSize: 12 },
        formatter: (params) => params.name
      },
      series: [
        {
          type: 'tree',
          data: [tree],
          left: 150,
          right: 170,
          top: 24,
          bottom: 24,
          orient: 'LR',
          layout: 'orthogonal',
          edgeShape: 'curve',
          symbol: 'circle',
          initialTreeDepth: -1,
          expandAndCollapse: false,
          roam: true,
          animationDuration: 480,
          lineStyle: { color: 'rgba(148,163,184,.5)', width: 1.2, curveness: 0.5 },
          label: {
            position: 'left',
            align: 'right',
            verticalAlign: 'middle',
            distance: 8,
            color: '#334155',
            fontSize: 12,
            width: 128,
            overflow: 'break',
            backgroundColor: 'rgba(255,255,255,.92)',
            borderRadius: 5,
            padding: [3, 6]
          },
          leaves: {
            label: {
              position: 'right',
              align: 'left',
              color: '#64748B',
              fontSize: 11.5,
              width: 140,
              overflow: 'break'
            }
          }
        }
      ]
    },
    true
  )
  chart.resize()
}

function scheduleRender() {
  if (!props.open || props.loading || !props.text) return
  nextTick(() => {
    clearTimeout(renderTimer)
    // 等弹层动画结束再测量尺寸，避免图表宽度算成 0
    renderTimer = setTimeout(render, 60)
  })
}

watch(
  () => [props.open, props.loading, props.text],
  ([open]) => {
    if (!open) {
      clearTimeout(renderTimer)
      clearTimeout(detailTimer)
      resizeObserver?.disconnect()
      resizeObserver = null
      chart?.dispose()
      chart = null
      explainController?.abort()
      explainController = null
      detailOpen.value = false
      selectedNode.value = ''
      explainText.value = ''
      explainError.value = ''
      explainLoading.value = false
      return
    }
    scheduleRender()
  }
)

watch(
  () => props.open,
  (open) => {
    if (open && chartEl.value && !resizeObserver) {
      nextTick(() => {
        if (!chartEl.value) return
        resizeObserver = new ResizeObserver(() => chart && chart.resize())
        resizeObserver.observe(chartEl.value)
      })
    }
  }
)

function close() {
  emit('close')
}

function onKeydown(event) {
  if (event.key === 'Escape') close()
}

window.addEventListener('keydown', onKeydown)

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  clearTimeout(renderTimer)
  clearTimeout(detailTimer)
  explainController?.abort()
  resizeObserver?.disconnect()
  chart?.dispose()
  chart = null
})
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="fixed inset-0 z-50 flex items-center justify-center px-4 py-6">
      <div class="absolute inset-0 bg-slate-900/40 backdrop-blur-sm" @click="close"></div>

      <div
        class="relative flex h-[min(80vh,760px)] w-full max-w-[1040px] flex-col overflow-hidden rounded-2xl border border-slate-100 bg-white shadow-xl"
      >
        <header class="flex items-start justify-between border-b border-slate-100 px-5 py-3.5">
          <div>
            <div class="flex items-center gap-2">
              <span class="text-[13.5px] font-medium text-slate-900">知识点思维导图</span>
              <span
                v-if="point"
                class="rounded-full border border-blue-100 bg-blue-50 px-2 py-[1px] text-[10.5px] text-blue-700"
              >
                {{ point }}
              </span>
            </div>
            <p class="mt-1 text-[11px] text-slate-400">
              按你的薄弱点梳理知识结构 · 点击节点查看概念与例题 · 可拖动缩放
            </p>
          </div>

          <button
            type="button"
            class="flex h-8 w-8 items-center justify-center rounded-lg border border-slate-200 text-[16px] leading-none text-slate-400 transition hover:border-rose-200 hover:text-rose-500"
            title="关闭"
            @click="close"
          >
            ×
          </button>
        </header>

        <div class="relative flex min-h-0 flex-1">
          <div class="relative min-h-0 flex-1">
            <div v-if="loading" class="absolute inset-0 flex flex-col items-center justify-center gap-3">
              <span class="h-7 w-7 animate-spin rounded-full border-2 border-blue-100 border-t-blue-600"></span>
              <p class="text-[12.5px] text-slate-500">正在按「{{ point }}」梳理知识结构…</p>
              <p v-if="text" class="max-w-[70%] truncate text-[10.5px] text-slate-400">
                {{ text.replace(/[-\n]/g, ' ').slice(-60) }}
              </p>
            </div>

            <div v-else-if="error" class="absolute inset-0 flex items-center justify-center px-6">
              <p class="text-[12.5px] leading-relaxed text-rose-500">{{ error }}</p>
            </div>

            <div v-else-if="!text" class="absolute inset-0 flex items-center justify-center">
              <p class="text-[12.5px] text-slate-400">还没有生成内容</p>
            </div>

            <div ref="chartEl" class="h-full w-full" :class="{ invisible: loading || !!error || !text }"></div>
          </div>

          <!-- 点击节点后滑出的讲解面板：宽屏并排显示，窄屏覆盖在图上 -->
          <div
            v-if="detailOpen"
            class="flex w-[380px] shrink-0 flex-col border-l border-slate-100 bg-white max-sm:absolute max-sm:inset-y-0 max-sm:right-0 max-sm:z-10 max-sm:w-[94%]"
          >
            <div class="flex items-start justify-between gap-3 border-b border-slate-100 px-4 py-3">
              <div class="min-w-0">
                <p class="truncate text-[12.5px] font-medium text-slate-900">{{ selectedNode }}</p>
                <p class="mt-0.5 truncate text-[10.5px] text-slate-500">所属知识点：{{ point }}</p>
              </div>
              <button
                type="button"
                class="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border border-slate-200 text-[15px] leading-none text-slate-400 transition hover:border-rose-200 hover:text-rose-500"
                title="关闭讲解"
                @click="closeDetail"
              >
                ×
              </button>
            </div>

            <div ref="detailBodyRef" class="thin-scroll min-h-0 flex-1 overflow-y-auto px-4 py-3">
              <div v-if="explainLoading && !explainText" class="space-y-2 py-1">
                <div class="h-3 w-2/3 animate-pulse rounded bg-slate-200"></div>
                <div class="h-3 w-full animate-pulse rounded bg-slate-100"></div>
                <div class="h-3 w-1/2 animate-pulse rounded bg-slate-200/70"></div>
                <p class="pt-1 text-[11px] text-slate-400">正在生成概念与例题…</p>
              </div>

              <p v-if="explainError" class="text-[12px] leading-relaxed text-rose-500">{{ explainError }}</p>

              <div v-show="!explainError" ref="detailRef" class="md-body text-[12.5px]"></div>
            </div>

            <div class="border-t border-slate-100 px-4 py-2 text-[10px] text-slate-400">
              例题只给思路提示，不直接给答案
            </div>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>
