import { fetchJson } from './http'

export function fetchDashboardLines() {
  return fetchJson('/api/lines')
}

export function fetchDashboardData({ line, direction, date, signal }) {
  const params = new URLSearchParams({
    line,
    direction
  })
  if (date) {
    params.set('date', date)
  }
  return fetchJson(`/api/dashboard?${params.toString()}`, signal ? { signal } : undefined)
}
