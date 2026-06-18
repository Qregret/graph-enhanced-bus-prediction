<template>
  <div class="analytics-page">
    <section class="analytics-metrics">
      <article
        v-for="metric in mockData.metrics"
        :key="metric.title"
        class="analytics-metric"
        :class="`analytics-metric--${metric.tone}`"
      >
        <span class="analytics-metric__icon">
          <component :is="metric.icon" />
        </span>
        <div class="analytics-metric__body">
          <div class="analytics-metric__head">
            <p>{{ metric.title }}</p>
            <span>{{ metric.status }}</span>
          </div>
          <strong>{{ metric.value }}</strong>
          <small :class="metric.trendClass">{{ metric.trend }}</small>
        </div>
        <i class="analytics-metric__bar"></i>
      </article>
    </section>

    <section class="analytics-core">
      <article class="analytics-panel analytics-panel--trend">
        <header class="analytics-panel__head">
          <div>
            <h3>误差趋势分析</h3>
            <p>近 7 天预测误差与推理时延变化</p>
          </div>
          <div class="analytics-switch" aria-label="趋势时间范围">
            <button
              v-for="range in ranges"
              :key="range.value"
              type="button"
              :class="{ 'is-active': activeRange === range.value }"
              @click="activeRange = range.value"
            >
              {{ range.label }}
            </button>
          </div>
        </header>
        <div ref="trendChartRef" class="analytics-chart"></div>
      </article>

      <article class="analytics-panel analytics-panel--conclusion">
        <header class="analytics-panel__head">
          <div>
            <h3>评估结论</h3>
            <p>模型部署前的关键判断</p>
          </div>
        </header>

        <div class="analytics-conclusions">
          <article
            v-for="item in mockData.conclusions"
            :key="item.title"
            class="analytics-conclusion"
          >
            <span class="analytics-conclusion__icon" :class="item.tone">
              <component :is="item.icon" />
            </span>
            <div>
              <h4>{{ item.title }}</h4>
              <p>{{ item.text }}</p>
            </div>
          </article>
        </div>
      </article>
    </section>

    <section class="analytics-bottom">
      <article class="analytics-panel analytics-panel--bottom">
        <h3>特征贡献</h3>
        <div class="analytics-feature-list">
          <div v-for="item in mockData.features" :key="item.name" class="analytics-feature">
            <div>
              <span>{{ item.name }}</span>
              <strong>{{ item.value }}%</strong>
            </div>
            <b><i :style="{ width: `${item.value}%` }"></i></b>
          </div>
        </div>
      </article>

      <article class="analytics-panel analytics-panel--bottom">
        <h3>数据质量检查</h3>
        <div class="analytics-row-list">
          <div v-for="item in displayQualityRows" :key="item.label" class="analytics-row">
            <span class="analytics-row__name">
              <i :class="item.tone">{{ item.mark }}</i>
              {{ item.label }}
            </span>
            <strong :class="item.valueClass">{{ item.value }}</strong>
          </div>
        </div>
      </article>

      <article class="analytics-panel analytics-panel--bottom">
        <h3>模型运行状态</h3>
        <div class="analytics-row-list">
          <div v-for="item in displayRuntimeRows" :key="item.label" class="analytics-row">
            <span class="analytics-row__name">
              <i :class="item.tone">{{ item.mark }}</i>
              {{ item.label }}
            </span>
            <strong :class="item.valueClass">{{ item.value }}</strong>
          </div>
        </div>
      </article>
    </section>
  </div>
</template>

<script setup>
import { computed, h, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  pageData: {
    type: Object,
    default: () => ({})
  },
  selectedDate: {
    type: String,
    default: ''
  }
})

const TargetIcon = (props) => h('svg', { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': '2.2', ...props }, [
  h('circle', { cx: '12', cy: '12', r: '8' }),
  h('circle', { cx: '12', cy: '12', r: '3' }),
  h('path', { d: 'M12 2v3M12 19v3M2 12h3M19 12h3' })
])

const ClockIcon = (props) => h('svg', { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': '2.2', ...props }, [
  h('circle', { cx: '12', cy: '12', r: '9' }),
  h('path', { d: 'M12 7v5l3 2' })
])

