import * as echarts from 'echarts'

const commonGrid = { top: '15%', bottom: '10%', left: '5%', right: '5%', containLabel: true }
const trendGrid = { top: '18%', bottom: '18%', left: '8%', right: '8%', containLabel: true }
const hourlyGrid = { top: '14%', bottom: '18%', left: '9%', right: '6%', containLabel: true }
const topGrid = { top: '14%', bottom: '12%', left: '8%', right: '8%', containLabel: true }
const mirrorGrid = { top: '14%', bottom: '12%', left: '8%', right: '8%', containLabel: true }

const tooltipBase = {
  appendToBody: true,
  confine: false,
  backgroundColor: 'rgba(6, 18, 34, 0.96)',
  borderColor: 'rgba(87, 223, 255, 0.32)',
  borderWidth: 1,
  textStyle: { color: '#effcff' },
  extraCssText: 'box-shadow:0 12px 28px rgba(0,0,0,0.35);border-radius:10px;padding:10px 12px;'
}

export function createEmptyDashboard() {
  return {
    line: '',
    direction: '上行',
    date: '--',
    state: '等待接口数据',
    availableDates: [],
    activeMoment: '--',
    selectedStation: '',
    summary: {
      predictedBoardings: 0,
      predictedAlightings: 0,
      stationCount: 0
    },
    trend: {
      moments: [],
      speed: [],
      congestion: []
    },
    hourlyTraffic: [],
    topStations: [],
    mirrorComparison: [],
    odFlow: {
      nodes: [],
      links: []
    },
    stations: [],
    path: []
  }
}

export function normalizeDashboard(payload = {}) {
  const fallback = createEmptyDashboard()
  return {
    ...fallback,
    ...payload,
    summary: {
      ...fallback.summary,
      ...(payload.summary || {})
    },
    trend: {
      ...fallback.trend,
      ...(payload.trend || {})
    },
    odFlow: {
      ...fallback.odFlow,
      ...(payload.odFlow || {})
    },
    hourlyTraffic: Array.isArray(payload.hourlyTraffic) ? payload.hourlyTraffic : [],
    topStations: Array.isArray(payload.topStations) ? payload.topStations : [],
    mirrorComparison: Array.isArray(payload.mirrorComparison) ? payload.mirrorComparison : [],
    stations: Array.isArray(payload.stations) ? payload.stations : [],
    path: Array.isArray(payload.path) ? payload.path : []
  }
}

export function buildTrendOption(dashboard) {
  const trend = dashboard.trend || {}
  return {
    backgroundColor: 'transparent',
    grid: trendGrid,
    tooltip: { ...tooltipBase, trigger: 'axis' },
    legend: {
      bottom: 2,
      right: 8,
      itemWidth: 18,
      itemHeight: 10,
      textStyle: { color: '#d6f7ff' }
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: trend.moments,
      axisLine: { lineStyle: { color: 'rgba(140, 202, 255, 0.25)' } },
      axisLabel: { color: '#a8c7db' },
      axisTick: { show: false }
    },
    yAxis: [
      {
        type: 'value',
        name: '车速',
        nameGap: 14,
        nameTextStyle: { color: '#cfefff', padding: [0, 0, 2, 2] },
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: '#a8c7db' },
        splitLine: { lineStyle: { color: 'rgba(140, 202, 255, 0.10)' } }
      },
      {
        type: 'value',
        name: '拥堵指数',
        nameGap: 14,
        nameTextStyle: { color: '#cfefff', padding: [0, 2, 2, 0] },
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: '#a8c7db' },
        splitLine: { show: false }
      }
    ],
    series: [
      {
        name: '平均车速',
        type: 'line',
        smooth: true,
        yAxisIndex: 0,
        data: trend.speed,
        symbolSize: 8,
        itemStyle: { color: '#ffd45a' },
        lineStyle: { width: 3, color: '#ffd45a' }
      },
      {
        name: '拥堵指数',
        type: 'line',
        smooth: true,
        yAxisIndex: 1,
        data: trend.congestion,
        symbolSize: 8,
        itemStyle: { color: '#38f0b8' },
        lineStyle: { width: 3, color: '#38f0b8' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(56, 240, 184, 0.35)' },
            { offset: 1, color: 'rgba(56, 240, 184, 0.03)' }
          ])
        }
      }
    ]
  }
}

