<template>
  <section class="gis-map-stage">
    <div class="gis-map-toolbar">
      <button type="button" @click="fitActiveRoute">{{ TEXT.locateCurrentRoute }}</button>
      <button type="button" :class="{ 'is-active': showDirection }" @click="$emit('update:show-direction', !showDirection)">
        {{ TEXT.toggleDirection }}
      </button>
      <button
        type="button"
        :class="{ 'is-active': showStationNames }"
        @click="$emit('update:show-station-names', !showStationNames)"
      >
        {{ TEXT.toggleStationNames }}
      </button>
      <button type="button" :class="{ 'is-active': showHeatLayer }" @click="$emit('update:show-heat-layer', !showHeatLayer)">
        {{ TEXT.toggleHeatLayer }}
      </button>
      <button
        type="button"
        :class="{ 'is-active': showEncodingLayer }"
        @click="$emit('update:show-encoding-layer', !showEncodingLayer)"
      >
        {{ TEXT.toggleEncodingLayer }}
      </button>
    </div>

    <div class="gis-map-status">
      <span class="gis-map-status__chip">{{ routeChipText }}</span>
      <span v-if="selectedStation" class="gis-map-status__chip is-station">{{ stationChipText }}</span>
    </div>

    <div class="gis-layer-control">
      <button
        v-for="layer in layers"
        :key="layer"
        type="button"
        :class="{ 'is-active': activeLayer === layer }"
        @click="$emit('update:active-layer', layer)"
      >
        {{ layer }}
      </button>
    </div>

    <div ref="mapRootRef" class="gis-map-surface">
      <div v-if="mapError" class="gis-map-fallback">
        <strong>{{ TEXT.mapLoadFailed }}</strong>
        <span>{{ mapError }}</span>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, markRaw, onActivated, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import debounce from 'lodash-es/debounce'
import AMapLoader from '@amap/amap-jsapi-loader'

const TEXT = {
  locateCurrentRoute: '\u5b9a\u4f4d\u5f53\u524d\u7ebf\u8def',
  toggleDirection: '\u67e5\u770b\u4e0a\u4e0b\u884c',
  toggleStationNames: '\u663e\u793a\u7ad9\u70b9\u540d\u79f0',
  toggleHeatLayer: '\u6253\u5f00\u5ba2\u6d41\u70ed\u529b',
  toggleEncodingLayer: '\u663e\u793a\u7a7a\u95f4\u7f16\u7801\u7ed3\u679c',
  currentRoute: '\u5f53\u524d\u7ebf\u8def\uff1a',
  currentStation: '\u5f53\u524d\u7ad9\u70b9\uff1a',
  mapLoadFailed: '\u5730\u56fe\u52a0\u8f7d\u5931\u8d25',
  loadMapError: '\u5730\u56fe\u521d\u59cb\u5316\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u9ad8\u5fb7 Key\u3001\u5b89\u5168\u5bc6\u94a5\u6216\u7f51\u7edc\u73af\u5883\u3002',
  road: '\u9053\u8def',
  satellite: '\u536b\u661f',
  heat: '\u5ba2\u6d41\u70ed\u529b',
  crowding: '\u62e5\u6324\u5ea6',
  encodingStatus: '\u7f16\u7801\u72b6\u6001',
  stationUnit: '\u7ad9'
}

const AMAP_KEY = import.meta.env.VITE_AMAP_KEY || ''
const AMAP_SECURITY_JS_CODE = import.meta.env.VITE_AMAP_SECURITY_JS_CODE || ''
const DEFAULT_CENTER = [107.3948, 29.7031]
const DEFAULT_ZOOM = 12
const ROUTE_FIT_MAX_ZOOM = 14
const STATION_FOCUS_ZOOM = 15.5
const EMPTY_IMAGE = 'data:image/gif;base64,R0lGODlhAQABAAD/ACwAAAAAAQABAAACADs='

const props = defineProps({
  activeLayer: { type: String, required: true },
  activeRoute: { type: Object, required: true },
  layers: { type: Array, required: true },
  routeDetailLoading: { type: Boolean, default: false },
  routeCatalog: { type: Array, required: true },
  selectedDate: { type: String, default: '' },
  selectedStation: { type: Object, default: null },
  selectedStationId: { type: String, default: '' },
  showDirection: { type: Boolean, required: true },
  showEncodingLayer: { type: Boolean, required: true },
  showHeatLayer: { type: Boolean, required: true },
  showStationNames: { type: Boolean, required: true }
})

