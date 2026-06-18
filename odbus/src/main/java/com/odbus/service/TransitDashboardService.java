package com.odbus.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
public class TransitDashboardService {

    private static final String HOT_DATA_VERSION = "new_tables_v1";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TransitHotDataWarmupService hotDataWarmupService;
    private final String keyPrefix;
    private final String hotPrefix;
    private final int scanCount;
    private final long cacheTtlMillis;
    private final Object snapshotLock = new Object();
    private final AtomicReference<Snapshot> snapshotRef = new AtomicReference<Snapshot>();
    private volatile CompletableFuture<Snapshot> loadingFuture;

    public TransitDashboardService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            TransitHotDataWarmupService hotDataWarmupService,
            @Value("${app.redis.key-prefix:cdbus}") String keyPrefix,
            @Value("${app.redis.hot-prefix:cdbus:hot}") String hotPrefix,
            @Value("${app.redis.scan-count:500}") int scanCount,
            @Value("${app.redis.cache-minutes:10}") long cacheMinutes
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.hotDataWarmupService = hotDataWarmupService;
        this.keyPrefix = keyPrefix;
        this.hotPrefix = hotPrefix;
        this.scanCount = Math.max(scanCount, 100);
        this.cacheTtlMillis = Duration.ofMinutes(Math.max(cacheMinutes, 1)).toMillis();
    }

    @PostConstruct
    public void warmup() {
        CompletableFuture.runAsync(() -> ensureSnapshot(false));
    }

    public Map<String, Object> health() {
        String pong = redisTemplate.execute(new RedisCallback<String>() {
            @Override
            public String doInRedis(RedisConnection connection) throws DataAccessException {
                return connection.ping();
            }
        });
        return mapOf("status", "ok", "redis", "PONG".equalsIgnoreCase(pong));
    }

    public List<Map<String, Object>> listLines() {
        return ensureSnapshot(false).routes.stream().map(route -> mapOf(
                "line", route.name,
                "lineId", route.id,
                "lineName", route.name,
                "latestDate", route.latestDate,
                "availableDates", route.availableDates,
                "directions", route.directionLabels()
        )).collect(Collectors.toList());
    }

    public List<Map<String, Object>> listRoutes() {
        return ensureSnapshot(false).routes.stream().map(route -> mapOf(
                "id", route.id,
                "name", route.name,
                "latestDate", route.latestDate,
                "availableDates", route.availableDates,
                "directions", route.directions.stream()
                        .sorted()
                        .map(value -> mapOf("value", value, "label", directionLabel(value)))
                        .collect(Collectors.toList()),
                "totalRecords", route.totalRecords
        )).collect(Collectors.toList());
    }

    public Map<String, Object> getDashboard(String line, String directionText, String date) {
        Snapshot snapshot = ensureSnapshot(false);
        Route route = snapshot.routeById.containsKey(line) ? snapshot.routeById.get(line) : snapshot.routeByName.get(line);
        if (route == null) {
            throw new IllegalArgumentException("未找到对应线路: " + line);
        }
        int direction = normalizeDirection(route, directionText);
        return buildDashboard(snapshot, route, direction, normalizeDate(route, date));
    }

    public Map<String, Object> getDashboardByRouteId(String routeId, Integer direction, String date) {
        Snapshot snapshot = ensureSnapshot(false);
        Route route = snapshot.routeById.get(routeId);
        if (route == null) {
            throw new IllegalArgumentException("未找到对应线路: " + routeId);
        }
        int normalizedDirection = direction == null ? route.firstDirection() : normalizeDirection(route, String.valueOf(direction));
        return buildDashboard(snapshot, route, normalizedDirection, normalizeDate(route, date));
    }

    public Map<String, Object> reloadDataset() {
        hotDataWarmupService.ensureHotCache(true);
        Snapshot snapshot = ensureSnapshot(true);
        return mapOf("ok", true, "routeCount", snapshot.routes.size(), "loadedAt", snapshot.loadedAt);
    }

    private Snapshot ensureSnapshot(boolean forceReload) {
        Snapshot snapshot = snapshotRef.get();
        long now = System.currentTimeMillis();
        if (!forceReload && snapshot != null && now - snapshot.loadedAt <= cacheTtlMillis) {
            return snapshot;
        }
        CompletableFuture<Snapshot> current = loadingFuture;
        if (!forceReload && current != null && !current.isDone()) {
            return current.join();
        }
        synchronized (snapshotLock) {
            snapshot = snapshotRef.get();
            now = System.currentTimeMillis();
            if (!forceReload && snapshot != null && now - snapshot.loadedAt <= cacheTtlMillis) {
                return snapshot;
            }
            if (loadingFuture == null || loadingFuture.isDone() || forceReload) {
                loadingFuture = CompletableFuture.supplyAsync(() -> loadSnapshot(forceReload));
            }
            return loadingFuture.join();
        }
    }

    private Snapshot loadSnapshot(boolean forceReload) {
        try {
            if (forceReload) {
                hotDataWarmupService.ensureHotCache(true);
            }
            Snapshot hotSnapshot = loadHotSnapshot();
            if (hotSnapshot != null) {
                snapshotRef.set(hotSnapshot);
                return hotSnapshot;
            }

            hotDataWarmupService.ensureHotCache(false);
            hotSnapshot = loadHotSnapshot();
            if (hotSnapshot != null) {
                snapshotRef.set(hotSnapshot);
                return hotSnapshot;
            }

            final Map<String, StationMeta> stationById = new HashMap<String, StationMeta>();
            final Map<String, List<RouteStation>> routeStations = new HashMap<String, List<RouteStation>>();
            final Map<String, RouteAcc> routeAcc = new HashMap<String, RouteAcc>();
            final Map<String, DashboardAcc> dashboards = new HashMap<String, DashboardAcc>();
            final Set<String> routeStationSeen = new LinkedHashSet<String>();
            final Set<String> odSeen = new LinkedHashSet<String>();

            scanHashes(pattern("new_station"), row -> {
                String stationId = row.get("id");
                if (blank(stationId)) return;
                stationById.put(stationId, new StationMeta(
                        stationId,
                        fallback(row.get("sname"), stationId),
                        toDouble(row.get("lng")),
                        toDouble(row.get("lat"))
                ));
            });

            scanHashes(pattern("new_route_station"), row -> {
                String routeId = row.get("line_id");
                String stationId = row.get("station_id");
                Integer direction = toIntOrNull(row.get("direction"));
                if (blank(routeId) || blank(stationId) || direction == null) return;
                String stationRelationKey = routeId + "|" + direction + "|" + stationId + "|" + toInt(row.get("sno"), 0);
                if (!routeStationSeen.add(stationRelationKey)) return;
                StationMeta meta = stationById.get(stationId);
                List<RouteStation> list = routeStations.computeIfAbsent(routeKey(routeId, direction), key -> new ArrayList<RouteStation>());
                list.add(new RouteStation(
                        stationId,
                        meta == null ? stationId : meta.name,
                        meta == null ? null : meta.lng,
                        meta == null ? null : meta.lat,
                        toInt(row.get("sno"), 0)
                ));
            });

            scanHashes(pattern("new_od"), row -> {
                String routeId = row.get("line_id");
                Integer direction = toIntOrNull(row.get("is_up_down"));
                String time = row.get("trade_time");
                String date = parseDate(time);
                if (blank(routeId) || direction == null || blank(date)) return;
                String odUniqueKey = routeId + "|" + direction + "|" + fallback(row.get("card_id"), "-") + "|" + fallback(time, "-") + "|" + fallback(row.get("origin_id"), "-") + "|" + fallback(row.get("destination_id"), "-");
                if (!odSeen.add(odUniqueKey)) return;

                String lineName = fallback(row.get("line_name"), routeId);
                RouteAcc acc = routeAcc.computeIfAbsent(routeId, key -> new RouteAcc(routeId, lineName));
                acc.name = fallback(acc.name, lineName);
                acc.latestDate = maxDate(acc.latestDate, date);
                acc.totalRecords += 1;
                acc.directions.add(direction);
                acc.availableDates.add(date);

                DashboardAcc dashboard = dashboards.computeIfAbsent(dashboardKey(routeId, direction, date), key -> new DashboardAcc());
                Integer hour = parseHour(time);
                if (hour != null && hour >= 0 && hour < 24) dashboard.hourlyTraffic[hour] += 1;

                String originId = row.get("origin_id");
                if (!blank(originId)) {
                    Flow flow = dashboard.stationFlow.computeIfAbsent(originId, key -> new Flow(originId, fallback(row.get("origin"), originId)));
                    flow.boardings += 1;
                }
                String destinationId = row.get("destination_id");
                if (!blank(destinationId)) {
                    Flow flow = dashboard.stationFlow.computeIfAbsent(destinationId, key -> new Flow(destinationId, fallback(row.get("destination"), destinationId)));
                    flow.alightings += 1;
                }
            });

            routeStations.values().forEach(list -> list.sort(Comparator.comparingInt(item -> item.order)));

            List<Route> routes = routeAcc.values().stream()
                    .map(RouteAcc::build)
                    .sorted((left, right) -> Integer.compare(right.totalRecords, left.totalRecords))
                    .collect(Collectors.toList());

            Map<String, Route> routeById = new HashMap<String, Route>();
            Map<String, Route> routeByName = new HashMap<String, Route>();
            routes.forEach(route -> {
                routeById.put(route.id, route);
                routeByName.put(route.name, route);
            });

            Map<String, double[]> avgSpeed = new HashMap<String, double[]>();

            Snapshot snapshot = new Snapshot();
            snapshot.loadedAt = System.currentTimeMillis();
            snapshot.routes = routes;
            snapshot.routeById = routeById;
            snapshot.routeByName = routeByName;
            snapshot.routeStations = routeStations;
            snapshot.dashboards = dashboards;
            snapshot.avgSpeed = avgSpeed;
            snapshot.dashboardPayloads = new HashMap<String, Map<String, Object>>();
            for (Route route : routes) {
                for (Integer direction : route.directions) {
                    List<RouteStation> stations = routeStations.getOrDefault(routeKey(route.id, direction), Collections.<RouteStation>emptyList());
                    for (String date : route.availableDates) {
                        DashboardAcc dashboard = dashboards.get(dashboardKey(route.id, direction, date));
                        int[] hourlyTraffic = dashboard == null ? new int[24] : dashboard.hourlyTraffic;
                        double[] hourlySpeed = avgSpeed.get(speedKey(route.id, date));
                        snapshot.dashboardPayloads.put(
                                dashboardKey(route.id, direction, date),
                                buildDashboardPayload(route, direction, date, stations, dashboard, hourlyTraffic, hourlySpeed)
                        );
                    }
                }
            }
            snapshotRef.set(snapshot);
            return snapshot;
        } finally {
            loadingFuture = null;
        }
    }

    private Map<String, Object> buildDashboard(Snapshot snapshot, Route route, int direction, String selectedDate) {
        Map<String, Object> cached = snapshot.dashboardPayloads.get(dashboardKey(route.id, direction, selectedDate));
        if (cached != null) {
            return cached;
        }

        List<RouteStation> stations = snapshot.routeStations.getOrDefault(routeKey(route.id, direction), Collections.<RouteStation>emptyList());
        DashboardAcc dashboard = snapshot.dashboards.get(dashboardKey(route.id, direction, selectedDate));
        int[] hourlyTraffic = dashboard == null ? new int[24] : dashboard.hourlyTraffic;
        double[] hourlySpeed = snapshot.avgSpeed.get(speedKey(route.id, selectedDate));
        return buildDashboardPayload(route, direction, selectedDate, stations, dashboard, hourlyTraffic, hourlySpeed);
    }

    private Map<String, Object> buildDashboardPayload(Route route,
                                                      int direction,
                                                      String selectedDate,
                                                      List<RouteStation> stations,
                                                      DashboardAcc dashboard,
                                                      int[] hourlyTraffic,
                                                      double[] hourlySpeed) {

        List<Map<String, Object>> stationPayload = new ArrayList<Map<String, Object>>();
        List<List<Double>> path = new ArrayList<List<Double>>();
        int totalBoardings = 0;
        int totalAlightings = 0;
        for (RouteStation station : stations) {
            Flow flow = dashboard == null ? null : dashboard.stationFlow.get(station.id);
            int boardings = flow == null ? 0 : flow.boardings;
            int alightings = flow == null ? 0 : flow.alightings;
            totalBoardings += boardings;
            totalAlightings += alightings;
            stationPayload.add(mapOf(
                    "id", station.id,
                    "line", route.name,
                    "lineId", route.id,
                    "direction", directionLabel(direction),
                    "stationOrder", station.order,
                    "name", station.name,
                    "lng", station.lng,
                    "lat", station.lat,
                    "predictedBoardings", boardings,
                    "predictedAlightings", alightings,
                    "date", selectedDate,
                    "state", "Redis OD 聚合结果"
            ));
            if (station.lng != null && station.lat != null) path.add(Arrays.asList(station.lng, station.lat));
        }

        Trend trend = buildTrend(hourlyTraffic, hourlySpeed);
        return mapOf(
                "line", route.name,
                "lineId", route.id,
                "lineName", route.name,
                "direction", directionLabel(direction),
                "date", selectedDate,
                "availableDates", route.availableDates,
                "state", totalBoardings >= totalAlightings ? "早高峰换乘增强" : "晚高峰回流增强",
                "activeMoment", inferActiveMoment(hourlyTraffic),
                "selectedStation", stationPayload.isEmpty() ? "" : stationPayload.get(0).get("name"),
                "summary", mapOf("predictedBoardings", totalBoardings, "predictedAlightings", totalAlightings, "stationCount", stationPayload.size()),
                "trend", mapOf("moments", trend.labels, "speed", trend.speed, "congestion", trend.congestion),
                "hourlyTraffic", hourlyPayload(hourlyTraffic),
                "topStations", topStations(stationPayload),
                "mirrorComparison", stationPayload.stream().map(item -> mapOf("name", item.get("name"), "predictedBoardings", item.get("predictedBoardings"), "predictedAlightings", item.get("predictedAlightings"))).collect(Collectors.toList()),
                "odFlow", odFlow(stationPayload),
                "stations", stationPayload,
                "path", path
        );
    }

    private void scanHashes(String pattern, HashRowConsumer consumer) {
        redisTemplate.execute(new RedisCallback<Object>() {
            @Override
            public Object doInRedis(RedisConnection connection) throws DataAccessException {
                ScanOptions options = ScanOptions.scanOptions().match(pattern).count(scanCount).build();
                List<byte[]> batch = new ArrayList<byte[]>(scanCount);
                try (Cursor<byte[]> cursor = connection.scan(options)) {
                    while (cursor.hasNext()) {
                        batch.add(cursor.next());
                        if (batch.size() >= scanCount) {
                            flushBatch(batch, consumer);
                            batch.clear();
                        }
                    }
                    if (!batch.isEmpty()) flushBatch(batch, consumer);
                } catch (Exception exception) {
                    throw new IllegalStateException("Redis scan failed: " + pattern, exception);
                }
                return null;
            }
        });
    }

    private void flushBatch(List<byte[]> batch, HashRowConsumer consumer) {
        List<byte[]> keys = new ArrayList<byte[]>(batch);
        List<Object> rows = redisTemplate.executePipelined(new RedisCallback<Object>() {
            @Override
            public Object doInRedis(RedisConnection connection) throws DataAccessException {
                for (byte[] key : keys) connection.hGetAll(key);
                return null;
            }
        });
        for (Object row : rows) {
            if (!(row instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<Object, Object> raw = (Map<Object, Object>) row;
            if (raw.isEmpty()) continue;
            Map<String, String> decoded = new HashMap<String, String>();
            for (Map.Entry<Object, Object> entry : raw.entrySet()) {
                decoded.put(asRedisString(entry.getKey()), asRedisString(entry.getValue()));
            }
            consumer.accept(decoded);
        }
    }

    private Snapshot loadHotSnapshot() {
        try {
            String routesJson = redisTemplate.opsForValue().get(versionedHotPrefix() + ":index:routes");
            if (blank(routesJson)) {
                return null;
            }

            List<Map<String, Object>> routeRows = objectMapper.readValue(routesJson, new TypeReference<List<Map<String, Object>>>() {});
            if (routeRows.isEmpty()) {
                return null;
            }

            Snapshot snapshot = new Snapshot();
            snapshot.loadedAt = System.currentTimeMillis();
            snapshot.routes = routeRows.stream().map(this::routeFromHotMap).collect(Collectors.toList());
            snapshot.routeById = new HashMap<String, Route>();
            snapshot.routeByName = new HashMap<String, Route>();
            snapshot.routeStations = Collections.emptyMap();
            snapshot.dashboards = Collections.emptyMap();
            snapshot.avgSpeed = Collections.emptyMap();
            snapshot.dashboardPayloads = new HashMap<String, Map<String, Object>>();

            List<String> redisKeys = new ArrayList<String>();
            List<String> scenarioKeys = new ArrayList<String>();
            for (Route route : snapshot.routes) {
                snapshot.routeById.put(route.id, route);
                snapshot.routeByName.put(route.name, route);
                for (Integer direction : route.directions) {
                    for (String date : route.availableDates) {
                        scenarioKeys.add(dashboardKey(route.id, direction, date));
                        redisKeys.add(versionedHotPrefix() + ":dashboard:" + route.id + ":" + direction + ":" + date);
                    }
                }
            }

            if (!redisKeys.isEmpty()) {
                List<String> values = redisTemplate.opsForValue().multiGet(redisKeys);
                if (values == null || values.size() != redisKeys.size()) {
                    return null;
                }
                for (int index = 0; index < values.size(); index++) {
                    String json = values.get(index);
                    if (blank(json)) {
                        return null;
                    }
                    snapshot.dashboardPayloads.put(
                            scenarioKeys.get(index),
                            objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {})
                    );
                }
            }
            return snapshot;
        } catch (Exception exception) {
            return null;
        }
    }

    private Route routeFromHotMap(Map<String, Object> row) {
        String id = String.valueOf(row.get("id"));
        String name = fallback(valueAsString(row.get("name")), id);
        String latestDate = valueAsString(row.get("latestDate"));
        int totalRecords = valueAsNumber(row.get("totalRecords"));

        Set<Integer> directions = new LinkedHashSet<Integer>();
        Object rawDirections = row.get("directions");
        if (rawDirections instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> items = (List<Object>) rawDirections;
            for (Object item : items) {
                Integer value = toIntOrNull(valueAsString(item));
                if (value != null) {
                    directions.add(value);
                }
            }
        }
        if (directions.isEmpty()) {
            directions.add(1);
        }

        List<String> dates = new ArrayList<String>();
        Object rawDates = row.get("availableDates");
        if (rawDates instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> items = (List<Object>) rawDates;
            for (Object item : items) {
                String value = valueAsString(item);
                if (!blank(value)) {
                    dates.add(value);
                }
            }
        }
        dates.sort(Comparator.reverseOrder());
        return new Route(id, name, latestDate, totalRecords, directions, dates);
    }

    private String asRedisString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[]) {
            return new String((byte[]) value, StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    private String valueAsString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int valueAsNumber(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : toInt(valueAsString(value), 0);
    }

    private Trend buildTrend(int[] hourlyTraffic, double[] hourlySpeed) {
        List<String> labels = Arrays.asList("00:00", "02:00", "04:00", "06:00", "08:00", "10:00", "12:00", "14:00", "16:00", "18:00", "20:00", "22:00", "24:00");
        List<Double> traffic = new ArrayList<Double>();
        List<Double> speed = new ArrayList<Double>();
        for (int hour = 0; hour < 24; hour += 2) {
            int next = Math.min(hour + 1, 23);
            double trafficValue = hourlyTraffic[hour] + hourlyTraffic[next];
            traffic.add(trafficValue);
            List<Double> speedValues = new ArrayList<Double>();
            if (hourlySpeed != null && !Double.isNaN(hourlySpeed[hour])) speedValues.add(hourlySpeed[hour]);
            if (hourlySpeed != null && !Double.isNaN(hourlySpeed[next])) speedValues.add(hourlySpeed[next]);
            speed.add(speedValues.isEmpty() ? null : round(speedValues.stream().mapToDouble(Double::doubleValue).average().orElse(0D)));
        }
        traffic.add(traffic.get(traffic.size() - 1));
        speed.add(speed.get(speed.size() - 1));
        double maxTraffic = Math.max(1D, traffic.stream().mapToDouble(Double::doubleValue).max().orElse(1D));
        List<Double> congestion = traffic.stream().map(value -> round(0.9 + value / maxTraffic * 0.9)).collect(Collectors.toList());
        List<Double> fallbackSpeed = traffic.stream().map(value -> round(52 - value / maxTraffic * 16)).collect(Collectors.toList());
        for (int index = 0; index < speed.size(); index++) if (speed.get(index) == null) speed.set(index, fallbackSpeed.get(index));
        return new Trend(labels, speed, congestion);
    }

    private List<Map<String, Object>> hourlyPayload(int[] hourlyTraffic) {
        List<Map<String, Object>> payload = new ArrayList<Map<String, Object>>();
        for (int hour = 0; hour < 24; hour++) payload.add(mapOf("time", String.format("%02d:00", hour), "value", hourlyTraffic[hour]));
        return payload;
    }

    private List<Map<String, Object>> topStations(List<Map<String, Object>> stations) {
        return stations.stream()
                .sorted((left, right) -> Integer.compare(score(right), score(left)))
                .limit(5)
                .map(item -> mapOf("name", item.get("name"), "value", score(item)))
                .collect(Collectors.toList());
    }

    private Map<String, Object> odFlow(List<Map<String, Object>> stations) {
        List<Map<String, Object>> boardTop = stations.stream().sorted((left, right) -> Integer.compare(intValue(right, "predictedBoardings"), intValue(left, "predictedBoardings"))).limit(3).collect(Collectors.toList());
        List<Map<String, Object>> alightTop = stations.stream().sorted((left, right) -> Integer.compare(intValue(right, "predictedAlightings"), intValue(left, "predictedAlightings"))).limit(3).collect(Collectors.toList());
        List<Map<String, Object>> nodes = new ArrayList<Map<String, Object>>();
        boardTop.forEach(item -> nodes.add(mapOf(
                "name", "source:" + item.get("name"),
                "displayName", item.get("name"),
                "group", "source"
        )));
        alightTop.forEach(item -> nodes.add(mapOf(
                "name", "target:" + item.get("name"),
                "displayName", item.get("name"),
                "group", "target"
        )));
        List<Map<String, Object>> links = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < boardTop.size(); i++) for (int j = 0; j < alightTop.size(); j++) {
            int value = Math.max((int) Math.round(intValue(boardTop.get(i), "predictedBoardings") * 0.18 + intValue(alightTop.get(j), "predictedAlightings") * 0.11 - i * 18 - j * 14), 1);
            links.add(mapOf(
                    "source", "source:" + boardTop.get(i).get("name"),
                    "target", "target:" + alightTop.get(j).get("name"),
                    "value", value
            ));
        }
        return mapOf("nodes", nodes, "links", links);
    }

    private int normalizeDirection(Route route, String directionText) {
        int target = "2".equals(directionText) || "下行".equals(directionText) ? 2 : 1;
        return route.directions.contains(target) ? target : route.firstDirection();
    }

    private String normalizeDate(Route route, String dateText) {
        if (!blank(dateText) && route.availableDates.contains(dateText)) {
            return dateText;
        }
        return route.latestDate;
    }

    private String inferActiveMoment(int[] hourlyTraffic) {
        int bestHour = 0;
        int max = -1;
        for (int index = 0; index < hourlyTraffic.length; index++) if (hourlyTraffic[index] > max) { max = hourlyTraffic[index]; bestHour = index; }
        return String.format("%02d:00", bestHour);
    }

    private String pattern(String table) { return keyPrefix + ":" + table + ":row:*"; }
    private String versionedHotPrefix() { return hotPrefix + ":" + HOT_DATA_VERSION; }
    private String routeKey(String routeId, int direction) { return routeId + ":" + direction; }
    private String dashboardKey(String routeId, int direction, String date) { return routeId + ":" + direction + ":" + date; }
    private String speedKey(String routeId, String date) { return routeId + ":" + date; }
    private String directionLabel(int direction) { return direction == 2 ? "下行" : "上行"; }
    private String fallback(String primary, String fallback) { return blank(primary) ? fallback : primary; }
    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private int score(Map<String, Object> item) { return intValue(item, "predictedBoardings") + intValue(item, "predictedAlightings"); }
    private int intValue(Map<String, Object> item, String key) { return ((Number) item.get(key)).intValue(); }
    private String parseDate(String value) { return blank(value) || value.length() < 10 ? null : value.substring(0, 10); }
    private Integer parseHour(String value) { try { return blank(value) || value.length() < 13 ? null : Integer.parseInt(value.substring(11, 13)); } catch (NumberFormatException exception) { return null; } }
    private Integer toIntOrNull(String value) { try { return blank(value) ? null : Integer.parseInt(value.trim()); } catch (NumberFormatException exception) { return null; } }
    private int toInt(String value, int fallback) { Integer parsed = toIntOrNull(value); return parsed == null ? fallback : parsed; }
    private Double toDouble(String value) { try { return blank(value) ? null : Double.parseDouble(value.trim()); } catch (NumberFormatException exception) { return null; } }
    private String maxDate(String left, String right) { return blank(left) ? right : (left.compareTo(right) >= 0 ? left : right); }
    private double round(double value) { return Math.round(value * 100.0D) / 100.0D; }
    private SpeedAcc[] newSpeedBuckets() { SpeedAcc[] buckets = new SpeedAcc[24]; for (int index = 0; index < 24; index++) buckets[index] = new SpeedAcc(); return buckets; }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) map.put(String.valueOf(values[index]), values[index + 1]);
        return map;
    }

    private interface HashRowConsumer { void accept(Map<String, String> row); }

    private static class Snapshot {
        private long loadedAt;
        private List<Route> routes;
        private Map<String, Route> routeById;
        private Map<String, Route> routeByName;
        private Map<String, List<RouteStation>> routeStations;
        private Map<String, DashboardAcc> dashboards;
        private Map<String, double[]> avgSpeed;
        private Map<String, Map<String, Object>> dashboardPayloads;
    }

    private static class RouteAcc {
        private final String id;
        private String name;
        private String latestDate;
        private int totalRecords;
        private final Set<Integer> directions = new LinkedHashSet<Integer>();
        private final Set<String> availableDates = new LinkedHashSet<String>();
        private RouteAcc(String id, String name) { this.id = id; this.name = name; }
        private Route build() {
            List<String> sortedDates = new ArrayList<String>(availableDates);
            sortedDates.sort(Comparator.reverseOrder());
            return new Route(
                    id,
                    blankStatic(name) ? id : name,
                    latestDate,
                    totalRecords,
                    directions.isEmpty() ? new LinkedHashSet<Integer>(Collections.singleton(1)) : new LinkedHashSet<Integer>(directions),
                    sortedDates
            );
        }
    }

    private static class Route {
        private final String id;
        private final String name;
        private final String latestDate;
        private final int totalRecords;
        private final Set<Integer> directions;
        private final List<String> availableDates;
        private Route(String id, String name, String latestDate, int totalRecords, Set<Integer> directions, List<String> availableDates) { this.id = id; this.name = name; this.latestDate = latestDate; this.totalRecords = totalRecords; this.directions = directions; this.availableDates = availableDates; }
        private List<String> directionLabels() { return directions.stream().sorted().map(value -> value == 2 ? "下行" : "上行").collect(Collectors.toList()); }
        private int firstDirection() { return directions.stream().sorted().findFirst().orElse(1); }
    }

    private static class StationMeta {
        private final String id;
        private final String name;
        private final Double lng;
        private final Double lat;
        private StationMeta(String id, String name, Double lng, Double lat) { this.id = id; this.name = name; this.lng = lng; this.lat = lat; }
    }

    private static class RouteStation {
        private final String id;
        private final String name;
        private final Double lng;
        private final Double lat;
        private final int order;
        private RouteStation(String id, String name, Double lng, Double lat, int order) { this.id = id; this.name = name; this.lng = lng; this.lat = lat; this.order = order; }
    }

    private static class DashboardAcc {
        private final int[] hourlyTraffic = new int[24];
        private final Map<String, Flow> stationFlow = new HashMap<String, Flow>();
    }

    private static class Flow {
        private final String id;
        private final String name;
        private int boardings;
        private int alightings;
        private Flow(String id, String name) { this.id = id; this.name = name; }
    }

    private static class SpeedAcc { private double sum; private int count; }

    private static class Trend {
        private final List<String> labels;
        private final List<Double> speed;
        private final List<Double> congestion;
        private Trend(List<String> labels, List<Double> speed, List<Double> congestion) { this.labels = labels; this.speed = speed; this.congestion = congestion; }
    }

    private static boolean blankStatic(String value) { return value == null || value.trim().isEmpty(); }
}
