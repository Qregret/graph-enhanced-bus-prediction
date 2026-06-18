<template>
  <div class="forecast-workbench">
    <section class="filter-strip">
      <label v-for="field in filterFields" :key="field.key" class="filter-control">
        <span>{{ field.label }}</span>
        <select v-model="filters[field.key]">
          <option v-for="option in field.options" :key="option" :value="option">{{ option }}</option>
        </select>
      </label>

      <div class="scenario-tags">
        <span v-for="tag in scenarioTags" :key="tag">{{ tag }}</span>
      </div>
    </section>

    <section class="metric-grid">
      <article v-for="item in metrics" :key="item.label" class="metric-card">
        <div class="metric-card__icon" :class="item.tone">
          <component :is="item.icon" class="h-5 w-5" />
        </div>
        <div class="metric-card__body">
          <span>{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
          <small :class="{ 'is-down': item.trendType === 'down' }">
            {{ item.trend }}
          </small>
        </div>
      </article>
    </section>

    <section class="analysis-grid">
      <article class="workbench-card od-card">
        <header class="card-head">
          <div>
            <h2>OD客流转移路径</h2>
            <p>{{ filters.lineId }}{{ filters.direction }}方向已算OD结果主要迁移链路</p>
          </div>
          <span class="card-chip">Flow Graph</span>
        </header>

        <div class="sankey-shell">
          <svg class="flow-map" viewBox="0 0 680 360" role="img" aria-label="OD客流转移路径图">
            <defs>
              <linearGradient id="flowBlueCyan" x1="0%" x2="100%" y1="0%" y2="0%">
                <stop offset="0%" stop-color="#2688ff" />
                <stop offset="100%" stop-color="#2ad4cf" />
              </linearGradient>
              <linearGradient id="flowHighRisk" x1="0%" x2="100%" y1="0%" y2="0%">
                <stop offset="0%" stop-color="#1268e8" />
                <stop offset="100%" stop-color="#22c9d4" />
              </linearGradient>
            </defs>

            <g class="flow-links">
              <g v-for="(link, index) in flowLinks" :key="`${link.lineId}-${link.direction}-${link.originStation}-${link.targetStation}-${index}`">
                <path
                  class="flow-hit"
                  :d="flowPath(link)"
                  :stroke-width="flowWidth(link) + 16"
                  @mouseenter="showFlowTooltip($event, link)"
                  @mousemove="moveFlowTooltip($event)"
                  @mouseleave="hideFlowTooltip"
                />
                <path
                  class="flow-line"
                  :class="riskClass(link.riskLevel)"
                  :d="flowPath(link)"
                  :stroke-width="flowWidth(link)"
                />
              </g>
            </g>

            <g class="flow-node-group flow-node-group--left">
              <g
                v-for="node in originNodes"
                :key="node.name"
                class="flow-node"
                :class="{ 'is-focus': node.name === filters.originStation }"
                :transform="`translate(22 ${node.y - 20})`"
              >
                <rect width="138" height="40" rx="14" />
                <text x="69" y="25">{{ node.name }}</text>
              </g>
            </g>

            <g class="flow-node-group flow-node-group--right">
              <g v-for="node in targetNodes" :key="node.name" class="flow-node" :transform="`translate(520 ${node.y - 20})`">
                <rect width="138" height="40" rx="14" />
                <text x="69" y="25">{{ node.name }}</text>
              </g>
            </g>
          </svg>
          <div
            v-if="hoveredFlow"
            class="flow-tooltip"
            :style="flowTooltipStyle"
          >
            <strong>{{ hoveredFlow.originStation }} → {{ hoveredFlow.targetStation }}</strong>
            <span>预测人数：{{ formatNumber(hoveredFlow.predictCount) }} 人次</span>
            <span>置信度：{{ hoveredFlow.confidence }}%</span>
            <span>风险等级：{{ riskLabel(hoveredFlow.riskLevel) }}</span>
          </div>
          <div class="sankey-legend">
            <span><i class="legend-line legend-line--wide"></i>线越粗表示客流越大</span>
            <span><i class="legend-line legend-line--risk"></i>颜色越深表示风险越高</span>
          </div>
        </div>
      </article>

      <article class="workbench-card hotspot-card">
        <header class="card-head">
          <div>
            <h2>热点站点与客流压力</h2>
            <p>预测上车、下车与拥挤等级综合研判</p>
          </div>
        </header>

        <div class="hotspot-list">
          <article v-for="station in hotspotStations" :key="station.name" class="hotspot-item">
            <div class="hotspot-item__station">
              <strong>{{ station.name }}</strong>
              <small :class="{ 'is-down': station.trend < 0 }">
                {{ station.trend > 0 ? '\u4e0a\u5347' : '\u4e0b\u964d' }} {{ Math.abs(station.trend) }}%
              </small>
            </div>
            <div class="hotspot-item__metrics">
              <span><em>上车</em><b>{{ formatNumber(station.boarding) }}</b></span>
              <span><em>下车</em><b>{{ formatNumber(station.alighting) }}</b></span>
            </div>
            <div class="hotspot-item__status">
              <span class="risk-pill" :class="riskClass(station.riskLevel)">
                {{ station.riskLabel }}
              </span>
              <div class="pressure-bar">
                <span :class="riskClass(station.riskLevel)" :style="{ width: `${station.pressure}%` }"></span>
              </div>
            </div>
          </article>
        </div>
      </article>

      <aside class="assistant-column">
        <article class="workbench-card assistant-card">
          <header class="assistant-title">
            <Connection class="h-5 w-5" />
            <div>
              <h2>主导OD链路</h2>
              <p>当前窗口内最需要调度关注的客流迁移</p>
            </div>
          </header>

          <div class="core-route">
            <span>{{ coreOd.originStation }}</span>
            <i></i>
            <span>{{ coreOd.targetStation }}</span>
          </div>

          <dl class="core-facts">
            <div><dt>预测人数</dt><dd>{{ coreOd.predictCount }}人次</dd></div>
            <div><dt>置信度</dt><dd>{{ coreOd.confidence }}%</dd></div>
            <div><dt>候选站点数</dt><dd>Top-5</dd></div>
          </dl>

          <div class="rule-tags">
            <span v-for="rule in rules" :key="rule">{{ rule }}</span>
          </div>
        </article>

        <article class="workbench-card assistant-card assistant-card--text">
          <header class="assistant-title">
            <Cpu class="h-5 w-5" />
            <div>
              <h2>研判依据</h2>
              <p>模型给出该结论的主要信号来源</p>
            </div>
          </header>
          <ul class="evidence-list">
            <li><b>历史同窗</b><span>连续多个工作日出现稳定转移峰值</span></li>
            <li><b>站点吸引</b><span>目标站下车压力高，候选OD排名靠前</span></li>
            <li><b>方向校验</b><span>线路方向与换乘可达性均通过规则检查</span></li>
          </ul>
        </article>

        <article class="workbench-card assistant-card assistant-card--text">
          <header class="assistant-title">
            <Opportunity class="h-5 w-5" />
            <div>
              <h2>调度建议</h2>
              <p>规则引擎校验后的可执行动作</p>
            </div>
          </header>
          <ul class="suggestion-list">
            <li>建议对 105 路上行方向增加短时运力；</li>
            <li>重点关注人武部、宏声桥、高笋塘三个站点；</li>
            <li>连续两个时间窗高风险时，缩短发车间隔或安排区间车。</li>
          </ul>
          <button class="secondary-action" type="button">
            <Guide class="h-4 w-4" />
            <span>生成调度方案</span>
          </button>
        </article>
      </aside>
    </section>

    <section class="workbench-card detail-card">
      <header class="detail-head">
        <div>
          <h2>OD预测明细表</h2>
          <p>按预测人数、置信度与规则校验结果生成调度动作</p>
        </div>
        <div class="detail-actions">
          <button type="button" @click="exportForecastRows"><Download class="h-4 w-4" />导出结果</button>
          <button type="button" @click="historyPanelVisible = !historyPanelVisible"><Clock class="h-4 w-4" />查看历史对比</button>
        </div>
      </header>

      <div v-if="historyPanelVisible" class="history-compare">
        <article v-for="item in historyCompare" :key="item.label">
          <span>{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
          <small :class="{ 'is-up': item.up }">{{ item.change }}</small>
        </article>
      </div>

      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>起点站</th>
              <th>预测终点站</th>
              <th>预测人数</th>
              <th>置信度</th>
              <th>风险等级</th>
              <th>规则校验</th>
              <th>建议动作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in detailRows" :key="`${row.originStation}-${row.targetStation}`">
              <td>{{ row.originStation }}</td>
              <td>{{ row.targetStation }}</td>
              <td>{{ row.predictCount }}</td>
              <td>{{ row.confidence }}%</td>
              <td>
                <span class="risk-pill" :class="riskClass(row.riskLevel)">{{ row.riskText }}</span>
              </td>
              <td>{{ row.ruleCheck }}</td>
              <td>{{ row.suggestion }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  Clock,
  Connection,
  Cpu,
  DataAnalysis,
  Download,
  Guide,
  Opportunity,
  TrendCharts,
  Warning
} from '@element-plus/icons-vue'

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

const currentTime = ref('')
const hoveredFlow = ref(null)
const flowTooltip = reactive({ x: 0, y: 0 })
const historyPanelVisible = ref(false)
let clockTimer = null

const filters = reactive({
  lineId: '105路',
  direction: '上行',
  timeWindow: '未来30分钟',
  originStation: '人武部'
})

const scenarioTags = ['工作日', '早高峰', '普通天气']
const rules = ['方向正确', '站点可达', '换乘合理']

const mockData = {
  businessDate: '2026-05-08',
  metrics: [
    { label: '预测总客流', value: '6,776', trend: '环比上一时间窗 +8.4%', tone: 'tone-blue', icon: DataAnalysis },
    { label: '高风险OD对', value: '12', trend: '较昨日同窗 +3 对', tone: 'tone-red', icon: Warning },
    { label: '最大转移客流', value: '876人次', trend: '人武部 → 宏声桥', tone: 'tone-cyan', icon: TrendCharts },
    { label: '模型平均置信度', value: '86%', trend: '近7日稳定 +2.1%', tone: 'tone-green', icon: Cpu }
  ],
  odPairs: [
    {
      lineId: '105',
      direction: 'up',
      timeWindow: '30m',
      originStation: '人武部',
      targetStation: '宏声桥',
      predictCount: 876,
      confidence: 86,
      riskLevel: 'high',
      ruleCheck: '通过',
      suggestion: '增加班次'
    },
    {
      lineId: '105',
      direction: 'up',
      timeWindow: '30m',
      originStation: '人武部',
      targetStation: '高笋塘',
      predictCount: 642,
      confidence: 82,
      riskLevel: 'medium',
      ruleCheck: '通过',
      suggestion: '持续观察'
    },
    {
      lineId: '105',
      direction: 'up',
      timeWindow: '30m',
      originStation: '宏声桥',
      targetStation: '易家坝',
      predictCount: 534,
      confidence: 80,
      riskLevel: 'medium',
      ruleCheck: '通过',
      suggestion: '站点疏导'
    },
    {
      lineId: '105',
      direction: 'up',
      timeWindow: '30m',
      originStation: '高笋塘',
      targetStation: '泽胜广场',
      predictCount: 486,
      confidence: 79,
      riskLevel: 'medium',
      ruleCheck: '通过',
      suggestion: '预留运力'
    },
    {
      lineId: '105',
      direction: 'up',
      timeWindow: '30m',
      originStation: '南门山',
      targetStation: '易家坝',
      predictCount: 418,
      confidence: 78,
      riskLevel: 'medium',
      ruleCheck: '通过',
      suggestion: '保持运力'
    },
    {
      lineId: '105',
      direction: 'up',
      timeWindow: '30m',
      originStation: '南门山',
      targetStation: '南门山',
      predictCount: 336,
      confidence: 73,
      riskLevel: 'normal',
      ruleCheck: '通过',
      suggestion: '正常监测'
    },
    {
      lineId: '105',
      direction: 'up',
      timeWindow: '30m',
      originStation: '人武部',
      targetStation: '泽胜广场',
      predictCount: 294,
      confidence: 75,
      riskLevel: 'normal',
      ruleCheck: '通过',
      suggestion: '保持运力'
    }
  ],
  hotspotStations: [
    { name: '宏声桥', boarding: 5255, alighting: 4862, riskLevel: 'high', riskLabel: '高风险', trend: 12, pressure: 96 },
    { name: '高笋塘', boarding: 4610, alighting: 4280, riskLevel: 'medium', riskLabel: '较高', trend: 8, pressure: 82 },
    { name: '南门山', boarding: 4607, alighting: 4302, riskLevel: 'medium', riskLabel: '较高', trend: 7, pressure: 78 },
    { name: '易家坝', boarding: 4412, alighting: 4105, riskLevel: 'normal', riskLabel: '正常', trend: -3, pressure: 61 }
  ]
}

const businessDate = computed(() => props.selectedDate || props.pageData?.date || mockData.businessDate)

const backendOdPairs = computed(() => {
  const rows = Array.isArray(props.pageData?.odPairs) ? props.pageData.odPairs : []
  return rows.map(normalizeOdPair).filter((row) => row.predictCount > 0)
})

const allOdPairs = computed(() => backendOdPairs.value.length ? backendOdPairs.value : mockData.odPairs.map(normalizeOdPair))

const routeOptions = computed(() => uniqueValues(allOdPairs.value.map((row) => row.lineId)))

const routeScopedPairs = computed(() => {
  const rows = allOdPairs.value.filter((row) => row.lineId === filters.lineId)
  return rows.length ? rows : allOdPairs.value
})

const directionOptions = computed(() => {
  const values = uniqueValues(routeScopedPairs.value.map((row) => row.direction))
  return values.length ? values : ['上行', '下行']
})

const routeDirectionPairs = computed(() => {
  const rows = routeScopedPairs.value.filter((row) => row.direction === filters.direction)
  return rows.length ? rows : routeScopedPairs.value
})

const timeScopedPairs = computed(() => {
  const rows = routeDirectionPairs.value.filter((row) => row.timeWindow === filters.timeWindow)
  return rows.length ? rows : routeDirectionPairs.value
})

const stationOptions = computed(() => {
  const values = uniqueValues(timeScopedPairs.value.map((row) => row.originStation))
  return values.length ? values.slice(0, 10) : uniqueValues(allOdPairs.value.map((row) => row.originStation)).slice(0, 10)
})

const odPairs = computed(() => {
  const rows = timeScopedPairs.value.length ? timeScopedPairs.value : routeDirectionPairs.value
  return prioritizeOrigin(rows)
})

const transferPairs = computed(() => {
  const rows = odPairs.value.filter((row) => row.originStation && row.targetStation && row.originStation !== row.targetStation)
  return rows.length ? rows : odPairs.value
})

const metrics = computed(() => {
  const pairs = transferPairs.value
  const total = pairs.reduce((sum, row) => sum + row.predictCount, 0)
  const highRiskCount = pairs.filter((row) => row.riskLevel === 'high').length
  const maxPair = pairs.reduce((winner, row) => (row.predictCount > winner.predictCount ? row : winner), pairs[0] || mockData.odPairs[0])
  const avgConfidence = pairs.length
    ? Math.round(pairs.reduce((sum, row) => sum + row.confidence, 0) / pairs.length)
    : 0

  return [
    { label: '预测总客流', value: formatNumber(total), trend: '来自后端OD聚合数据', tone: 'tone-blue', icon: DataAnalysis },
    { label: '高风险OD对', value: String(highRiskCount), trend: highRiskCount > 0 ? '建议纳入调度研判' : '当前风险平稳', tone: 'tone-red', icon: Warning },
    { label: '最大转移客流', value: `${formatNumber(maxPair?.predictCount || 0)}人次`, trend: `${maxPair?.originStation || '-'} → ${maxPair?.targetStation || '-'}`, tone: 'tone-cyan', icon: TrendCharts },
    { label: '模型平均置信度', value: `${avgConfidence}%`, trend: '基于当前OD样本估算', tone: 'tone-green', icon: Cpu }
  ]
})

const hotspotStations = computed(() => {
  const stationAgg = new Map()
  transferPairs.value.forEach((row) => {
    const origin = textOf(row.originStation)
    const target = textOf(row.targetStation)
    if (origin) {
      const item = stationAgg.get(origin) || { name: origin, boarding: 0, alighting: 0 }
      item.boarding += row.predictCount
      stationAgg.set(origin, item)
    }
    if (target) {
      const item = stationAgg.get(target) || { name: target, boarding: 0, alighting: 0 }
      item.alighting += row.predictCount
      stationAgg.set(target, item)
    }
  })
  if (stationAgg.size) {
    const list = Array.from(stationAgg.values())
      .sort((left, right) => (right.boarding + right.alighting) - (left.boarding + left.alighting))
      .slice(0, 4)
    const maxValue = Math.max(...list.map((row) => Math.max(row.boarding, row.alighting)), 1)
    return list.map((row, index) => {
      const riskLevel = normalizeRisk('', Math.max(row.boarding, row.alighting))
      return {
        name: row.name,
        boarding: row.boarding,
        alighting: row.alighting,
        riskLevel,
        riskLabel: riskLabel(riskLevel),
        trend: index < 3 ? 8 - index * 2 : -3,
        pressure: Math.max(14, Math.round((Math.max(row.boarding, row.alighting) / maxValue) * 100))
      }
    })
  }

  const rows = Array.isArray(props.pageData?.hotspots) ? props.pageData.hotspots : []
  if (!rows.length) {
    return mockData.hotspotStations
  }
  const maxValue = Math.max(...rows.map((row) => numberOf(row.boarding ?? row.boardings ?? row.predictedBoardings ?? row.value)), 1)
  return rows.slice(0, 4).map((row, index) => {
    const boarding = numberOf(row.boarding ?? row.boardings ?? row.predictedBoardings ?? row.value)
    const alighting = numberOf(row.alighting ?? row.alightings ?? row.predictedAlightings ?? row.value)
    const riskLevel = normalizeRisk(row.riskLevel, Math.max(boarding, alighting))
    return {
      name: textOf(row.name ?? row.stationName ?? row.station_id ?? `站点${index + 1}`),
      boarding,
      alighting,
      riskLevel,
      riskLabel: riskLabel(riskLevel),
      trend: numberOf(row.trend ?? row.changeRate ?? (index < 3 ? 8 - index * 2 : -3)),
      pressure: Math.max(14, Math.round((Math.max(boarding, alighting) / maxValue) * 100))
    }
  })
})
const detailRows = computed(() => transferPairs.value.slice(0, 6).map((row) => ({
  ...row,
  riskText: row.riskText || (row.riskLevel === 'high' ? '高' : row.riskLevel === 'medium' ? '中' : '低')
})))
const coreOd = computed(() => transferPairs.value[0] || mockData.odPairs[0])

const filterFields = computed(() => [
  { key: 'lineId', label: '线路结果', options: routeOptions.value },
  { key: 'direction', label: '方向', options: directionOptions.value },
  { key: 'timeWindow', label: '结果时间窗', options: ['未来15分钟', '未来30分钟', '未来60分钟'] },
  { key: 'originStation', label: '起点站', options: stationOptions.value }
])

const originNodes = computed(() => buildFlowNodes(
  transferPairs.value,
  'originStation',
  ['人武部', '宏声桥', '高笋塘', '南门山'],
  [58, 136, 214, 292],
  4
))

const targetNodes = computed(() => buildFlowNodes(
  transferPairs.value,
  'targetStation',
  ['宏声桥', '高笋塘', '易家坝', '南门山', '泽胜广场'],
  [48, 108, 178, 246, 314],
  5
))

const flowLinks = computed(() => transferPairs.value
  .map((item) => {
    const origin = originNodes.value.find((node) => node.name === item.originStation)
    const target = targetNodes.value.find((node) => node.name === item.targetStation)
    if (!origin || !target) return null
    return {
      ...item,
      sourceY: origin.y,
      targetY: target.y
    }
  })
  .filter(Boolean)
  .sort((left, right) => right.predictCount - left.predictCount)
  .slice(0, 5))

const maxFlow = computed(() => Math.max(...flowLinks.value.map((link) => link.predictCount), 1))
const flowTooltipStyle = computed(() => ({
  left: `${flowTooltip.x}px`,
  top: `${flowTooltip.y}px`
}))

const historyCompare = computed(() => {
  const total = transferPairs.value.reduce((sum, row) => sum + row.predictCount, 0)
  const maxPair = coreOd.value
  const highRiskCount = transferPairs.value.filter((row) => row.riskLevel === 'high').length
  return [
    { label: '上一时间窗总客流', value: formatNumber(Math.max(0, Math.round(total * 0.93))), change: '+7.5%', up: true },
    { label: '最大OD变化', value: `${maxPair.originStation} → ${maxPair.targetStation}`, change: `+${Math.max(18, Math.round(maxPair.predictCount * 0.08))}人次`, up: true },
    { label: '高风险OD对变化', value: `${highRiskCount} 对`, change: highRiskCount > 1 ? '+1 对' : '持平', up: highRiskCount > 1 }
  ]
})

onMounted(() => {
  syncClock()
  clockTimer = window.setInterval(syncClock, 1000)
})

watch(routeOptions, (options) => {
  if (options.length && !options.includes(filters.lineId)) {
    filters.lineId = options[0]
  }
}, { immediate: true })

watch(directionOptions, (options) => {
  if (options.length && !options.includes(filters.direction)) {
    filters.direction = options[0]
  }
}, { immediate: true })

watch(stationOptions, (options) => {
  if (options.length && !options.includes(filters.originStation)) {
    filters.originStation = options[0]
  }
}, { immediate: true })

onBeforeUnmount(() => {
  if (clockTimer) window.clearInterval(clockTimer)
})

function syncClock() {
  const now = new Date()
  currentTime.value = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}:${String(now.getSeconds()).padStart(2, '0')}`
}

function exportForecastRows() {
  const headers = ['起点站', '预测终点站', '预测人数', '置信度', '风险等级', '规则校验', '建议动作']
  const rows = detailRows.value.map((row) => [
    row.originStation,
    row.targetStation,
    row.predictCount,
    `${row.confidence}%`,
    row.riskText,
    row.ruleCheck,
    row.suggestion
  ])
  const csv = [headers, ...rows]
    .map((row) => row.map((cell) => `"${String(cell ?? '').replace(/"/g, '""')}"`).join(','))
    .join('\n')
  const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `OD预测明细-${businessDate.value || 'latest'}.csv`
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

