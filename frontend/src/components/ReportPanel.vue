<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { apiFetch } from '../api'

const props = defineProps({
  open: { type: Boolean, default: false }
})

const emit = defineEmits(['close'])

const loading = ref(false)
const error = ref('')
const report = ref({ weakness: {}, trend: [], mastery: [], mistakes: {} })

const radarEl = ref(null)
const trendEl = ref(null)
let radarChart = null
let trendChart = null
let radarObserver = null
let trendObserver = null

const mastery = computed(() => report.value.mastery || [])
const trend = computed(() => report.value.trend || [])
const mistakes = computed(() => report.value.mistakes || {})

const answeredTotal = computed(() => mastery.value.reduce((sum, m) => sum + (m.total || 0), 0))
const correctTotal = computed(() => mastery.value.reduce((sum, m) => sum + (m.correct || 0), 0))
const avgRate = computed(() =>
  answeredTotal.value ? Math.round((correctTotal.value * 100) / answeredTotal.value) : 0
)
const weekTotal = computed(() => trend.value.reduce((sum, d) => sum + (d.count || 0), 0))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await apiFetch('/api/report')
    if (!res.ok) throw new Error(`加载失败（HTTP ${res.status}）`)
    report.value = await res.json()
  } catch (err) {
    error.value = err?.message || '学习报告加载失败，请确认后端已启动'
  } finally {
    loading.value = false
  }

  // 图表容器在 loading 结束时才被 v-else 渲染出来，
  // 必须先结束 loading 再等一次 DOM 更新，否则 ref 还是 null，画不出来。
  if (error.value) return
  await nextTick()
  paintRadar()
  paintTrend()
}

function paintRadar() {
  const items = mastery.value.slice(0, 6)
  if (!radarEl.value || items.length < 1) {
    radarChart?.dispose()
    radarChart = null
    return
  }
  if (!radarChart) {
    radarChart = echarts.init(radarEl.value, null, { renderer: 'canvas' })
    radarObserver = new ResizeObserver(() => radarChart && radarChart.resize())
    radarObserver.observe(radarEl.value)
  }

  // 雷达图至少要 3 个维度才成形，只有 1~2 个知识点时会退化成一条线，
  // 因此知识点不足 3 个时改用横向条形图展示正确率。
  const useBar = items.length < 3
  const option = useBar
    ? {
        backgroundColor: 'transparent',
        tooltip: {
          backgroundColor: 'rgba(255,255,255,0.96)',
          borderColor: '#E2E8F0',
          borderWidth: 1,
          textStyle: { color: '#0F172A', fontSize: 12 },
          formatter: (p) => `${p.name}<br/>正确率：<b>${p.value}%</b>`
        },
        grid: { left: 8, right: 44, top: 18, bottom: 8, containLabel: true },
        xAxis: {
          type: 'value',
          min: 0,
          max: 100,
          axisLine: { show: false },
          axisTick: { show: false },
          axisLabel: { color: '#64748B', fontSize: 10.5, formatter: '{value}%' },
          splitLine: { lineStyle: { color: '#F1F5F9', type: 'dashed' } }
        },
        yAxis: {
          type: 'category',
          data: items.map((m) => m.point),
          axisLine: { show: false },
          axisTick: { show: false },
          axisLabel: { color: '#64748B', fontSize: 10.5 }
        },
        series: [
          {
            type: 'bar',
            data: items.map((m) => m.rate || 0),
            barWidth: 14,
            itemStyle: { color: '#2563EB', borderRadius: [0, 7, 7, 0] },
            label: {
              show: true,
              position: 'right',
              color: '#2563EB',
              fontSize: 10.5,
              formatter: '{c}%'
            }
          }
        ]
      }
    : {
        backgroundColor: 'transparent',
        tooltip: {
          backgroundColor: 'rgba(255,255,255,0.96)',
          borderColor: '#E2E8F0',
          borderWidth: 1,
          textStyle: { color: '#0F172A', fontSize: 12 }
        },
        radar: {
          radius: '62%',
          center: ['50%', '54%'],
          indicator: items.map((m) => ({ name: m.point, max: 100 })),
          axisName: { color: '#64748B', fontSize: 10.5 },
          splitLine: { lineStyle: { color: '#E2E8F0' } },
          splitArea: {
            areaStyle: {
              color: ['rgba(241,245,249,0.5)', 'rgba(255,255,255,0.8)']
            }
          },
          axisLine: { lineStyle: { color: '#E2E8F0' } }
        },
        series: [
          {
            type: 'radar',
            symbolSize: 5,
            data: [
              {
                value: items.map((m) => m.rate || 0),
                name: '正确率',
                lineStyle: { color: '#2563EB', width: 2 },
                itemStyle: { color: '#2563EB' },
                areaStyle: { color: 'rgba(37, 99, 235, 0.18)' }
              }
            ]
          }
        ]
      }

  radarChart.setOption(option, true)
}

