package com.odbus.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
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
import java.util.stream.Collectors;

@Service
public class TransitHotDataWarmupService {

    private static final String HOT_DATA_VERSION = "new_tables_v1";
    private static final long HOT_CACHE_CHECK_TTL_MILLIS = 60L * 60_000L;

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String hotPrefix;
    private final int hotDays;
    private volatile boolean hotCacheReady;
    private volatile long hotCacheCheckedAt;

    public TransitHotDataWarmupService(
            JdbcTemplate jdbcTemplate,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${app.redis.hot-prefix:cdbus:hot}") String hotPrefix,
            @Value("${app.redis.hot-days:7}") int hotDays
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.hotPrefix = hotPrefix;
        this.hotDays = Math.max(hotDays, 1);
    }

    public void ensureHotCache(boolean forceRebuild) {
        long now = System.currentTimeMillis();
        if (!forceRebuild && hotCacheReady && now - hotCacheCheckedAt <= HOT_CACHE_CHECK_TTL_MILLIS) {
            return;
        }

        List<String> hotDates = queryHotDates();
        if (hotDates.isEmpty()) {
            hotCacheReady = false;
            hotCacheCheckedAt = now;
            return;
        }

        boolean needFullRewrite = forceRebuild || !Boolean.TRUE.equals(redisTemplate.hasKey(routesIndexKey()));
        Set<String> targetDates = new LinkedHashSet<String>();
        for (String date : hotDates) {
            if (needFullRewrite || !Boolean.TRUE.equals(redisTemplate.hasKey(statusKey(date)))) {
                targetDates.add(date);
            }
        }
        if (targetDates.isEmpty() && !needFullRewrite) {
            hotCacheReady = true;
            hotCacheCheckedAt = now;
            return;
        }
        if (needFullRewrite) {
            targetDates.addAll(hotDates);
        }

        Map<String, StationMeta> stationById = queryStationMeta();
        Map<String, List<RouteStation>> routeStations = queryRouteStations(stationById);
        List<RouteScenario> scenarios = queryRouteScenarios(hotDates);
        Map<String, RouteMeta> routeMeta = buildRouteMeta(scenarios);
        Map<String, Map<String, FlowPair>> flowByScenario = queryStationFlows(targetDates);
        Map<String, int[]> hourlyByScenario = queryHourlyTraffic(targetDates);
        Map<String, double[]> speedByRouteDate = querySpeed(targetDates);

        writeJson(routesIndexKey(), routeMeta.values().stream()
                .sorted((left, right) -> Integer.compare(right.totalRecords, left.totalRecords))
                .map(RouteMeta::toMap)
                .collect(Collectors.toList()));

        List<ScenarioPayload> payloads = new ArrayList<ScenarioPayload>();
        for (RouteScenario scenario : scenarios) {
            if (!targetDates.contains(scenario.date)) {
                continue;
            }
            RouteMeta meta = routeMeta.get(scenario.routeId);
            if (meta == null) {
                continue;
            }
            List<RouteStation> stations = routeStations.getOrDefault(routeKey(scenario.routeId, scenario.direction), Collections.<RouteStation>emptyList());
            Map<String, FlowPair> flowPairs = flowByScenario.getOrDefault(scenario.cacheKey(), Collections.<String, FlowPair>emptyMap());
            int[] hourlyTraffic = hourlyByScenario.getOrDefault(scenario.cacheKey(), new int[24]);
            double[] hourlySpeed = speedByRouteDate.get(speedKey(scenario.routeId, scenario.date));
            payloads.add(new ScenarioPayload(
                    scenario.redisDashboardKey(hotPrefix),
                    buildDashboardPayload(meta, scenario, stations, flowPairs, hourlyTraffic, hourlySpeed)
            ));
        }

        redisTemplate.executePipelined(new RedisCallback<Object>() {
            @Override
            public Object doInRedis(RedisConnection connection) throws DataAccessException {
                for (ScenarioPayload payload : payloads) {
                    connection.set(bytes(payload.key), bytes(writeJson(payload.payload)));
                }
                return null;
            }
        });

        for (String date : targetDates) {
            long routeCount = scenarios.stream().filter(item -> date.equals(item.date)).map(item -> item.routeId).distinct().count();
            long scenarioCount = scenarios.stream().filter(item -> date.equals(item.date)).count();
            writeJson(statusKey(date), mapOf(
                    "date", date,
                    "computedAt", System.currentTimeMillis(),
                    "routeCount", routeCount,
                    "scenarioCount", scenarioCount,
                    "status", "done"
            ));
        }
        hotCacheReady = true;
        hotCacheCheckedAt = System.currentTimeMillis();
    }