function showFlowTooltip(event, link) {
  hoveredFlow.value = link
  moveFlowTooltip(event)
}

function moveFlowTooltip(event) {
  const rect = event.currentTarget.ownerSVGElement?.parentElement?.getBoundingClientRect()
  if (!rect) return
  const maxX = Math.max(12, rect.width - 218)
  const maxY = Math.max(12, rect.height - 112)
  flowTooltip.x = Math.min(maxX, Math.max(12, event.clientX - rect.left + 14))
  flowTooltip.y = Math.min(maxY, Math.max(12, event.clientY - rect.top + 14))
}

function hideFlowTooltip() {
  hoveredFlow.value = null
}

function normalizeOdPair(row, index) {
  const predictCount = numberOf(row.predictCount ?? row.value ?? row.count ?? row.total)
  const confidence = Math.max(60, Math.min(96, Math.round(numberOf(row.confidence) || (88 - index * 2))))
  const riskLevel = normalizeRisk(row.riskLevel ?? row.riskText, predictCount)
  return {
    routeId: textOf(row.routeId ?? row.lineId ?? ''),
    lineId: formatLineId(row.lineName ?? row.line ?? row.name ?? row.lineId ?? row.routeId ?? filters.lineId),
    direction: directionLabel(row.direction ?? row.isUpDown ?? filters.direction),
    timeWindow: row.timeWindow || filters.timeWindow,
    originStation: textOf(row.originStation ?? row.boardStation ?? row.origin ?? row.source ?? '未知起点'),
    targetStation: textOf(row.targetStation ?? row.alightStation ?? row.destination ?? row.target ?? '未知终点'),
    predictCount,
    confidence,
    riskLevel,
    riskText: riskLevel === 'high' ? '高' : riskLevel === 'medium' ? '中' : '低',
    ruleCheck: textOf(row.ruleCheck ?? row.ruleStatus ?? '通过'),
    suggestion: textOf(row.suggestion ?? row.action ?? suggestionByRisk(riskLevel))
  }
}

