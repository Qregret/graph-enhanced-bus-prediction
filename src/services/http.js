export async function fetchJson(url, options) {
  const response = await fetch(url, options)
  let payload = null

  try {
    payload = await response.json()
  } catch {
    payload = null
  }

  if (!response.ok) {
    throw new Error(payload?.message || `请求失败: ${response.status}`)
  }

  return payload
}
