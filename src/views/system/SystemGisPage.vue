<template>
  <div class="h-full overflow-hidden bg-slate-50 p-2.5 text-slate-900 xl:p-3">
    <div class="mx-auto flex h-full min-h-0 max-w-[1680px] flex-col">
      <section class="grid min-h-0 flex-1 grid-cols-1 gap-3 xl:grid-cols-[252px_minmax(0,1fr)] 2xl:grid-cols-[273px_minmax(0,1fr)]">
        <aside class="flex min-h-0 flex-col rounded-[28px] border border-slate-200 bg-white p-4 shadow-[0_18px_36px_-24px_rgba(15,23,42,0.24)]">
          <div class="rounded-[22px] bg-slate-50 px-4 py-3.5">
            <div class="flex flex-col gap-2.5">
              <div class="max-w-[210px]">
                <h2 class="text-[18px] font-bold tracking-tight text-slate-900">线路资产台账</h2>
                <p class="mt-1 text-[12px] leading-5 text-slate-500">按状态与长度快速筛选线路。</p>
              </div>
              <div class="flex items-end justify-between rounded-2xl bg-white px-4 py-2.5 shadow-[0_8px_20px_-16px_rgba(15,23,42,0.18)] ring-1 ring-slate-100">
                <div class="text-[14px] font-semibold tracking-tight text-slate-700">线路总数</div>
                <div class="flex items-baseline gap-1">
                  <span class="text-[34px] font-bold leading-none text-sky-600">{{ displayRoutes.length }}</span>
                  <span class="text-[13px] font-medium text-slate-400">条</span>
                </div>
              </div>
            </div>
          </div>

          <div
            class="mt-3 rounded-2xl border border-slate-200 bg-slate-50 px-3 py-2.5 transition-colors focus-within:border-blue-400 focus-within:bg-white"
          >
            <div class="flex items-center gap-2 text-sm text-slate-400">
              <Search class="h-4 w-4" />
              <input
                v-model.trim="searchQuery"
                type="text"
                placeholder="搜索线路、方向或告警信息"
                class="w-full border-none bg-transparent text-slate-700 outline-none focus:ring-0 placeholder:text-slate-400"
              />
            </div>
          </div>

          <div class="mt-3 grid grid-cols-1 gap-2.5 2xl:grid-cols-2">
            <div>
              <div class="mb-2 text-xs font-semibold text-slate-500">运行状态</div>
              <select
                v-model="statusFilter"
                class="w-full rounded-2xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-700 outline-none"
              >
                <option value="all">全部</option>
                <option value="normal">正常</option>
                <option value="warning">预警</option>
                <option value="error">编码异常</option>
              </select>
            </div>
            <div>
              <div class="mb-2 text-xs font-semibold text-slate-500">线路长度</div>
              <select
                v-model="lengthFilter"
                class="w-full rounded-2xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-700 outline-none"
              >
                <option value="all">全部</option>
                <option value="short">短线</option>
                <option value="medium">中线</option>
                <option value="long">长线</option>
              </select>
            </div>
          </div>

          <div class="mt-3 flex-1 overflow-auto px-1 pr-3">
            <div class="space-y-2.5 py-1">
            <article
              v-for="route in displayRoutes"
              :key="route.routeId"
              class="cursor-pointer rounded-2xl px-4 py-3 transition-all duration-200"
              :class="
                route.routeId === activeRoute.routeId
                  ? 'border border-blue-200 bg-blue-50/50'
                  : 'border border-slate-200 bg-white hover:shadow-[0_8px_24px_-20px_rgba(15,23,42,0.24)]'
              "
              @click="selectRoute(route.routeId)"
            >
              <div class="flex items-center justify-between gap-3">
                <div class="min-w-0 flex-1">
                  <h3 class="truncate font-bold text-[15px] text-slate-900">
                    {{ route.routeName }} {{ route.directionLabel }}
                  </h3>
                </div>
                <span
                  class="shrink-0 whitespace-nowrap rounded-full px-2 py-0.5 text-[11px] font-semibold leading-5"
                  :class="routeTagClass(route)"
                >
                  {{ routeTagText(route) }}
                </span>
              </div>
            </article>
            </div>
          </div>
        </aside>

        <section class="flex min-h-0 flex-col rounded-[28px] border border-slate-200 bg-white p-4 shadow-[0_18px_36px_-24px_rgba(15,23,42,0.24)]">
          <div class="grid shrink-0 grid-cols-[minmax(0,0.82fr)_minmax(620px,1.18fr)] gap-4 max-xl:grid-cols-1">
            <div class="min-w-0">
              <h2 class="text-[18px] font-bold tracking-tight text-slate-900">
                {{ activeRoute.routeName }} {{ activeRoute.directionLabel }}
              </h2>
              <p class="mt-1 text-[13px] leading-5 text-slate-500">{{ activeRoute.overview }}</p>
            </div>

            <div class="grid grid-cols-5 gap-3 max-2xl:grid-cols-3 max-md:grid-cols-2">
              <div
                v-for="metric in detailMetrics"
                :key="metric.label"
                class="flex min-h-[72px] min-w-0 flex-col rounded-2xl border border-slate-100 bg-white px-4 py-2.5 shadow-[0_2px_10px_-4px_rgba(0,0,0,0.05)]"
              >
                <div class="text-xs font-medium leading-4 text-slate-500">{{ metric.label }}</div>
                <div class="mt-1 text-[18px] font-bold leading-none text-slate-900">{{ metric.value }}</div>
              </div>
            </div>
          </div>

          <div
            class="mt-3 shrink-0 rounded-2xl px-3 py-2.5 text-[12px]"
            :class="
              warnings.length
                ? 'border border-orange-100 bg-orange-50 text-orange-900'
                : 'border border-emerald-100 bg-emerald-50 text-emerald-900'
            "
          >
            <div v-if="warnings.length" class="grid gap-x-6 gap-y-1.5 xl:grid-cols-2">
              <div
                v-for="(warning, index) in warnings.slice(0, 3)"
                :key="warning"
                class="flex items-center gap-2 font-medium"
              >
                <span class="inline-block h-2.5 w-2.5 rounded-full bg-orange-500"></span>
                {{ index + 1 }}. {{ warning }}
              </div>
            </div>
            <div v-else class="flex items-center gap-2 font-medium">
              <span class="inline-block h-2.5 w-2.5 rounded-full bg-emerald-500"></span>
              当前线路运行正常，暂无需要立即处置的空间编码与客流异常。
            </div>
          </div>

          <div class="mt-3 grid gap-3">
            <div
              class="relative z-20 min-h-[342px] overflow-visible rounded-[24px] border border-slate-100 bg-gradient-to-b from-slate-50 to-white p-4 shadow-sm"
            >
              <div class="flex items-center justify-between gap-4">
                <div class="text-[14px] font-semibold text-slate-700">线路主拓扑视图</div>
                <div class="text-xs text-slate-500">客流强度：低 → 高</div>
              </div>

              <div class="relative mt-4 h-[252px] overflow-visible">
                <div class="absolute left-0 top-[76px] z-10">
                  <div class="rounded-2xl border border-slate-200 bg-white px-3 py-1.5 text-sm font-semibold text-slate-600 shadow-sm">
                    起点
                  </div>
                </div>

                <div class="absolute right-0 top-[76px] z-10">
                  <div class="rounded-2xl border border-slate-200 bg-white px-3 py-1.5 text-sm font-semibold text-slate-600 shadow-sm">
                    终点
                  </div>
                </div>

                <div class="pointer-events-none absolute left-[58px] right-[58px] top-[88px] h-1.5 rounded-full bg-slate-300"></div>

                <div class="relative ml-[80px] mr-[80px] h-full overflow-visible">
                  <div
                    v-for="station in topologyStations"
                    :key="station.id"
                    class="absolute top-0 transition-all duration-500"
                    :style="stationAxisStyle(station)"
                  >
                    <button
                      data-topology-interactive="true"
                      type="button"
                      class="relative block h-[210px] w-full border-none bg-transparent p-0"
                      @click.stop="handleTopologyStationClick(station)"
                    >
                      <div
                        class="absolute left-1/2 top-[14px] w-[84px] -translate-x-1/2 text-center text-[10px] font-semibold leading-4 transition-all duration-300"
                        :class="[station.showLabel ? 'opacity-100' : 'opacity-0', stationLabelClass(station)]"
                      >
                        {{ station.showLabel ? station.name : '' }}
                      </div>

                      <div class="absolute left-1/2 top-[50px] z-10 flex h-10 w-10 -translate-x-1/2 items-center justify-center">
                        <span
                          v-if="station.state === 'selected'"
                          class="absolute h-11 w-11 rounded-full border-4 border-blue-200 bg-blue-500/15 shadow-[0_0_24px_rgba(59,130,246,0.24)]"
                        ></span>
                        <span
                          v-else-if="station.state === 'warning'"
                          class="absolute h-9 w-9 animate-pulse rounded-full border-4 border-rose-200 bg-rose-500/12"
                        ></span>
                        <span
                          class="relative inline-flex rounded-full border-4 border-white shadow-sm transition-all duration-500"
                          :class="stationDotClass(station)"
                        ></span>
                      </div>

                      <div class="absolute left-1/2 top-[112px] flex h-[88px] -translate-x-1/2 items-start justify-center">
                        <div class="flex h-full w-3 flex-col justify-end gap-1">
                          <span
                            v-for="n in 6"
                            :key="n"
                            class="h-[8px] rounded-full transition-all duration-500"
                            :class="stackSegmentClass(n, station.heat)"
                          ></span>
                        </div>
                      </div>
                    </button>

                    <div
                      v-if="station.isTooltipVisible"
                      data-topology-interactive="true"
                      class="absolute top-[120px] z-50 w-[220px] rounded-2xl border border-slate-200 bg-white p-4 text-left text-slate-900 shadow-xl"
                      :style="tooltipPlacementStyle(station)"
                    >
                      <div class="flex items-start justify-between gap-3">
                        <div class="text-[15px] font-bold">{{ station.name }}</div>
                        <span
                          class="rounded-full px-2.5 py-1 text-[11px] font-semibold"
                          :class="station.isAnomaly ? 'bg-rose-50 text-rose-600' : 'bg-emerald-50 text-emerald-600'"
                        >
                          {{ station.isAnomaly ? '异常' : '正常' }}
                        </span>
                      </div>

                      <div class="mt-3 space-y-2 text-[13px]">
                        <div class="flex items-center justify-between">
                          <span class="text-slate-500">当前客流</span>
                          <span class="font-semibold text-slate-900">{{ station.flow }}</span>
                        </div>
                        <div class="flex items-center justify-between">
                          <span class="text-slate-500">GIS 状态</span>
                          <span class="font-semibold" :class="station.isAnomaly ? 'text-rose-500' : 'text-slate-900'">
                            {{ station.encodingStatus }}
                          </span>
                        </div>
                      </div>

                      <div class="mt-4 flex items-end justify-between gap-3 rounded-xl bg-slate-50 px-3 py-3">
                        <div class="space-y-1 text-[12px] text-slate-500">
                          <div>客流强度</div>
                          <div class="text-[18px] font-bold text-slate-900">{{ station.heat }}%</div>
                        </div>
                        <div class="flex h-[72px] w-4 flex-col justify-end gap-1">
                          <span
                            v-for="n in 6"
                            :key="`tooltip-${station.id}-${n}`"
                            class="h-[9px] rounded-full"
                            :class="stackSegmentClass(n, station.heat)"
                          ></span>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <div class="relative z-10 rounded-3xl border border-slate-200 bg-white p-4">
              <div class="flex flex-wrap items-start justify-between gap-4">
                <div class="text-[14px] font-bold text-slate-900">站点健康图例</div>
                <div class="flex flex-wrap items-center gap-2 text-[11px] text-slate-500">
                  <span class="font-semibold text-slate-900">客流强度</span>
                  <span class="inline-flex items-center gap-1.5"><span class="h-4 w-2 rounded bg-rose-600"></span><span>&gt; 0.85</span></span>
                  <span class="inline-flex items-center gap-1.5"><span class="h-4 w-2 rounded bg-orange-500"></span><span>0.70 - 0.85</span></span>
                  <span class="inline-flex items-center gap-1.5"><span class="h-4 w-2 rounded bg-orange-400"></span><span>0.50 - 0.70</span></span>
                  <span class="inline-flex items-center gap-1.5"><span class="h-4 w-2 rounded bg-orange-300"></span><span>0.30 - 0.50</span></span>
                  <span class="inline-flex items-center gap-1.5"><span class="h-4 w-2 rounded bg-orange-200"></span><span>0 - 0.30</span></span>
                </div>
              </div>

              <div class="mt-3 grid gap-3 md:grid-cols-3">
                <div class="flex items-start gap-3 rounded-2xl border border-slate-100 bg-slate-50 px-3 py-3">
                  <span class="mt-1 h-3 w-3 rounded-full bg-slate-500"></span>
                  <div>
                    <div class="font-semibold text-slate-900">正常站点</div>
                    <div class="mt-1 text-[12px] text-slate-500">空间编码与运营数据正常</div>
                  </div>
                </div>

                <div class="flex items-start gap-3 rounded-2xl border border-slate-100 bg-slate-50 px-3 py-3">
                  <span class="mt-1 h-3 w-3 rounded-full bg-rose-500"></span>
                  <div>
                    <div class="font-semibold text-slate-900">异常站点</div>
                    <div class="mt-1 text-[12px] text-slate-500">存在坐标异常或编码问题</div>
                  </div>
                </div>

                <div class="flex items-start gap-3 rounded-2xl border border-slate-100 bg-slate-50 px-3 py-3">
                  <span class="mt-1 h-3 w-3 rounded-full bg-blue-500"></span>
                  <div>
                    <div class="font-semibold text-slate-900">当前选中站点</div>
                    <div class="mt-1 text-[12px] text-slate-500">显示详细客流与 GIS 状态信息</div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </section>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { useSystemGisPage } from '../../composables/useSystemGisPage'

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