function paintTrend() {
  if (!trendEl.value) return
  if (!trendChart) {
    trendChart = echarts.init(trendEl.value, null, { renderer: 'canvas' })
    trendObserver = new ResizeObserver(() => trendChart && trendChart.resize())
    trendObserver.observe(trendEl.value)
  }

  trendChart.setOption(
    {
      backgroundColor: 'transparent',
      grid: { left: 8, right: 14, top: 26, bottom: 4, containLabel: true },
      tooltip: {
        trigger: 'axis',
        backgroundColor: 'rgba(255,255,255,0.96)',
        borderColor: '#E2E8F0',
        borderWidth: 1,
        textStyle: { color: '#0F172A', fontSize: 12 },
        formatter: (params) => `${params[0].axisValue}<br/>提问次数：<b>${params[0].value}</b>`
      },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: trend.value.map((d) => d.label),
        axisLine: { lineStyle: { color: '#E2E8F0' } },
        axisTick: { show: false },
        axisLabel: { color: '#64748B', fontSize: 10.5 }
      },
      yAxis: {
        type: 'value',
        minInterval: 1,
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: '#64748B', fontSize: 10.5 },
        splitLine: { lineStyle: { color: '#F1F5F9', type: 'dashed' } }
      },
      series: [
        {
          type: 'line',
          smooth: true,
          symbolSize: 6,
          data: trend.value.map((d) => d.count),
          lineStyle: { color: '#2563EB', width: 2.2 },
          itemStyle: { color: '#2563EB' },
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: 'rgba(37,99,235,.22)' },
              { offset: 1, color: 'rgba(37,99,235,0)' }
            ])
          }
        }
      ]
    },
    true
  )
}

function disposeAll() {
  radarObserver?.disconnect()
  trendObserver?.disconnect()
  radarObserver = null
  trendObserver = null
  radarChart?.dispose()
  trendChart?.dispose()
  radarChart = null
  trendChart = null
}