const DatabaseIcon = (props) => h('svg', { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': '2.2', ...props }, [
  h('ellipse', { cx: '12', cy: '5', rx: '8', ry: '3' }),
  h('path', { d: 'M4 5v6c0 1.7 3.6 3 8 3s8-1.3 8-3V5' }),
  h('path', { d: 'M4 11v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6' })
])

const RefreshIcon = (props) => h('svg', { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': '2.2', ...props }, [
  h('path', { d: 'M20 11a8 8 0 0 0-14.5-4.5L4 8' }),
  h('path', { d: 'M4 4v4h4' }),
  h('path', { d: 'M4 13a8 8 0 0 0 14.5 4.5L20 16' }),
  h('path', { d: 'M20 20v-4h-4' })
])

const CheckIcon = (props) => h('svg', { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': '2.4', ...props }, [
  h('path', { d: 'M20 6 9 17l-5-5' })
])

const BoltIcon = (props) => h('svg', { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': '2.4', ...props }, [
  h('path', { d: 'm13 2-9 13h7l-1 7 10-14h-7l0-6Z' })
])

const AlertIcon = (props) => h('svg', { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': '2.3', ...props }, [
  h('path', { d: 'M12 9v4' }),
  h('path', { d: 'M12 17h.01' }),
  h('path', { d: 'M10.3 3.9 2.6 17.2A2 2 0 0 0 4.3 20h15.4a2 2 0 0 0 1.7-2.8L13.7 3.9a2 2 0 0 0-3.4 0Z' })
])

const ranges = [
  { label: '近 7 天', value: '7d' },
  { label: '近 30 天', value: '30d' }
]

const activeRange = ref('7d')
const trendChartRef = ref(null)
let chartInstance = null
let resizeObserver = null

const fallbackData = {
  metrics: [
    {
      title: 'MAPE',
      value: '7.4%',
      status: '稳定',
      trend: '较上周 ▼ 0.6%',
      trendClass: 'is-good',
      icon: TargetIcon,
      tone: 'blue'
    },
    {
      title: '平均推理时延',
      value: '3.4 mins',
      status: '良好',
      trend: '较上周 ▼ 0.2 mins',
      trendClass: 'is-good',
      icon: ClockIcon,
      tone: 'indigo'
    },
    {
      title: '样本覆盖率',
      value: '99%',
      status: '已同步',
      trend: '较上周 ▲ 1.2%',
      trendClass: 'is-good',
      icon: DatabaseIcon,
      tone: 'green'
    },
    {
      title: '数据新鲜度',
      value: '1天',
      status: '需关注',
      trend: '较上周 0 天',
      trendClass: 'is-muted',
      icon: RefreshIcon,
      tone: 'orange'
    }
  ],
  trend: {
    dates: ['05-02', '05-03', '05-04', '05-05', '05-06', '05-07', '05-08'],
    mape: [8.8, 10.6, 10.5, 9.6, 9.6, 8.8, 9.1],
    peak: [5.5, 6.7, 6.8, 5.8, 6.0, 5.3, 5.1],
    latency: [1.8, 2.4, 2.6, 1.8, 2.1, 1.7, 1.9]
  },
  conclusions: [
    {
      title: '主干线路 OD 预测稳定',
      text: '整体误差保持在可控范围，预测结果可作为调度参考。',
      icon: CheckIcon,
      tone: 'is-blue'
    },
    {
      title: '当前推理时延适合分钟级更新',
      text: '服务耗时处于良好水平，满足滚动预测与快速回溯需求。',
      icon: BoltIcon,
      tone: 'is-green'
    },
    {
      title: '节假日样本仍需补充',
      text: '节假日与恶劣天气样本偏少，建议继续补齐特征维度。',
      icon: AlertIcon,
      tone: 'is-orange'
    }
  ],
  features: [
    { name: 'OD 历史序列', value: 92 },
    { name: '高峰时段识别', value: 84 },
    { name: '线路热度', value: 76 },
    { name: '天气特征', value: 61 }
  ],
  quality: [
    { label: '数据同步状态', value: '已完成', valueClass: 'is-good', mark: 'S', tone: 'is-muted' },
    { label: '缺失率', value: '0.8%', valueClass: '', mark: '%', tone: 'is-muted' },
    { label: '异常样本', value: '12 条', valueClass: '', mark: '!', tone: 'is-muted' },
    { label: '标签完整性', value: '高', valueClass: 'is-good', mark: 'T', tone: 'is-muted' }
  ],
  runtime: [
    { label: '服务状态', value: '正常', valueClass: 'is-good', mark: 'P', tone: 'is-muted' },
    { label: '最近更新时间', value: '08:00:00', valueClass: '', mark: 'C', tone: 'is-muted' },
    { label: '今日推理批次', value: '128', valueClass: '', mark: 'N', tone: 'is-muted' },
    { label: '最近一次评估任务', value: '已完成', valueClass: '', mark: 'R', tone: 'is-muted' }
  ]
}

