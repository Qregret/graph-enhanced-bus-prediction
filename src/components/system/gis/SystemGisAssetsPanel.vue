<template>
  <aside class="gis-float-panel gis-float-panel--assets">
    <div class="gis-assets__summary">
      <article v-for="metric in overviewMetrics" :key="metric.key" class="gis-assets__metric">
        <strong>{{ metric.value }}</strong>
        <span>{{ metric.label }}</span>
      </article>
    </div>

    <div class="gis-assets__header">
      <div>
        <h3>{{ TEXT.title }}</h3>
        <p>{{ TEXT.subtitle }}</p>
      </div>
      <span class="gis-assets__count">{{ filteredRoutes.length }} {{ TEXT.countUnit }}</span>
    </div>

    <label class="gis-assets__filter gis-assets__filter--full">
      <span>{{ TEXT.routeSelect }}</span>
      <select v-model="activeRouteProxy">
        <option v-for="item in routeOptions" :key="item.value" :value="item.value">
          {{ item.label }}
        </option>
      </select>
    </label>

    <div class="gis-assets__filters">
      <label class="gis-assets__filter">
        <span>{{ TEXT.routeStatus }}</span>
        <select v-model="statusFilterProxy">
          <option v-for="item in statusOptions" :key="item.value" :value="item.value">
            {{ item.label }}
          </option>
        </select>
      </label>

      <label class="gis-assets__filter">
        <span>{{ TEXT.routeLength }}</span>
        <select v-model="lengthFilterProxy">
          <option v-for="item in lengthOptions" :key="item.value" :value="item.value">
            {{ item.label }}
          </option>
        </select>
      </label>
    </div>

    <el-scrollbar class="gis-assets__list">
      <button
        v-for="route in filteredRoutes"
        :key="route.routeId"
        class="gis-assets__card"
        :class="{ 'is-active': route.routeId === activeRouteId }"
        :style="{ '--route-color': route.color }"
        @click="$emit('select-route', route.routeId)"
      >
        <div class="gis-assets__card-top">
          <div class="gis-assets__title">
            <span class="gis-assets__lamp" :class="`is-${route.status}`"></span>
            <div>
              <strong>{{ route.routeName }}</strong>
              <small>{{ route.directionLabel }}</small>
            </div>
          </div>

          <div class="gis-assets__actions">
            <el-tag round effect="light" :type="route.statusTagType">
              {{ route.statusLabel }}
            </el-tag>
            <button
              type="button"
              class="gis-assets__locate"
              :title="TEXT.locateRoute"
              @click.stop="$emit('locate-route', route.routeId)"
            >
              <el-icon><Location /></el-icon>
            </button>
          </div>
        </div>

        <div class="gis-assets__meta">
          <span>{{ route.mileage }} km</span>
          <span>{{ route.stationCount }} {{ TEXT.stationUnit }}</span>
          <span>{{ TEXT.encodingPrefix }} {{ route.codingCompleteness }}%</span>
        </div>

        <div class="gis-assets__status-row">
          <span>{{ route.pressureLabel }}</span>
          <span>{{ route.codingIssues ? `${TEXT.anomalyPrefix}${route.codingIssues}${TEXT.anomalySuffix}` : TEXT.encodingStable }}</span>
        </div>

        <div class="gis-assets__progress">
          <i :style="{ width: `${route.codingCompleteness}%` }"></i>
        </div>
      </button>
    </el-scrollbar>
  </aside>
</template>

<script setup>
import { computed } from 'vue'
import { Location } from '@element-plus/icons-vue'

const TEXT = {
  title: '\u7ebf\u8def\u8d44\u4ea7\u5217\u8868',
  subtitle: '\u56f4\u7ed5\u5730\u56fe\u5feb\u901f\u7b5b\u9009\u7ebf\u8def\u3001\u5ba2\u6d41\u538b\u529b\u4e0e\u7a7a\u95f4\u7f16\u7801\u5f02\u5e38\u3002',
  countUnit: '\u6761',
  routeSelect: '\u7ebf\u8def\u9009\u62e9',
  routeStatus: '\u8fd0\u884c\u72b6\u6001',
  routeLength: '\u7ebf\u8def\u957f\u5ea6',
  locateRoute: '\u5b9a\u4f4d\u7ebf\u8def',
  stationUnit: '\u7ad9',
  encodingPrefix: '\u7f16\u7801',
  anomalyPrefix: '\u5f02\u5e38 ',
  anomalySuffix: ' \u5904',
  encodingStable: '\u7f16\u7801\u7a33\u5b9a',
  all: '\u5168\u90e8',
  normal: '\u8fd0\u884c\u6b63\u5e38',
  warning: '\u91cd\u70b9\u5173\u6ce8',
  error: '\u7f16\u7801\u5f02\u5e38',
  short: '14km \u4ee5\u4e0b',
  medium: '14-17km',
  long: '17km \u4ee5\u4e0a'
}

const props = defineProps({
  activeRouteId: { type: String, required: true },
  filteredRoutes: { type: Array, required: true },
  lengthFilter: { type: String, required: true },
  overviewMetrics: { type: Array, required: true },
  routeOptions: { type: Array, required: true },
  statusFilter: { type: String, required: true }
})

const emit = defineEmits(['update:status-filter', 'update:length-filter', 'select-route', 'locate-route'])

const statusOptions = [
  { value: 'all', label: TEXT.all },
  { value: 'normal', label: TEXT.normal },
  { value: 'warning', label: TEXT.warning },
  { value: 'error', label: TEXT.error }
]

const lengthOptions = [
  { value: 'all', label: TEXT.all },
  { value: 'short', label: TEXT.short },
  { value: 'medium', label: TEXT.medium },
  { value: 'long', label: TEXT.long }
]

const activeRouteProxy = computed({
  get: () => props.activeRouteId,
  set: (value) => emit('select-route', value)
})

const statusFilterProxy = computed({
  get: () => props.statusFilter,
  set: (value) => emit('update:status-filter', value)
})

const lengthFilterProxy = computed({
  get: () => props.lengthFilter,
  set: (value) => emit('update:length-filter', value)
})
</script>
