import { computed } from 'vue'

export function useSystemLaunchpadPage(pageData) {
  const statusPreview = computed(() => {
    const source = Array.isArray(pageData.value?.status) ? pageData.value.status : []
    if (source.length) {
      return source.slice(0, 2)
    }
    return [
      { label: '数据库连接', value: '正常' },
      { label: '热缓存场景', value: '58' }
    ]
  })

  function formatNumber(value) {
    if (typeof value !== 'number') return value || '-'
    return value.toLocaleString('zh-CN')
  }

  return {
    statusPreview,
    formatNumber
  }
}