const metricIconMap = {
  target: TargetIcon,
  clock: ClockIcon,
  database: DatabaseIcon,
  refresh: RefreshIcon
}

const conclusionIconMap = {
  check: CheckIcon,
  bolt: BoltIcon,
  alert: AlertIcon
}

function asArray(value) {
  return Array.isArray(value) ? value : []
}

function asText(value, fallback = '') {
  if (value === undefined || value === null || value === '') {
    return fallback
  }

  return String(value)
}

function asNumber(value, fallback = 0) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : fallback
}

function normalizeMetrics(data) {
  const cards = asArray(data.metricCards)
  if (cards.length) {
    return cards.map((item, index) => ({
      title: asText(item.title, fallbackData.metrics[index]?.title),
      value: asText(item.value, fallbackData.metrics[index]?.value),
      status: asText(item.status, fallbackData.metrics[index]?.status),
      trend: asText(item.trend, fallbackData.metrics[index]?.trend),
      trendClass: asText(item.trendClass, fallbackData.metrics[index]?.trendClass || 'is-muted'),
      icon: metricIconMap[item.iconKey] || fallbackData.metrics[index]?.icon || TargetIcon,
      tone: asText(item.tone, fallbackData.metrics[index]?.tone || 'blue')
    }))
  }

  const metrics = data.metrics || {}
  if (Object.keys(metrics).length) {
    return [
      { title: 'MAPE', value: `${asNumber(metrics.mape, 0)}%`, status: '动态计算', trend: `场景 ${asNumber(metrics.scenarioCount, 0)} 个`, trendClass: 'is-good', icon: TargetIcon, tone: 'blue' },
      { title: '平均推理时延', value: `${asNumber(metrics.avgLatencyMinutes, 0)} mins`, status: '动态计算', trend: `有效日期 ${asNumber(metrics.effectiveDays, 0)} 天`, trendClass: 'is-good', icon: ClockIcon, tone: 'indigo' },
      { title: '样本覆盖率', value: `${asNumber(metrics.sampleCoverage, 0)}%`, status: '动态计算', trend: '来自后端样本聚合', trendClass: 'is-good', icon: DatabaseIcon, tone: 'green' },
      { title: '数据新鲜度', value: `${asNumber(metrics.dataFreshnessDays, 0)}天`, status: '动态计算', trend: `有效日期 ${asNumber(metrics.effectiveDays, 0)} 天`, trendClass: 'is-muted', icon: RefreshIcon, tone: 'orange' }
    ]
  }

  return fallbackData.metrics
}

function normalizeTrend(data) {
  const trend = data.trend || {}
  const dates = asArray(trend.dates)
  if (!dates.length) {
    return fallbackData.trend
  }

  return {
    dates: dates.map((item) => asText(item)),
    mape: asArray(trend.mape).map((item) => asNumber(item)),
    peak: asArray(trend.peak).map((item) => asNumber(item)),
    latency: asArray(trend.latency).map((item) => asNumber(item))
  }
}