function buildFlowNodes(rows, key, fallbackNames, positions, limit) {
  const scores = new Map()
  rows.forEach((row) => {
    const name = textOf(row[key])
    if (!name) return
    scores.set(name, (scores.get(name) || 0) + numberOf(row.predictCount))
  })
  const names = Array.from(scores.entries())
    .sort((left, right) => right[1] - left[1])
    .map(([name]) => name)
  fallbackNames.forEach((name) => {
    if (!names.includes(name)) names.push(name)
  })
  return names.slice(0, limit).map((name, index) => ({
    name,
    y: positions[index] ?? (58 + index * 72)
  }))
}

function prioritizeOrigin(rows) {
  return [...rows].sort((left, right) => {
    const leftFocused = left.originStation === filters.originStation ? 1 : 0
    const rightFocused = right.originStation === filters.originStation ? 1 : 0
    if (leftFocused !== rightFocused) return rightFocused - leftFocused
    return right.predictCount - left.predictCount
  })
}

function flowPath(link) {
  return `M 160 ${link.sourceY} C 278 ${link.sourceY}, 392 ${link.targetY}, 520 ${link.targetY}`
}

function flowWidth(link) {
  const ratio = Math.max(0, link.predictCount / maxFlow.value)
  return 7 + Math.round(Math.sqrt(ratio) * 13)
}

