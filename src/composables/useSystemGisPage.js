import { computed, ref, watch } from 'vue'
import { fetchJson } from '../services/http'

const TEXT = {
  layerRoad: '\u9053\u8def',
  layerSatellite: '\u536b\u661f',
  layerHeat: '\u5ba2\u6d41\u70ed\u529b',
  emptyRoute: '\u6682\u65e0\u7ebf\u8def',
  statusNormal: '\u8fd0\u884c\u6b63\u5e38',
  statusWarning: '\u91cd\u70b9\u5173\u6ce8',
  statusError: '\u7f16\u7801\u5f02\u5e38',
  pressureNormal: '\u8fd0\u884c\u5e73\u7a33',
  pressureWarning: '\u5ba2\u6d41\u538b\u529b\u504f\u9ad8',
  pressureError: '\u7f16\u7801\u4fee\u590d\u961f\u5217',
  stationPrefix: '\u7ad9\u70b9',
  routeFallbackOverview: '\u6682\u672a\u83b7\u53d6\u5230\u7ebf\u8def GIS \u6570\u636e\u3002',
  routeCount: '\u7ebf\u8def\u6570',
  stationCount: '\u7ad9\u70b9\u6570',
  warningRoutes: '\u9884\u8b66\u7ebf\u8def',
  encodingIssues: '\u7f16\u7801\u5f02\u5e38',
  routeDetail: 'GIS \u8be6\u60c5',
  stable: '\u7a33\u5b9a',
  waitReview: '\u5f85\u590d\u6838',
  logRecord: '\u8fd0\u884c\u8bb0\u5f55',
  encodingReview: '\u7f16\u7801\u590d\u6838',
  encodingTask: '\u7f16\u7801\u590d\u6838\u4efb\u52a1',
  metricFallback: '\u6307\u6807',
  direction: '\u65b9\u5411'
}

const LAYERS = [TEXT.layerRoad, TEXT.layerSatellite, TEXT.layerHeat]
const EMPTY_ROUTE_ID = ''
const DEFAULT_ROUTE_COLOR = '#30B7FF'
const ROUTE_DETAIL_CACHE_LIMIT = 160
const STATION_DETAIL_CACHE_LIMIT = 320
const routeDetailCache = new Map()
const routeDetailRequests = new Map()
const stationDetailCache = new Map()
const stationDetailRequests = new Map()

const EMPTY_ROUTE = Object.freeze({
  routeId: EMPTY_ROUTE_ID,
  routeName: TEXT.emptyRoute,
  directionLabel: '',
  stationCount: 0,
  mileage: 0,
  status: 'normal',
  statusLabel: TEXT.statusNormal,
  statusTagType: 'success',
  pressureLabel: TEXT.pressureNormal,
  pressurePercent: 0,
  codingCompleteness: 0,
  codingIssues: 0,
  color: DEFAULT_ROUTE_COLOR,
  overview: TEXT.routeFallbackOverview,
  warnings: [],
  trend: [],
  encodingTasks: [],
  operationLogs: [],
  bbox: null,
  path: [],
  stations: []
})

function firstDefined(...values) {
  return values.find((value) => value !== undefined && value !== null)
}

function asArray(value) {
  return Array.isArray(value) ? value : []
}

function asText(value, fallback = '') {
  if (value === undefined || value === null) {
    return fallback
  }

  return String(value)
}

function asNumber(value, fallback = 0) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : fallback
}

function clamp01(value, fallback = 0) {
  const parsed = asNumber(value, fallback)
  if (parsed > 1) {
    return Math.max(0, Math.min(1, parsed / 100))
  }

  return Math.max(0, Math.min(1, parsed))
}

function uniqueValues(values) {
  return [...new Set(values.filter(Boolean))]
}

function buildStatusLabel(status) {
  if (status === 'error') return TEXT.statusError
  if (status === 'warning') return TEXT.statusWarning
  return TEXT.statusNormal
}

function buildStatusTagType(status) {
  if (status === 'error') return 'danger'
  if (status === 'warning') return 'warning'
  return 'success'
}

