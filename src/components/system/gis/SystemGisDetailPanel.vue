<template>
  <aside class="gis-float-panel gis-float-panel--detail">
    <div class="gis-detail__head">
      <div class="gis-detail__title">
        <span class="gis-detail__kicker">{{ detailMode === 'route' ? TEXT.routeObject : TEXT.stationObject }}</span>
        <h3>{{ currentDetailTitle }}</h3>
        <p>{{ detailDescription }}</p>
      </div>
      <el-tag round :type="detailMode === 'route' ? activeRoute.statusTagType : stationTagType">
        {{ detailMode === 'route' ? activeRoute.statusLabel : stationStatusText }}
      </el-tag>
    </div>

    <div class="gis-detail__metrics">
      <article v-for="item in metricCards" :key="item.label">
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
      </article>
    </div>

    <section class="gis-detail__alert">
      <div class="gis-detail__alert-title">{{ TEXT.alertTitle }}</div>
      <ul>
        <li v-for="warning in warningList" :key="warning">{{ warning }}</li>
      </ul>
    </section>

    <div class="gis-detail__content">
      <template v-if="activeDetailTab === 'trend'">
        <section v-if="detailMode === 'route'" class="gis-detail__section">
          <header>
            <strong>{{ TEXT.routeTrend }}</strong>
            <span>{{ `${activeRoute.routeName} ${TEXT.routePanorama}` }}</span>
          </header>
          <div class="gis-route-trend">
            <article v-for="point in activeRoute.trend" :key="point.label" class="gis-route-trend__item">
              <div class="gis-route-trend__row">
                <span>{{ point.label }}</span>
                <strong>{{ point.value }}</strong>
              </div>
              <div class="gis-route-trend__bar">
                <i :style="{ width: `${point.value}%` }"></i>
              </div>
            </article>
          </div>
        </section>

        <section v-else class="gis-detail__section">
          <header>
            <strong>{{ TEXT.stationTrend }}</strong>
            <span>{{ stationTrendHint }}</span>
          </header>
          <div ref="stationTrendRef" class="gis-station-trend"></div>
          <div class="gis-tag-group">
            <el-tag v-for="tag in selectedStation?.nearbyTags || []" :key="tag" round effect="light" type="primary">
              {{ tag }}
            </el-tag>
          </div>
        </section>
      </template>

      <template v-else-if="activeDetailTab === 'encoding'">
        <section v-if="detailMode === 'route'" class="gis-detail__section">
          <header>
            <strong>{{ TEXT.encodingIssues }}</strong>
            <span>{{ `${activeRoute.codingIssues} ${TEXT.pendingCount}` }}</span>
          </header>
          <div class="gis-task-list">
            <button
              v-for="task in activeRoute.encodingTasks"
              :key="task.id"
              type="button"
              class="gis-task-item"
              :class="`is-${task.severity}`"
              @click="$emit('focus-encoding-issue', task)"
            >
              <strong>{{ task.title }}</strong>
              <p>{{ task.summary }}</p>
            </button>
            <p v-if="!activeRoute.encodingTasks.length" class="gis-empty-hint">{{ TEXT.noEncodingIssues }}</p>
          </div>
        </section>

        <section v-else class="gis-detail__section">
          <header>
            <strong>{{ TEXT.encodingStatus }}</strong>
            <span>{{ stationStatusText }}</span>
          </header>
          <div class="gis-code-card">
            <span>{{ TEXT.encodingStatus }}</span>
            <strong>{{ stationStatusText }}</strong>
            <p>{{ selectedStation?.encodingRemark || '-' }}</p>
          </div>
        </section>
      </template>

      <template v-else>
        <section class="gis-detail__section">
          <header>
            <strong>{{ TEXT.latestLogs }}</strong>
            <span>{{ detailMode === 'route' ? TEXT.routeView : TEXT.stationView }}</span>
          </header>
          <ul class="gis-record-list" v-if="detailMode === 'route'">
            <li v-for="item in activeRoute.operationLogs" :key="`${item.time}-${item.title}`">
              <time>{{ item.time }}</time>
              <div>
                <strong>{{ item.title }}</strong>
                <p>{{ item.detail }}</p>
              </div>
            </li>
          </ul>
          <ul class="gis-record-list" v-else>
            <li v-for="item in selectedStation?.updates || []" :key="item">
              <time>{{ TEXT.updated }}</time>
              <div>
                <strong>{{ item }}</strong>
              </div>
            </li>
          </ul>
        </section>
      </template>
    </div>

    <div class="gis-detail__tabs">
      <button
        v-for="tab in detailTabs"
        :key="tab.value"
        type="button"
        :class="{ 'is-active': activeDetailTab === tab.value }"
        @click="$emit('update:active-detail-tab', tab.value)"
      >
        {{ tab.label }}
      </button>
    </div>
  </aside>
