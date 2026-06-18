import express from 'express'
import cors from 'cors'
import { createClient } from 'redis'

const app = express()
const port = 3000

const redisClient = createClient({ url: 'redis://127.0.0.1:6379' })
const datasetState = {
  loadedAt: 0,
  loadingPromise: null,
  data: null
}

app.use(cors())
app.use(express.json())

const createHourlyArray = () => Array.from({ length: 24 }, () => 0)
const routeKey = (routeId, direction) => `${routeId}:${direction}`
const dashboardKey = (routeId, direction, date) => `${routeId}:${direction}:${date}`

const parseHour = value => {
  if (!value || value.length < 13) {
    return null
  }

  const hour = Number(value.slice(11, 13))
  return Number.isNaN(hour) ? null : hour
}

const parseDate = value => {
  if (!value) {
    return null
  }

  return value.slice(0, 10)
}

const compareDate = (left, right) => {
  if (!left) {
    return right
  }

  return left > right ? left : right
}

const scanHashes = async (pattern, onRow, count = 500) => {
  let batchKeys = []
  let cursor = '0'

  const flush = async () => {
    if (!batchKeys.length) {
      return
    }

    const pipeline = redisClient.multi()
    batchKeys.forEach(key => pipeline.hGetAll(key))
    const rows = await pipeline.exec()

    rows.forEach((row, index) => {
      const value = Array.isArray(row) ? row[1] : row
      if (value && Object.keys(value).length) {
        onRow(value, batchKeys[index])
      }
    })

    batchKeys = []
  }

  do {
    const reply = await redisClient.scan(cursor, {
      MATCH: pattern,
      COUNT: count
    })
    cursor = reply.cursor

    for (const key of reply.keys) {
      batchKeys.push(key)
      if (batchKeys.length >= count) {
        await flush()
      }
    }
  } while (cursor !== '0')

  await flush()
}

const buildTrend = (hourlyTraffic, gpsHourlySpeed) => {
  const labels = [
    '00:00', '02:00', '04:00', '06:00', '08:00', '10:00', '12:00',
    '14:00', '16:00', '18:00', '20:00', '22:00', '24:00'
  ]

  const trafficBuckets = []
  const speedBuckets = []

  for (let hour = 0; hour < 24; hour += 2) {
    trafficBuckets.push(hourlyTraffic[hour] + hourlyTraffic[hour + 1])

    const speedValues = [gpsHourlySpeed[hour], gpsHourlySpeed[hour + 1]].filter(
      value => typeof value === 'number' && value >= 0
    )

    speedBuckets.push(
      speedValues.length
        ? Number((speedValues.reduce((sum, item) => sum + item, 0) / speedValues.length).toFixed(1))
        : null
    )
  }

  trafficBuckets.push(trafficBuckets[trafficBuckets.length - 1] ?? 0)
  speedBuckets.push(speedBuckets[speedBuckets.length - 1] ?? speedBuckets[speedBuckets.length - 2] ?? 0)

  const maxTraffic = Math.max(...trafficBuckets, 1)
  const congestion = trafficBuckets.map(value => Number((0.9 + (value / maxTraffic) * 0.9).toFixed(2)))
  const fallbackSpeed = trafficBuckets.map(value => Number((52 - (value / maxTraffic) * 16).toFixed(1)))

  const speed = speedBuckets.map((value, index) => (value === null ? fallbackSpeed[index] : value))

  return { labels, speed, congestion }
}

const buildTopStations = stations =>
  [...stations]
    .sort((left, right) => right.predictedBoardings + right.predictedAlightings - (left.predictedBoardings + left.predictedAlightings))
    .slice(0, 5)
    .map(item => ({
      name: item.stationName,
      value: item.predictedBoardings + item.predictedAlightings
    }))

const parseBounds = value => {
  if (!value) {
    return null
  }

  const parts = String(value)
    .split(',')
    .map(item => Number(item))

  if (parts.length !== 4 || parts.some(item => Number.isNaN(item))) {
    return null
  }

  const [west, south, east, north] = parts
  return { west, south, east, north }
}