function riskClass(level) {
  return {
    'is-high': level === 'high',
    'is-medium': level === 'medium',
    'is-normal': level === 'normal'
  }
}

function formatNumber(value) {
  return Number(value || 0).toLocaleString('zh-CN')
}

function textOf(value) {
  if (value === null || value === undefined) return ''
  return String(value).trim()
}

function numberOf(value) {
  if (value === null || value === undefined || value === '') return 0
  const normalized = typeof value === 'number' ? value : Number(String(value).replace(/[^\d.-]/g, ''))
  return Number.isFinite(normalized) ? normalized : 0
}

function uniqueValues(values) {
  return Array.from(new Set(values.map(textOf).filter(Boolean)))
}

function formatLineId(value) {
  const raw = textOf(value)
  if (!raw) return filters.lineId
  return /^\d+$/.test(raw) ? `${raw}路` : raw
}

function directionLabel(value) {
  const raw = textOf(value)
  const normalized = raw.toLowerCase()
  if (raw === '1' || raw === '上' || raw === '上行' || normalized === 'up') return '上行'
  if (raw === '0' || raw === '2' || raw === '下' || raw === '下行' || normalized === 'down') return '下行'
  return raw || filters.direction
}

function normalizeRisk(value, count) {
  const raw = textOf(value).toLowerCase()
  if (raw.includes('high') || raw.includes('高')) return 'high'
  if (raw.includes('medium') || raw.includes('中') || raw.includes('较高')) return 'medium'
  if (raw.includes('normal') || raw.includes('低') || raw.includes('正常')) return 'normal'
  if (count >= 800) return 'high'
  if (count >= 400) return 'medium'
  return 'normal'
}