function buildPressureLabel(status) {
  if (status === 'error') return TEXT.pressureError
  if (status === 'warning') return TEXT.pressureWarning
  return TEXT.pressureNormal
}

function normalizeStatus(value, codingIssues = 0, warningCount = 0) {
  const raw = asText(value, '').toLowerCase()

  if (['error', 'danger', 'abnormal', 'offline', TEXT.statusError].includes(raw)) {
    return 'error'
  }

  if (['warning', 'warn', 'alert', 'busy', TEXT.statusWarning].includes(raw)) {
    return 'warning'
  }

  if (codingIssues > 0) {
    return 'error'
  }

  if (warningCount > 0) {
    return 'warning'
  }

  return 'normal'
}

function normalizeRouteTrend(value) {
  return asArray(value).map((item, index) => {
    if (item && typeof item === 'object') {
      return {
        label: asText(firstDefined(item.label, item.name, item.stationName), `${TEXT.stationPrefix}${index + 1}`),
        value: asNumber(firstDefined(item.value, item.score, item.count), 0)
      }
    }

    return {
      label: `${TEXT.stationPrefix}${index + 1}`,
      value: asNumber(item, 0)
    }
  })
}

function normalizeStationTrend(value) {
  return asArray(value).map((item) => {
    if (item && typeof item === 'object') {
      return asNumber(firstDefined(item.value, item.count, item.flow), 0)
    }

    return asNumber(item, 0)
  })
}

function normalizeLog(log, index) {
  return {
    time: asText(firstDefined(log?.time, log?.occurTime, log?.timestamp), `${TEXT.logRecord}${index + 1}`),
    title: asText(firstDefined(log?.title, log?.summary, log?.name), TEXT.logRecord),
    detail: asText(firstDefined(log?.detail, log?.content, log?.description), '')
  }
}

function normalizeStation(station, routeName) {
  const id = asText(firstDefined(station?.id, station?.stationId, station?.station_id), '')
  const name = asText(firstDefined(station?.name, station?.stationName, station?.station_name), id || TEXT.stationPrefix)
  const isAnomaly = Boolean(firstDefined(station?.isAnomaly, station?.anomaly, station?.hasEncodingIssue))
  const status = normalizeStatus(firstDefined(station?.status, isAnomaly ? 'error' : 'normal'))

  return {
    id,
    name,
    lng: asNumber(firstDefined(station?.lng, station?.longitude), 0),
    lat: asNumber(firstDefined(station?.lat, station?.latitude), 0),
    crowding: clamp01(
      firstDefined(station?.crowding, station?.crowdingLevel, station?.crowdingRatio, station?.occupancy),
      0
    ),
    status,
    encodingStatus: asText(
      firstDefined(station?.encodingStatus, station?.encoding_status, station?.codingStatus),
      isAnomaly ? TEXT.waitReview : TEXT.stable
    ),
    encodingRemark: asText(
      firstDefined(station?.encodingRemark, station?.encoding_remark, station?.codingRemark, station?.remark),
      ''
    ),
    area: asText(firstDefined(station?.area, station?.district, station?.region), ''),
    nearbyTags: asArray(firstDefined(station?.nearbyTags, station?.tags, station?.nearbyPOI))
      .map((item) => asText(item))
      .filter(Boolean),
    trend: normalizeStationTrend(firstDefined(station?.trend, station?.trendSeries, station?.series)),
    updates: asArray(firstDefined(station?.updates, station?.operationLogs, station?.logs))
      .map((item) => {
        if (item && typeof item === 'object') {
          return asText(firstDefined(item.title, item.summary, item.detail), '')
        }

        return asText(item)
      })
      .filter(Boolean),
    isAnomaly,
    influence: asText(firstDefined(station?.influence, station?.feature, station?.scene), ''),
    lineNames: uniqueValues(
      asArray(firstDefined(station?.lineNames, station?.routeNames, station?.routes))
        .map((item) => {
          if (item && typeof item === 'object') {
            return asText(firstDefined(item.routeName, item.name, item.label), '')
          }

          return asText(item)
        })
        .concat(routeName ? [routeName] : [])
    )
  }
}