const isInsideBounds = (station, bounds) => {
  if (!bounds) {
    return true
  }

  return (
    station.longitude >= bounds.west &&
    station.longitude <= bounds.east &&
    station.latitude >= bounds.south &&
    station.latitude <= bounds.north
  )
}

const getGridSize = zoom => {
  if (zoom >= 16) return 0.0018
  if (zoom >= 15) return 0.003
  if (zoom >= 14) return 0.005
  if (zoom >= 13) return 0.008
  if (zoom >= 12) return 0.012
  if (zoom >= 11) return 0.02
  return 0.035
}

const buildStationMapPayload = (dataset, bounds, zoom) => {
  const total = dataset.allStations.length
  const visibleStations = bounds
    ? dataset.allStations.filter(station => isInsideBounds(station, bounds))
    : dataset.allStations

  const visibleTotal = visibleStations.length
  const normalizedZoom = Number.isNaN(Number(zoom)) ? 0 : Number(zoom)

  if (!visibleStations.length) {
    return {
      mode: 'point',
      total,
      visibleTotal,
      reduced: false,
      stations: []
    }
  }

  if (normalizedZoom < 14 || visibleStations.length > 3500) {
    const gridSize = getGridSize(normalizedZoom)
    const buckets = new Map()

    for (const station of visibleStations) {
      const lngIndex = Math.floor(station.longitude / gridSize)
      const latIndex = Math.floor(station.latitude / gridSize)
      const key = `${lngIndex}:${latIndex}`
      const bucket = buckets.get(key) ?? {
        longitude: 0,
        latitude: 0,
        count: 0,
        stationName: station.stationName
      }

      bucket.longitude += station.longitude
      bucket.latitude += station.latitude
      bucket.count += 1
      buckets.set(key, bucket)
    }

    return {
      mode: 'cluster',
      total,
      visibleTotal,
      reduced: true,
      stations: [...buckets.values()].map(item => ({
        longitude: Number((item.longitude / item.count).toFixed(6)),
        latitude: Number((item.latitude / item.count).toFixed(6)),
        count: item.count,
        stationName: item.count > 1 ? `${item.stationName}等${item.count}站` : item.stationName
      }))
    }
  }

  if (visibleStations.length > 2500) {
    const step = Math.ceil(visibleStations.length / 2500)
    return {
      mode: 'point',
      total,
      visibleTotal,
      reduced: true,
      stations: visibleStations.filter((_, index) => index % step === 0)
    }
  }

  return {
    mode: 'point',
    total,
    visibleTotal,
    reduced: false,
    stations: visibleStations
  }
}