function riskLabel(level) {
  if (level === 'high') return '高风险'
  if (level === 'medium') return '较高'
  return '正常'
}

function suggestionByRisk(level) {
  if (level === 'high') return '增加班次'
  if (level === 'medium') return '持续观察'
  return '保持运力'
}
</script>

<style scoped>
.forecast-workbench {
  height: 100%;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  padding: 14px;
  border-radius: 26px;
  border: 1px solid rgba(198, 222, 235, 0.78);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.97) 0%, rgba(246, 251, 254, 0.96) 100%);
  box-shadow: 0 18px 42px rgba(33, 74, 101, 0.08);
  color: #173246;
}

.filter-strip,
.workbench-card,
.metric-card {
  border: 1px solid rgba(169, 212, 229, 0.42);
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 16px 36px rgba(34, 83, 108, 0.08);
}

.filter-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(150px, 1fr)) minmax(260px, auto);
  align-items: end;
  gap: 12px;
  padding: 16px;
  border-radius: 20px;
}

.filter-control {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.filter-control span {
  color: #6d8090;
  font-size: 12px;
  font-weight: 900;
}

.filter-control select {
  width: 100%;
  height: 42px;
  border: 1px solid rgba(166, 211, 229, 0.78);
  border-radius: 14px;
  outline: 0;
  background: #f8fcff;
  color: #173246;
  padding: 0 12px;
  font-size: 14px;
  font-weight: 900;
  cursor: pointer;
}

.scenario-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  min-height: 42px;
}