</template>

<script setup>
import { computed, markRaw, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import * as echarts from 'echarts'

const TEXT = {
  routeObject: '\u7ebf\u8def\u5bf9\u8c61',
  stationObject: '\u7ad9\u70b9\u5bf9\u8c61',
  stationAreaFallback: '\u7ad9\u70b9\u7247\u533a',
  stationInfluenceFallback: '\u67e5\u770b\u7ad9\u70b9 GIS \u8be6\u60c5\u4e0e\u5ba2\u6d41\u7279\u5f81\u3002',
  stable: '\u7a33\u5b9a',
  stationCount: '\u7ad9\u70b9\u6570\u91cf',
  routeBelong: '\u6240\u5c5e\u7ebf\u8def',
  totalMileage: '\u603b\u91cc\u7a0b',
  coordinate: '\u7ecf\u7eac\u5ea6',
  completeness: '\u7f16\u7801\u5b8c\u6574\u5ea6',
  crowding: '\u62e5\u6324\u6307\u6570',
  alertTitle: '\u9884\u8b66\u4fe1\u606f',
  routeTrend: '\u5ba2\u6d41\u8fd0\u8425\u8d8b\u52bf',
  routePanorama: '\u5168\u666f',
  stationTrend: '\u7ad9\u70b9\u5ba2\u6d41\u8d8b\u52bf',
  currentDayData: '\u5f53\u65e5\u6570\u636e',
  encodingIssues: '\u7a7a\u95f4\u7f16\u7801\u5f02\u5e38',
  pendingCount: '\u5904\u5f85\u5904\u7406',
  noEncodingIssues: '\u5f53\u524d\u7ebf\u8def\u6682\u65e0\u7f16\u7801\u5f02\u5e38\u4efb\u52a1\u3002',
  encodingStatus: '\u7a7a\u95f4\u7f16\u7801\u72b6\u6001',
  latestLogs: '\u6700\u65b0\u8fd0\u8425\u8bb0\u5f55',
  routeView: '\u7ebf\u8def\u89c6\u89d2',
  stationView: '\u7ad9\u70b9\u89c6\u89d2',
  updated: '\u66f4\u65b0',
  trendTab: '\u5ba2\u6d41\u8fd0\u8425\u8d8b\u52bf',
  encodingTab: '\u7a7a\u95f4\u7f16\u7801\u5f02\u5e38',
  logsTab: '\u6700\u65b0\u8fd0\u8425\u8bb0\u5f55',
  noStationWarning: '\u5f53\u524d\u7ad9\u70b9\u6682\u65e0\u7a7a\u95f4\u7f16\u7801\u5f02\u5e38\u3002',
  loadingStationDetail: '\u6b63\u5728\u52a0\u8f7d\u7ad9\u70b9\u8be6\u60c5'
}

const props = defineProps({
  activeDetailTab: { type: String, required: true },
  activeRoute: { type: Object, required: true },
  currentDetailTitle: { type: String, required: true },
  detailMode: { type: String, required: true },
  selectedDate: { type: String, default: '' },
  stationDetailLoading: { type: Boolean, default: false },
  selectedStation: { type: Object, default: null }
})

defineEmits(['focus-encoding-issue', 'update:active-detail-tab'])

const detailTabs = [
  { value: 'trend', label: TEXT.trendTab },
  { value: 'encoding', label: TEXT.encodingTab },
  { value: 'logs', label: TEXT.logsTab }
]

const stationTrendRef = ref(null)
const stationTrendChart = shallowRef(null)

const detailDescription = computed(() => {
  if (props.detailMode === 'route') {
    return props.activeRoute.overview
  }

  return `${props.selectedStation?.area || TEXT.stationAreaFallback}，${props.selectedStation?.influence || TEXT.stationInfluenceFallback}`
})

const stationTagType = computed(() => (props.selectedStation?.isAnomaly ? 'danger' : 'success'))
const stationStatusText = computed(() => props.selectedStation?.encodingStatus || TEXT.stable)
const stationTrendHint = computed(() => (props.stationDetailLoading ? TEXT.loadingStationDetail : props.selectedDate || TEXT.currentDayData))

const formatStationCoordinate = computed(() => {
  if (!props.selectedStation) return '-'
  return `${Number(props.selectedStation.lng).toFixed(4)} / ${Number(props.selectedStation.lat).toFixed(4)}`
})

const formatStationCrowding = computed(() => {
  if (!props.selectedStation) return '-'
  return `${Math.round((props.selectedStation.crowding || 0) * 100)}%`
})

const metricCards = computed(() => {
  if (props.detailMode === 'route') {
    return [
      { label: TEXT.stationCount, value: props.activeRoute.stationCount },
      { label: TEXT.totalMileage, value: `${props.activeRoute.mileage} km` },
      { label: TEXT.completeness, value: `${props.activeRoute.codingCompleteness}%` }
    ]
  }

  return [
    { label: TEXT.routeBelong, value: props.selectedStation?.lineNames?.[0] || '-' },
    { label: TEXT.coordinate, value: formatStationCoordinate.value },
    { label: TEXT.crowding, value: formatStationCrowding.value }
  ]
})

const warningList = computed(() => {
  if (props.detailMode === 'route') {
    return props.activeRoute.warnings?.length ? props.activeRoute.warnings : [TEXT.noStationWarning]
  }

  return [props.selectedStation?.encodingRemark || TEXT.noStationWarning]
})

function renderStationTrend() {
  if (!stationTrendChart.value || !props.selectedStation) return

  stationTrendChart.value.setOption({
    animationDuration: 200,
    grid: { top: 16, right: 12, bottom: 20, left: 30, containLabel: true },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: ['06:00', '08:00', '10:00', '12:00', '14:00', '16:00'],
      axisLine: { lineStyle: { color: '#d0d9e5' } },
      axisTick: { show: false },
      axisLabel: { color: '#7a8ca3', fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { lineStyle: { color: 'rgba(184, 198, 215, 0.3)' } },
      axisLabel: { color: '#7a8ca3', fontSize: 11 }
    },
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(15, 29, 49, 0.94)',
      borderWidth: 0,
      textStyle: { color: '#f4f8fc' }
    },
    series: [
      {
        type: 'line',
        smooth: true,
        data: props.selectedStation.trend || [],
        symbol: 'circle',
        symbolSize: 7,
        lineStyle: { width: 3, color: '#00a3ff' },
        itemStyle: { color: '#00a3ff', borderColor: '#fff', borderWidth: 2 },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(0, 163, 255, 0.26)' },
            { offset: 1, color: 'rgba(0, 163, 255, 0.02)' }
          ])
        }
      }
    ]
  })
}

function resizeChart() {
  stationTrendChart.value?.resize()
}

onMounted(() => {
  if (stationTrendRef.value) {
    stationTrendChart.value = markRaw(echarts.init(stationTrendRef.value))
  }
  nextTick(renderStationTrend)
  window.addEventListener('resize', resizeChart)
})

watch(() => props.selectedStation, () => nextTick(renderStationTrend))

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeChart)
  stationTrendChart.value?.dispose()
  stationTrendChart.value = null
})
</script>