const ensureDataset = async () => {
  const now = Date.now()
  if (datasetState.data && now - datasetState.loadedAt < 10 * 60 * 1000) {
    return datasetState.data
  }

  if (datasetState.loadingPromise) {
    return datasetState.loadingPromise
  }

  datasetState.loadingPromise = (async () => {
    if (!redisClient.isOpen) {
      await redisClient.connect()
    }

    const stationMap = new Map()
    const routeStations = new Map()
    const routeMeta = new Map()
    const odDashboards = new Map()
    const gpsAgg = new Map()

    await scanHashes('cdbus:ods_station_inc:row:*', row => {
      const station = {
        stationId: row.id,
        stationName: row.name || row.id,
        longitude: row.longitude ? Number(row.longitude) : null,
        latitude: row.latitude ? Number(row.latitude) : null
      }

      stationMap.set(station.stationId, station)
    })

    await scanHashes('cdbus:ods_route_station_full:row:*', row => {
      if (!row.route_id || !row.station_id || !row.direction) {
        return
      }

      const key = routeKey(row.route_id, Number(row.direction))
      const stationMeta = stationMap.get(row.station_id)
      const list = routeStations.get(key) ?? []

      list.push({
        stationId: row.station_id,
        stationName: stationMeta?.stationName || row.station_id,
        longitude: stationMeta?.longitude ?? null,
        latitude: stationMeta?.latitude ?? null,
        serialNumber: Number(row.serial_number ?? 0)
      })

      routeStations.set(key, list)
    })

    await scanHashes('cdbus:dwd_od:row:*', row => {
      const routeId = row.line_id
      const lineName = row.line_name || row.line_id
      const direction = Number(row.is_up_down || 0)
      const date = parseDate(row.dt || row.trade_time)

      if (!routeId || !direction || !date) {
        return
      }

      const meta = routeMeta.get(routeId) ?? {
        id: routeId,
        name: lineName,
        latestDate: null,
        directions: new Set(),
        totalRecords: 0
      }

      meta.name = meta.name || lineName
      meta.latestDate = compareDate(meta.latestDate, date)
      meta.directions.add(direction)
      meta.totalRecords += 1
      routeMeta.set(routeId, meta)

      const key = dashboardKey(routeId, direction, date)
      const dashboard = odDashboards.get(key) ?? {
        routeId,
        routeName: lineName,
        direction,
        date,
        hourlyTraffic: createHourlyArray(),
        stationFlow: new Map()
      }

      const hour = parseHour(row.trade_time)
      if (hour !== null && hour >= 0 && hour < 24) {
        dashboard.hourlyTraffic[hour] += 1
      }

      if (row.origin_id) {
        const station = dashboard.stationFlow.get(row.origin_id) ?? {
          stationId: row.origin_id,
          stationName: row.origin || row.origin_id,
          predictedBoardings: 0,
          predictedAlightings: 0
        }
        station.predictedBoardings += 1
        dashboard.stationFlow.set(row.origin_id, station)
      }

      if (row.destination_id) {
        const station = dashboard.stationFlow.get(row.destination_id) ?? {
          stationId: row.destination_id,
          stationName: row.destination || row.destination_id,
          predictedBoardings: 0,
          predictedAlightings: 0
        }
        station.predictedAlightings += 1
        dashboard.stationFlow.set(row.destination_id, station)
      }

      odDashboards.set(key, dashboard)
    })

    await scanHashes('cdbus:ods_vehicle_gps_inc:row:*', row => {
      const routeId = row.route_id
      const date = parseDate(row.dt || row.gps_time)
      const hour = parseHour(row.gps_time)
      const speed = Number(row.speed)

      if (!routeId || !date || hour === null || Number.isNaN(speed)) {
        return
      }

      const key = `${routeId}:${date}`
      const item = gpsAgg.get(key) ?? Array.from({ length: 24 }, () => ({ sum: 0, count: 0 }))
      item[hour].sum += speed
      item[hour].count += 1
      gpsAgg.set(key, item)
    })

    for (const [key, list] of routeStations.entries()) {
      routeStations.set(
        key,
        list
          .sort((left, right) => left.serialNumber - right.serialNumber)
          .map(item => ({
            ...item,
            stationName: stationMap.get(item.stationId)?.stationName || item.stationName
          }))
      )
    }

    const routeList = [...routeMeta.values()]
      .map(item => ({
        id: item.id,
        name: item.name || item.id,
        latestDate: item.latestDate,
        directions: [...item.directions]
          .sort((left, right) => left - right)
          .map(value => ({ value, label: value === 1 ? '上行' : '下行' })),
        totalRecords: item.totalRecords
      }))
      .sort((left, right) => right.totalRecords - left.totalRecords)

    const data = {
      allStations: [...stationMap.values()].filter(
        station => station.longitude !== null && station.latitude !== null
      ),
      routeStations,
      routeList,
      odDashboards,
      gpsAgg,
      stationMap
    }

    datasetState.data = data
    datasetState.loadedAt = Date.now()
    datasetState.loadingPromise = null
    return data
  })().catch(error => {
    datasetState.loadingPromise = null
    throw error
  })

  return datasetState.loadingPromise
}

