<template>
  <SystemShell
    :active-page="activePage"
    :page-title="currentPage.title"
    :page-description="currentPage.description"
    :current-date="currentDate"
    :current-time="currentTime"
    :selected-date="selectedBusinessDate"
    :available-dates="availableDates"
    @navigate="navigateTo"
    @update:selected-date="selectedBusinessDate = $event"
  >
    <div v-if="systemLoading" class="system-state">系统数据加载中...</div>
    <div v-else-if="systemError" class="system-state system-state--error">{{ systemError }}</div>
    <KeepAlive v-else>
      <component
        :is="currentPage.component"
        :page-data="currentPageData"
        :selected-date="selectedBusinessDate"
        @open-dashboard="openDashboard"
      />
    </KeepAlive>
  </SystemShell>
</template>

<script setup>
import { KeepAlive } from 'vue'
import SystemShell from '../components/system/SystemShell.vue'
import { useSystemWorkspace } from '../composables/useSystemWorkspace'
import '../styles/system-workspace.css'

const AMAP_KEY = import.meta.env.VITE_AMAP_KEY || ''
const AMAP_SECURITY_JS_CODE = import.meta.env.VITE_AMAP_SECURITY_JS_CODE || ''

let gisWarmupPromise = null

function warmGisRuntime() {
  if (gisWarmupPromise) {
    return gisWarmupPromise
  }

  gisWarmupPromise = Promise.allSettled([
    import('../views/system/SystemGisPage.vue'),
    import('@amap/amap-jsapi-loader').then(({ default: AMapLoader }) => {
      if (window._AMapSecurityConfig == null) {
        window._AMapSecurityConfig = { securityJsCode: AMAP_SECURITY_JS_CODE }
      }

      return AMapLoader.load({
        key: AMAP_KEY,
        version: '2.0',
        plugins: ['AMap.Scale', 'AMap.ToolBar', 'AMap.HeatMap']
      })
    })
  ]).catch(() => null)

  return gisWarmupPromise
}

const {
  activePage,
  availableDates,
  currentDate,
  currentPage,
  currentPageData,
  currentTime,
  navigateTo,
  openDashboard,
  selectedBusinessDate,
  systemError,
  systemLoading
} = useSystemWorkspace()

if (activePage.value === 'gis') {
  warmGisRuntime()
} else if ('requestIdleCallback' in window) {
  window.requestIdleCallback(() => warmGisRuntime(), { timeout: 8000 })
} else {
  window.setTimeout(() => warmGisRuntime(), 6000)
}
</script>
