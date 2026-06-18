import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import { getSystemPageFromLocation, systemPageRegistry } from '../config/systemPages'
import { fetchSystemMeta, fetchSystemPage, fetchSystemPages } from '../services/systemApi'

const SYSTEM_BUNDLE_CACHE_PREFIX = 'cdbus-system-bundle:v2:'
const SYSTEM_PAGE_CACHE_PREFIX = 'cdbus-system-page:v1:'
const SYSTEM_PAGE_ENDPOINTS = new Set(['launchpad', 'overview', 'forecast', 'dispatch', 'analytics', 'gis'])

export function useSystemWorkspace() {
  const activePage = ref(getSystemPageFromLocation())
  const currentDate = ref('')
  const currentTime = ref('')
  const selectedBusinessDate = ref('')
  const availableDates = ref([])
  const systemBundle = ref({})
  const systemLoading = ref(false)
  const systemError = ref('')

  let timer = null
  let activeRequestId = 0
  let skipNextDateWatch = false

  const currentPage = computed(() => systemPageRegistry[activePage.value] ?? systemPageRegistry.launchpad)
  const currentPageData = computed(() => systemBundle.value?.[activePage.value] ?? {})

  function syncClock() {
    const now = new Date()
    const year = now.getFullYear()
    const month = String(now.getMonth() + 1).padStart(2, '0')
    const day = String(now.getDate()).padStart(2, '0')
    const hours = String(now.getHours()).padStart(2, '0')
    const minutes = String(now.getMinutes()).padStart(2, '0')
    const seconds = String(now.getSeconds()).padStart(2, '0')
    currentDate.value = `${year}-${month}-${day}`
    currentTime.value = `${hours}:${minutes}:${seconds}`
  }

  async function loadMeta() {
    const meta = await fetchSystemMeta()
    availableDates.value = Array.isArray(meta.availableDates) ? meta.availableDates : []
    if (!selectedBusinessDate.value && availableDates.value.length) {
      skipNextDateWatch = true
      selectedBusinessDate.value = availableDates.value[0]
    }
  }

  function applyBundle(bundle) {
    systemBundle.value = bundle
    if (Array.isArray(bundle.availableDates) && bundle.availableDates.length) {
      availableDates.value = bundle.availableDates
    }
    if (bundle.date) {
      skipNextDateWatch = true
      selectedBusinessDate.value = bundle.date
    }
  }

  function applyPageData(page, payload, date) {
    if (!payload || typeof payload !== 'object') {
      return
    }
    systemBundle.value = {
      ...systemBundle.value,
      date: payload.date || date || systemBundle.value?.date,
      availableDates: systemBundle.value?.availableDates || availableDates.value,
      [page]: payload
    }
  }

  function hasUsefulPageData(page, payload) {
    if (!payload || typeof payload !== 'object') {
      return false
    }

    if (page === 'overview') {
      const metrics = payload.metrics || {}
      const trend = payload.trend || {}
      return metrics.totalPassengers != null ||
        metrics.onlineVehicles != null ||
        metrics.congestedStations != null ||
        (Array.isArray(trend.labels) && trend.labels.length > 0) ||
        (Array.isArray(payload.topCongestedRoutes) && payload.topCongestedRoutes.length > 0) ||
        (Array.isArray(payload.liveFeed) && payload.liveFeed.length > 0)
    }

    return Object.keys(payload).some((key) => {
      if (key === 'date' || key === 'availableDates') return false
      const value = payload[key]
      return Array.isArray(value) ? value.length > 0 : value != null && value !== ''
    })
  }

  function hasUsefulBundleData(bundle, page = activePage.value) {
    if (!bundle || typeof bundle !== 'object') {
      return false
    }
    return hasUsefulPageData(page, bundle[page])
  }

  function readCachedPage(page, date) {
    try {
      const key = `${SYSTEM_PAGE_CACHE_PREFIX}${page}:${date || 'default'}`
      const raw = window.localStorage.getItem(key)
      const payload = raw ? JSON.parse(raw) : null
      if (hasUsefulPageData(page, payload)) {
        return payload
      }
      if (raw) {
        window.localStorage.removeItem(key)
      }
      return null
    } catch {
      return null
    }
  }

  function writeCachedPage(page, date, payload) {
    if (!hasUsefulPageData(page, payload)) {
      return
    }
    try {
      const key = `${SYSTEM_PAGE_CACHE_PREFIX}${page}:${date || payload.date || 'default'}`
      window.localStorage.setItem(key, JSON.stringify(payload))
    } catch {
      // Local cache is only a speed-up; ignore storage quota/private-mode failures.
    }
  }

  function readCachedBundle(date) {
    try {
      const key = `${SYSTEM_BUNDLE_CACHE_PREFIX}${date || 'default'}`
      const raw = window.localStorage.getItem(key)
      const bundle = raw ? JSON.parse(raw) : null
      if (hasUsefulBundleData(bundle)) {
        return bundle
      }
      if (raw) {
        window.localStorage.removeItem(key)
      }
      return null
    } catch {
      return null
    }
  }

  function writeCachedBundle(date, bundle) {
    if (!hasUsefulBundleData(bundle)) {
      return
    }
    try {
      const key = `${SYSTEM_BUNDLE_CACHE_PREFIX}${date || bundle.date || 'default'}`
      window.localStorage.setItem(key, JSON.stringify(bundle))
    } catch {
      // Local cache is only a speed-up; ignore storage quota/private-mode failures.
    }
  }

  async function fetchAndApplyPage(page, date, requestId) {
    if (!SYSTEM_PAGE_ENDPOINTS.has(page)) {
      return null
    }
    const payload = await fetchSystemPage(page, date)
    if (requestId !== activeRequestId) {
      return null
    }
    if (hasUsefulPageData(page, payload)) {
      applyPageData(page, payload, date)
      writeCachedPage(page, date, payload)
      return payload
    }
    return null
  }

  async function fetchAndApplyBundle(date, requestId) {
    const bundle = await fetchSystemPages(date)
    if (requestId !== activeRequestId) {
      return null
    }
    if (hasUsefulBundleData(bundle)) {
      applyBundle(bundle)
      writeCachedBundle(date, bundle)
      return bundle
    }
    return null
  }

  async function loadBundle(date) {
    const requestId = ++activeRequestId
    const page = activePage.value
    const cachedBundle = readCachedBundle(date)
    const cachedPage = cachedBundle ? null : readCachedPage(page, date)
    const hasCachedData = Boolean(cachedBundle || cachedPage)

    if (cachedBundle) {
      applyBundle(cachedBundle)
    } else if (cachedPage) {
      applyPageData(page, cachedPage, date)
    }

    systemLoading.value = !hasCachedData
    systemError.value = ''

    const pageRequest = fetchAndApplyPage(page, date, requestId)
    const bundleRequest = fetchAndApplyBundle(date, requestId)

    try {
      if (!hasCachedData) {
        await Promise.race([
          pageRequest.catch(() => null),
          bundleRequest.catch(() => null)
        ])
        if (requestId === activeRequestId && hasUsefulPageData(page, currentPageData.value)) {
          systemLoading.value = false
        }
      }

      const results = await Promise.allSettled([pageRequest, bundleRequest])
      const hasData = hasUsefulPageData(page, currentPageData.value)
      if (!hasData && results.every((result) => result.status === 'rejected' || !result.value)) {
        const rejected = results.find((result) => result.status === 'rejected')
        throw rejected?.reason || new Error('系统页数据为空')
      }
    } catch (error) {
      if (requestId !== activeRequestId) {
        return
      }
      if (!hasUsefulPageData(page, currentPageData.value)) {
        systemError.value = error instanceof Error ? error.message : '系统页数据加载失败'
      }
    } finally {
      if (requestId === activeRequestId) {
        systemLoading.value = false
      }
    }
  }

  async function ensureActivePageData(page) {
    if (!selectedBusinessDate.value || hasUsefulPageData(page, systemBundle.value?.[page])) {
      return
    }
    const requestId = ++activeRequestId
    const cachedPage = readCachedPage(page, selectedBusinessDate.value)
    if (cachedPage) {
      applyPageData(page, cachedPage, selectedBusinessDate.value)
      return
    }
    systemLoading.value = true
    systemError.value = ''
    try {
      await fetchAndApplyPage(page, selectedBusinessDate.value, requestId)
    } catch (error) {
      if (requestId === activeRequestId) {
        systemError.value = error instanceof Error ? error.message : '系统页数据加载失败'
      }
    } finally {
      if (requestId === activeRequestId) {
        systemLoading.value = false
      }
    }
  }

  function navigateTo(page) {
    activePage.value = page
    const url = new URL(window.location.href)
    url.searchParams.set('page', page)
    window.history.replaceState({}, '', url.toString())
    ensureActivePageData(page)
  }

  function openDashboard() {
    const url = new URL(window.location.href)
    url.searchParams.set('screen', 'dashboard')
    url.searchParams.delete('page')
    window.open(url.toString(), '_blank', 'noopener,noreferrer')
  }

  watch(selectedBusinessDate, (date, prev) => {
    if (skipNextDateWatch) {
      skipNextDateWatch = false
      return
    }
    if (!date || date === prev) {
      return
    }
    loadBundle(date)
  })

  onMounted(async () => {
    syncClock()
    timer = window.setInterval(syncClock, 1000)
    try {
      await loadMeta()
      await loadBundle(selectedBusinessDate.value)
    } catch (error) {
      systemError.value = error instanceof Error ? error.message : '系统页初始化失败'
    }
  })

  onBeforeUnmount(() => {
    if (timer) {
      window.clearInterval(timer)
    }
  })

  return {
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
  }
}