export function buildHourlyOption(dashboard) {
  const hourly = (dashboard.hourlyTraffic || []).filter(item => Number(item?.value || 0) > 0)
  return {
    backgroundColor: 'transparent',
    grid: hourlyGrid,
    tooltip: { ...tooltipBase, trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: hourly.map(item => item.time),
      axisLine: { lineStyle: { color: 'rgba(140, 202, 255, 0.25)' } },
      axisLabel: { color: '#a8c7db', rotate: 45 },
      axisTick: { show: false }
    },
    yAxis: {
      type: 'value',
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#a8c7db' },
      splitLine: { lineStyle: { color: 'rgba(140, 202, 255, 0.10)' } }
    },
    series: [
      {
        type: 'bar',
        data: hourly.map(item => item.value),
        barWidth: '54%',
        itemStyle: {
          borderRadius: [7, 7, 0, 0],
          color: () =>
            new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: '#76f7ff' },
              { offset: 0.55, color: '#2bc7ff' },
              { offset: 1, color: '#295dff' }
            ]),
          shadowBlur: 16,
          shadowColor: 'rgba(43, 199, 255, 0.34)'
        }
      }
    ]
  }
}

function normalizeStationLabel(value) {
  return String(value || '')
    .replace(/^source:/i, '')
    .replace(/^target:/i, '')
    .trim()
}

function wrapTargetStationLabel(value) {
  const text = normalizeStationLabel(value)
  if (text.length <= 4) {
    return text
  }
  return `${text.slice(0, 4)}\n${text.slice(4)}`
}

export function buildSankeyOption(dashboard) {
  const odFlow = dashboard.odFlow || { nodes: [], links: [] }
  return {
    backgroundColor: 'transparent',
    grid: commonGrid,
    tooltip: {
      ...tooltipBase,
      trigger: 'item',
      formatter: params => {
        if (params?.dataType === 'edge') {
          const sourceName = normalizeStationLabel(params.data?.sourceDisplayName || params.data?.source || '--')
          const targetName = normalizeStationLabel(params.data?.targetDisplayName || params.data?.target || '--')
          return `上车站点：${sourceName}<br/>下车站点：${targetName}<br/>客流人数：${params.data?.value ?? 0}`
        }
        return normalizeStationLabel(params.data?.displayName || params.name)
      }
    },
    series: [
      {
        type: 'sankey',
        top: '10%',
        bottom: '8%',
        left: '8%',
        right: '26%',
        data: odFlow.nodes,
        links: odFlow.links,
        emphasis: { focus: 'adjacency' },
        draggable: false,
        nodeGap: 22,
        nodeWidth: 12,
        nodeAlign: 'justify',
        layoutIterations: 64,
        lineStyle: {
          color: 'gradient',
          curveness: 0.5,
          opacity: 0.56
        },
        levels: [
          {
            depth: 0,
            itemStyle: {
              color: '#39d8ff',
              borderColor: 'rgba(121, 236, 255, 0.82)',
              borderWidth: 1
            },
            lineStyle: {
              color: 'source',
              opacity: 0.5
            }
          },
          {
            depth: 1,
            itemStyle: {
              color: '#5eead4',
              borderColor: 'rgba(142, 255, 230, 0.8)',
              borderWidth: 1
            }
          },
          {
            depth: 2,
            itemStyle: {
              color: '#ffb85c',
              borderColor: 'rgba(255, 208, 120, 0.85)',
              borderWidth: 1
            },
            lineStyle: {
              color: 'target',
              opacity: 0.5
            }
          }
        ],
        label: {
          color: '#dff6ff',
          fontSize: 12,
          width: 96,
          lineHeight: 16,
          distance: 8,
          textBorderColor: 'rgba(4, 13, 33, 0.92)',
          textBorderWidth: 3,
          formatter: params => {
            const text = normalizeStationLabel(params.data?.displayName || params.name)
            if (params.data?.group === 'target') {
              return wrapTargetStationLabel(text)
            }
            return text
          }
        }
      }
    ]
  }
}