const emit = defineEmits([
  'select-route',
  'select-station',
  'update:show-direction',
  'update:show-station-names',
  'update:show-heat-layer',
  'update:show-encoding-layer',
  'update:active-layer'
])

const mapRootRef = ref(null)
const mapError = ref('')
const amapNamespace = shallowRef(null)
const mapInstance = shallowRef(null)
const infoWindow = shallowRef(null)
const roadLayer = shallowRef(null)
const satelliteLayer = shallowRef(null)
const roadNetLayer = shallowRef(null)
const heatmapLayer = shallowRef(null)
const labelLayer = shallowRef(null)
const routeGlowLine = shallowRef(null)
const routeMainLine = shallowRef(null)
const stationMarkers = shallowRef([])
const labelMarkers = shallowRef([])
const selectedOverlay = shallowRef(null)
const resizeHandler = ref(null)
const renderFrame = ref(0)

const shouldShowHeat = computed(() => props.activeLayer === TEXT.heat || props.showHeatLayer)
const routeChipText = computed(() => `${TEXT.currentRoute}${props.activeRoute.routeName} ${props.activeRoute.directionLabel}`.trim())
const stationChipText = computed(() => `${TEXT.currentStation}${props.selectedStation?.name || ''}`)

const fitActiveRouteDebounced = debounce(() => {
  if (!props.selectedStationId) {
    fitActiveRoute()
  }
}, 48)

function hasCoordinates(station) {
  return Number.isFinite(Number(station?.lng)) && Number.isFinite(Number(station?.lat))
}

function simplifyPath(points) {
  if (!Array.isArray(points) || points.length <= 2) {
    return Array.isArray(points) ? points : []
  }

  const output = [points[0]]
  const step = points.length > 160 ? Math.ceil(points.length / 120) : 1
  for (let index = step; index < points.length - 1; index += step) {
    output.push(points[index])
  }
  output.push(points[points.length - 1])
  return output
}

function buildRoutePath(route) {
  const routePath = Array.isArray(route?.path)
    ? route.path
        .filter((point) => Array.isArray(point) && point.length >= 2)
        .map((point) => [Number(point[0]), Number(point[1])])
        .filter(([lng, lat]) => Number.isFinite(lng) && Number.isFinite(lat))
    : []

  if (routePath.length >= 2) {
    return simplifyPath(routePath)
  }

  return simplifyPath((route?.stations || []).filter(hasCoordinates).map((station) => [Number(station.lng), Number(station.lat)]))
}

function buildRouteBounds(route) {
  const bbox = route?.bbox
  if (bbox && [bbox.minLng, bbox.minLat, bbox.maxLng, bbox.maxLat].every((item) => Number.isFinite(Number(item)))) {
    return {
      minLng: Number(bbox.minLng),
      minLat: Number(bbox.minLat),
      maxLng: Number(bbox.maxLng),
      maxLat: Number(bbox.maxLat)
    }
  }

  const path = buildRoutePath(route)
  if (!path.length) return null

  const lngs = path.map(([lng]) => lng)
  const lats = path.map(([, lat]) => lat)
  return {
    minLng: Math.min(...lngs),
    minLat: Math.min(...lats),
    maxLng: Math.max(...lngs),
    maxLat: Math.max(...lats)
  }
}

function ensureMapLayers() {
  const map = mapInstance.value
  const AMap = amapNamespace.value
  if (!map || !AMap) return

  if (!roadLayer.value) {
    roadLayer.value = markRaw(new AMap.TileLayer())
  }
  if (!satelliteLayer.value) {
    satelliteLayer.value = markRaw(new AMap.TileLayer.Satellite())
  }
  if (!roadNetLayer.value) {
    roadNetLayer.value = markRaw(new AMap.TileLayer.RoadNet())
  }

  if (props.activeLayer === TEXT.satellite) {
    map.setLayers([satelliteLayer.value, roadNetLayer.value])
  } else {
    map.setLayers([roadLayer.value])
  }
}

function ensureLabelLayer() {
  const map = mapInstance.value
  const AMap = amapNamespace.value
  if (!map || !AMap) return

  if (!labelLayer.value) {
    labelLayer.value = markRaw(
      new AMap.LabelsLayer({
        zIndex: 145,
        collision: false,
        allowCollision: true
      })
    )
    map.add(labelLayer.value)
  }
}