const {
  activeRoute,
  activeRouteId,
  filteredRoutes,
  lengthFilter,
  routeOptions,
  selectRoute,
  selectStation,
  statusFilter
} = useSystemGisPage(props)

const searchQuery = ref('')
const activeTopologyStationId = ref('')

const displayRoutes = computed(() => {
  const keyword = searchQuery.value.trim().toLowerCase()

  return filteredRoutes.value.filter((route) => {
    const keywordMatch =
      !keyword ||
      `${route.routeName} ${route.directionLabel} ${route.overview} ${route.warnings.join(' ')}`
        .toLowerCase()
        .includes(keyword)

    return keywordMatch
  })
})

const selectedRoute = computed(() => activeRoute.value)

const anomalyCount = computed(() =>
  (selectedRoute.value.stations || []).filter((station) => station.isAnomaly).length
)
const crowdedCount = computed(() =>
  (selectedRoute.value.stations || []).filter((station) => station.crowding >= 0.8).length
)
const warnings = computed(() => selectedRoute.value.warnings || [])

const detailMetrics = computed(() => [
  { label: '总里程', value: formatDistance(selectedRoute.value.mileage) },
  { label: '站点数', value: `${selectedRoute.value.stationCount} 个` },
  { label: '编码完整度', value: `${selectedRoute.value.codingCompleteness}%` },
  { label: '异常站点数', value: `${anomalyCount.value}` },
  { label: '高拥挤站点数', value: `${crowdedCount.value}` }
])