    private List<String> queryHotDates() {
        String sql = "select biz_date from (" +
                " select distinct left(trade_time, 10) as biz_date" +
                " from new_od" +
                " where trade_time is not null" +
                "   and trade_time <> ''" +
                ") t order by biz_date desc limit ?";
        return jdbcTemplate.query(sql, new Object[]{hotDays}, (rs, rowNum) -> rs.getString("biz_date"));
    }

    private Map<String, StationMeta> queryStationMeta() {
        String sql = "select id, sname as name, lng as longitude, lat as latitude from new_station";
        Map<String, StationMeta> stationById = new HashMap<String, StationMeta>();
        jdbcTemplate.query(sql, rs -> {
            String id = rs.getString("id");
            if (blank(id)) {
                return;
            }
            Double lng = rs.getObject("longitude") == null ? null : rs.getDouble("longitude");
            Double lat = rs.getObject("latitude") == null ? null : rs.getDouble("latitude");
            stationById.put(id, new StationMeta(id, fallback(rs.getString("name"), id), lng, lat));
        });
        return stationById;
    }

    private Map<String, List<RouteStation>> queryRouteStations(Map<String, StationMeta> stationById) {
        String sql = "select line_id as route_id, direction, station_id, min(sno) as serial_number " +
                "from new_route_station " +
                "where line_id is not null and line_id <> '' and station_id is not null and station_id <> '' and direction is not null " +
                "group by line_id, direction, station_id";
        Map<String, List<RouteStation>> routeStations = new HashMap<String, List<RouteStation>>();
        jdbcTemplate.query(sql, rs -> {
            String routeId = rs.getString("route_id");
            int direction = rs.getInt("direction");
            String stationId = rs.getString("station_id");
            StationMeta meta = stationById.get(stationId);
            List<RouteStation> items = routeStations.computeIfAbsent(routeKey(routeId, direction), key -> new ArrayList<RouteStation>());
            items.add(new RouteStation(
                    stationId,
                    meta == null ? stationId : meta.name,
                    meta == null ? null : meta.lng,
                    meta == null ? null : meta.lat,
                    rs.getInt("serial_number")
            ));
        });
        routeStations.values().forEach(list -> list.sort(Comparator.comparingInt(item -> item.order)));
        return routeStations;
    }

    private List<RouteScenario> queryRouteScenarios(List<String> dates) {
        String sql = "select line_id as route_id, max(line_name) as line_name, is_up_down as direction, " +
                "left(trade_time, 10) as biz_date, count(*) as total_records " +
                "from new_od " +
                "where left(trade_time, 10) in (" + placeholders(dates.size()) + ") " +
                "group by line_id, is_up_down, left(trade_time, 10)";
        return jdbcTemplate.query(sql, dates.toArray(), (rs, rowNum) -> new RouteScenario(
                rs.getString("route_id"),
                fallback(rs.getString("line_name"), rs.getString("route_id")),
                rs.getInt("direction"),
                rs.getString("biz_date"),
                rs.getInt("total_records")
        ));
    }

    private Map<String, RouteMeta> buildRouteMeta(List<RouteScenario> scenarios) {
        Map<String, RouteMeta> routeMeta = new LinkedHashMap<String, RouteMeta>();
        for (RouteScenario scenario : scenarios) {
            RouteMeta meta = routeMeta.computeIfAbsent(scenario.routeId, key -> new RouteMeta(scenario.routeId, scenario.lineName));
            meta.name = fallback(meta.name, scenario.lineName);
            meta.latestDate = maxDate(meta.latestDate, scenario.date);
            meta.totalRecords += scenario.totalRecords;
            meta.directions.add(scenario.direction);
            if (!meta.availableDates.contains(scenario.date)) {
                meta.availableDates.add(scenario.date);
            }
        }
        routeMeta.values().forEach(RouteMeta::sortDates);
        return routeMeta;
    }