function normalizeTask(task, routeId, stationMap) {
  const stationId = asText(firstDefined(task?.stationId, task?.station_id), '')
  const stationName = stationMap.get(stationId)?.name ?? ''

  return {
    id: asText(firstDefined(task?.id, task?.taskId), `${routeId}-${stationId || 'task'}`),
    routeId,
    stationId,
    title: asText(
      firstDefined(task?.title, task?.name),
      stationName ? `${stationName} ${TEXT.encodingReview}` : TEXT.encodingTask
    ),
    summary: asText(firstDefined(task?.summary, task?.description, task?.remark), ''),
    severity: asText(firstDefined(task?.severity, task?.level), 'medium').toLowerCase(),
    stationName
  }
}

function normalizeBounds(value) {
  if (!value || typeof value !== 'object') {
    return null
  }

  const minLng = asNumber(firstDefined(value.minLng, value.left, value.west), NaN)
  const minLat = asNumber(firstDefined(value.minLat, value.bottom, value.south), NaN)
  const maxLng = asNumber(firstDefined(value.maxLng, value.right, value.east), NaN)
  const maxLat = asNumber(firstDefined(value.maxLat, value.top, value.north), NaN)

  if (![minLng, minLat, maxLng, maxLat].every((item) => Number.isFinite(item))) {
    return null
  }

  return { minLng, minLat, maxLng, maxLat }
}

function normalizeRoute(route, index = 0) {
  const routeId = asText(firstDefined(route?.routeId, route?.id, route?.lineId, route?.line_id), `route-${index + 1}`)
  const routeName = asText(firstDefined(route?.routeName, route?.name, route?.lineName, route?.line_name), routeId)
  const directionLabel = asText(
    firstDefined(route?.directionLabel, route?.directionName, route?.direction_label, route?.direction),
    ''
  )
  const stations = asArray(firstDefined(route?.stations, route?.stationList, route?.stationVOList)).map((station) =>
    normalizeStation(station, routeName)
  )
  const path = asArray(firstDefined(route?.path, route?.polyline, route?.routePath))
    .map((point) => {
      if (!Array.isArray(point) || point.length < 2) {
        return null
      }

      const lng = asNumber(firstDefined(point[0], point?.lng, point?.longitude), NaN)
      const lat = asNumber(firstDefined(point[1], point?.lat, point?.latitude), NaN)
      if (!Number.isFinite(lng) || !Number.isFinite(lat)) {
        return null
      }

      return [lng, lat]
    })
    .filter(Boolean)
  const stationMap = new Map(stations.map((station) => [station.id, station]))
  const warnings = asArray(firstDefined(route?.warnings, route?.warningList, route?.alerts))
    .map((item) => {
      if (item && typeof item === 'object') {
        return asText(firstDefined(item.message, item.title, item.summary), '')
      }

      return asText(item)
    })
    .filter(Boolean)
  const codingIssues = asNumber(firstDefined(route?.codingIssues, route?.encodingIssues, route?.issueCount), 0)
  const status = normalizeStatus(route?.status, codingIssues, warnings.length)

  return {
    routeId,
    routeName,
    directionLabel,
    stationCount: asNumber(firstDefined(route?.stationCount, route?.station_count), stations.length),
    mileage: Number(asNumber(firstDefined(route?.mileage, route?.distanceKm, route?.lengthKm, route?.length), 0).toFixed(1)),
    status,
    statusLabel: asText(route?.statusLabel, buildStatusLabel(status)),
    statusTagType: asText(route?.statusTagType, buildStatusTagType(status)),
    pressureLabel: asText(
      firstDefined(route?.pressureLabel, route?.pressureDesc, route?.pressureDescription),
      buildPressureLabel(status)
    ),
    pressurePercent: asNumber(firstDefined(route?.pressurePercent, route?.pressureScore, route?.pressure), 0),
    codingCompleteness: asNumber(firstDefined(route?.codingCompleteness, route?.encodingCompleteness, route?.codingRate), 0),
    codingIssues,
    color: asText(firstDefined(route?.color, route?.routeColor), DEFAULT_ROUTE_COLOR),
    overview: asText(firstDefined(route?.overview, route?.description, route?.summary), TEXT.routeFallbackOverview),
    warnings,
    trend: normalizeRouteTrend(firstDefined(route?.trend, route?.trendList, route?.routeTrend)),
    encodingTasks: asArray(firstDefined(route?.encodingTasks, route?.codingTasks, route?.issues)).map((item) =>
      normalizeTask(item, routeId, stationMap)
    ),
    operationLogs: asArray(firstDefined(route?.operationLogs, route?.logs, route?.events)).map((item, logIndex) =>
      normalizeLog(item, logIndex)
    ),
    bbox: normalizeBounds(firstDefined(route?.bbox, route?.bounds, route?.extent)),
    path,
    stations
  }
}