function normalizeConclusions(data) {
  const items = asArray(data.conclusions)
  if (!items.length) {
    return fallbackData.conclusions
  }

  return items.map((item, index) => ({
    title: asText(item.title, fallbackData.conclusions[index]?.title),
    text: asText(item.text, fallbackData.conclusions[index]?.text),
    icon: conclusionIconMap[item.iconKey] || fallbackData.conclusions[index]?.icon || CheckIcon,
    tone: asText(item.tone, fallbackData.conclusions[index]?.tone || 'is-blue')
  }))
}

function normalizeRows(rows, fallback) {
  const items = asArray(rows)
  if (!items.length) {
    return fallback
  }

  return items.map((item, index) => ({
    label: asText(item.label, fallback[index]?.label),
    value: asText(item.value, fallback[index]?.value),
    valueClass: asText(item.valueClass, fallback[index]?.valueClass),
    mark: asText(item.mark, fallback[index]?.mark),
    tone: asText(item.tone, fallback[index]?.tone || 'is-muted')
  }))
}

function normalizeFeatures(data) {
  const items = asArray(data.features)
  if (!items.length) {
    return fallbackData.features
  }

  return items.map((item, index) => ({
    name: asText(item.name, fallbackData.features[index]?.name),
    value: Math.max(0, Math.min(100, asNumber(item.value, fallbackData.features[index]?.value || 0)))
  }))
}

const mockData = computed(() => {
  const data = props.pageData || {}

  return {
    metrics: normalizeMetrics(data),
    trend: normalizeTrend(data),
    conclusions: normalizeConclusions(data),
    features: normalizeFeatures(data),
    quality: normalizeRows(data.quality, fallbackData.quality),
    runtime: normalizeRows(data.runtime, fallbackData.runtime)
  }
})

const displayQualityRows = computed(() => mockData.value.quality.filter((item) => {
  const label = asText(item.label)
  return item.mark !== '!' && !label.includes('异常')
}))

const displayRuntimeRows = computed(() => mockData.value.runtime.filter((item) => {
  const label = asText(item.label)
  return item.mark !== 'N' && !label.includes('推理批次')
}))

function getTrendOption() {
  const isThirtyDays = activeRange.value === '30d'
  const multiplier = isThirtyDays ? 1.06 : 1
  const trendData = mockData.value.trend

  return {
    animationDuration: 760,
    color: ['#1677ff', '#12b58f', '#ff9d12'],
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(255, 255, 255, 0.98)',
      borderColor: '#d8e6f2',
      borderWidth: 1,
      extraCssText: 'box-shadow: 0 16px 32px rgba(31, 57, 84, 0.12); border-radius: 12px;',
      textStyle: { color: '#14213d', fontSize: 12, fontWeight: 700 }
    },
    legend: {
      top: 10,
      right: 20,
      icon: 'roundRect',
      itemWidth: 16,
      itemHeight: 7,
      itemGap: 18,
      textStyle: { color: '#61738b', fontWeight: 800, fontSize: 12 }
    },
    grid: {
      top: 68,
      left: 46,
      right: 26,
      bottom: 34
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: trendData.dates,
      axisLine: { lineStyle: { color: '#d7e2ec' } },
      axisTick: { show: false },
      axisLabel: { color: '#8a9bb0', fontSize: 12, fontWeight: 800 }
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: 12,
      interval: 2,
      splitLine: { lineStyle: { color: '#edf3f8' } },
      axisLabel: { color: '#8a9bb0', fontSize: 12, fontWeight: 800 }
    },
    series: [
      {
        name: 'MAPE 趋势',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 8,
        lineStyle: { width: 3.5 },
        itemStyle: { borderColor: '#fff', borderWidth: 2 },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(22, 119, 255, 0.18)' },
            { offset: 1, color: 'rgba(22, 119, 255, 0.02)' }
          ])
        },
        data: trendData.mape.map((value) => +(value * multiplier).toFixed(1))
      },
      {
        name: '高峰误差波动',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 8,
        lineStyle: { width: 3.5 },
        itemStyle: { borderColor: '#fff', borderWidth: 2 },
        data: trendData.peak.map((value) => +(value * multiplier).toFixed(1))
      },
      {
        name: '推理时延',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 7,
        lineStyle: { width: 3, type: 'dashed' },
        itemStyle: { borderColor: '#fff', borderWidth: 2 },
        data: trendData.latency.map((value) => +(value * multiplier).toFixed(1))
      }
    ]
  }
}

