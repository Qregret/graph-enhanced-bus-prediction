import { computed, markRaw, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import * as echarts from 'echarts'
import { Bell, Cpu, RefreshRight } from '@element-plus/icons-vue'

export function useSystemOverviewPage(props) {
  const metrics = computed(() => props.pageData?.metrics || {})
  const liveFeed = computed(() => Array.isArray(props.pageData?.liveFeed) ? props.pageData.liveFeed : [])
  const topCongestedRoutes = computed(() => Array.isArray(props.pageData?.topCongestedRoutes) ? props.pageData.topCongestedRoutes : [])
  const trend = computed(() => props.pageData?.trend || {})
  const trendChartRef = ref(null)
  const trendChart = shallowRef(null)
  let resizeObserver = null
  const congestionSummary = computed(() => {
    const routes = topCongestedRoutes.value
    const max = routes.length ? Math.max(...routes.map(item => Number(item.congestion || 0))) : 0
    const names = new Set(routes.map(item => item.name).filter(Boolean))
    const directions = new Set(routes.map(item => item.direction).filter(Boolean))
    return {
      max: max ? max.toFixed(1) : '-',
      warningRoutes: `${names.size || routes.length} 条`,
      directions: `${directions.size || 0} 个`
    }
  })

  const formatVehicles = computed(() => {
    if (metrics.value.onlineVehicles == null) return '-'
    return `${metrics.value.onlineVehicles}${metrics.value.totalVehicles ? ` / ${metrics.value.totalVehicles}` : ''}`
  })

  watch(trend, () => {
    renderTrendChart()
  }, { deep: true })

  onMounted(() => {
    nextTick(() => {
      initTrendChart()
      renderTrendChart()
      window.addEventListener('resize', handleResize)
      if (trendChartRef.value && window.ResizeObserver) {
        resizeObserver = new ResizeObserver(handleResize)
        resizeObserver.observe(trendChartRef.value)
      }
    })
  })

  onBeforeUnmount(() => {
    window.removeEventListener('resize', handleResize)
    resizeObserver?.disconnect()
    resizeObserver = null
    if (trendChart.value) {
      trendChart.value.dispose()
      trendChart.value = null
    }
  })

  function initTrendChart() {
    if (!trendChartRef.value || trendChart.value) return
    trendChart.value = markRaw(echarts.init(trendChartRef.value))
  }

  function renderTrendChart() {
    if (!trendChartRef.value) return
    if (!trendChart.value) {
      initTrendChart()
    }
    if (!trendChart.value) return

    const labels = Array.isArray(trend.value.labels) ? trend.value.labels : []
    const predicted = Array.isArray(trend.value.predicted) ? trend.value.predicted : []
    const actual = Array.isArray(trend.value.actual) ? trend.value.actual : []
    const parseLabelHour = (label) => {
      const match = String(label || '').match(/^(\d{1,2}):/)
      return match ? Number(match[1]) : null
    }
    const startBoundaryIndex = labels.findIndex(label => label === '04:00')
    const endBoundaryIndex = labels.findIndex(label => label === '24:00')
    const activeIndexes = labels
      .map((_, index) => index)
      .filter(index => Number(predicted[index] || 0) > 0 || Number(actual[index] || 0) > 0)
    const rangeStart = activeIndexes.length ? activeIndexes[0] : 0
    const rangeEnd = activeIndexes.length ? activeIndexes[activeIndexes.length - 1] : labels.length - 1
    const visibleStart = startBoundaryIndex >= 0 ? Math.min(rangeStart, startBoundaryIndex) : rangeStart
    const visibleEnd = endBoundaryIndex >= 0 ? Math.max(rangeEnd, endBoundaryIndex) : rangeEnd
    const visibleLabels = labels.slice(visibleStart, visibleEnd + 1)
    const visiblePredicted = predicted.slice(visibleStart, visibleEnd + 1)
    const visibleActual = actual.slice(visibleStart, visibleEnd + 1)

    if (visibleLabels.length && startBoundaryIndex < 0 && (parseLabelHour(visibleLabels[0]) ?? 99) > 4) {
      visibleLabels.unshift('04:00')
      visiblePredicted.unshift(0)
      visibleActual.unshift(0)
    }

    if (visibleLabels.length && endBoundaryIndex < 0 && (parseLabelHour(visibleLabels[visibleLabels.length - 1]) ?? -1) < 24) {
      visibleLabels.push('24:00')
      visiblePredicted.push(0)
      visibleActual.push(0)
    }

    trendChart.value.setOption({
      animationDuration: 700,
      animationEasing: 'cubicOut',
      grid: {
        top: 18,
        bottom: 12,
        left: 16,
        right: 8,
        containLabel: true
      },
      tooltip: {
        trigger: 'axis',
        confine: true,
        appendToBody: false,
        backgroundColor: 'rgba(10, 26, 44, 0.86)',
        borderColor: 'rgba(74, 209, 255, 0.35)',
        borderWidth: 1,
        padding: [8, 10],
        extraCssText: 'width: 168px; max-width: 168px; max-height: 76px; min-width: 0; overflow: hidden; border-radius: 10px; box-shadow: 0 10px 22px rgba(10, 26, 44, 0.18);',
        textStyle: {
          color: '#e9fbff',
          fontSize: 11,
          lineHeight: 16
        },
        formatter(params) {
          const rows = Array.isArray(params) ? params : [params]
          const title = rows[0]?.axisValueLabel || rows[0]?.axisValue || ''
          const body = rows
            .map(item => `${item.marker}${item.seriesName}：${Number(item.data || 0).toLocaleString('zh-CN')}`)
            .join('<br/>')
          return `${title}<br/>${body}`
        }
      },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: visibleLabels,
        axisLine: {
          lineStyle: {
            color: 'rgba(116, 144, 168, 0.38)'
          }
        },
        axisTick: { show: false },
        axisLabel: {
          color: '#7b8d9f',
          fontSize: 12,
          margin: 10
        }
      },
      yAxis: {
        type: 'value',
        axisLine: { show: false },
        axisTick: { show: false },
        splitLine: {
          lineStyle: {
            color: 'rgba(84, 114, 136, 0.14)'
          }
        },
        axisLabel: {
          color: '#7b8d9f',
          fontSize: 12,
          margin: 8
        }
      },
      series: [
        {
          name: '预测客流',
          type: 'line',
          smooth: true,
          symbol: 'circle',
          symbolSize: 7,
          data: visiblePredicted,
          lineStyle: {
            width: 4,
            color: '#38d6f2',
            shadowColor: 'rgba(56, 214, 242, 0.38)',
            shadowBlur: 10
          },
          itemStyle: {
            color: '#7cecff',
            borderColor: '#eaffff',
            borderWidth: 2
          },
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: 'rgba(56, 214, 242, 0.30)' },
              { offset: 1, color: 'rgba(56, 214, 242, 0.03)' }
            ])
          }
        },
        {
          name: '实际客流',
          type: 'line',
          smooth: true,
          symbol: 'circle',
          symbolSize: 7,
          data: visibleActual,
          lineStyle: {
            width: 4,
            color: '#1f70e5',
            shadowColor: 'rgba(31, 112, 229, 0.32)',
            shadowBlur: 10
          },
          itemStyle: {
            color: '#5aa1ff',
            borderColor: '#dfefff',
            borderWidth: 2
          },
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: 'rgba(31, 112, 229, 0.28)' },
              { offset: 1, color: 'rgba(31, 112, 229, 0.03)' }
            ])
          }
        }
      ]
    }, true)
    trendChart.value.resize()
  }

  function handleResize() {
    if (trendChart.value) {
      trendChart.value.resize()
    }
  }

  function formatFeedTime(value) {
    if (!value) return '--:--:--'
    const text = String(value)
    if (text.length >= 8) return text.slice(-8)
    return text
  }

  function resolveFeedIcon(type) {
    if (type === 'event') return Bell
    if (type === 'prediction') return Cpu
    return RefreshRight
  }

  function typeLabel(type) {
    if (type === 'event') return '交通事件'
    if (type === 'prediction') return '预测波动'
    return '系统动作'
  }

  function formatNumber(value) {
    if (typeof value !== 'number') return value || '-'
    return value.toLocaleString('zh-CN')
  }

  return {
    metrics,
    liveFeed,
    topCongestedRoutes,
    congestionSummary,
    trendChartRef,
    formatVehicles,
    formatFeedTime,
    resolveFeedIcon,
    typeLabel,
    formatNumber
  }
}