    private Map<String, Map<String, FlowPair>> queryStationFlows(Set<String> dates) {
        if (dates.isEmpty()) {
            return Collections.emptyMap();
        }
        String dateClause = placeholders(dates.size());
        Object[] params = dates.toArray();
        Map<String, Map<String, FlowPair>> flowByScenario = new HashMap<String, Map<String, FlowPair>>();

        String boardSql = "select line_id as route_id, is_up_down as direction, left(trade_time, 10) as biz_date, " +
                "origin_id as station_id, max(coalesce(origin, origin_id)) as station_name, count(*) as total " +
                "from new_od " +
                "where origin_id is not null and origin_id <> '' " +
                "and left(trade_time, 10) in (" + dateClause + ") " +
                "group by line_id, is_up_down, left(trade_time, 10), origin_id";
        jdbcTemplate.query(boardSql, params, rs -> {
            String routeId = rs.getString("route_id");
            int direction = rs.getInt("direction");
            String bizDate = rs.getString("biz_date");
            String stationId = rs.getString("station_id");
            String stationName = fallback(rs.getString("station_name"), stationId);
            String scenarioKey = routeKey(routeId, direction) + ":" + bizDate;
            Map<String, FlowPair> flow = flowByScenario.computeIfAbsent(scenarioKey, key -> new LinkedHashMap<String, FlowPair>());
            FlowPair pair = flow.computeIfAbsent(stationId, key -> new FlowPair(stationId, stationName));
            pair.boardings = rs.getInt("total");
        });

        String alightSql = "select line_id as route_id, is_up_down as direction, left(trade_time, 10) as biz_date, " +
                "destination_id as station_id, max(coalesce(destination, destination_id)) as station_name, count(*) as total " +
                "from new_od " +
                "where destination_id is not null and destination_id <> '' " +
                "and left(trade_time, 10) in (" + dateClause + ") " +
                "group by line_id, is_up_down, left(trade_time, 10), destination_id";
        jdbcTemplate.query(alightSql, params, rs -> {
            String routeId = rs.getString("route_id");
            int direction = rs.getInt("direction");
            String bizDate = rs.getString("biz_date");
            String stationId = rs.getString("station_id");
            String stationName = fallback(rs.getString("station_name"), stationId);
            String scenarioKey = routeKey(routeId, direction) + ":" + bizDate;
            Map<String, FlowPair> flow = flowByScenario.computeIfAbsent(scenarioKey, key -> new LinkedHashMap<String, FlowPair>());
            FlowPair pair = flow.computeIfAbsent(stationId, key -> new FlowPair(stationId, stationName));
            pair.alightings = rs.getInt("total");
        });

        return flowByScenario;
    }

    private Map<String, int[]> queryHourlyTraffic(Set<String> dates) {
        if (dates.isEmpty()) {
            return Collections.emptyMap();
        }
        String sql = "select line_id as route_id, is_up_down as direction, left(trade_time, 10) as biz_date, " +
                "cast(substring(trade_time, 12, 2) as unsigned) as biz_hour, count(*) as total " +
                "from new_od " +
                "where left(trade_time, 10) in (" + placeholders(dates.size()) + ") " +
                "and trade_time is not null and trade_time <> '' " +
                "group by line_id, is_up_down, left(trade_time, 10), cast(substring(trade_time, 12, 2) as unsigned)";
        Map<String, int[]> hourlyByScenario = new HashMap<String, int[]>();
        jdbcTemplate.query(sql, dates.toArray(), rs -> {
            int hour = rs.getInt("biz_hour");
            if (hour < 0 || hour > 23) {
                return;
            }
            String routeId = rs.getString("route_id");
            int direction = rs.getInt("direction");
            String bizDate = rs.getString("biz_date");
            String key = routeKey(routeId, direction) + ":" + bizDate;
            int[] hourly = hourlyByScenario.computeIfAbsent(key, item -> new int[24]);
            hourly[hour] = rs.getInt("total");
        });
        return hourlyByScenario;
    }

    private Map<String, double[]> querySpeed(Set<String> dates) {
        return Collections.emptyMap();
    }

