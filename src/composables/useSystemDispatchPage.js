import { computed, markRaw, nextTick, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import * as echarts from 'echarts'
import { Bottom } from '@element-plus/icons-vue'

export function useSystemDispatchPage(props) {
  const gaugeChartRef = ref(null)
  const gaugeChart = shallowRef(null)

  const metrics = computed(() => props.pageData?.metrics || {})
  const strategies = computed(() => Array.isArray(props.pageData?.strategies) ? props.pageData.strategies : [])
  const resourcePool = computed(() => props.pageData?.resourcePool || {})

  const totalVehicles = computed(() => Number(resourcePool.value.totalVehicles || 2800))

  const vehicleUtilization = computed(() => {
    const standby = Number(resourcePool.value.standbyVehicles || 0)
    return Math.max(6, Math.min(100, Math.round((standby / totalVehicles.value) * 100)))
  })

  const activeVehiclePercentage = computed(() => {
    const active = Number(resourcePool.value.activeVehicles || 0)
    return Math.max(6, Math.min(100, Math.round((active / totalVehicles.value) * 100)))
  })

  const backupDriverPercentage = computed(() => {
    const drivers = Number(resourcePool.value.backupDrivers || 0)
    return Math.max(8, Math.min(100, Math.round((drivers / 320) * 100)))
  })

  const signalPlanPercentage = computed(() => {
    const plans = Number(resourcePool.value.signalPlans || 0)
    return Math.max(8, Math.min(100, Math.round((plans / 24) * 100)))
  })

  const vehicleBarColor = computed(() => vehicleUtilization.value >= 90 ? '#f5a623' : '#42d3b6')
  const activeBarColor = computed(() => activeVehiclePercentage.value >= 90 ? '#f5a623' : '#32c6ff')

  const normalizedStrategies = computed(() => {
    const source = strategies.value.length
      ? strategies.value
      : [
          { line: '105路', action: '建议高峰增发 1 班补车' },
          { line: '202路', action: '建议提前 8 分钟发车' },
          { line: 'K6 快线', action: '建议切换大站快车模式' }
        ]

    return source.slice(0, 4).map((item, index) => {
      const presets = [
        {
          levelLabel: '紧急增派',
          levelType: 'danger',
          reason: '预测 18:00 宏声桥站将溢出 120 人，需立即补充运力。',
          time: '17:42'
        },
        {
          levelLabel: '防拥堵预警',
          levelType: 'warning',
          reason: '实验中学站换乘客流快速攀升，建议前置调度车辆。',
          time: '17:35'
        },
        {
          levelLabel: '潮汐优化',
          levelType: 'success',
          reason: '下行需求已超过上行 1.3 倍，建议切换弹性发车间隔。',
          time: '17:28'
        },
        {
          levelLabel: '策略校正',
          levelType: 'info',
          reason: '模型识别到站群吸附增强，建议启动联动信号方案。',
          time: '17:21'
        }
      ]

      return {
        ...item,
        ...presets[index % presets.length]
      }
    })
  })

  onMounted(() => {
    nextTick(() => {
      initGauge()
      renderGauge()
      window.addEventListener('resize', handleResize)
    })
  })

  onBeforeUnmount(() => {
    window.removeEventListener('resize', handleResize)
    if (gaugeChart.value) {
      gaugeChart.value.dispose()
      gaugeChart.value = null
    }
  })

  function initGauge() {
    if (!gaugeChartRef.value || gaugeChart.value) return
    gaugeChart.value = markRaw(echarts.init(gaugeChartRef.value))
  }

  function renderGauge() {
    if (!gaugeChartRef.value) return
    if (!gaugeChart.value) initGauge()
    if (!gaugeChart.value) return

    gaugeChart.value.setOption({
      animationDuration: 900,
      series: [
        {
          type: 'gauge',
          startAngle: 220,
          endAngle: -40,
          min: 0,
          max: 100,
          radius: '94%',
          progress: {
            show: true,
            roundCap: true,
            width: 12,
            itemStyle: {
              color: '#46d1b6'
            }
          },
          axisLine: {
            roundCap: true,
            lineStyle: {
              width: 12,
              color: [[1, 'rgba(214, 223, 233, 0.48)']]
            }
          },
          axisTick: { show: false },
          splitLine: { show: false },
          axisLabel: { show: false },
          pointer: { show: false },
          anchor: { show: false },
          detail: {
            valueAnimation: true,
            offsetCenter: [0, '6%'],
            fontSize: 28,
            fontWeight: 900,
            color: '#183044',
            formatter: '{value}%'
          },
          title: {
            offsetCenter: [0, '58%'],
            color: '#6f7d88',
            fontSize: 11
          },
          data: [{ value: Number(metrics.value.dispatchAdoptionRate || 88.5), name: '采纳率' }]
        }
      ]
    }, true)
  }

  function handleResize() {
    if (gaugeChart.value) gaugeChart.value.resize()
  }

  function suffix(value, unit) {
    return value == null ? '-' : `${value}${unit}`
  }

  return {
    Bottom,
    metrics,
    gaugeChartRef,
    resourcePool,
    totalVehicles,
    vehicleUtilization,
    activeVehiclePercentage,
    backupDriverPercentage,
    signalPlanPercentage,
    vehicleBarColor,
    activeBarColor,
    normalizedStrategies,
    suffix
  }
}