watch(
  () => props.open,
  (open) => {
    if (open) {
      load()
    } else {
      disposeAll()
    }
  }
)
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="fixed inset-0 z-50 flex items-start justify-center px-4 py-10 sm:py-14">
      <div class="absolute inset-0 bg-slate-900/40 backdrop-blur-sm" @click="emit('close')"></div>

      <div
        class="relative flex max-h-[84vh] w-full max-w-[880px] flex-col overflow-hidden rounded-2xl border border-slate-100 bg-white shadow-xl"
      >
        <header class="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-3.5">
          <div>
            <p class="text-[14px] font-semibold text-slate-900">学习报告</p>
            <p class="mt-1 text-[12px] text-slate-500">
              最近 7 天提问 {{ weekTotal }} 次 · 自测 {{ answeredTotal }} 题 · 平均正确率 {{ avgRate }}%
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

        <div class="thin-scroll min-h-0 flex-1 overflow-y-auto px-5 py-4">
          <p v-if="loading" class="py-16 text-center text-[12px] text-slate-500">正在汇总学习数据…</p>
          <p v-else-if="error" class="py-16 text-center text-[12px] text-rose-500">{{ error }}</p>

          <template v-else>
            <div class="grid grid-cols-2 gap-2.5 sm:grid-cols-4">
              <div class="rounded-xl border border-slate-100 bg-slate-50 px-3 py-2.5">
                <p class="text-[10.5px] text-slate-500">待复习错题</p>
                <p class="mt-0.5 text-[18px] font-semibold text-blue-600">{{ mistakes.pending || 0 }}</p>
              </div>
              <div class="rounded-xl border border-slate-100 bg-slate-50 px-3 py-2.5">
                <p class="text-[10.5px] text-slate-500">已掌握错题</p>
                <p class="mt-0.5 text-[18px] font-semibold text-emerald-600">{{ mistakes.mastered || 0 }}</p>
              </div>
              <div class="rounded-xl border border-slate-100 bg-slate-50 px-3 py-2.5">
                <p class="text-[10.5px] text-slate-500">累计答题</p>
                <p class="mt-0.5 text-[18px] font-semibold text-blue-600">{{ answeredTotal }}</p>
              </div>
              <div class="rounded-xl border border-slate-100 bg-slate-50 px-3 py-2.5">
                <p class="text-[10.5px] text-slate-500">平均正确率</p>
                <p class="mt-0.5 text-[18px] font-semibold text-blue-600">{{ avgRate }}%</p>
              </div>
            </div>

            <div class="mt-3 grid gap-3 sm:grid-cols-2">
              <section class="rounded-xl border border-slate-100 bg-slate-50/70 px-3 py-3">
                <h3 class="mb-1 text-[12px] font-medium text-slate-900">知识点掌握度</h3>
                <div v-show="mastery.length > 0" ref="radarEl" class="h-[208px] w-full"></div>
                <p
                  v-if="!mastery.length"
                  class="flex h-[208px] items-center justify-center px-4 text-center text-[11.5px] leading-relaxed text-slate-500"
                >
                  还没有自测记录，去「知识点自测」答一组题吧。
                </p>
              </section>

              <section class="rounded-xl border border-slate-100 bg-slate-50/70 px-3 py-3">
                <h3 class="mb-1 text-[12px] font-medium text-slate-900">最近 7 天提问趋势</h3>
                <div ref="trendEl" class="h-[208px] w-full"></div>
              </section>
            </div>

            <section class="mt-3 rounded-xl border border-slate-100 bg-slate-50/70 px-4 py-3">
              <h3 class="mb-2 text-[12px] font-medium text-slate-900">各知识点正确率</h3>
              <p v-if="!mastery.length" class="py-4 text-center text-[11.5px] text-slate-500">
                还没有自测记录，去「知识点自测」答一组题吧。
              </p>
              <div v-else class="space-y-2">
                <div v-for="m in mastery" :key="m.point" class="flex items-center gap-3">
                  <span class="w-24 shrink-0 truncate text-[11.5px] text-slate-600">{{ m.point }}</span>
                  <div class="h-1.5 flex-1 overflow-hidden rounded-full bg-slate-200">
                    <div
                      class="h-full rounded-full bg-blue-600"
                      :style="{ width: `${m.rate}%` }"
                    ></div>
                  </div>
                  <span class="w-24 shrink-0 text-right text-[11px] text-slate-500">
                    {{ m.rate }}% · {{ m.correct }}/{{ m.total }}
                  </span>
                </div>
              </div>
            </section>

            <section class="mt-3 rounded-xl border border-slate-100 bg-slate-50/70 px-4 py-3">
              <h3 class="mb-2 text-[12px] font-medium text-slate-900">提问最多的知识点</h3>
              <p v-if="!Object.keys(report.weakness || {}).length" class="py-3 text-center text-[11.5px] text-slate-500">
                还没有提问记录。
              </p>
              <div v-else class="flex flex-wrap gap-1.5">
                <span
                  v-for="(count, name) in report.weakness"
                  :key="name"
                  class="inline-flex items-center gap-1 rounded-md border border-blue-100 bg-blue-50 px-2 py-0.5 text-[10.5px] text-blue-700"
                >
                  {{ name }}
                  <b class="font-semibold text-blue-700">{{ count }}</b>
                </span>
              </div>
            </section>
          </template>
        </div>
      </div>
    </div>
  </Teleport>
</template>
