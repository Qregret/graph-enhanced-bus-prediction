import { computed, markRaw, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import * as echarts from 'echarts'
import AMapLoader from '@amap/amap-jsapi-loader'

import {
  buildHourlyOption,
  buildMirrorOption,
  buildSankeyOption,
  buildTopOption,
  buildTrendOption,
  createEmptyDashboard,
  normalizeDashboard
} from '../utils/dashboardOptions'
import { BUS_MARKER_ICON } from '../utils/dashboardMapAssets'
import { fetchDashboardData, fetchDashboardLines } from '../services/dashboardApi'

const AMAP_KEY = 'YOUR_AMAP_KEY'
const AMAP_SECURITY_JS_CODE = 'YOUR_AMAP_SECURITY_CODE'
const BUS_MARKER_HEADING_OFFSET = 0
const BUS_MARKER_SIZE = { width: 56, height: 36 }

function toLngLatPair(point) {
  if (Array.isArray(point)) {
    return [Number(point[0]), Number(point[1])]
  }

  const lng = typeof point?.getLng === 'function' ? point.getLng() : point?.lng
  const lat = typeof point?.getLat === 'function' ? point.getLat() : point?.lat
  return [Number(lng), Number(lat)]
}

function getBusMarkerAngle(from, to) {
  const [fromLng, fromLat] = toLngLatPair(from)
  const [toLng, toLat] = toLngLatPair(to)
  if (![fromLng, fromLat, toLng, toLat].every(Number.isFinite)) {
    return null
  }

  const averageLat = ((fromLat + toLat) / 2) * Math.PI / 180
  const deltaLng = (toLng - fromLng) * Math.cos(averageLat)
  const deltaLat = toLat - fromLat
  if (Math.abs(deltaLng) < 1e-8 && Math.abs(deltaLat) < 1e-8) {
    return null
  }

  return (Math.atan2(-deltaLat, deltaLng) * 180 / Math.PI) + BUS_MARKER_HEADING_OFFSET
}

function normalizeBusMarkerPose(angle) {
  let displayAngle = ((angle + 180) % 360 + 360) % 360 - 180
  let scaleX = 1

  if (displayAngle > 90) {
    displayAngle -= 180
    scaleX = -1
  } else if (displayAngle < -90) {
    displayAngle += 180
    scaleX = -1
  }

  return { displayAngle, scaleX }
}

function getClosestPathAngle(path, position) {
  if (!position || !Array.isArray(path) || path.length < 2) {
    return null
  }

  const [positionLng, positionLat] = toLngLatPair(position)
  if (![positionLng, positionLat].every(Number.isFinite)) {
    return null
  }

  let closestAngle = null
  let closestDistance = Number.POSITIVE_INFINITY

  for (let index = 0; index < path.length - 1; index += 1) {
    const [fromLng, fromLat] = toLngLatPair(path[index])
    const [toLng, toLat] = toLngLatPair(path[index + 1])
    if (![fromLng, fromLat, toLng, toLat].every(Number.isFinite)) {
      continue
    }

    const averageLat = ((fromLat + toLat) / 2) * Math.PI / 180
    const segmentX = (toLng - fromLng) * Math.cos(averageLat)
    const segmentY = toLat - fromLat
    const pointX = (positionLng - fromLng) * Math.cos(averageLat)
    const pointY = positionLat - fromLat
    const segmentLengthSquared = segmentX * segmentX + segmentY * segmentY
    if (segmentLengthSquared < 1e-12) {
      continue
    }

    const ratio = Math.max(0, Math.min(1, (pointX * segmentX + pointY * segmentY) / segmentLengthSquared))
    const projectedX = segmentX * ratio
    const projectedY = segmentY * ratio
    const distance = (pointX - projectedX) ** 2 + (pointY - projectedY) ** 2

    if (distance < closestDistance) {
      closestDistance = distance
      closestAngle = getBusMarkerAngle(path[index], path[index + 1])
    }
  }

  return closestAngle
}

function applyBusMarkerPose(marker, angle) {
  const content = marker?.getContent?.()
  const markerElement = typeof HTMLElement !== 'undefined' && content instanceof HTMLElement ? content : null
  if (!markerElement) {
    marker?.setAngle?.(angle)
    return
  }

  const { displayAngle, scaleX } = normalizeBusMarkerPose(angle)
  markerElement.style.setProperty('--bus-marker-angle', `${displayAngle}deg`)
  markerElement.style.setProperty('--bus-marker-scale-x', String(scaleX))
}

function createBusMarkerContent() {
  const wrapper = document.createElement('div')
  wrapper.className = 'dashboard-bus-marker'
  wrapper.style.setProperty('--bus-marker-angle', '0deg')
  wrapper.style.setProperty('--bus-marker-scale-x', '1')

  const image = document.createElement('img')
  image.src = BUS_MARKER_ICON
  image.alt = ''
  image.draggable = false
  wrapper.appendChild(image)

  return wrapper
}

function syncBusMarkerAngle(marker, path, position = null) {
  if (!marker || !Array.isArray(path) || path.length < 2) {
    return
  }

  const angle = getClosestPathAngle(path, position) ?? getBusMarkerAngle(path[path.length - 2], path[path.length - 1])
  if (angle != null) {
    applyBusMarkerPose(marker, angle)
  }
}
const directionOptions = ['上行', '下行']

function getStationLabelStyle(isSelected = false) {
  return {
    padding: isSelected ? '4px 10px' : '3px 9px',
    borderRadius: '10px',
    border: isSelected ? '1px solid rgba(96, 165, 250, 0.5)' : '1px solid rgba(15, 23, 42, 0.08)',
    background: isSelected
      ? 'linear-gradient(135deg, rgba(37,99,235,0.96) 0%, rgba(14,165,233,0.92) 100%)'
      : 'rgba(255, 255, 255, 0.82)',
    backdropFilter: 'blur(10px)',
    color: isSelected ? '#eff6ff' : '#0f172a',
    fontSize: isSelected ? '12px' : '11px',
    fontWeight: isSelected ? '700' : '600',
    lineHeight: '16px',
    letterSpacing: '0.01em',
    boxShadow: isSelected
      ? '0 10px 22px rgba(37, 99, 235, 0.22)'
      : '0 6px 16px rgba(15, 23, 42, 0.08)'
  }
}

export function useDashboardScreen() {
  const mapContainerRef = ref(null)
  const trendChartRef = ref(null)
  const hourlyChartRef = ref(null)
  const sankeyChartRef = ref(null)
  const topChartRef = ref(null)
  const mirrorChartRef = ref(null)

  const trendChart = shallowRef(null)
  const hourlyChart = shallowRef(null)
  const sankeyChart = shallowRef(null)
  const topChart = shallowRef(null)
  const mirrorChart = shallowRef(null)

  const mapInstance = shallowRef(null)
  const routeGlowLine = shallowRef(null)
  const routeMainLine = shallowRef(null)
  const routePassedLine = shallowRef(null)
  const routePlaybackMarker = shallowRef(null)
  const stationMarkers = shallowRef([])
  const stationLabels = shallowRef([])
  const selectedMarker = shallowRef(null)
  const amapNamespace = shallowRef(null)

  const lineOptions = shallowRef([])
  const dashboardData = shallowRef(createEmptyDashboard())
  const dashboardAbortController = shallowRef(null)

  const currentLine = ref('')
  const currentDirection = ref('上行')
  const selectedDashboardDate = ref('')
  const selectedStationName = ref('')
  const currentDate = ref('')
  const currentTime = ref('')
  const loading = ref(false)
  const errorMessage = ref('')
  const isMapFocusMode = ref(false)

  let clockTimer = null
  let focusLeaveTimer = null
  let longPressTimer = null
  let longPressArmed = false
  let isPointerDown = false
  let mapEventCleanup = null
  let playbackVersion = 0

  const currentStations = computed(() => dashboardData.value.stations || [])
  const stationCount = computed(() => dashboardData.value.summary?.stationCount || currentStations.value.length)
  const currentScenarioDate = computed(() => dashboardData.value.date || '--')
  const currentScenarioState = computed(() => dashboardData.value.state || '等待接口数据')
  const currentLineMeta = computed(() => lineOptions.value.find((item) => (item.lineId || item.line) === currentLine.value) || null)
  const availableDates = computed(() => {
    const datesFromDashboard = Array.isArray(dashboardData.value.availableDates) ? dashboardData.value.availableDates : []
    return datesFromDashboard.length ? datesFromDashboard : Array.isArray(currentLineMeta.value?.availableDates) ? currentLineMeta.value.availableDates : []
  })
  const currentLineLabel = computed(() => {
    const match = currentLineMeta.value
    return dashboardData.value.lineName || match?.lineName || match?.line || currentLine.value
  })
  const selectedStation = computed(
    () =>
      currentStations.value.find((station) => station.name === selectedStationName.value) ||
      currentStations.value[0] || {
        name: '',
        predictedBoardings: 0,
        predictedAlightings: 0,
        lng: 0,
        lat: 0
      }
  )

  function formatClock() {
    const now = new Date()
    const month = String(now.getMonth() + 1).padStart(2, '0')
    const day = String(now.getDate()).padStart(2, '0')
    currentDate.value = `${now.getFullYear()}-${month}-${day}`
    currentTime.value = now.toLocaleTimeString('zh-CN', { hour12: false })
  }

  function ensureSelectedStation() {
    if (!currentStations.value.length) {
      selectedStationName.value = ''
      return
    }
    const exists = currentStations.value.some((station) => station.name === selectedStationName.value)
    if (!exists) {
      selectedStationName.value = dashboardData.value.selectedStation || currentStations.value[0].name
    }
  }

  function ensureSelectedDate() {
    if (!availableDates.value.length) {
      selectedDashboardDate.value = ''
      return
    }
    if (!availableDates.value.includes(selectedDashboardDate.value)) {
      selectedDashboardDate.value = availableDates.value[0]
    }
  }

  async function loadLines() {
    const lines = await fetchDashboardLines()
    lineOptions.value = Array.isArray(lines) ? lines : []
    if (!lineOptions.value.length) {
      throw new Error('后端未返回任何线路，请检查 Redis 数据或 Java 聚合逻辑。')
    }
    if (!currentLine.value) {
      const [firstLine] = lineOptions.value
      currentLine.value = firstLine.lineId || firstLine.line
      currentDirection.value = firstLine.directions?.[0] || '上行'
      selectedDashboardDate.value = firstLine.availableDates?.[0] || firstLine.latestDate || ''
    }
  }

  async function loadDashboard() {
    if (!currentLine.value) {
      return
    }

    dashboardAbortController.value?.abort()
    const controller = new AbortController()
    dashboardAbortController.value = controller
    loading.value = true
    errorMessage.value = ''

    try {
      const payload = await fetchDashboardData({
        line: currentLine.value,
        direction: currentDirection.value,
        date: selectedDashboardDate.value,
        signal: controller.signal
      })
      dashboardData.value = normalizeDashboard(payload)
      if (payload?.date) {
        selectedDashboardDate.value = payload.date
      }
      ensureSelectedStation()
    } catch (error) {
      if (error.name !== 'AbortError') {
        errorMessage.value = `数据接口加载失败：${error?.message || '请检查 Java 后端、MySQL、Redis 或接口代理。'}`
      }
    } finally {
      if (dashboardAbortController.value === controller) {
        dashboardAbortController.value = null
        loading.value = false
      }
    }
  }

  function initChart(domRef, targetRef) {
    if (domRef.value) {
      targetRef.value = markRaw(echarts.init(domRef.value))
    }
  }

  function updateCharts() {
    trendChart.value?.setOption(buildTrendOption(dashboardData.value), true)
    hourlyChart.value?.setOption(buildHourlyOption(dashboardData.value), true)
    try {
      sankeyChart.value?.setOption(buildSankeyOption(dashboardData.value), true)
    } catch {
      sankeyChart.value?.clear()
    }
    topChart.value?.setOption(buildTopOption(dashboardData.value), true)
    mirrorChart.value?.setOption(buildMirrorOption(dashboardData.value), true)
  }

  function clearFocusLeaveTimer() {
    if (focusLeaveTimer) {
      window.clearTimeout(focusLeaveTimer)
      focusLeaveTimer = null
    }
  }

  function scheduleFocusRestore(delay = 1000) {
    clearFocusLeaveTimer()
    focusLeaveTimer = window.setTimeout(() => {
      if (!isPointerDown) {
        isMapFocusMode.value = false
      }
    }, delay)
  }

  function activateMapFocusMode() {
    clearFocusLeaveTimer()
    isMapFocusMode.value = true
  }

  function clearLongPressTimer() {
    if (longPressTimer) {
      window.clearTimeout(longPressTimer)
      longPressTimer = null
    }
  }

  function handlePointerDown(event) {
    if (event.button !== 0) {
      return
    }
    isPointerDown = true
    longPressArmed = true
    clearLongPressTimer()
    longPressTimer = window.setTimeout(() => {
      if (isPointerDown && longPressArmed) {
        activateMapFocusMode()
      }
    }, 180)
  }

  function handlePointerRelease() {
    isPointerDown = false
    longPressArmed = false
    clearLongPressTimer()
    scheduleFocusRestore()
  }

  function bindMapFocusInteractions() {
    const container = mapContainerRef.value
    const map = mapInstance.value
    if (!container || !map) {
      return
    }

    const onMouseDown = (event) => handlePointerDown(event)
    const onMouseUp = () => handlePointerRelease()
    const onMouseLeave = () => {
      if (!isPointerDown) {
        scheduleFocusRestore(1000)
      }
    }
    const onMoveStart = () => activateMapFocusMode()
    const onMoveEnd = () => scheduleFocusRestore()

    container.addEventListener('mousedown', onMouseDown)
    window.addEventListener('mouseup', onMouseUp)
    container.addEventListener('mouseleave', onMouseLeave)
    map.on('movestart', onMoveStart)
    map.on('moveend', onMoveEnd)

    mapEventCleanup = () => {
      container.removeEventListener('mousedown', onMouseDown)
      window.removeEventListener('mouseup', onMouseUp)
      container.removeEventListener('mouseleave', onMouseLeave)
      map.off('movestart', onMoveStart)
      map.off('moveend', onMoveEnd)
    }
  }

  function buildPlaybackPath() {
    const stationPath = currentStations.value
      .filter((station) => Number.isFinite(Number(station.lng)) && Number.isFinite(Number(station.lat)))
      .map((station) => [Number(station.lng), Number(station.lat)])
      .filter((point, index, source) => index === 0 || source[index - 1][0] !== point[0] || source[index - 1][1] !== point[1])

    if (stationPath.length >= 2) {
      return stationPath
    }

    return (dashboardData.value.path || []).filter(
      (point, index, source) =>
        Array.isArray(point) &&
        point.length >= 2 &&
        (index === 0 || source[index - 1]?.[0] !== point[0] || source[index - 1]?.[1] !== point[1])
    )
  }

  function startRoutePlayback(path) {
    const marker = routePlaybackMarker.value
    const passedLine = routePassedLine.value
    if (!marker || !passedLine || !Array.isArray(path) || path.length < 2) {
      return
    }

    playbackVersion += 1
    const currentVersion = playbackVersion

    marker.stopMove?.()
    marker.setPosition(path[0])
    syncBusMarkerAngle(marker, path.slice(0, 2), path[0])
    passedLine.setPath([path[0]])
    marker.off?.('moving')
    marker.off?.('moveend')

    marker.on('moving', (event) => {
      if (currentVersion === playbackVersion) {
        const passedPath = event.passedPath || []
        passedLine.setPath(passedPath)
        syncBusMarkerAngle(marker, path, marker.getPosition?.())
      }
    })

    marker.on('moveend', () => {
      if (currentVersion === playbackVersion) {
        passedLine.setPath(path)
        marker.setPosition(path[path.length - 1])
        syncBusMarkerAngle(marker, path, path[path.length - 1])
      }
    })

    marker.moveAlong(path, {
      duration: Math.max(path.length * 220, 5000),
      autoRotation: false
    })
  }

  function updateSelectedStationOverlay() {
    const map = mapInstance.value
    const AMap = amapNamespace.value
    if (!map || !AMap) {
      return
    }

    const current = selectedStation.value

    stationMarkers.value.forEach((marker) => {
      const station = marker.getExtData?.() || {}
      const isSelected = station.name === current?.name
      marker.setOptions?.({
        radius: isSelected ? 8 : 5,
        strokeColor: isSelected ? '#DBEAFE' : '#0F172A',
        strokeWeight: isSelected ? 3 : 2,
        fillColor: isSelected ? '#2563EB' : '#38BDF8'
      })
    })

    stationLabels.value.forEach((label) => {
      const station = label.getExtData?.() || {}
      const isSelected = station.name === current?.name
      label.setStyle?.(getStationLabelStyle(isSelected))
      label.setzIndex?.(isSelected ? 123 : 118)
    })

    if (selectedMarker.value) {
      map.remove(selectedMarker.value)
      selectedMarker.value = null
    }

    if (current?.name) {
      selectedMarker.value = markRaw(
        new AMap.Marker({
          position: [current.lng, current.lat],
          anchor: 'center',
          content:
            '<div class="dashboard-station-focus-marker"><span class="dashboard-station-focus-marker__core"></span></div>',
          zIndex: 140
        })
      )
      map.add(selectedMarker.value)
    }
  }

  function clearMapOverlays() {
    const map = mapInstance.value
    if (!map) {
      return
    }
    playbackVersion += 1
    routePlaybackMarker.value?.stopMove?.()
    const overlays = [
      routeGlowLine.value,
      routeMainLine.value,
      routePassedLine.value,
      routePlaybackMarker.value,
      selectedMarker.value,
      ...stationLabels.value,
      ...stationMarkers.value
    ].filter(Boolean)
    if (overlays.length) {
      map.remove(overlays)
    }
    routeGlowLine.value = null
    routeMainLine.value = null
    routePassedLine.value = null
    routePlaybackMarker.value = null
    selectedMarker.value = null
    stationMarkers.value = []
    stationLabels.value = []
  }

  function renderMap(fitView = true) {
    const map = mapInstance.value
    const AMap = amapNamespace.value
    if (!map || !AMap) {
      return
    }

    clearMapOverlays()

    const path = dashboardData.value.path || []
    const playbackPath = buildPlaybackPath()
    const stations = currentStations.value
    if (!path.length || !stations.length) {
      return
    }

    routeGlowLine.value = markRaw(
      new AMap.Polyline({
        path,
        strokeColor: '#0F172A',
        strokeWeight: 12,
        strokeOpacity: 0.26,
        lineJoin: 'round',
        lineCap: 'round'
      })
    )
    routeMainLine.value = markRaw(
      new AMap.Polyline({
        path,
        strokeColor: '#2563EB',
        strokeWeight: 6,
        strokeOpacity: 0.98,
        lineJoin: 'round',
        lineCap: 'round',
        showDir: true
      })
    )
    routePassedLine.value = markRaw(
      new AMap.Polyline({
        path: [],
        strokeColor: '#93C5FD',
        strokeWeight: 7,
        strokeOpacity: 0.96,
        lineJoin: 'round',
        lineCap: 'round',
        zIndex: 128
      })
    )
    routePlaybackMarker.value = markRaw(
      new AMap.Marker({
        position: playbackPath[0] || path[0],
        offset: new AMap.Pixel(-BUS_MARKER_SIZE.width / 2, -BUS_MARKER_SIZE.height / 2),
        content: createBusMarkerContent(),
        zIndex: 140
      })
    )

    stationMarkers.value = stations.map((station) => {
      const isSelected = station.name === selectedStation.value.name
      const marker = new AMap.CircleMarker({
        center: [station.lng, station.lat],
        radius: isSelected ? 7 : 5,
        strokeColor: isSelected ? '#DBEAFE' : '#0F172A',
        strokeWeight: isSelected ? 3 : 2,
        strokeOpacity: 1,
        fillColor: isSelected ? '#2563EB' : '#38BDF8',
        fillOpacity: 0.95,
        bubble: true,
        cursor: 'pointer'
      })
      marker.on('click', () => {
        clearLongPressTimer()
        clearFocusLeaveTimer()
        isMapFocusMode.value = false
        selectedStationName.value = station.name
      })
      marker.setExtData?.(station)
      return markRaw(marker)
    })

    stationLabels.value = stations.map((station) => {
      const isSelected = station.name === selectedStation.value.name
      const label = new AMap.Text({
        text: station.name,
        position: [station.lng, station.lat],
        anchor: 'bottom-center',
        offset: new AMap.Pixel(0, -14),
        style: getStationLabelStyle(isSelected),
        zIndex: isSelected ? 123 : 118
      })
      label.on('click', () => {
        clearLongPressTimer()
        clearFocusLeaveTimer()
        isMapFocusMode.value = false
        selectedStationName.value = station.name
      })
      label.setExtData?.(station)
      return markRaw(label)
    })

    updateSelectedStationOverlay()

    const overlays = [
      routeGlowLine.value,
      routeMainLine.value,
      routePassedLine.value,
      routePlaybackMarker.value,
      selectedMarker.value,
      ...stationLabels.value,
      ...stationMarkers.value
    ].filter(Boolean)
    map.add(overlays)
    if (fitView) {
      map.setFitView(overlays, false, [80, 80, 80, 80], 14)
    }
    startRoutePlayback(playbackPath.length >= 2 ? playbackPath : path)
  }

  async function initMap() {
    if (!mapContainerRef.value) {
      return
    }

    if (window._AMapSecurityConfig == null) {
      window._AMapSecurityConfig = { securityJsCode: AMAP_SECURITY_JS_CODE }
    }

    const AMap = await AMapLoader.load({
      key: AMAP_KEY,
      version: '2.0',
      plugins: ['AMap.Scale', 'AMap.ToolBar', 'AMap.MoveAnimation']
    })

    amapNamespace.value = markRaw(AMap)
    mapInstance.value = markRaw(
      new AMap.Map(mapContainerRef.value, {
        zoom: 12,
        center: [107.3948, 29.7031],
        viewMode: '2D',
        mapStyle: 'amap://styles/normal',
        resizeEnable: true,
        dragEnable: true,
        zoomEnable: true,
        doubleClickZoom: true,
        scrollWheel: true,
        jogEnable: false
      })
    )

    mapInstance.value.addControl(markRaw(new AMap.Scale()))
    bindMapFocusInteractions()
  }

  function handleResize() {
    trendChart.value?.resize()
    hourlyChart.value?.resize()
    sankeyChart.value?.resize()
    topChart.value?.resize()
    mirrorChart.value?.resize()
    mapInstance.value?.resize?.()
  }

  watch([currentLine, currentDirection], () => {
    const before = selectedDashboardDate.value
    ensureSelectedDate()
    if (before === selectedDashboardDate.value || !selectedDashboardDate.value) {
      loadDashboard()
    }
  })

  watch(
    () => availableDates.value,
    () => {
      ensureSelectedDate()
    },
    { immediate: true }
  )

  watch(selectedDashboardDate, () => {
    if (currentLine.value && selectedDashboardDate.value) {
      loadDashboard()
    }
  })

  watch(
    () => dashboardData.value,
    () => {
      ensureSelectedDate()
      ensureSelectedStation()
      updateCharts()
      renderMap()
    },
    { flush: 'post' }
  )

  watch(selectedStationName, () => {
    updateSelectedStationOverlay()
  })

  onMounted(async () => {
    formatClock()
    clockTimer = window.setInterval(formatClock, 1000)
    initChart(trendChartRef, trendChart)
    initChart(hourlyChartRef, hourlyChart)
    initChart(sankeyChartRef, sankeyChart)
    initChart(topChartRef, topChart)
    initChart(mirrorChartRef, mirrorChart)
    window.addEventListener('resize', handleResize)

    try {
      await initMap()
    } catch (error) {
      errorMessage.value = `地图初始化失败：${error?.message || '请检查高德 Key、安全密钥或网络环境。'}`
      return
    }

    try {
      await loadLines()
    } catch (error) {
      errorMessage.value = `数据接口加载失败：${error?.message || '请检查 Java 后端、Redis 或接口代理。'}`
    }
  })

  onBeforeUnmount(() => {
    if (clockTimer) {
      window.clearInterval(clockTimer)
    }
    clearLongPressTimer()
    clearFocusLeaveTimer()
    mapEventCleanup?.()
    dashboardAbortController.value?.abort()
    window.removeEventListener('resize', handleResize)
    clearMapOverlays()
    trendChart.value?.dispose()
    hourlyChart.value?.dispose()
    sankeyChart.value?.dispose()
    topChart.value?.dispose()
    mirrorChart.value?.dispose()
    mapInstance.value?.destroy?.()
  })

  return {
    availableDates,
    currentDate,
    currentDirection,
    currentLine,
    currentLineLabel,
    currentScenarioDate,
    currentScenarioState,
    currentStations,
    currentTime,
    dashboardData,
    directionOptions,
    errorMessage,
    hourlyChartRef,
    isMapFocusMode,
    lineOptions,
    loading,
    mapContainerRef,
    mirrorChartRef,
    sankeyChartRef,
    selectedDashboardDate,
    selectedStation,
    selectedStationName,
    stationCount,
    topChartRef,
    trendChartRef
  }
}