const getDashboard = (dataset, routeId, direction) => {
  const route = dataset.routeList.find(item => item.id === routeId)
  if (!route) {
    return null
  }

  const normalizedDirection = route.directions.some(item => item.value === direction)
    ? direction
    : route.directions[0]?.value ?? 1

  const date = route.latestDate
  const od = dataset.odDashboards.get(dashboardKey(routeId, normalizedDirection, date))
  const orderedStations = dataset.routeStations.get(routeKey(routeId, normalizedDirection)) ?? []

  const mergedStations = orderedStations.map(station => {
    const flow = od?.stationFlow.get(station.stationId)
    return {
      ...station,
      stationName: station.stationName || flow?.stationName || station.stationId,
      predictedBoardings: flow?.predictedBoardings ?? 0,
      predictedAlightings: flow?.predictedAlightings ?? 0
    }
  })

  const totalPredictedBoardings = mergedStations.reduce((sum, item) => sum + item.predictedBoardings, 0)
  const totalPredictedAlightings = mergedStations.reduce((sum, item) => sum + item.predictedAlightings, 0)
  const gpsRaw = dataset.gpsAgg.get(`${routeId}:${date}`) ?? Array.from({ length: 24 }, () => ({ sum: 0, count: 0 }))
  const gpsHourlySpeed = gpsRaw.map(item => (item.count ? Number((item.sum / item.count).toFixed(1)) : null))
  const trend = buildTrend(od?.hourlyTraffic ?? createHourlyArray(), gpsHourlySpeed)
  const path = mergedStations
    .filter(item => item.longitude !== null && item.latitude !== null)
    .map(item => [item.longitude, item.latitude])

  return {
    route: {
      id: route.id,
      name: route.name,
      latestDate: date,
      direction: normalizedDirection,
      directionLabel: normalizedDirection === 1 ? '上行' : '下行',
      directions: route.directions,
      totalRecords: route.totalRecords
    },
    overview: {
      totalPredictedBoardings,
      totalPredictedAlightings,
      stationCount: mergedStations.length
    },
    stations: mergedStations,
    path,
    center: path[Math.floor(path.length / 2)] ?? null,
    hourlyTraffic: od?.hourlyTraffic ?? createHourlyArray(),
    trend,
    pie: [
      { name: '预测上车', value: totalPredictedBoardings, color: '#15d6ff' },
      { name: '预测下车', value: totalPredictedAlightings, color: '#835bff' }
    ],
    topStations: buildTopStations(mergedStations)
  }
}

app.get('/api/health', async (_request, response) => {
  try {
    if (!redisClient.isOpen) {
      await redisClient.connect()
    }
    const pong = await redisClient.ping()
    response.json({ ok: pong === 'PONG' })
  } catch (error) {
    response.status(500).json({ ok: false, message: error.message })
  }
})

app.get('/api/routes', async (_request, response) => {
  try {
    const dataset = await ensureDataset()
    response.json({ routes: dataset.routeList })
  } catch (error) {
    response.status(500).json({ message: error.message })
  }
})

app.get('/api/map/stations', async (_request, response) => {
  try {
    const dataset = await ensureDataset()
    const bounds = parseBounds(_request.query.bounds)
    const zoom = Number(_request.query.zoom ?? 0)
    response.json(buildStationMapPayload(dataset, bounds, zoom))
  } catch (error) {
    response.status(500).json({ message: error.message })
  }
})

app.get('/api/routes/:routeId/dashboard', async (request, response) => {
  try {
    const dataset = await ensureDataset()
    const { routeId } = request.params
    const direction = Number(request.query.direction ?? 1)
    const dashboard = getDashboard(dataset, routeId, direction)

    if (!dashboard) {
      response.status(404).json({ message: '未找到对应线路。' })
      return
    }

    response.json(dashboard)
  } catch (error) {
    response.status(500).json({ message: error.message })
  }
})

app.listen(port, async () => {
  console.log(`API server listening on http://127.0.0.1:${port}`)
  try {
    await ensureDataset()
    console.log('Redis dataset loaded.')
  } catch (error) {
    console.error('Failed to preload dataset:', error.message)
  }
})