function renderChart() {
  if (!trendChartRef.value) return

  if (!chartInstance) {
    chartInstance = echarts.init(trendChartRef.value)
  }

  chartInstance.setOption(getTrendOption(), true)
  chartInstance.resize()
}

function resizeChart() {
  chartInstance?.resize()
}

watch(activeRange, () => {
  nextTick(renderChart)
})

watch(
  () => props.pageData,
  () => {
    nextTick(renderChart)
  },
  { deep: true }
)

onMounted(() => {
  nextTick(renderChart)
  window.addEventListener('resize', resizeChart)

  if (trendChartRef.value && window.ResizeObserver) {
    resizeObserver = new ResizeObserver(resizeChart)
    resizeObserver.observe(trendChartRef.value)
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeChart)
  resizeObserver?.disconnect()
  chartInstance?.dispose()
  chartInstance = null
})
</script>

<style scoped>
.analytics-page {
  display: grid;
  grid-template-rows: clamp(104px, 15vh, 126px) minmax(0, 1fr) clamp(188px, 24vh, 232px);
  gap: 14px;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  padding: 18px;
  border: 1px solid rgba(213, 226, 239, 0.92);
  border-radius: 28px;
  background:
    radial-gradient(circle at 16% 0%, rgba(24, 134, 255, 0.08), transparent 28%),
    radial-gradient(circle at 92% 18%, rgba(24, 185, 146, 0.08), transparent 24%),
    linear-gradient(180deg, #fbfdff 0%, #f4f8fc 100%);
  color: #14213d;
  box-shadow: 0 24px 56px rgba(31, 57, 84, 0.09);
}

.analytics-metrics,
.analytics-core,
.analytics-bottom {
  min-height: 0;
}

.analytics-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.analytics-panel,
.analytics-metric {
  min-width: 0;
  border: 1px solid rgba(215, 226, 238, 0.92);
  background: rgba(255, 255, 255, 0.95);
  box-shadow:
    0 18px 38px rgba(31, 57, 84, 0.08),
    inset 0 1px 0 rgba(255, 255, 255, 0.88);
}

.analytics-metric {
  position: relative;
  display: grid;
  grid-template-columns: clamp(40px, 3.6vw, 50px) minmax(0, 1fr);
  align-items: center;
  gap: 12px;
  overflow: hidden;
  padding: 14px 16px 17px;
  border-radius: 20px;
}

.analytics-metric--blue { --metric-color: #1677ff; --metric-soft: rgba(22, 119, 255, 0.12); --metric-glow: rgba(22, 119, 255, 0.2); }
.analytics-metric--indigo { --metric-color: #4f46e5; --metric-soft: rgba(79, 70, 229, 0.12); --metric-glow: rgba(79, 70, 229, 0.18); }
.analytics-metric--green { --metric-color: #13b58d; --metric-soft: rgba(19, 181, 141, 0.14); --metric-glow: rgba(19, 181, 141, 0.18); }
.analytics-metric--orange { --metric-color: #ff5a1f; --metric-soft: rgba(255, 90, 31, 0.13); --metric-glow: rgba(255, 90, 31, 0.16); }

.analytics-metric__icon {
  display: grid;
  width: clamp(40px, 3.6vw, 50px);
  height: clamp(40px, 3.6vw, 50px);
  place-items: center;
  border-radius: 50%;
  background: var(--metric-soft);
  color: var(--metric-color);
  box-shadow: 0 10px 20px var(--metric-glow);
}

.analytics-metric__icon svg {
  width: 58%;
  height: 58%;
}

.analytics-metric__body {
  min-width: 0;
}

.analytics-metric__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.analytics-metric__head p {
  margin: 0;
  overflow: hidden;
  color: #5f7087;
  font-size: clamp(12px, 0.86vw, 14px);
  font-weight: 900;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.analytics-metric__head span {
  flex: 0 0 auto;
  max-width: 76px;
  padding: 4px 10px;
  border-radius: 999px;
  background: var(--metric-soft);
  color: var(--metric-color);
  font-size: 11px;
  font-weight: 950;
  line-height: 1;
  white-space: nowrap;
}

.analytics-metric strong {
  display: block;
  margin-top: 5px;
  color: #0b1530;
  font-size: clamp(22px, 1.9vw, 28px);
  font-weight: 950;
  line-height: 1;
  white-space: nowrap;
}

.analytics-metric small {
  display: block;
  margin-top: 6px;
  overflow: hidden;
  color: #8a9bb0;
  font-size: 12px;
  font-weight: 950;
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.analytics-metric small.is-good {
  color: #119b81;
}

.analytics-metric__bar {
  position: absolute;
  left: 16px;
  right: 16px;
  bottom: 10px;
  height: 4px;
  border-radius: 999px;
  background: linear-gradient(90deg, var(--metric-color), color-mix(in srgb, var(--metric-color) 72%, #21e2c5));
  box-shadow: 0 0 18px var(--metric-glow);
}

.analytics-core {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(340px, 1fr);
  gap: 16px;
}

.analytics-panel {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 22px;
  border-radius: 22px;
}

.analytics-panel__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
}

.analytics-panel__head h3,
.analytics-panel--bottom h3 {
  margin: 0;
  color: #0b1530;
  font-size: clamp(18px, 1.55vw, 25px);
  font-weight: 950;
  line-height: 1.15;
}

.analytics-panel__head p {
  margin: 7px 0 0;
  color: #8292a8;
  font-size: 13px;
  font-weight: 850;
}

.analytics-switch {
  display: inline-flex;
  flex: 0 0 auto;
  gap: 5px;
  padding: 5px;
  border-radius: 999px;
  background: #f2f6fb;
  box-shadow: inset 0 0 0 1px rgba(215, 226, 238, 0.8);
}

.analytics-switch button {
  min-width: 70px;
  height: 36px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: #64748b;
  font-size: 13px;
  font-weight: 950;
  cursor: pointer;
  transition: all 0.2s ease;
}

.analytics-switch button.is-active {
  background: #1677ff;
  color: #ffffff;
  box-shadow: 0 12px 22px rgba(22, 119, 255, 0.22);
}

.analytics-chart {
  flex: 1;
  min-height: 0;
  margin-top: 8px;
}

.analytics-conclusions {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
  margin-top: 10px;
  overflow-y: auto;
  padding-right: 4px;
  scrollbar-width: thin;
  scrollbar-color: rgba(22, 119, 255, 0.3) transparent;
}

.analytics-conclusions::-webkit-scrollbar {
  width: 6px;
}

.analytics-conclusions::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: rgba(22, 119, 255, 0.3);
}

.analytics-conclusion {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr);
  align-items: center;
  gap: 10px;
  flex: 0 0 auto;
  min-height: 56px;
  padding: 8px 12px;
  border-radius: 15px;
  background: linear-gradient(180deg, #fbfdff 0%, #f7fbff 100%);
  box-shadow: inset 0 0 0 1px rgba(215, 226, 238, 0.9);
}

.analytics-conclusion__icon {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 50%;
  color: #ffffff;
}

.analytics-conclusion__icon.is-blue { background: linear-gradient(135deg, #1677ff, #0f63d9); }
.analytics-conclusion__icon.is-green { background: linear-gradient(135deg, #16c78f, #0da571); }
.analytics-conclusion__icon.is-orange { background: linear-gradient(135deg, #ff9f17, #ff7a1a); }

.analytics-conclusion__icon svg {
  width: 52%;
  height: 52%;
}

.analytics-conclusion h4 {
  margin: 0;
  color: #10203b;
  font-size: clamp(13px, 0.95vw, 15px);
  font-weight: 950;
  line-height: 1.25;
}

.analytics-conclusion p {
  margin: 3px 0 0;
  color: #697a91;
  font-size: 12px;
  font-weight: 760;
  line-height: 1.26;
}

.analytics-bottom {
  display: grid;
  grid-template-columns: minmax(0, 1.04fr) minmax(0, 1fr) minmax(0, 1.08fr);
  gap: 14px;
}

.analytics-panel--bottom {
  padding: 20px 22px;
}

.analytics-panel--bottom h3 {
  font-size: clamp(16px, 1.25vw, 22px);
}

.analytics-feature-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 28px;
  row-gap: 20px;
  margin-top: 20px;
}

.analytics-feature div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.analytics-feature span,
.analytics-row__name {
  overflow: hidden;
  color: #61738b;
  font-size: 13px;
  font-weight: 900;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.analytics-feature strong,
.analytics-row strong {
  color: #0b1530;
  font-size: 13px;
  font-weight: 950;
  white-space: nowrap;
}

.analytics-feature b {
  display: block;
  height: 7px;
  margin-top: 12px;
  overflow: hidden;
  border-radius: 999px;
  background: #e8eef5;
}

.analytics-feature i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #16a4ff, #16c78f);
}

.analytics-row-list {
  display: grid;
  gap: 10px;
  min-height: 0;
  margin-top: 16px;
}

.analytics-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-height: 40px;
  padding: 9px 13px;
  border-radius: 12px;
  background: rgba(248, 251, 254, 0.9);
  box-shadow: inset 0 0 0 1px rgba(226, 233, 240, 0.92);
}

.analytics-row__name {
  display: inline-flex;
  flex: 1 1 auto;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.analytics-row__name i {
  display: grid;
  width: 21px;
  height: 21px;
  flex: 0 0 21px;
  place-items: center;
  border-radius: 50%;
  background: #f1f5f9;
  color: #7b8aa0;
  font-size: 10px;
  font-style: normal;
  font-weight: 950;
}

.analytics-row strong.is-good {
  color: #16b889;
}

.analytics-row strong {
  max-width: 48%;
  min-width: 0;
  overflow: hidden;
  text-align: right;
  text-overflow: ellipsis;
}

@media (max-width: 1360px) {
  .analytics-page {
    grid-template-rows: 104px minmax(0, 1fr) 192px;
    gap: 12px;
    padding: 14px;
  }

  .analytics-metrics,
  .analytics-core,
  .analytics-bottom {
    gap: 12px;
  }

  .analytics-metric {
    grid-template-columns: 42px minmax(0, 1fr);
    gap: 12px;
    padding: 12px 16px 16px;
  }

  .analytics-metric__icon {
    width: 42px;
    height: 42px;
  }

  .analytics-metric strong {
    font-size: 24px;
  }

  .analytics-metric__head span {
    padding: 5px 10px;
    font-size: 11px;
  }

  .analytics-metric small {
    margin-top: 6px;
    font-size: 11px;
  }

  .analytics-metric__bar {
    left: 16px;
    right: 16px;
    bottom: 10px;
    height: 4px;
  }

  .analytics-panel {
    padding: 14px;
  }

  .analytics-panel__head h3,
  .analytics-panel--bottom h3 {
    font-size: 18px;
  }

  .analytics-conclusions {
    gap: 7px;
    margin-top: 8px;
  }

  .analytics-conclusion {
    grid-template-columns: 34px minmax(0, 1fr);
    gap: 10px;
    padding: 7px 10px;
    min-height: 60px;
  }

  .analytics-conclusion__icon {
    width: 34px;
    height: 34px;
  }

  .analytics-conclusion h4 {
    font-size: 13px;
  }

  .analytics-conclusion p {
    margin-top: 4px;
    font-size: 12px;
    line-height: 1.32;
  }

  .analytics-feature-list {
    column-gap: 12px;
    row-gap: 10px;
    margin-top: 13px;
  }

  .analytics-feature b {
    margin-top: 6px;
    height: 6px;
  }

  .analytics-row-list {
    gap: 8px;
    margin-top: 12px;
  }

  .analytics-row {
    min-height: 36px;
    padding: 8px 10px;
  }
}

@media (max-width: 900px) {
  .analytics-page {
    grid-template-rows: auto auto auto;
    overflow: auto;
  }

  .analytics-metrics,
  .analytics-core,
  .analytics-bottom {
    grid-template-columns: 1fr;
  }

  .analytics-chart {
    min-height: 300px;
  }
}
</style>