function normalizeRouteCatalog(pageData) {
  const source = firstDefined(pageData?.routeCatalog, pageData?.routes, pageData?.items, pageData?.records)
  return asArray(source).map((item, index) => normalizeRoute(item, index))
}

function normalizeMetricLabel(key, label) {
  const normalizedKey = asText(key, '').toLowerCase()
  const normalizedLabel = asText(label, '').toLowerCase()

  if (normalizedKey.includes('route') || normalizedLabel.includes('route')) return TEXT.routeCount
  if (normalizedKey.includes('station') || normalizedLabel.includes('station')) return TEXT.stationCount
  if (normalizedKey.includes('warning') || normalizedLabel.includes('warning')) return TEXT.warningRoutes
  if (normalizedKey.includes('encoding') || normalizedLabel.includes('encoding')) return TEXT.encodingIssues
  return asText(label, TEXT.metricFallback)
}

function normalizeOverviewMetrics(pageData, routeCatalog) {
  const metricsSource = firstDefined(pageData?.overviewMetrics, pageData?.metrics)

  if (Array.isArray(metricsSource)) {
    return metricsSource.map((item, index) => ({
      key: asText(firstDefined(item?.key, item?.code), `metric-${index + 1}`),
      label: normalizeMetricLabel(item?.key, firstDefined(item?.label, item?.name)),
      value: firstDefined(item?.value, item?.count, 0)
    }))
  }

  if (metricsSource && typeof metricsSource === 'object') {
    return [
      { key: 'routes', label: TEXT.routeCount, value: firstDefined(metricsSource.routes, metricsSource.routeCount, routeCatalog.length) },
      { key: 'stations', label: TEXT.stationCount, value: firstDefined(metricsSource.stations, metricsSource.stationCount, 0) },
      { key: 'warnings', label: TEXT.warningRoutes, value: firstDefined(metricsSource.warnings, metricsSource.warningCount, 0) },
      {
        key: 'encoding',
        label: TEXT.encodingIssues,
        value: firstDefined(metricsSource.encoding, metricsSource.encodingIssueCount, metricsSource.codingIssues, 0)
      }
    ]
  }

  const stationIds = new Set()
  let warningCount = 0
  let encodingCount = 0

  routeCatalog.forEach((route) => {
    if (route.status !== 'normal') {
      warningCount += 1
    }

    encodingCount += route.codingIssues
    route.stations.forEach((station) => stationIds.add(station.id))
  })

  return [
    { key: 'routes', label: TEXT.routeCount, value: routeCatalog.length },
    { key: 'stations', label: TEXT.stationCount, value: stationIds.size },
    { key: 'warnings', label: TEXT.warningRoutes, value: warningCount },
    { key: 'encoding', label: TEXT.encodingIssues, value: encodingCount }
  ]
}

function preferredRouteId(pageData, routes) {
  const routeId = asText(firstDefined(pageData?.activeRouteId, pageData?.currentRouteId, pageData?.defaultRouteId), '')
  if (routeId && routes.some((route) => route.routeId === routeId)) {
    return routeId
  }

  return routes[0]?.routeId ?? EMPTY_ROUTE_ID
}

