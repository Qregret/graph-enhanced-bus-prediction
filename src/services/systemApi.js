import { fetchJson } from './http'

export function fetchSystemMeta() {
  return fetchJson('/api/system/meta')
}

export function fetchSystemPages(date) {
  const params = new URLSearchParams()
  if (date) {
    params.set('date', date)
  }
  const query = params.toString()
  return fetchJson(`/api/system/pages${query ? `?${query}` : ''}`)
}

export function fetchSystemOverview(date) {
  const params = new URLSearchParams()
  if (date) {
    params.set('date', date)
  }
  return fetchJson(`/api/system/overview?${params.toString()}`)
}

export function fetchSystemPage(page, date) {
  const safePage = String(page || '').replace(/[^a-z]/g, '')
  const params = new URLSearchParams()
  if (date) {
    params.set('date', date)
  }
  const query = params.toString()
  return fetchJson(`/api/system/${safePage}${query ? `?${query}` : ''}`)
}
