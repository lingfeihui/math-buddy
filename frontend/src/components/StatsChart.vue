<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  data: { type: Object, default: () => ({}) }
})

const CATEGORIES = ['函数', '几何', '概率', '数列', '导数']

const KEYWORD_MAP = {
  函数: ['函数', '集合与逻辑', '三角函数'],
  几何: ['几何', '圆锥曲线', '直线与圆', '立体几何', '解三角形', '平面向量'],
  概率: ['概率', '统计', '计数原理', '排列', '组合', '二项式'],
  数列: ['数列'],
  导数: ['导数']
}

const radarData = computed(() => {
  const values = CATEGORIES.map((name) => {
    const keywords = KEYWORD_MAP[name]
    return Object.entries(props.data || {}).reduce((sum, [point, count]) => {
      if (keywords.some((k) => point.includes(k))) return sum + count
      return sum
    }, 0)
  })
  return { values, max: Math.max(3, ...values) }
})

const hasData = computed(() => radarData.value.values.some((v) => v > 0))

const chartEl = ref(null)
let chart = null
let resizeObserver = null

function updateChart() {
  if (!chart) return

  if (!hasData.value) {
    chart.clear()
    return
  }

  const { values, max } = radarData.value

  chart.setOption(
    {
      backgroundColor: 'transparent',
      tooltip: {
        backgroundColor: 'rgba(255,255,255,0.96)',
        borderColor: '#E2E8F0',
        borderWidth: 1,
        textStyle: { color: '#0F172A', fontSize: 12 },
        formatter: (params) => {
          const rows = params.map(
            (p) => `${p.name}：提问 ${p.value} 次`
          )
          return rows.join('<br/>')
        }
      },
      radar: {
        radius: '62%',
        center: ['50%', '54%'],
        indicator: CATEGORIES.map((name) => ({ name, max })),
        axisName: { color: '#64748B', fontSize: 11 },
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
              value: values,
              name: '提问热度',
              lineStyle: { color: '#2563EB', width: 2 },
              itemStyle: { color: '#2563EB' },
              areaStyle: { color: 'rgba(37, 99, 235, 0.18)' }
            }
          ]
        }
      ]
    },
    true
  )
}

onMounted(() => {
  chart = echarts.init(chartEl.value, null, { renderer: 'canvas' })
  updateChart()
  resizeObserver = new ResizeObserver(() => chart && chart.resize())
  resizeObserver.observe(chartEl.value)
})

watch(() => props.data, updateChart, { deep: true })

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div class="relative">
    <div ref="chartEl" class="h-[236px] w-full"></div>

    <div
      v-if="!hasData"
      class="pointer-events-none absolute inset-0 flex flex-col items-center justify-center gap-1 text-center"
    >
      <span class="text-[13px] text-slate-500">还没有提问记录</span>
      <span class="text-[11px] text-slate-400">问一道导数或圆锥曲线的题，这里就会亮起来</span>
    </div>
  </div>
</template>