function detailCacheKey(date, ...parts) {
  return [date || 'latest', ...parts].join('|')
}

function remember(cache, key, value, limit) {
  if (cache.has(key)) {
    cache.delete(key)
  }

  cache.set(key, value)
  if (cache.size > limit) {
    cache.delete(cache.keys().next().value)
  }
}

function fetchRouteDetail(date, routeId) {
  const cacheKey = detailCacheKey(date, routeId)
  if (routeDetailCache.has(cacheKey)) {
    return Promise.resolve(routeDetailCache.get(cacheKey))
  }

  if (routeDetailRequests.has(cacheKey)) {
    return routeDetailRequests.get(cacheKey)
  }

  const search = new URLSearchParams({ routeId })
  if (date) {
    search.set('date', date)
  }

  const request = fetchJson(`/api/system/gis/route?${search.toString()}`)
    .then((payload) => {
      const detail = normalizeRoute(payload || {})
      remember(routeDetailCache, cacheKey, detail, ROUTE_DETAIL_CACHE_LIMIT)
      return detail
    })
    .finally(() => {
      if (routeDetailRequests.get(cacheKey) === request) {
        routeDetailRequests.delete(cacheKey)
      }
    })

  routeDetailRequests.set(cacheKey, request)
  return request
}

function fetchStationDetail(date, routeId, stationId, routeName) {
  const cacheKey = detailCacheKey(date, routeId, stationId)
  if (stationDetailCache.has(cacheKey)) {
    return Promise.resolve(stationDetailCache.get(cacheKey))
  }

  if (stationDetailRequests.has(cacheKey)) {
    return stationDetailRequests.get(cacheKey)
  }

  const search = new URLSearchParams({ routeId, stationId })
  if (date) {
    search.set('date', date)
  }

  const request = fetchJson(`/api/system/gis/station?${search.toString()}`)
    .then((payload) => {
      const detail = normalizeStation(payload || {}, routeName)
      remember(stationDetailCache, cacheKey, detail, STATION_DETAIL_CACHE_LIMIT)
      return detail
    })
    .finally(() => {
      if (stationDetailRequests.get(cacheKey) === request) {
        stationDetailRequests.delete(cacheKey)
      }
    })

  stationDetailRequests.set(cacheKey, request)
  return request
}

