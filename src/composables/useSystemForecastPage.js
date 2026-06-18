import { computed, markRaw, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import * as echarts from 'echarts'

export function useSystemForecastPage(props) {
  const sankeyChartRef = ref(null)
  const gaugeChartRef = ref(null)
  const sankeyChart = shallowRef(null)
  const gaugeChart = shallowRef(null)

  const odPairs = computed(() => {
    const source = Array.isArray(props.pageData?.odPairs) ? props.pageData.odPairs : []
    if (source.length) return source
    return [
      { boardStation: '宏声桥', alightStation: '宏声桥', value: 4180 },
      { boardStation: '宏声桥', alightStation: '实验中学', value: 2650 },
      { boardStation: '消防队', alightStation: '宏声桥', value: 2210 },
      { boardStation: '中天广场', alightStation: '洗墨路口', value: 1980 },
      { boardStation: '易家坝', alightStation: '实验中学', value: 1760 },
      { boardStation: '宏声桥', alightStation: '洗墨路口', value: 1490 }
    ]
  })

  const rankedHotspots = computed(() => {
    const source = Array.isArray(props.pageData?.hotspots) ? props.pageData.hotspots : []
    const normalized = (source.length
      ? source
      : [
          { id: '1', name: '宏声桥', value: 5255, delta: 5 },
          { id: '2', name: '实验中学', value: 4880, delta: 4 },
          { id: '3', name: '中天广场', value: 4620, delta: 3 },
          { id: '4', name: '消防队', value: 4310, delta: 2 },
          { id: '5', name: '易家坝', value: 3950, delta: -1 }
        ]).map((item, index) => ({
      id: item.id || `hot-${index}`,
      name: item.name || `站点 ${index + 1}`,
      value: Number(item.value || 0),
      delta: Number(item.delta ?? (index < 3 ? 5 - index : 2 - index))
    }))

    const maxValue = Math.max(...normalized.map(item => item.value), 1)
    return normalized
      .sort((a, b) => b.value - a.value)
      .slice(0, 5)
      .map((item) => ({
        ...item,
        progress: Math.max(24, Math.round((item.value / maxValue) * 100))
      }))
  })

  const insights = computed(() => Array.isArray(props.pageData?.insights) ? props.pageData.insights : [])
  const primaryInsight = computed(() => insights.value[0] || '建议提升高峰换乘参数权重')

  const corePath = computed(() => {
    const first = odPairs.value[0] || {}
    return {
      source: first.boardStation || '宏声桥',
      target: first.alightStation || '宏声桥',
      value: Number(first.value || 4180)
    }
  })

  const sankeyData = computed(() => {
    const startNodes = Array.from(new Set(odPairs.value.map(item => item.boardStation))).slice(0, 5)
    const endNodes = Array.from(new Set(odPairs.value.map(item => item.alightStation))).slice(0, 5)
    const nodes = [
      ...startNodes.map(name => ({ name: `起点-${name}`, displayName: name, depth: 0 })),
      ...endNodes.map(name => ({ name: `终点-${name}`, displayName: name, depth: 1 }))
    ]
    const links = odPairs.value
      .filter(item => startNodes.includes(item.boardStation) && endNodes.includes(item.alightStation))
      .map(item => ({
        source: `起点-${item.boardStation}`,
        target: `终点-${item.alightStation}`,
        value: Number(item.value || 0),
        sourceName: item.boardStation,
        targetName: item.alightStation
      }))
    return { nodes, links }
  })

  watch(sankeyData, () => {
    renderSankey()
  }, { deep: true })

  onMounted(() => {
    nextTick(() => {
      initCharts()
      renderSankey()
      renderGauge()
      window.addEventListener('resize', handleResize)
    })
  })

  onBeforeUnmount(() => {
    window.removeEventListener('resize', handleResize)
    if (sankeyChart.value) {
      sankeyChart.value.dispose()
      sankeyChart.value = null
    }
    if (gaugeChart.value) {
      gaugeChart.value.dispose()
      gaugeChart.value = null
    }
  })

  function initCharts() {
    if (sankeyChartRef.value && !sankeyChart.value) {
      sankeyChart.value = markRaw(echarts.init(sankeyChartRef.value))
    }
    if (gaugeChartRef.value && !gaugeChart.value) {
      gaugeChart.value = markRaw(echarts.init(gaugeChartRef.value))
    }
  }

  function renderSankey() {
    if (!sankeyChartRef.value) return
    if (!sankeyChart.value) initCharts()
    if (!sankeyChart.value) return

    sankeyChart.value.setOption({
      animationDuration: 900,
      tooltip: {
        trigger: 'item',
        backgroundColor: 'rgba(13, 24, 36, 0.94)',
        borderColor: 'rgba(86, 218, 255, 0.28)',
        textStyle: {
          color: '#eefbff',
          fontSize: 12
        },
        formatter(params) {
          if (params.dataType === 'edge') {
            return [
              `上车站点：${params.data.sourceName || ''}`,
              `下车站点：${params.data.targetName || ''}`,
              `预测客流：${formatNumber(params.data.value)}`
            ].join('<br/>')
          }
          return params.data?.displayName || params.name
        }
      },
      series: [
        {
          type: 'sankey',
          left: 10,
          top: 30,
          right: 10,
          bottom: 20,
          emphasis: {
            focus: 'adjacency'
          },
          data: sankeyData.value.nodes,
          links: sankeyData.value.links,
          nodeWidth: 16,
          nodeGap: 24,
          draggable: false,
          label: {
            color: '#163248',
            fontSize: 13,
            fontWeight: 800,
            formatter(node) {
              return node.data?.displayName || node.name
            }
          },
          lineStyle: {
            color: 'gradient',
            curveness: 0.5,
            opacity: 0.52
          },
          itemStyle: {
            borderWidth: 0,
            color(params) {
              return params.data.depth === 0 ? '#32d7f4' : '#1a74ff'
            }
          }
        }
      ]
    }, true)
  }

  function renderGauge() {
    if (!gaugeChartRef.value) return
    if (!gaugeChart.value) initCharts()
    if (!gaugeChart.value) return

    gaugeChart.value.setOption({
      animationDuration: 900,
      series: [
        {
          type: 'gauge',
          startAngle: 210,
          endAngle: -30,
          min: 0,
          max: 100,
          progress: {
            show: true,
            roundCap: true,
            width: 12,
            itemStyle: {
              color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [
                { offset: 0, color: '#46dcff' },
                { offset: 1, color: '#1f77ff' }
              ])
            }
          },
          axisLine: {
            roundCap: true,
            lineStyle: {
              width: 12,
              color: [[1, 'rgba(202, 217, 232, 0.3)']]
            }
          },
          pointer: { show: false },
          axisTick: { show: false },
          splitLine: { show: false },
          axisLabel: { show: false },
          detail: {
            valueAnimation: true,
            offsetCenter: [0, '10%'],
            fontSize: 24,
            fontWeight: 900,
            color: '#18222d',
            formatter: '{value}%'
          },
          title: {
            offsetCenter: [0, '58%'],
            color: '#7a8895',
            fontSize: 11
          },
          data: [{ value: 86, name: '吸引力' }]
        }
      ]
    }, true)
  }

  function handleResize() {
    if (sankeyChart.value) sankeyChart.value.resize()
    if (gaugeChart.value) gaugeChart.value.resize()
  }

  function medalClass(index) {
    if (index === 0) return 'is-gold'
    if (index === 1) return 'is-silver'
    if (index === 2) return 'is-bronze'
    return 'is-default'
  }

  function formatNumber(value) {
    return Number(value || 0).toLocaleString('zh-CN')
  }

  return {
    sankeyChartRef,
    gaugeChartRef,
    rankedHotspots,
    corePath,
    primaryInsight,
    medalClass,
    formatNumber
  }
}