    private Map<String, Object> buildDashboardPayload(RouteMeta routeMeta,
                                                      RouteScenario scenario,
                                                      List<RouteStation> stations,
                                                      Map<String, FlowPair> flowPairs,
                                                      int[] hourlyTraffic,
                                                      double[] hourlySpeed) {
        List<Map<String, Object>> stationPayload = new ArrayList<Map<String, Object>>();
        List<List<Double>> path = new ArrayList<List<Double>>();
        int totalBoardings = 0;
        int totalAlightings = 0;

        for (RouteStation station : stations) {
            FlowPair flow = flowPairs.get(station.id);
            int boardings = flow == null ? 0 : flow.boardings;
            int alightings = flow == null ? 0 : flow.alightings;
            totalBoardings += boardings;
            totalAlightings += alightings;

            stationPayload.add(mapOf(
                    "id", station.id,
                    "line", routeMeta.name,
                    "lineId", routeMeta.id,
                    "direction", directionLabel(scenario.direction),
                    "stationOrder", station.order,
                    "name", station.name,
                    "lng", station.lng,
                    "lat", station.lat,
                    "predictedBoardings", boardings,
                    "predictedAlightings", alightings,
                    "date", scenario.date,
                    "state", "MySQL 聚合后写入 Redis 热缓存"
            ));

            if (station.lng != null && station.lat != null) {
                path.add(Arrays.asList(station.lng, station.lat));
            }
        }

        Trend trend = buildTrend(hourlyTraffic, hourlySpeed);
        return mapOf(
                "line", routeMeta.name,
                "lineId", routeMeta.id,
                "lineName", routeMeta.name,
                "direction", directionLabel(scenario.direction),
                "date", scenario.date,
                "availableDates", routeMeta.availableDates,
                "state", totalBoardings >= totalAlightings ? "早高峰换乘增强" : "晚高峰回流增强",
                "activeMoment", inferActiveMoment(hourlyTraffic),
                "selectedStation", stationPayload.isEmpty() ? "" : stationPayload.get(0).get("name"),
                "summary", mapOf(
                        "predictedBoardings", totalBoardings,
                        "predictedAlightings", totalAlightings,
                        "stationCount", stationPayload.size()
                ),
                "trend", mapOf("moments", trend.labels, "speed", trend.speed, "congestion", trend.congestion),
                "hourlyTraffic", hourlyPayload(hourlyTraffic),
                "topStations", topStations(stationPayload),
                "mirrorComparison", stationPayload.stream().map(item -> mapOf(
                        "name", item.get("name"),
                        "predictedBoardings", item.get("predictedBoardings"),
                        "predictedAlightings", item.get("predictedAlightings")
                )).collect(Collectors.toList()),
                "odFlow", odFlow(stationPayload),
                "stations", stationPayload,
                "path", path
        );
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
        for (int index = 0; index < speed.size(); index++) {
            if (speed.get(index) == null) {
                speed.set(index, fallbackSpeed.get(index));
            }
        }
        return new Trend(labels, speed, congestion);
    }

    private List<Map<String, Object>> hourlyPayload(int[] hourlyTraffic) {
        List<Map<String, Object>> payload = new ArrayList<Map<String, Object>>();
        for (int hour = 0; hour < 24; hour++) {
            payload.add(mapOf("time", String.format("%02d:00", hour), "value", hourlyTraffic[hour]));
        }
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
        List<Map<String, Object>> boardTop = stations.stream()
                .sorted((left, right) -> Integer.compare(intValue(right, "predictedBoardings"), intValue(left, "predictedBoardings")))
                .limit(3)
                .collect(Collectors.toList());
        List<Map<String, Object>> alightTop = stations.stream()
                .sorted((left, right) -> Integer.compare(intValue(right, "predictedAlightings"), intValue(left, "predictedAlightings")))
                .limit(3)
                .collect(Collectors.toList());

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
        for (int i = 0; i < boardTop.size(); i++) {
            for (int j = 0; j < alightTop.size(); j++) {
                int value = Math.max((int) Math.round(
                        intValue(boardTop.get(i), "predictedBoardings") * 0.18
                                + intValue(alightTop.get(j), "predictedAlightings") * 0.11
                                - i * 18
                                - j * 14
                ), 1);
                links.add(mapOf(
                        "source", "source:" + boardTop.get(i).get("name"),
                        "target", "target:" + alightTop.get(j).get("name"),
                        "value", value
                ));
            }
        }
        return mapOf("nodes", nodes, "links", links);
    }