const activeTopologyIndex = computed(() =>
  (selectedRoute.value.stations || []).findIndex((station) => station.id === activeTopologyStationId.value)
)

const topologyStations = computed(() => {
  const source = selectedRoute.value.stations || []
  const total = source.length

  if (!total) return []

  const activeIndex = activeTopologyIndex.value
  const gaps = Array.from({ length: Math.max(total - 1, 0) }, () => 1)
  let focusStart = -1
  let focusEnd = -1

  if (activeIndex >= 0) {
    if (activeIndex <= 1) {
      focusStart = 0
      focusEnd = Math.min(total - 1, 2)
    } else if (activeIndex >= total - 2) {
      focusEnd = total - 1
      focusStart = Math.max(0, total - 3)
    } else {
      focusStart = activeIndex - 1
      focusEnd = activeIndex + 1
    }

    gaps.forEach((_, gapIndex) => {
      if (gapIndex >= focusStart && gapIndex < focusEnd) {
        gaps[gapIndex] = 2.8
      } else if (gapIndex === focusStart - 1 || gapIndex === focusEnd) {
        gaps[gapIndex] = 1.3
      } else {
        gaps[gapIndex] = 0.72
      }
    })
  }

  const totalGapWeight = gaps.reduce((sum, value) => sum + value, 0) || 1
  const positions = source.map((_, index) => {
    if (index === 0) return 0
    const consumed = gaps.slice(0, index).reduce((sum, value) => sum + value, 0)
    return consumed / totalGapWeight
  })
  const intervalLabelIndexes = source
    .map((_, index) => index)
    .filter((index) => index % 3 === 0 && index !== activeIndex)
  const suppressedIntervalLabelIndexes = new Set()

  if (activeIndex >= 0) {
    const nearestLeftIntervalLabel = [...intervalLabelIndexes]
      .filter((labelIndex) => labelIndex < activeIndex && activeIndex - labelIndex <= 2)
      .sort((a, b) => b - a)[0]
    const nearestRightIntervalLabel = [...intervalLabelIndexes]
      .filter((labelIndex) => labelIndex > activeIndex && labelIndex - activeIndex <= 2)
      .sort((a, b) => a - b)[0]

    if (nearestLeftIntervalLabel !== undefined) {
      suppressedIntervalLabelIndexes.add(nearestLeftIntervalLabel)
    }
    if (nearestRightIntervalLabel !== undefined) {
      suppressedIntervalLabelIndexes.add(nearestRightIntervalLabel)
    }
  }

  return source.map((station, index) => {
    const heat = Math.round((station.crowding || 0) * 100)
    const isSelected = station.id === activeTopologyStationId.value
    const isLeftAdjacentToSelected = activeIndex >= 0 && index === activeIndex - 1
    const isRightAdjacentToSelected = activeIndex >= 0 && index === activeIndex + 1
    const isAdjacentToSelected = isLeftAdjacentToSelected || isRightAdjacentToSelected
    const inFocusWindow = activeIndex >= 0 && index >= focusStart && index <= focusEnd
    const nearFocusWindow =
      activeIndex >= 0 && (index === focusStart - 1 || index === focusEnd + 1)

    let width = 28
    if (activeIndex >= 0) {
      if (inFocusWindow) width = 108
      else if (nearFocusWindow) width = 52
      else width = 28
    }

    return {
      ...station,
      index,
      heat,
      flow: station.flow ?? Math.round((station.crowding || 0) * 1000),
      isAnomaly: Boolean(station.isAnomaly),
      isTooltipVisible: isSelected,
      showLabel:
        isSelected ||
        isAdjacentToSelected ||
        (index % 3 === 0 && !suppressedIntervalLabelIndexes.has(index)),
      state: isSelected ? 'selected' : station.isAnomaly ? 'warning' : 'normal',
      width,
      position: positions[index]
    }
  })
})

