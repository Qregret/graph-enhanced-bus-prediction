const toFiniteNumber = value => {
  const result = Number(value)
  return Number.isFinite(result) ? result : null
}

const normalizeStations = stations =>
  Array.isArray(stations)
    ? stations.map(item => ({
        ...item,
        longitude: toFiniteNumber(item.longitude),
        latitude: toFiniteNumber(item.latitude),
        predictedBoardings: Number(item.predictedBoardings ?? 0),
        predictedAlightings: Number(item.predictedAlightings ?? 0),
        count: Number(item.count ?? 1)
      }))
    : []

const getSquareDistance = (a, b) => {
  const dx = a[0] - b[0]
  const dy = a[1] - b[1]
  return dx * dx + dy * dy
}

const getSquareSegmentDistance = (point, start, end) => {
  let x = start[0]
  let y = start[1]
  let dx = end[0] - x
  let dy = end[1] - y

  if (dx !== 0 || dy !== 0) {
    const t = ((point[0] - x) * dx + (point[1] - y) * dy) / (dx * dx + dy * dy)

    if (t > 1) {
      x = end[0]
      y = end[1]
    } else if (t > 0) {
      x += dx * t
      y += dy * t
    }
  }

  dx = point[0] - x
  dy = point[1] - y
  return dx * dx + dy * dy
}

const simplifyRadialDistance = (points, tolerance) => {
  let previous = points[0]
  const output = [previous]

  for (let index = 1; index < points.length; index += 1) {
    const point = points[index]
    if (getSquareDistance(point, previous) > tolerance) {
      output.push(point)
      previous = point
    }
  }

  if (previous !== points[points.length - 1]) {
    output.push(points[points.length - 1])
  }

  return output
}

const simplifyDouglasPeuckerStep = (points, first, last, tolerance, output) => {
  let maxTolerance = tolerance
  let maxIndex = 0

  for (let index = first + 1; index < last; index += 1) {
    const distance = getSquareSegmentDistance(points[index], points[first], points[last])
    if (distance > maxTolerance) {
      maxIndex = index
      maxTolerance = distance
    }
  }

  if (maxTolerance > tolerance) {
    if (maxIndex - first > 1) {
      simplifyDouglasPeuckerStep(points, first, maxIndex, tolerance, output)
    }
    output.push(points[maxIndex])
    if (last - maxIndex > 1) {
      simplifyDouglasPeuckerStep(points, maxIndex, last, tolerance, output)
    }
  }
}

const simplifyDouglasPeucker = (points, tolerance) => {
  const last = points.length - 1
  const output = [points[0]]
  simplifyDouglasPeuckerStep(points, 0, last, tolerance, output)
  output.push(points[last])
  return output
}

const simplifyPath = (points, tolerance) => {
  if (!Array.isArray(points) || points.length < 3) {
    return Array.isArray(points) ? points : []
  }

  const sqTolerance = tolerance * tolerance
  const radialSimplified = simplifyRadialDistance(points, sqTolerance)
  return simplifyDouglasPeucker(radialSimplified, sqTolerance)
}

const getPathTolerance = zoom => {
  if (zoom >= 16) return 0.00001
  if (zoom >= 14) return 0.00003
  if (zoom >= 12) return 0.00008
  return 0.00015
}

self.onmessage = event => {
  const { taskId, type, payload, meta } = event.data

  try {
    if (type === 'stations') {
      const stations = normalizeStations(payload?.stations)
      self.postMessage({
        taskId,
        payload: {
          stations,
          total: Number(payload?.total ?? stations.length),
          mode: payload?.mode ?? 'point'
        }
      })
      return
    }

    if (type === 'dashboard') {
      const stations = normalizeStations(payload?.stations)
      const fallbackPath = stations
        .filter(item => item.longitude !== null && item.latitude !== null)
        .map(item => [item.longitude, item.latitude])
      const pathSource = Array.isArray(payload?.path) && payload.path.length ? payload.path : fallbackPath
      const path = simplifyPath(pathSource, getPathTolerance(Number(meta?.zoom ?? 13)))

      self.postMessage({
        taskId,
        payload: {
          ...payload,
          stations,
          path
        }
      })
    }
  } catch (error) {
    self.postMessage({
      taskId,
      error: error.message || 'Worker processing failed'
    })
  }
}