export function buildTopOption(dashboard) {
  const topStations = [...(dashboard.topStations || [])].reverse()
  return {
    backgroundColor: 'transparent',
    grid: topGrid,
    tooltip: { ...tooltipBase, trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: {
      type: 'value',
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#a8c7db' },
      splitLine: { lineStyle: { color: 'rgba(140, 202, 255, 0.10)' } }
    },
    yAxis: {
      type: 'category',
      data: topStations.map(item => item.name),
      position: 'left',
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: {
        color: '#dff6ff',
        width: 110,
        overflow: 'truncate',
        margin: 10
      }
    },
    series: [
      {
        type: 'bar',
        data: topStations.map(item => item.value),
        barWidth: 18,
        label: {
          show: true,
          position: 'insideRight',
          color: '#06101d',
          fontWeight: 700
        },
        itemStyle: {
          borderRadius: [0, 10, 10, 0],
          color: new echarts.graphic.LinearGradient(1, 0, 0, 0, [
            { offset: 0, color: '#ff834f' },
            { offset: 1, color: '#ffd45a' }
          ])
        }
      }
    ]
  }
}

export function buildMirrorOption(dashboard) {
  const list = dashboard.mirrorComparison || []
  const maxAbsValue = Math.max(
    1,
    ...list.map(item => Math.max(Math.abs(item.predictedBoardings || 0), Math.abs(item.predictedAlightings || 0)))
  )
  const axisMax = Math.ceil(maxAbsValue * 1.12)
  return {
    backgroundColor: 'transparent',
    grid: mirrorGrid,
    tooltip: {
      ...tooltipBase,
      trigger: 'axis',
      axisPointer: { type: 'cross' },
      valueFormatter: value => Math.abs(value)
    },
    dataZoom: [
      {
        type: 'inside',
        xAxisIndex: 0,
        startValue: 0,
        endValue: Math.min(8, list.length - 1)
      }
    ],
    legend: {
      bottom: 0,
      right: 4,
      itemWidth: 14,
      itemHeight: 8,
      textStyle: { color: '#d6f7ff' }
    },
    xAxis: {
      type: 'category',
      data: list.map(item => item.name),
      axisLine: { lineStyle: { color: 'rgba(140, 202, 255, 0.18)' } },
      axisTick: { show: false },
      axisLabel: {
        color: '#dff6ff',
        interval: 0,
        rotate: 35,
        width: 74,
        overflow: 'truncate',
        margin: 10
      }
    },
    yAxis: {
      type: 'value',
      min: -axisMax,
      max: axisMax,
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: {
        color: '#a8c7db',
        formatter: value => Math.abs(value)
      },
      splitLine: { lineStyle: { color: 'rgba(140, 202, 255, 0.10)' } }
    },
    series: [
      {
        name: '预测下车',
        type: 'line',
        smooth: true,
        data: list.map(item => -item.predictedAlightings),
        symbol: 'circle',
        symbolSize: 6,
        itemStyle: {
          color: '#ff62bc'
        },
        lineStyle: {
          width: 3,
          color: '#ff62bc'
        },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(255, 98, 188, 0.52)' },
            { offset: 1, color: 'rgba(255, 98, 188, 0.06)' }
          ])
        }
      },
      {
        name: '预测上车',
        type: 'line',
        smooth: true,
        data: list.map(item => item.predictedBoardings),
        symbol: 'circle',
        symbolSize: 6,
        itemStyle: {
          color: '#51f4ff'
        },
        lineStyle: {
          width: 3,
          color: '#51f4ff'
        },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(81, 244, 255, 0.56)' },
            { offset: 1, color: 'rgba(81, 244, 255, 0.08)' }
          ])
        }
      }
    ]
  }
}
