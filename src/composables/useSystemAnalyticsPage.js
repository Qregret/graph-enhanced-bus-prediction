import { computed, markRaw, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import * as echarts from 'echarts'
import {
  CircleCheckFilled,
  Clock,
  DataAnalysis,
  Histogram,
  WarningFilled
} from '@element-plus/icons-vue'

export function useSystemAnalyticsPage(props) {
  const activeVersion = ref('v2.0')
  const activeRange = ref('7d')
  const trendChartRef = ref(null)
  const trendChart = shallowRef(null)
  const versions = ['v1.0', 'v2.0']

  const fallbackMetrics = {
    mape: 7.4,
    avgLatencyMinutes: 3.4,
    sampleCoverage: 99,
    effectiveDays: 1
  }

  const metrics = computed(() => ({
    ...fallbackMetrics,
    ...(props.pageData?.metrics || {})
  }))

  const kpiCards = computed(() => [
    {
      label: 'MAPE',
      value: `${metrics.value.mape}%`,
      description: '较上周期下降 0.6%，主干线路误差收敛明显。',
      tag: '稳定',
      tagType: 'success',
      tone: 'green'
    },
    {
      label: '平均推理时延',
      value: `${metrics.value.avgLatencyMinutes} mins`,
      description: '满足分钟级滚动更新，当前吞吐维持平稳。',
      tag: '良好',
      tagType: 'primary',
      tone: 'blue'
    },
    {
      label: '样本覆盖率',
      value: `${metrics.value.sampleCoverage}%`,
      description: '样本完整度较高，节假日标签已纳入本轮评估。',
      tag: '已同步',
      tagType: 'success',
      tone: 'cyan'
    },
    {
      label: '有效日期',
      value: `${metrics.value.effectiveDays}`,
      description: '当前评估窗口 1 天，建议持续观察高峰样本。',
      tag: '需关注',
      tagType: 'warning',
      tone: 'orange'
    }
  ])

  const modelBenchmark = computed(() => [
    { name: 'OD误差', v1: 68, v2: 76, result: '提升 8%', comment: 'OD 主路径误差带收敛更明显' },
    { name: '站点误差', v1: 64, v2: 72, result: '提升 8%', comment: '站点级波动控制更稳' },
    { name: '高峰拟合', v1: 70, v2: 85, result: '提升 15%', comment: '晚高峰波峰拟合更接近真实' },
    { name: '推理耗时', v1: 78, v2: 78, result: '持平', comment: '分钟级推理吞吐维持稳定' }
  ])

  const summaryCards = computed(() => [
    {
      title: 'v2.0 在主干线路 OD 预测上表现稳定',
      description: '宏声桥、实验中学等主干站点转移误差下降，适合优先部署到核心走廊。',
      icon: DataAnalysis,
      type: 'is-cyan'
    },
    {
      title: '当前模型推理耗时适合分钟级滚动更新',
      description: '平均推理时延控制在 3.4 分钟，支持工作台分钟级刷新与快速回溯。',
      icon: Clock,
      type: 'is-green'
    },
    {
      title: '高峰客流拟合能力优于旧版本',
      description: '晚高峰波峰拟合更平滑，线路客流峰值识别准确率明显提升。',
      icon: Histogram,
      type: 'is-blue'
    },
    {
      title: '建议补充节假日与恶劣天气标签特征',
      description: '节假日和突发天气样本仍偏少，建议在下一轮训练中补齐特征维度。',
      icon: WarningFilled,
      type: 'is-orange'
    }
  ])

  const trendSeries = computed(() => {
    if (activeRange.value === '30d') {
      return {
        labels: ['03-12', '03-14', '03-16', '03-18', '03-20', '03-22', '03-24', '03-26', '03-28', '03-30', '04-01', '04-03', '04-05', '04-07'],
        mape: [8.2, 8.0, 7.9, 7.8, 7.6, 7.5, 7.4, 7.3, 7.2, 7.1, 7.0, 7.2, 7.3, 7.4],
        peak: [10.8, 10.5, 10.2, 10.0, 9.8, 9.5, 9.3, 9.2, 9.0, 8.9, 8.7, 8.9, 9.0, 9.1],
        latency: [3.9, 3.8, 3.7, 3.7, 3.6, 3.5, 3.4, 3.3, 3.2, 3.2, 3.1, 3.3, 3.3, 3.4]
      }
    }

    return {
      labels: ['04-01', '04-02', '04-03', '04-04', '04-05', '04-06', '04-07'],
      mape: [7.9, 7.6, 7.3, 7.5, 7.1, 7.2, 7.4],
      peak: [9.6, 9.3, 8.8, 9.0, 8.7, 8.9, 9.1],
      latency: [3.5, 3.4, 3.3, 3.2, 3.3, 3.2, 3.4]
    }
  })

  const trendSummary = computed(() => {
    if (activeRange.value === '30d') {
      return '近 30 日误差整体缓慢下降，高峰误差波动已趋于收敛，服务耗时保持在分钟级阈值内。'
    }
    return '近 7 日误差整体稳定，高峰期存在轻微波动，但未超过阈值，适合继续灰度验证。'
  })

  const featureContributions = computed(() => [
    { name: 'OD历史序列', value: 92 },
    { name: '高峰时段识别', value: 84 },
    { name: '线路热度', value: 76 },
    { name: '天气特征', value: 61 },
    { name: '节假日标签', value: 48 }
  ])

  const dataQuality = computed(() => [
    {
      label: '数据同步状态',
      value: '已完成',
      description: '热缓存与评估样本同步完成，可直接参与回溯评估。',
      level: 'is-green',
      tagType: 'success'
    },
    {
      label: '缺失率',
      value: '0.8%',
      description: '关键特征表缺失率控制在阈值内，样本完整度较高。',
      level: 'is-cyan',
      tagType: 'primary'
    },
    {
      label: '异常样本',
      value: '12 条',
      description: '主要集中在晚高峰异常波动线路，建议继续排查标签分布。',
      level: 'is-orange',
      tagType: 'warning'
    },
    {
      label: '标签完整性',
      value: '高',
      description: '节假日、高峰时段与天气标签完整，可支持下一轮校准。',
      level: 'is-green',
      tagType: 'success'
    }
  ])

  const serviceStatus = computed(() => [
    {
      label: '当前模型版本',
      description: '主推理服务已完成 v2.0 部署，运行状态稳定。',
      meta: '08:00:00',
      status: activeVersion.value,
      tagType: 'primary'
    },
    {
      label: '最近更新时间',
      description: '最近一轮参数同步与模型热更新已按计划完成。',
      meta: '08:00:00',
      status: '已完成',
      tagType: 'success'
    },
    {
      label: '服务状态',
      description: '在线推理服务和回溯评估服务均正常运行。',
      meta: '实时',
      status: '正常',
      tagType: 'success'
    },
    {
      label: '数据同步',
      description: '特征表、标签表与热缓存场景同步成功。',
      meta: '07:48:22',
      status: '已完成',
      tagType: 'success'
    },
    {
      label: '今日推理批次',
      description: '分钟级滚动推理累计完成批次。',
      meta: '累计',
      status: '128',
      tagType: 'primary'
    },
    {
      label: '最近一次评估任务',
      description: '今日历史评估任务执行完毕，可继续追踪高峰窗口。',
      meta: '09:12:40',
      status: '已完成',
      tagType: 'success'
    }
  ])

  watch(activeRange, () => {
    renderTrendChart()
  })

  watch(activeVersion, () => {
    renderTrendChart()
  })

  onMounted(() => {
    nextTick(() => {
      initTrendChart()
      renderTrendChart()
      window.addEventListener('resize', handleResize)
    })
  })

  onBeforeUnmount(() => {
    window.removeEventListener('resize', handleResize)
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
    if (!trendChart.value) initTrendChart()
    if (!trendChart.value) return

    const source = trendSeries.value
    trendChart.value.setOption({
      animationDuration: 900,
      animationEasing: 'cubicOut',
      grid: {
        top: 40,
        right: 26,
        bottom: 42,
        left: 56
      },
      legend: {
        top: 0,
        right: 0,
        itemWidth: 10,
        itemHeight: 10,
        textStyle: {
          color: '#7f8d99',
          fontSize: 12,
          fontWeight: 700
        }
      },
      tooltip: {
        trigger: 'axis',
        backgroundColor: 'rgba(15, 28, 40, 0.94)',
        borderColor: 'rgba(76, 198, 239, 0.22)',
        borderWidth: 1,
        textStyle: {
          color: '#eef9ff',
          fontSize: 12
        }
      },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: source.labels,
        axisTick: { show: false },
        axisLine: {
          lineStyle: {
            color: 'rgba(116, 144, 168, 0.24)'
          }
        },
        axisLabel: {
          color: '#8d9ba8',
          fontSize: 11
        }
      },
      yAxis: [
        {
          type: 'value',
          name: '误差',
          nameTextStyle: {
            color: '#90a0ad',
            fontSize: 11,
            padding: [0, 0, 0, -10]
          },
          axisLine: { show: false },
          axisTick: { show: false },
          splitLine: {
            lineStyle: {
              color: 'rgba(101, 123, 141, 0.12)'
            }
          },
          axisLabel: {
            color: '#8d9ba8',
            fontSize: 11
          }
        },
        {
          type: 'value',
          name: '时延',
          nameTextStyle: {
            color: '#90a0ad',
            fontSize: 11,
            padding: [0, -8, 0, 0]
          },
          axisLine: { show: false },
          axisTick: { show: false },
          splitLine: { show: false },
          axisLabel: {
            color: '#8d9ba8',
            fontSize: 11
          }
        }
      ],
      series: [
        {
          name: 'MAPE 趋势',
          type: 'line',
          smooth: true,
          symbol: 'circle',
          symbolSize: 7,
          data: source.mape,
          lineStyle: {
            width: 3,
            color: '#35d5f2'
          },
          itemStyle: {
            color: '#b2f6ff',
            borderColor: '#ffffff',
            borderWidth: 2
          },
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: 'rgba(53, 213, 242, 0.28)' },
              { offset: 1, color: 'rgba(53, 213, 242, 0.02)' }
            ])
          }
        },
        {
          name: '高峰误差波动',
          type: 'line',
          smooth: true,
          symbol: 'circle',
          symbolSize: 6,
          data: source.peak,
          lineStyle: {
            width: 3,
            color: '#2b72ea'
          },
          itemStyle: {
            color: '#6ea4ff',
            borderColor: '#ffffff',
            borderWidth: 2
          }
        },
        {
          name: '推理时延波动',
          type: 'line',
          yAxisIndex: 1,
          smooth: true,
          symbol: 'circle',
          symbolSize: 5,
          data: source.latency,
          lineStyle: {
            width: 2,
            type: 'dashed',
            color: '#55c393'
          },
          itemStyle: {
            color: '#55c393'
          }
        }
      ]
    }, true)
  }

  function handleResize() {
    if (trendChart.value) {
      trendChart.value.resize()
    }
  }

  return {
    CircleCheckFilled,
    activeVersion,
    activeRange,
    versions,
    kpiCards,
    modelBenchmark,
    summaryCards,
    trendChartRef,
    trendSummary,
    featureContributions,
    dataQuality,
    serviceStatus
  }
}