function ensureHeatLayer() {
  const map = mapInstance.value
  const AMap = amapNamespace.value
  if (!map || !AMap) return

  if (!heatmapLayer.value && AMap.HeatMap) {
    heatmapLayer.value = markRaw(
      new AMap.HeatMap(map, {
        radius: 28,
        opacity: [0, 0.68],
        gradient: {
          0.4: '#66d5ff',
          0.65: '#00a3ff',
          0.85: '#f5b14a',
          1.0: '#f56c6c'
        }
      })
    )
  }

  if (!heatmapLayer.value) return

  if (shouldShowHeat.value) {
    const data = (props.activeRoute.stations || [])
      .filter(hasCoordinates)
      .map((station) => ({
        lng: Number(station.lng),
        lat: Number(station.lat),
        count: Math.round((station.crowding || 0) * 100) + 20
      }))

    heatmapLayer.value.setDataSet({ data, max: 120 })
    heatmapLayer.value.show()
  } else {
    heatmapLayer.value.hide()
  }
}

function clearRouteLines() {
  const map = mapInstance.value
  if (!map) return
  const overlays = [routeGlowLine.value, routeMainLine.value].filter(Boolean)
  if (overlays.length) {
    map.remove(overlays)
  }
  routeGlowLine.value = null
  routeMainLine.value = null
}

function clearStationMarkers() {
  const map = mapInstance.value
  if (!map) return
  if (stationMarkers.value.length) {
    map.remove(stationMarkers.value)
  }
  stationMarkers.value = []
}

function clearStationLabels() {
  labelMarkers.value = []
  labelLayer.value?.clear?.()
}

function clearSelectedOverlay() {
  const map = mapInstance.value
  if (!map) return
  if (selectedOverlay.value) {
    map.remove(selectedOverlay.value)
    selectedOverlay.value = null
  }
}

function createStationMarker(station) {
  const AMap = amapNamespace.value
  if (!AMap || !hasCoordinates(station)) return null

  const isSelected = station.id === props.selectedStationId
  const isEncodingAlert = props.showEncodingLayer && station.isAnomaly
  const fillColor = isSelected ? '#8ef7ff' : isEncodingAlert ? '#ef4444' : '#1aa9ff'

  const marker = new AMap.CircleMarker({
    center: [Number(station.lng), Number(station.lat)],
    radius: isSelected ? 7 : 5,
    strokeColor: isSelected ? '#f7ffff' : '#0a1b2d',
    strokeWeight: isSelected ? 3 : 2,
    strokeOpacity: 1,
    fillColor,
    fillOpacity: 0.95,
    bubble: true,
    cursor: 'pointer',
    zIndex: isSelected ? 132 : 116
  })

  marker.on('click', () => {
    infoWindow.value?.setContent(
      `<div class="gis-map-tip-card"><strong>${station.name}</strong><span>${props.activeRoute.routeName} ${props.activeRoute.directionLabel}<br />${TEXT.crowding}${Math.round((station.crowding || 0) * 100)}%<br />${TEXT.encodingStatus}${station.encodingStatus || '-'}</span></div>`
    )
    infoWindow.value?.open(mapInstance.value, [Number(station.lng), Number(station.lat)])
    emit('select-station', props.activeRoute.routeId, station.id)
  })

  marker.setExtData?.(station)
  return markRaw(marker)
}

function createStationLabel(station) {
  const AMap = amapNamespace.value
  if (!AMap || !hasCoordinates(station) || !props.showStationNames) return null

  return markRaw(
    new AMap.LabelMarker({
      position: [Number(station.lng), Number(station.lat)],
      zIndex: station.id === props.selectedStationId ? 4 : 2,
      opacity: 1,
      icon: {
        image: EMPTY_IMAGE,
        size: [1, 1],
        anchor: 'center'
      },
      text: {
        content: station.name,
        direction: 'right',
        style: {
          fontSize: station.id === props.selectedStationId ? 13 : 12,
          fontWeight: station.id === props.selectedStationId ? 700 : 500,
          fillColor: '#1b324b',
          strokeColor: 'rgba(255,255,255,0.96)',
          strokeWidth: 2,
          padding: [2, 6],
          backgroundColor: 'rgba(255,255,255,0.92)',
          borderColor: 'rgba(145,170,196,0.16)',
          borderWidth: 1
        }
      }
    })
  )
}