export function useSystemGisPage(props = {}) {
  const statusFilter = ref('all')
  const lengthFilter = ref('all')
  const activeRouteId = ref(EMPTY_ROUTE_ID)
  const selectedStationId = ref('')
  const detailMode = ref('route')
  const activeDetailTab = ref('trend')
  const activeLayer = ref(LAYERS[0])
  const showDirection = ref(false)
  const showStationNames = ref(true)
  const showHeatLayer = ref(false)
  const showEncodingLayer = ref(true)
  const routeDetail = ref(null)
  const routeDetailLoading = ref(false)
  const routeDetailRequestToken = ref(0)
  const selectedStationDetail = ref(null)
  const stationDetailLoading = ref(false)
  const stationDetailRequestToken = ref(0)

  const routeCatalog = computed(() => normalizeRouteCatalog(props.pageData || {}))

  watch(
    routeCatalog,
    (routes) => {
      if (!routes.length) {
        activeRouteId.value = EMPTY_ROUTE_ID
        selectedStationId.value = ''
        routeDetail.value = null
        selectedStationDetail.value = null
        detailMode.value = 'route'
        return
      }

      if (!routes.some((route) => route.routeId === activeRouteId.value)) {
        activeRouteId.value = preferredRouteId(props.pageData || {}, routes)
      }

      const activeRouteSummary = routes.find((route) => route.routeId === activeRouteId.value)
      if (routeDetail.value && routeDetail.value.routeId !== activeRouteId.value) {
        routeDetail.value = null
      }

      if (!activeRouteSummary?.stations.some((station) => station.id === selectedStationId.value)) {
        selectedStationId.value = ''
        selectedStationDetail.value = null
        if (detailMode.value === 'station') {
          detailMode.value = 'route'
        }
      }
    },
    { immediate: true }
  )

  const activeRouteSummary = computed(() => {
    return routeCatalog.value.find((route) => route.routeId === activeRouteId.value) ?? EMPTY_ROUTE
  })

  const activeRoute = computed(() => {
    if (!routeDetail.value || routeDetail.value.routeId !== activeRouteSummary.value.routeId) {
      return activeRouteSummary.value
    }

    return {
      ...activeRouteSummary.value,
      ...routeDetail.value,
      warnings: routeDetail.value.warnings?.length ? routeDetail.value.warnings : activeRouteSummary.value.warnings,
      trend: routeDetail.value.trend?.length ? routeDetail.value.trend : activeRouteSummary.value.trend,
      encodingTasks: routeDetail.value.encodingTasks?.length
        ? routeDetail.value.encodingTasks
        : activeRouteSummary.value.encodingTasks,
      operationLogs: routeDetail.value.operationLogs?.length
        ? routeDetail.value.operationLogs
        : activeRouteSummary.value.operationLogs,
      stations: routeDetail.value.stations || []
    }
  })

  const selectedStationBase = computed(() => {
    if (!selectedStationId.value) {
      return null
    }

    return activeRoute.value.stations.find((station) => station.id === selectedStationId.value) ?? null
  })

  const selectedStation = computed(() => {
    if (!selectedStationBase.value) {
      return null
    }

    if (!selectedStationDetail.value || selectedStationDetail.value.id !== selectedStationBase.value.id) {
      return selectedStationBase.value
    }

    return {
      ...selectedStationBase.value,
      ...selectedStationDetail.value,
      lineNames: uniqueValues([...(selectedStationBase.value.lineNames || []), ...(selectedStationDetail.value.lineNames || [])]),
      nearbyTags: selectedStationDetail.value.nearbyTags || selectedStationBase.value.nearbyTags || [],
      trend: selectedStationDetail.value.trend || selectedStationBase.value.trend || [],
      updates: selectedStationDetail.value.updates || selectedStationBase.value.updates || []
    }
  })

  const filteredRoutes = computed(() => {
    return routeCatalog.value.filter((route) => {
      const statusMatch =
        statusFilter.value === 'all' ||
        (statusFilter.value === 'normal' && route.status === 'normal') ||
        (statusFilter.value === 'warning' && route.status === 'warning') ||
        (statusFilter.value === 'error' && route.status === 'error')

      const lengthMatch =
        lengthFilter.value === 'all' ||
        (lengthFilter.value === 'short' && route.mileage < 14) ||
        (lengthFilter.value === 'medium' && route.mileage >= 14 && route.mileage < 17) ||
        (lengthFilter.value === 'long' && route.mileage >= 17)

      return statusMatch && lengthMatch
    })
  })

  const routeOptions = computed(() =>
    routeCatalog.value.map((route) => ({
      value: route.routeId,
      label: `${route.routeName} ${route.directionLabel}`.trim()
    }))
  )

  const overviewMetrics = computed(() => normalizeOverviewMetrics(props.pageData || {}, routeCatalog.value))

  const currentDetailTitle = computed(() => {
    if (detailMode.value === 'station' && selectedStation.value) {
      return selectedStation.value.name
    }

    if (activeRoute.value.routeId) {
      return `${activeRoute.value.routeName} ${activeRoute.value.directionLabel || TEXT.direction}`.trim()
    }

    return TEXT.routeDetail
  })

  function selectRoute(routeId) {
    if (!routeId) return

    activeRouteId.value = routeId
    selectedStationId.value = ''
    if (routeDetail.value?.routeId !== routeId) {
      routeDetail.value = null
    }
    selectedStationDetail.value = null
    detailMode.value = 'route'
    activeDetailTab.value = 'trend'
  }

  function locateRoute(routeId) {
    selectRoute(routeId)
  }

  function selectStation(routeId, stationId) {
    if (routeId) {
      activeRouteId.value = routeId
    }

    if (!stationId) {
      selectedStationId.value = ''
      selectedStationDetail.value = null
      detailMode.value = 'route'
      return
    }

    selectedStationId.value = stationId
    selectedStationDetail.value = null
    detailMode.value = 'station'
    activeDetailTab.value = 'trend'
  }

  function setDetailTab(tab) {
    activeDetailTab.value = tab
  }

  function focusEncodingIssue(issue) {
    if (!issue) return

    showEncodingLayer.value = true
    showHeatLayer.value = true
    selectStation(issue.routeId ?? activeRoute.value.routeId, issue.stationId)
  }

  async function loadActiveRouteDetail() {
    if (!activeRouteId.value) {
      routeDetail.value = null
      return
    }

    const routeId = activeRouteId.value
    const cacheKey = detailCacheKey(props.selectedDate, routeId)
    const requestToken = routeDetailRequestToken.value + 1
    routeDetailRequestToken.value = requestToken

    if (routeDetailCache.has(cacheKey)) {
      routeDetail.value = routeDetailCache.get(cacheKey)
      routeDetailLoading.value = false
      return
    }

    routeDetailLoading.value = true

    try {
      const detail = await fetchRouteDetail(props.selectedDate, routeId)
      if (routeDetailRequestToken.value !== requestToken) {
        return
      }

      routeDetail.value = detail
    } catch {
      if (routeDetailRequestToken.value === requestToken) {
        routeDetail.value = null
      }
    } finally {
      if (routeDetailRequestToken.value === requestToken) {
        routeDetailLoading.value = false
      }
    }
  }

  async function loadSelectedStationDetail() {
    if (!selectedStationId.value || !activeRoute.value.routeId || !selectedStationBase.value) {
      selectedStationDetail.value = null
      return
    }

    const routeId = activeRoute.value.routeId
    const stationId = selectedStationId.value
    const cacheKey = detailCacheKey(props.selectedDate, routeId, stationId)
    const requestToken = stationDetailRequestToken.value + 1
    stationDetailRequestToken.value = requestToken

    if (stationDetailCache.has(cacheKey)) {
      selectedStationDetail.value = stationDetailCache.get(cacheKey)
      stationDetailLoading.value = false
      return
    }

    stationDetailLoading.value = true

    try {
      const detail = await fetchStationDetail(props.selectedDate, routeId, stationId, activeRoute.value.routeName)
      if (stationDetailRequestToken.value !== requestToken) {
        return
      }

      selectedStationDetail.value = detail
    } catch {
      if (stationDetailRequestToken.value === requestToken) {
        selectedStationDetail.value = null
      }
    } finally {
      if (stationDetailRequestToken.value === requestToken) {
        stationDetailLoading.value = false
      }
    }
  }

  watch(
    () => [activeRouteId.value, props.selectedDate],
    () => {
      if (!activeRouteId.value) {
        routeDetail.value = null
        routeDetailLoading.value = false
        return
      }
      loadActiveRouteDetail()
    },
    { immediate: true }
  )

  watch(
    () => [activeRouteId.value, selectedStationId.value, props.selectedDate, activeRoute.value.stations.length],
    () => {
      if (!selectedStationId.value) {
        selectedStationDetail.value = null
        stationDetailLoading.value = false
        return
      }
      loadSelectedStationDetail()
    },
    { immediate: true }
  )

  return {
    activeDetailTab,
    activeLayer,
    activeRoute,
    activeRouteId,
    currentDetailTitle,
    detailMode,
    filteredRoutes,
    focusEncodingIssue,
    layers: LAYERS,
    lengthFilter,
    locateRoute,
    overviewMetrics,
    routeCatalog,
    routeDetailLoading,
    routeOptions,
    selectedStation,
    selectedStationId,
    selectRoute,
    selectStation,
    stationDetailLoading,
    setDetailTab,
    showDirection,
    showEncodingLayer,
    showHeatLayer,
    showStationNames,
    statusFilter
  }
}