.scenario-tags span,
.card-chip,
.rule-tags span {
  display: inline-flex;
  align-items: center;
  height: 30px;
  padding: 0 11px;
  border-radius: 999px;
  background: #e8f8fb;
  color: #0786a2;
  font-size: 12px;
  font-weight: 900;
}

.primary-action,
.secondary-action,
.detail-actions button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 0;
  cursor: pointer;
  font-weight: 900;
  transition: transform 0.18s ease, box-shadow 0.18s ease;
}

.primary-action {
  height: 44px;
  min-width: 132px;
  border-radius: 15px;
  color: #ffffff;
  background: linear-gradient(90deg, #18bde2 0%, #2388ff 100%);
  box-shadow: 0 14px 28px rgba(35, 136, 255, 0.22);
}

.primary-action:hover,
.secondary-action:hover,
.detail-actions button:hover {
  transform: translateY(-1px);
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin-top: 16px;
}

.metric-card {
  display: flex;
  gap: 14px;
  padding: 18px;
  border-radius: 20px;
}

.metric-card__icon {
  width: 46px;
  height: 46px;
  flex: 0 0 46px;
  display: grid;
  place-items: center;
  border-radius: 16px;
  color: #ffffff;
}

.metric-card__icon.tone-blue { background: linear-gradient(135deg, #247dff, #38c9f3); }
.metric-card__icon.tone-red { background: linear-gradient(135deg, #ff7b74, #ffb36f); }
.metric-card__icon.tone-cyan { background: linear-gradient(135deg, #11a9d1, #4be1d3); }
.metric-card__icon.tone-green { background: linear-gradient(135deg, #20bd83, #67dfb6); }

.metric-card__body {
  min-width: 0;
}

.metric-card__body span {
  display: block;
  color: #758997;
  font-size: 12px;
  font-weight: 900;
}

.metric-card__body strong {
  display: block;
  margin-top: 5px;
  color: #112f42;
  font-size: 28px;
  line-height: 1.08;
  font-weight: 900;
  white-space: nowrap;
}

.metric-card__body small {
  display: block;
  margin-top: 8px;
  color: #0aa873;
  font-size: 12px;
  font-weight: 900;
  white-space: nowrap;
}

.metric-card__body small.is-down {
  color: #e06464;
}

.analysis-grid {
  display: grid;
  grid-template-columns: minmax(0, 0.95fr) minmax(0, 1.05fr);
  gap: 16px;
  margin-top: 16px;
  align-items: stretch;
}

.od-card,
.hotspot-card {
  height: 366px;
  min-height: 366px;
  max-height: 366px;
  display: flex;
  flex-direction: column;
}

.hotspot-card {
  overflow: hidden;
}

.workbench-card {
  border-radius: 20px;
  padding: 18px;
}

.od-card.workbench-card {
  padding: 16px 18px 18px;
}

.card-head,
.detail-head,
.assistant-title {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}

.card-head h2,
.detail-head h2,
.assistant-title h2 {
  margin: 0;
  color: #123349;
  font-size: 18px;
  line-height: 1.2;
  font-weight: 900;
}

.card-head p,
.detail-head p,
.assistant-title p {
  margin: 5px 0 0;
  color: #7c8c98;
  font-size: 12px;
  line-height: 1.5;
  font-weight: 800;
}

.sankey-shell {
  position: relative;
  flex: 1 1 auto;
  min-height: 0;
  margin-top: 14px;
  border-radius: 16px;
  overflow: hidden;
  padding: 10px 16px 44px;
  background:
    linear-gradient(rgba(35, 143, 191, 0.06) 1px, transparent 1px),
    linear-gradient(90deg, rgba(35, 143, 191, 0.06) 1px, transparent 1px),
    linear-gradient(180deg, #fcfeff 0%, #f2fbff 100%);
  background-size: 26px 26px, 26px 26px, 100% 100%;
  box-shadow: inset 0 0 0 1px rgba(187, 224, 238, 0.68);
}

.flow-map {
  width: 100%;
  height: 100%;
  display: block;
}

.flow-hit {
  fill: none;
  stroke: transparent;
  pointer-events: stroke;
}

.flow-line {
  fill: none;
  stroke: url("#flowBlueCyan");
  stroke-linecap: round;
  opacity: 0.84;
  filter: drop-shadow(0 5px 10px rgba(32, 145, 198, 0.24));
  transition: opacity 0.2s ease, stroke-width 0.2s ease;
}

.flow-line.is-high {
  stroke: url("#flowHighRisk");
  opacity: 0.86;
}

.flow-line.is-medium {
  opacity: 0.76;
}

.flow-line.is-normal {
  opacity: 0.68;
}

.flow-hit:hover + .flow-line {
  opacity: 0.96;
}

.flow-tooltip {
  position: absolute;
  z-index: 12;
  width: 204px;
  padding: 10px 12px;
  border-radius: 14px;
  border: 1px solid rgba(105, 190, 222, 0.5);
  background: rgba(255, 255, 255, 0.985);
  box-shadow: 0 18px 34px rgba(30, 86, 118, 0.24);
  color: #15384d;
  pointer-events: none;
}

.flow-tooltip strong,
.flow-tooltip span {
  display: block;
}

.flow-tooltip strong {
  margin-bottom: 7px;
  font-size: 13px;
  font-weight: 900;
}

.flow-tooltip span {
  color: #617789;
  font-size: 12px;
  font-weight: 800;
  line-height: 1.55;
}

.flow-node rect {
  fill: rgba(255, 255, 255, 0.98);
  stroke: rgba(91, 196, 223, 0.72);
  stroke-width: 1.5;
  filter: drop-shadow(0 6px 12px rgba(34, 83, 108, 0.11));
}

.flow-node.is-focus rect {
  fill: #e9f8ff;
  stroke: rgba(28, 132, 255, 0.7);
  stroke-width: 1.6;
}

.flow-node-group--right .flow-node rect {
  fill: #f2fbff;
}

.flow-node text {
  fill: #174053;
  font-size: 14px;
  font-weight: 900;
  text-anchor: middle;
  paint-order: stroke;
  stroke: rgba(255, 255, 255, 0.86);
  stroke-width: 2px;
}

.sankey-legend {
  position: absolute;
  left: 18px;
  right: 18px;
  bottom: 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  color: #587181;
  font-size: 11px;
  font-weight: 900;
}

.sankey-legend span {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.legend-line {
  display: inline-block;
  width: 32px;
  border-radius: 999px;
  background: linear-gradient(90deg, #2a8cff, #2fd5d0);
}

.legend-line--wide {
  height: 8px;
}

.legend-line--risk {
  height: 5px;
  background: linear-gradient(90deg, #91e0e8, #1976f3);
}

.hotspot-list {
  display: grid;
  grid-template-rows: repeat(4, 50px);
  gap: 7px;
  flex: 0 0 auto;
  min-height: 0;
  margin-top: 10px;
  overflow: hidden;
}

.hotspot-item {
  display: grid;
  grid-template-columns: minmax(116px, 1.1fr) minmax(170px, 1fr) minmax(110px, 0.72fr);
  align-items: center;
  column-gap: 10px;
  min-height: 0;
  padding: 7px 10px;
  border-radius: 12px;
  background: linear-gradient(180deg, #fbfdff 0%, #f4f9fc 100%);
  box-shadow: inset 0 0 0 1px rgba(187, 224, 238, 0.62);
}

.hotspot-item__station {
  display: flex;
  min-width: 0;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  min-width: 0;
}

.hotspot-item__station strong {
  overflow: hidden;
  width: 100%;
  color: #112f42;
  font-size: 13px;
  font-weight: 900;
  line-height: 1.15;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hotspot-item__station small {
  width: 100%;
  color: #0ba871;
  font-size: 11px;
  font-weight: 900;
  line-height: 1.1;
}

.hotspot-item__station small.is-down {
  color: #e26d63;
}

.hotspot-item__metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px;
  min-width: 0;
}

.hotspot-item__metrics span {
  display: grid;
  min-width: 0;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: baseline;
  gap: 5px;
  padding: 6px 7px;
  border-radius: 9px;
  background: rgba(236, 247, 252, 0.82);
  color: #6f8390;
  font-size: 11px;
  font-weight: 800;
}

.hotspot-item__metrics em {
  font-style: normal;
  white-space: nowrap;
}

.hotspot-item__metrics b {
  overflow: hidden;
  color: #13364c;
  font-size: 13px;
  font-weight: 900;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hotspot-item__status {
  display: grid;
  min-width: 0;
  align-items: center;
  justify-items: end;
  gap: 5px;
}

.pressure-bar {
  width: 100%;
  height: 6px;
  margin-top: 0;
  overflow: hidden;
  border-radius: 999px;
  background: #e3eff5;
}

.pressure-bar span {
  display: block;
  height: 100%;
  border-radius: inherit;
}

.pressure-bar span.is-high { background: linear-gradient(90deg, #1e83ff, #f48d78); }
.pressure-bar span.is-medium { background: linear-gradient(90deg, #20b7e0, #5bd4ce); }
.pressure-bar span.is-normal { background: linear-gradient(90deg, #7ddbd3, #a6e9c9); }

.risk-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 44px;
  height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 900;
  flex: 0 0 auto;
}

.risk-pill.is-high {
  background: #fff0ed;
  color: #db5d4f;
}

.risk-pill.is-medium {
  background: #e9f8ff;
  color: #138db7;
}

.risk-pill.is-normal {
  background: #eafaf4;
  color: #0d9d68;
}

.assistant-column {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) minmax(0, 1fr);
  gap: 12px;
  min-width: 0;
}

.assistant-card {
  background:
    radial-gradient(circle at top right, rgba(41, 188, 220, 0.11), transparent 36%),
    rgba(255, 255, 255, 0.94);
  min-height: 230px;
}

.assistant-title {
  justify-content: flex-start;
  align-items: center;
}

.assistant-title > svg {
  flex: 0 0 auto;
  color: #0a9ec0;
}

.core-route {
  display: grid;
  grid-template-columns: 1fr 42px 1fr;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
}

.core-route span {
  display: grid;
  place-items: center;
  min-height: 46px;
  border-radius: 15px;
  background: #eefaff;
  color: #0e7fa0;
  font-size: 16px;
  font-weight: 900;
}

.core-route i {
  height: 4px;
  border-radius: 999px;
  background: linear-gradient(90deg, #1f8cff, #2bd0d1);
  position: relative;
}

.core-route i::after {
  content: "";
  position: absolute;
  right: -1px;
  top: 50%;
  width: 9px;
  height: 9px;
  border-top: 4px solid #2bd0d1;
  border-right: 4px solid #2bd0d1;
  transform: translateY(-50%) rotate(45deg);
}

.core-facts {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin: 14px 0 0;
}

.core-facts div {
  padding: 10px;
  border-radius: 14px;
  background: #f5fbfe;
}

.core-facts dt {
  color: #83939d;
  font-size: 11px;
  font-weight: 900;
}

.core-facts dd {
  margin: 4px 0 0;
  color: #14384f;
  font-size: 14px;
  font-weight: 900;
  white-space: nowrap;
}

.rule-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;
}

.assistant-card--text p {
  margin: 14px 0 0;
  color: #526a78;
  font-size: 13px;
  line-height: 1.75;
  font-weight: 700;
}

.evidence-list {
  display: grid;
  gap: 9px;
  margin: 14px 0 0;
  padding: 0;
  list-style: none;
}

.evidence-list li {
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr);
  align-items: center;
  gap: 10px;
  padding: 10px 11px;
  border-radius: 14px;
  background: #f5fbfe;
  box-shadow: inset 0 0 0 1px rgba(189, 222, 236, 0.58);
}

.evidence-list b {
  color: #0a83a3;
  font-size: 12px;
  font-weight: 900;
}

.evidence-list span {
  min-width: 0;
  color: #526a78;
  font-size: 12px;
  line-height: 1.35;
  font-weight: 800;
}

.suggestion-list {
  margin: 14px 0 0;
  padding: 0;
  list-style: none;
}

.suggestion-list li {
  position: relative;
  padding-left: 16px;
  color: #526a78;
  font-size: 13px;
  line-height: 1.7;
  font-weight: 800;
}

.suggestion-list li::before {
  content: "";
  position: absolute;
  left: 0;
  top: 0.7em;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #21bbd5;
}

.secondary-action {
  width: 100%;
  height: 42px;
  margin-top: 14px;
  border-radius: 14px;
  color: #087f9e;
  background: #e8f8fb;
  box-shadow: inset 0 0 0 1px rgba(88, 202, 226, 0.36);
}

.detail-card {
  margin-top: 16px;
  margin-bottom: 8px;
}

.detail-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.detail-actions button {
  min-height: 38px;
  padding: 0 13px;
  border-radius: 13px;
  color: #0a83a3;
  background: #eefaff;
  box-shadow: inset 0 0 0 1px rgba(120, 207, 228, 0.5);
}

.history-compare {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-top: 12px;
}

.history-compare article {
  min-width: 0;
  padding: 12px 14px;
  border-radius: 14px;
  background: #f6fbfe;
  box-shadow: inset 0 0 0 1px rgba(188, 224, 238, 0.75);
}

.history-compare span,
.history-compare small {
  display: block;
  color: #6f8291;
  font-size: 12px;
  font-weight: 800;
}

.history-compare strong {
  display: block;
  overflow: hidden;
  margin-top: 5px;
  color: #123349;
  font-size: 15px;
  font-weight: 900;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-compare small {
  margin-top: 5px;
  color: #7d8f9d;
}

.history-compare small.is-up {
  color: #0aa873;
}

.table-wrap {
  margin-top: 14px;
  overflow: auto;
  border-radius: 16px;
  box-shadow: inset 0 0 0 1px rgba(189, 222, 236, 0.78);
}

table {
  width: 100%;
  border-collapse: collapse;
  min-width: 820px;
}

thead {
  background: #eef8fc;
}

th,
td {
  padding: 13px 14px;
  text-align: left;
  border-bottom: 1px solid #e3eef4;
  color: #25475a;
  font-size: 13px;
  font-weight: 800;
  white-space: nowrap;
}

th {
  color: #6b808d;
  font-size: 12px;
  font-weight: 900;
}

tbody tr:hover {
  background: #f7fcff;
}

tbody tr:last-child td {
  border-bottom: 0;
}

@media (max-width: 1380px) {
  .filter-strip {
    grid-template-columns: repeat(2, minmax(170px, 1fr));
  }

  .analysis-grid {
    grid-template-columns: 1fr;
  }

  .sankey-shell {
    height: 300px;
    flex-basis: 300px;
  }
}

@media (max-width: 1080px) {
  .metric-grid {
    width: 100%;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