    private String routesIndexKey() {
        return versionedHotPrefix() + ":index:routes";
    }

    private String statusKey(String date) {
        return versionedHotPrefix() + ":status:" + date;
    }

    private String versionedHotPrefix() {
        return hotPrefix + ":" + HOT_DATA_VERSION;
    }

    private String routeKey(String routeId, int direction) {
        return routeId + ":" + direction;
    }

    private String speedKey(String routeId, String date) {
        return routeId + ":" + date;
    }

    private String directionLabel(int direction) {
        return direction == 2 ? "下行" : "上行";
    }

    private String placeholders(int size) {
        return String.join(",", Collections.nCopies(size, "?"));
    }

    private String fallback(String primary, String fallback) {
        return blank(primary) ? fallback : primary;
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private int score(Map<String, Object> item) {
        return intValue(item, "predictedBoardings") + intValue(item, "predictedAlightings");
    }

    private int intValue(Map<String, Object> item, String key) {
        return ((Number) item.get(key)).intValue();
    }

    private String maxDate(String left, String right) {
        return blank(left) ? right : (left.compareTo(right) >= 0 ? left : right);
    }

    private double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private String inferActiveMoment(int[] hourlyTraffic) {
        int bestHour = 0;
        int max = -1;
        for (int index = 0; index < hourlyTraffic.length; index++) {
            if (hourlyTraffic[index] > max) {
                max = hourlyTraffic[index];
                bestHour = index;
            }
        }
        return String.format("%02d:00", bestHour);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Redis 热缓存序列化失败", exception);
        }
    }

    private void writeJson(String key, Object value) {
        redisTemplate.opsForValue().set(key, writeJson(value));
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }

    private static class ScenarioPayload {
        private final String key;
        private final Map<String, Object> payload;

        private ScenarioPayload(String key, Map<String, Object> payload) {
            this.key = key;
            this.payload = payload;
        }
    }

    private static class RouteScenario {
        private final String routeId;
        private final String lineName;
        private final int direction;
        private final String date;
        private final int totalRecords;

        private RouteScenario(String routeId, String lineName, int direction, String date, int totalRecords) {
            this.routeId = routeId;
            this.lineName = lineName;
            this.direction = direction;
            this.date = date;
            this.totalRecords = totalRecords;
        }

        private String cacheKey() {
            return routeId + ":" + direction + ":" + date;
        }

        private String redisDashboardKey(String hotPrefix) {
            return hotPrefix + ":" + HOT_DATA_VERSION + ":dashboard:" + routeId + ":" + direction + ":" + date;
        }
    }

    private static class RouteMeta {
        private final String id;
        private String name;
        private String latestDate;
        private int totalRecords;
        private final Set<Integer> directions = new LinkedHashSet<Integer>();
        private final List<String> availableDates = new ArrayList<String>();

        private RouteMeta(String id, String name) {
            this.id = id;
            this.name = name;
        }

        private void sortDates() {
            availableDates.sort(Comparator.reverseOrder());
        }

        private Map<String, Object> toMap() {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", id);
            row.put("name", name);
            row.put("latestDate", latestDate);
            row.put("totalRecords", totalRecords);
            row.put("directions", new ArrayList<Integer>(directions));
            row.put("availableDates", availableDates);
            return row;
        }
    }

    private static class StationMeta {
        private final String id;
        private final String name;
        private final Double lng;
        private final Double lat;

        private StationMeta(String id, String name, Double lng, Double lat) {
            this.id = id;
            this.name = name;
            this.lng = lng;
            this.lat = lat;
        }
    }

    private static class RouteStation {
        private final String id;
        private final String name;
        private final Double lng;
        private final Double lat;
        private final int order;

        private RouteStation(String id, String name, Double lng, Double lat, int order) {
            this.id = id;
            this.name = name;
            this.lng = lng;
            this.lat = lat;
            this.order = order;
        }
    }

    private static class FlowPair {
        private final String id;
        private final String name;
        private int boardings;
        private int alightings;

        private FlowPair(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static class Trend {
        private final List<String> labels;
        private final List<Double> speed;
        private final List<Double> congestion;

        private Trend(List<String> labels, List<Double> speed, List<Double> congestion) {
            this.labels = labels;
            this.speed = speed;
            this.congestion = congestion;
        }
    }
}