function renderRouteLines() {
  const map = mapInstance.value
  const AMap = amapNamespace.value
  const path = buildRoutePath(props.activeRoute)
  if (!map || !AMap || !path.length) return

  clearRouteLines()

  routeGlowLine.value = markRaw(
    new AMap.Polyline({
      path,
      strokeColor: props.activeRoute.color || '#27e3ff',
      strokeWeight: 14,
      strokeOpacity: 0.18,
      lineJoin: 'round',
      lineCap: 'round',
      bubble: true
    })
  )

  routeMainLine.value = markRaw(
    new AMap.Polyline({
      path,
      strokeColor: props.activeRoute.color || '#30f2ff',
      strokeWeight: 6,
      strokeOpacity: 0.96,
      lineJoin: 'round',
      lineCap: 'round',
      showDir: props.showDirection,
      bubble: true,
      cursor: 'grab'
    })
  )

  routeMainLine.value.on('mouseover', (event) => {
    infoWindow.value?.setContent(
      `<div class="gis-map-tip-card"><strong>${props.activeRoute.routeName} ${props.activeRoute.directionLabel}</strong><span>${props.activeRoute.stationCount} ${TEXT.stationUnit}<br />${props.activeRoute.mileage} km<br />${props.activeRoute.statusLabel}</span></div>`
    )
    infoWindow.value?.open(mapInstance.value, event.lnglat)
  })
  routeMainLine.value.on('mouseout', () => infoWindow.value?.close())

  map.add([routeGlowLine.value, routeMainLine.value])
}

function renderStationMarkers() {
  const map = mapInstance.value
  if (!map) return

  clearStationMarkers()
  const overlays = (props.activeRoute?.stations || []).map((station) => createStationMarker(station)).filter(Boolean)
  stationMarkers.value = overlays
  if (overlays.length) {
    map.add(overlays)
  }
}

function renderStationLabels() {
  ensureLabelLayer()
  if (!labelLayer.value) return

  clearStationLabels()
  if (!props.showStationNames) return

  const labels = (props.activeRoute?.stations || []).map((station) => createStationLabel(station)).filter(Boolean)
  labelMarkers.value = labels
  if (labels.length) {
    labelLayer.value.add(labels)
  }
}

function updateSelectedOverlay() {
  const map = mapInstance.value
  const AMap = amapNamespace.value
  if (!map || !AMap) return

  clearSelectedOverlay()

  if (!props.selectedStationId || !props.selectedStation || !hasCoordinates(props.selectedStation)) {
    return
  }

  selectedOverlay.value = markRaw(
    new AMap.CircleMarker({
      center: [Number(props.selectedStation.lng), Number(props.selectedStation.lat)],
      radius: 20,
      strokeColor: '#ffffff',
      strokeWeight: 4,
      fillColor: 'rgba(0, 163, 255, 0.22)',
      fillOpacity: 0.95,
      zIndex: 180,
      bubble: true
    })
  )

  map.add(selectedOverlay.value)
}

function renderMapLayers() {
  const map = mapInstance.value
  const AMap = amapNamespace.value
  if (!map || !AMap || !props.activeRoute?.routeId) return

  const routePath = buildRoutePath(props.activeRoute)
  const routeBounds = buildRouteBounds(props.activeRoute)
  if (props.routeDetailLoading && !routePath.length && !routeBounds) {
    clearRouteLines()
    clearStationMarkers()
    clearStationLabels()
    clearSelectedOverlay()
    return
  }

  renderRouteLines()
  renderStationMarkers()
  renderStationLabels()
  ensureHeatLayer()
  updateSelectedOverlay()
}

function scheduleRender() {
  if (renderFrame.value) {
    cancelAnimationFrame(renderFrame.value)
  }

  renderFrame.value = requestAnimationFrame(() => {
    renderFrame.value = 0
    renderMapLayers()
  })
}

function fitActiveRoute() {
  const map = mapInstance.value
  const AMap = amapNamespace.value
  if (!map || !AMap || !props.activeRoute?.routeId) return

  const boundsData = buildRouteBounds(props.activeRoute)
  if (boundsData) {
    const bounds = new AMap.Bounds(
      new AMap.LngLat(boundsData.minLng, boundsData.minLat),
      new AMap.LngLat(boundsData.maxLng, boundsData.maxLat)
    )
    map.setBounds(bounds, true, [88, 108, 88, 108], ROUTE_FIT_MAX_ZOOM)
    return
  }

  const path = buildRoutePath(props.activeRoute)
  if (!path.length) return

  const overlays = [routeGlowLine.value, routeMainLine.value, ...stationMarkers.value].filter(Boolean)
  if (overlays.length) {
    map.setFitView(overlays, false, [88, 108, 88, 108], ROUTE_FIT_MAX_ZOOM)
  }
}