watch(
  () => selectedRoute.value.routeId,
  () => {
    activeTopologyStationId.value = ''
  }
)

function handleTopologyStationClick(station) {
  const nextId = activeTopologyStationId.value === station.id ? '' : station.id
  activeTopologyStationId.value = nextId

  if (nextId) {
    selectStation(activeRoute.value.routeId, station.id)
  }
}

function stationAxisStyle(station) {
  return {
    left: `calc(${(station.position * 100).toFixed(4)}% - ${station.width / 2}px)`,
    width: `${station.width}px`
  }
}

function tooltipPlacementStyle(station) {
  if (station.position <= 0.12) {
    return { left: '0', transform: 'translateX(0)' }
  }
  if (station.position >= 0.88) {
    return { right: '0', left: 'auto', transform: 'translateX(0)' }
  }
  return { left: '50%', transform: 'translateX(-50%)' }
}

function routeTagText(route) {
  if (route.codingIssues > 0) {
    return `编码异常 ${route.codingIssues} 处`
  }
  if (route.status === 'warning') {
    return '高客流'
  }
  return '正常'
}

function routeTagClass(route) {
  if (route.codingIssues > 0) {
    return 'border-rose-200 bg-rose-50 text-rose-600'
  }
  if (route.status === 'warning') {
    return 'border-orange-200 bg-orange-50 text-orange-600'
  }
  return 'border-emerald-200 bg-emerald-50 text-emerald-600'
}

function stationDotClass(station) {
  if (station.state === 'selected') {
    return 'h-6 w-6 bg-blue-500'
  }
  if (station.state === 'warning') {
    return 'h-4 w-4 bg-rose-500'
  }
  return 'h-3.5 w-3.5 bg-slate-500'
}

function stationLabelClass(station) {
  if (!station.showLabel) {
    return 'text-slate-400'
  }
  if (station.state === 'selected') {
    return 'text-blue-700'
  }
  if (station.state === 'warning') {
    return 'text-rose-600'
  }
  return 'text-slate-700'
}

function stackSegmentClass(n, heat) {
  const level = heat - (6 - n) * 10
  if (level > 55) return 'bg-rose-500'
  if (level > 40) return 'bg-orange-400'
  if (level > 25) return 'bg-orange-300'
  return 'bg-orange-200'
}

function handleDocumentPointerDown(event) {
  if (event.target?.closest?.('[data-topology-interactive="true"]')) {
    return
  }
  activeTopologyStationId.value = ''
}

function formatDistance(value) {
  return `${Number(value || 0).toFixed(1)} km`
}

onMounted(() => {
  document.addEventListener('pointerdown', handleDocumentPointerDown)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', handleDocumentPointerDown)
})
</script>