function focusSelectedStation() {
  const map = mapInstance.value
  if (!map || !props.selectedStation || !hasCoordinates(props.selectedStation)) return

  const currentZoom = Number(map.getZoom?.() ?? DEFAULT_ZOOM)
  const targetZoom = Math.max(currentZoom, STATION_FOCUS_ZOOM)
  map.setZoomAndCenter(targetZoom, [Number(props.selectedStation.lng), Number(props.selectedStation.lat)], true, 50)
}

async function initMap() {
  if (!mapRootRef.value) return

  try {
    if (window._AMapSecurityConfig == null) {
      window._AMapSecurityConfig = { securityJsCode: AMAP_SECURITY_JS_CODE }
    }

    const AMap = await AMapLoader.load({
      key: AMAP_KEY,
      version: '2.0',
      plugins: ['AMap.Scale', 'AMap.ToolBar', 'AMap.HeatMap']
    })

    amapNamespace.value = markRaw(AMap)
    mapInstance.value = markRaw(
      new AMap.Map(mapRootRef.value, {
        zoom: DEFAULT_ZOOM,
        center: DEFAULT_CENTER,
        viewMode: '2D',
        mapStyle: 'amap://styles/normal',
        resizeEnable: true,
        dragEnable: true,
        zoomEnable: true,
        doubleClickZoom: true,
        scrollWheel: true,
        jogEnable: false,
        animateEnable: false
      })
    )

    infoWindow.value = markRaw(
      new AMap.InfoWindow({
        isCustom: true,
        offset: new AMap.Pixel(0, -18)
      })
    )

    mapInstance.value.addControl(markRaw(new AMap.Scale()))
    ensureMapLayers()
    ensureLabelLayer()
    ensureHeatLayer()
    renderMapLayers()
    fitActiveRouteDebounced()

    resizeHandler.value = () => mapInstance.value?.resize?.()
    window.addEventListener('resize', resizeHandler.value)
  } catch (error) {
    mapError.value = error?.message || TEXT.loadMapError
  }
}

watch(
  () => props.activeLayer,
  () => {
    ensureMapLayers()
    ensureHeatLayer()
  }
)

watch(
  () => props.showHeatLayer,
  () => ensureHeatLayer()
)

watch(
  () => [
    props.activeRoute.routeId,
    props.activeRoute.color,
    props.activeRoute.path?.length || 0,
    props.activeRoute.stations?.length || 0,
    props.activeRoute.bbox?.minLng ?? null,
    props.activeRoute.bbox?.minLat ?? null,
    props.activeRoute.bbox?.maxLng ?? null,
    props.activeRoute.bbox?.maxLat ?? null,
    props.routeDetailLoading,
    props.showDirection,
    props.showStationNames,
    props.showEncodingLayer
  ],
  () => {
    scheduleRender()
    if (!props.selectedStationId) {
      fitActiveRouteDebounced()
    }
  }
)

watch(
  () => props.selectedStationId,
  () => {
    updateSelectedOverlay()
    renderStationLabels()

    if (props.selectedStationId && props.selectedStation) {
      focusSelectedStation()
      return
    }

    fitActiveRouteDebounced()
  }
)

onMounted(() => {
  initMap()
})

onActivated(() => {
  mapInstance.value?.resize?.()
  scheduleRender()
  if (props.selectedStationId && props.selectedStation) {
    focusSelectedStation()
    return
  }
  fitActiveRouteDebounced()
})

onBeforeUnmount(() => {
  if (resizeHandler.value) {
    window.removeEventListener('resize', resizeHandler.value)
  }
  if (renderFrame.value) {
    cancelAnimationFrame(renderFrame.value)
  }
  fitActiveRouteDebounced.cancel()
  clearRouteLines()
  clearStationMarkers()
  clearSelectedOverlay()
  clearStationLabels()
  labelLayer.value?.setMap?.(null)
  heatmapLayer.value?.setMap?.(null)
  infoWindow.value?.close?.()
  mapInstance.value?.destroy?.()
})
</script>
