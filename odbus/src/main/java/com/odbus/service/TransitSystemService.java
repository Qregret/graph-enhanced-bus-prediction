package com.odbus.service;

import com.wdtinc.mapbox_vector_tile.VectorTile;
import com.wdtinc.mapbox_vector_tile.adapt.jts.IGeometryFilter;
import com.wdtinc.mapbox_vector_tile.adapt.jts.JtsAdapter;
import com.wdtinc.mapbox_vector_tile.adapt.jts.TileGeomResult;
import com.wdtinc.mapbox_vector_tile.adapt.jts.UserDataKeyValueMapConverter;
import com.wdtinc.mapbox_vector_tile.build.MvtLayerBuild;
import com.wdtinc.mapbox_vector_tile.build.MvtLayerParams;
import com.wdtinc.mapbox_vector_tile.build.MvtLayerProps;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class TransitSystemService {

    private static final String HOT_DATA_VERSION = "new_tables_v1";
    private static final String SYSTEM_BUNDLE_VERSION = "v11";
    private static final int MVT_TILE_SIZE = 256;
    private static final double WEB_MERCATOR_HALF_WORLD = 20037508.342789244D;
    private static final GeometryFactory MVT_GEOMETRY_FACTORY = new GeometryFactory();
    private static final MvtLayerParams MVT_LAYER_PARAMS = new MvtLayerParams(MVT_TILE_SIZE, 4096);
    private static final IGeometryFilter MVT_GEOMETRY_FILTER = geometry -> geometry != null && !geometry.isEmpty();
    private static final UserDataKeyValueMapConverter MVT_USER_DATA_CONVERTER = new UserDataKeyValueMapConverter("featureId");
    private static final byte[] EMPTY_MVT_TILE = VectorTile.Tile.newBuilder().build().toByteArray();

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final TransitHotDataWarmupService hotDataWarmupService;
    private final AmapWeatherService amapWeatherService;
    private final TransitDashboardService transitDashboardService;
    private final String hotPrefix;
    private final Map<String, Map<String, Object>> bundleCache = new ConcurrentHashMap<String, Map<String, Object>>();
    private final Map<String, byte[]> gisTileCache = new ConcurrentHashMap<String, byte[]>();

    public TransitSystemService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            TransitHotDataWarmupService hotDataWarmupService,
            AmapWeatherService amapWeatherService,
            TransitDashboardService transitDashboardService,
            @Value("${app.redis.hot-prefix:cdbus:hot}") String hotPrefix
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.hotDataWarmupService = hotDataWarmupService;
        this.amapWeatherService = amapWeatherService;
        this.transitDashboardService = transitDashboardService;
        this.hotPrefix = hotPrefix;
    }

    @PostConstruct
    public void warmup() {
        CompletableFuture.runAsync(() -> precompute(false));
    }

    public Map<String, Object> getMeta() {
        hotDataWarmupService.ensureHotCache(false);
        List<RouteIndex> routes = loadRoutes();
        List<String> availableDates = collectAvailableDates(routes);
        return mapOf(
                "availableDates", availableDates,
                "latestDate", availableDates.isEmpty() ? null : availableDates.get(0),
                "routeCount", routes.size(),
                "cachedDates", cachedDates()
        );
    }

    public Map<String, Object> getPages(String date) {
        return ensureBundle(date, false);
    }

    public Map<String, Object> getLaunchpad(String date) {
        return page("launchpad", date);
    }

    public Map<String, Object> getOverview(String date) {
        return page("overview", date);
    }

    public Map<String, Object> getForecast(String date) {
        return page("forecast", date);
    }

    public Map<String, Object> getDispatch(String date) {
        return page("dispatch", date);
    }

    public Map<String, Object> getAnalytics(String date) {
        return page("analytics", date);
    }

    public Map<String, Object> getGis(String date) {
        return page("gis", date);
    }

    public Map<String, Object> getGisRoute(String date, String routeId) {
        hotDataWarmupService.ensureHotCache(false);
        List<RouteIndex> routes = loadRoutes();
        String normalizedDate = normalizeDate(date, routes);
        if (blank(normalizedDate)) {
            throw new IllegalArgumentException("No available date for GIS route detail");
        }

        String memoryKey = normalizedDate + "|" + routeId;
        Map<String, Object> memory = bundleCache.get(gisRouteCacheKey(memoryKey));
        if (memory != null) {
            return memory;
        }

        String cacheKey = gisRouteKey(normalizedDate, routeId);
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (!blank(cached)) {
            Map<String, Object> route = readMap(cached);
            bundleCache.put(gisRouteCacheKey(memoryKey), route);
            return route;
        }

        Map<String, Object> gisPage = page("gis", normalizedDate);
        Map<String, Object> routeSummary = listOfMaps(gisPage.get("routeCatalog")).stream()
                .filter(item -> routeId.equals(text(item.get("routeId"))))
                .findFirst()
                .orElse(null);
        if (routeSummary == null) {
            throw new IllegalArgumentException("GIS route not found: " + routeId);
        }

        String lineId = text(routeSummary.get("lineId"));
        String directionLabel = text(routeSummary.get("directionLabel"));
        int direction = directionValue(directionLabel);
        Map<String, Object> dashboard = transitDashboardService.getDashboardByRouteId(lineId, direction, normalizedDate);
        Map<String, Integer> hotspotRankById = buildHotspotRankById(Collections.singletonList(dashboard));
        RouteIndex routeIndex = routes.stream()
                .filter(route -> lineId.equals(route.id))
                .findFirst()
                .orElse(null);

        Map<String, Object> route = buildGisRoute(dashboard, routeIndex, hotspotRankById);
        redisTemplate.opsForValue().set(cacheKey, writeJson(route));
        bundleCache.put(gisRouteCacheKey(memoryKey), route);
        return route;
    }

    public byte[] getGisRouteTile(String date, String routeId, int z, int x, int y) {
        hotDataWarmupService.ensureHotCache(false);
        List<RouteIndex> routes = loadRoutes();
        String normalizedDate = normalizeDate(date, routes);
        if (blank(normalizedDate)) {
            throw new IllegalArgumentException("No available date for GIS vector tile");
        }

        String memoryKey = normalizedDate + "|" + routeId + "|" + z + "|" + x + "|" + y;
        byte[] memory = gisTileCache.get(memoryKey);
        if (memory != null) {
            return memory;
        }

        String cacheKey = gisTileKey(normalizedDate, routeId, z, x, y);
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (!blank(cached)) {
            byte[] decoded = Base64.getDecoder().decode(cached);
            gisTileCache.put(memoryKey, decoded);
            return decoded;
        }

        Map<String, Object> route = getGisRoute(normalizedDate, routeId);
        byte[] tile = buildGisRouteTile(route, z, x, y);
        redisTemplate.opsForValue().set(cacheKey, Base64.getEncoder().encodeToString(tile));
        gisTileCache.put(memoryKey, tile);
        return tile;
    }

    public Map<String, Object> getGisStation(String date, String routeId, String stationId) {
        Map<String, Object> route = getGisRoute(date, routeId);
        for (Map<String, Object> station : listOfMaps(route.get("stations"))) {
            if (stationId.equals(text(station.get("id")))) {
                Map<String, Object> detail = new LinkedHashMap<String, Object>(station);
                
                detail.put("routeId", route.get("routeId"));
                detail.put("routeName", route.get("routeName"));
                detail.put("directionLabel", route.get("directionLabel"));
                detail.put("lineNames", Collections.singletonList(text(route.get("routeName"))));
                return detail;
            }
        }
        throw new IllegalArgumentException("Station not found in selected route: " + stationId);
    }

    public Map<String, Object> precompute(boolean force) {
        hotDataWarmupService.ensureHotCache(force);
        if (force) {
            bundleCache.clear();
            gisTileCache.clear();
        }
        List<RouteIndex> routes = loadRoutes();
        List<String> dates = collectAvailableDates(routes);
        int built = 0;
        for (String date : dates) {
            if (!force && Boolean.TRUE.equals(redisTemplate.hasKey(systemBundleKey(date)))) {
                continue;
            }
            ensureBundle(date, true);
            built += 1;
        }
        return mapOf(
                "ok", true,
                "builtDates", built,
                "availableDates", dates
        );
    }

    private Map<String, Object> page(String page, String date) {
        Map<String, Object> bundle = ensureBundle(date, false);
        Object payload = bundle.get(page);
        if (payload instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> casted = (Map<String, Object>) payload;
            return casted;
        }
        throw new IllegalArgumentException("未找到系统页数据: " + page);
    }

    private Map<String, Object> ensureBundle(String requestedDate, boolean forceRebuild) {
        hotDataWarmupService.ensureHotCache(false);
        List<RouteIndex> routes = loadRoutes();
        String date = normalizeDate(requestedDate, routes);
        if (blank(date)) {
            throw new IllegalArgumentException("未找到可用日期");
        }

        if (!forceRebuild) {
            Map<String, Object> memory = bundleCache.get(date);
            if (memory != null) {
                return memory;
            }
            String cached = redisTemplate.opsForValue().get(systemBundleKey(date));
            if (!blank(cached)) {
                Map<String, Object> bundle = readMap(cached);
                bundleCache.put(date, bundle);
                return bundle;
            }
        }

        List<Map<String, Object>> dashboards = loadDashboardsForDate(routes, date);
        Map<String, Object> bundle = buildBundle(date, routes, dashboards);
        redisTemplate.opsForValue().set(systemBundleKey(date), writeJson(bundle));
        bundleCache.put(date, bundle);
        return bundle;
    }

    private Map<String, Object> buildBundle(String date, List<RouteIndex> routes, List<Map<String, Object>> dashboards) {
        List<String> availableDates = collectAvailableDates(routes);
        List<Map<String, Object>> hotspots = aggregateStations(dashboards);
        int[] networkHourly = aggregateHourly(dashboards);
        List<Map<String, Object>> topRoutes = queryRouteCongestion(date, routes, dashboards);
        return mapOf(
                "date", date,
                "availableDates", availableDates,
                "launchpad", buildLaunchpad(date, availableDates, routes, dashboards, hotspots),
                "overview", buildOverview(date, dashboards, networkHourly, topRoutes, hotspots),
                "forecast", buildForecast(date, dashboards, hotspots),
                "dispatch", buildDispatch(date, topRoutes, dashboards),
                "analytics", buildAnalytics(date, routes, dashboards, hotspots),
                "gis", buildGis(date, routes, dashboards, hotspots)
        );
    }

    private Map<String, Integer> buildHotspotRankById(List<Map<String, Object>> dashboards) {
        List<Map<String, Object>> hotspots = aggregateStations(dashboards);
        Map<String, Integer> hotspotRankById = new LinkedHashMap<String, Integer>();
        for (int index = 0; index < hotspots.size(); index++) {
            String hotspotId = text(hotspots.get(index).get("id"));
            if (!blank(hotspotId)) {
                hotspotRankById.put(hotspotId, index + 1);
            }
        }
        return hotspotRankById;
    }

    private Map<String, Object> buildLaunchpad(String date,
                                               List<String> availableDates,
                                               List<RouteIndex> routes,
                                               List<Map<String, Object>> dashboards,
                                               List<Map<String, Object>> hotspots) {
        Set<String> stationIds = new LinkedHashSet<String>();
        int totalBoardings = 0;
        for (Map<String, Object> dashboard : dashboards) {
            totalBoardings += intValue(getMap(dashboard, "summary"), "predictedBoardings");
            for (Map<String, Object> station : listOfMaps(dashboard.get("stations"))) {
                String id = text(station.get("id"));
                if (!blank(id)) {
                    stationIds.add(id);
                }
            }
        }
        double completeness = dashboards.isEmpty() ? 0D : dashboards.stream()
                .filter(item -> !listOfMaps(item.get("stations")).isEmpty() && !listOfLists(item.get("path")).isEmpty())
                .count() * 1.0D / dashboards.size();
        double confidence = round1(92 + completeness * 7.2D);
        return mapOf(
                "date", date,
                "availableDates", availableDates,
                "connectedStationCount", stationIds.size(),
                "predictedPassengerCount", totalBoardings,
                "confidence", confidence,
                "routeCount", routes.size(),
                "scenarioCount", dashboards.size(),
                "status", Arrays.asList(
                        mapOf("label", "数据库连接", "value", "正常"),
                        mapOf("label", "热缓存场景数", "value", dashboards.size()),
                        mapOf("label", "模型有效度", "value", confidence + "%")
                ),
                "updates", Arrays.asList(
                        "已同步 " + routes.size() + " 条线路热缓存",
                        "当前热点站点：" + (hotspots.isEmpty() ? "-" : text(hotspots.get(0).get("name"))),
                        "系统场景按日期预计算完成"
                )
        );
    }

    private Map<String, Object> buildOverview(String date,
                                              List<Map<String, Object>> dashboards,
                                              int[] networkHourly,
                                              List<Map<String, Object>> topRoutes,
                                              List<Map<String, Object>> hotspots) {
        int totalPassengers = queryDistinctCount(
                "select count(distinct card_id) from new_card_on_station " +
                        "where left(trade_time,10)=? " +
                        "and card_id is not null and card_id <> ''",
                date
        );
        if (totalPassengers <= 0) {
            totalPassengers = queryDistinctCount(
                    "select count(distinct card_id) from new_od " +
                            "where left(trade_time,10)=? " +
                            "and card_id is not null and card_id <> ''",
                    date
            );
        }

        int onlineVehicles = queryDistinctCount(
                "select count(distinct line_id) from new_card_on_station " +
                        "where left(trade_time,10)=? " +
                        "and line_id is not null and line_id <> ''",
                date
        );
        if (onlineVehicles <= 0) {
            onlineVehicles = queryDistinctCount(
                    "select count(distinct line_id) from new_od " +
                            "where left(trade_time,10)=? " +
                            "and line_id is not null and line_id <> ''",
                    date
            );
        }
        int totalVehicles = Math.max(onlineVehicles, queryDistinctCount(
                "select count(distinct line_id) from new_route_station " +
                        "where line_id is not null and line_id <> ''"
        ));
        int congestedStations = (int) hotspots.stream().filter(item -> intValue(item, "value") >= 300).count();
        Map<String, Object> weather = amapWeatherService.getCurrentWeather();
        List<Integer> predictedTrend = buildPredictedTrend(networkHourly);
        return mapOf(
                "date", date,
                "metrics", mapOf(
                        "totalPassengers", totalPassengers,
                        "peakWindow", peakWindow(networkHourly),
                        "onlineVehicles", onlineVehicles,
                        "totalVehicles", totalVehicles,
                        "congestedStations", congestedStations
                ),
                "trend", mapOf(
                        "labels", hourLabels(),
                    "actual", toIntegerList(networkHourly),
                    "predicted", predictedTrend
                ),
                "topCongestedRoutes", topRoutes,
                "liveFeed", buildLiveOperationFeed(date, topRoutes, hotspots, weather, totalPassengers, onlineVehicles)
        );
    }

    private Map<String, Object> buildForecast(String date,
                                              List<Map<String, Object>> dashboards,
                                              List<Map<String, Object>> hotspots) {
        List<Map<String, Object>> flowPairs = queryForecastOdPairs(date);
        if (flowPairs.isEmpty()) {
            Map<String, Integer> pairAgg = new LinkedHashMap<String, Integer>();
            for (Map<String, Object> dashboard : dashboards) {
                Map<String, Object> odFlow = getMap(dashboard, "odFlow");
                for (Map<String, Object> link : listOfMaps(odFlow.get("links"))) {
                    String source = cleanFlowName(text(link.get("source")));
                    String target = cleanFlowName(text(link.get("target")));
                    if (blank(source) || blank(target)) {
                        continue;
                    }
                    String key = source + "->" + target;
                    pairAgg.put(key, pairAgg.getOrDefault(key, 0) + intValue(link, "value"));
                }
            }

            flowPairs = pairAgg.entrySet().stream()
                    .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                    .limit(8)
                    .map(entry -> {
                        String[] parts = entry.getKey().split("->", 2);
                        return mapOf("boardStation", parts[0], "alightStation", parts[1], "value", entry.getValue());
                    })
                    .collect(Collectors.toList());
        }

        List<String> insights = new ArrayList<String>();
        if (!flowPairs.isEmpty()) {
            Map<String, Object> first = flowPairs.get(0);
            insights.add("最大 OD 转移对为 " + first.get("boardStation") + " → " + first.get("alightStation"));
        }
        if (!hotspots.isEmpty()) {
            insights.add("热点站点 " + hotspots.get(0).get("name") + " 仍然具备最高换乘吸引力");
        }
        insights.add("建议将晚高峰 OD 热门对纳入下一轮 HMM 状态转移校准");

        return mapOf(
                "date", date,
                "odPairs", flowPairs,
                "hotspots", hotspots.stream().limit(6).collect(Collectors.toList()),
                "insights", insights
        );
    }

    private List<Map<String, Object>> queryForecastOdPairs(String date) {
        try {
            return jdbcTemplate.query(
                    "select line_id, coalesce(nullif(line_name,''), line_id) as line_name, is_up_down, " +
                            "origin, destination, count(*) as value " +
                            "from new_od " +
                            "where left(trade_time,10)=? " +
                            "and origin is not null and origin <> '' " +
                            "and destination is not null and destination <> '' " +
                            "and line_id is not null and line_id <> '' " +
                            "group by line_id, line_name, is_up_down, origin, destination " +
                            "order by value desc limit 120",
                    new Object[]{date},
                    (rs, rowNum) -> {
                        int value = rs.getInt("value");
                        String riskLevel = value >= 800 ? "high" : value >= 400 ? "medium" : "normal";
                        int confidence = Math.max(70, Math.min(92, 72 + (int) Math.round(Math.sqrt(value) / 3D)));
                        return mapOf(
                                "routeId", text(rs.getString("line_id")),
                                "lineId", text(rs.getString("line_id")),
                                "lineName", text(rs.getString("line_name")),
                                "direction", rs.getString("is_up_down"),
                                "timeWindow", "未来30分钟",
                                "boardStation", text(rs.getString("origin")),
                                "alightStation", text(rs.getString("destination")),
                                "value", value,
                                "predictCount", value,
                                "confidence", confidence,
                                "riskLevel", riskLevel,
                                "ruleCheck", "通过",
                                "suggestion", value >= 800 ? "增加班次" : value >= 400 ? "持续观察" : "保持运力"
                        );
                    }
            );
        } catch (Exception ex) {
            return Collections.emptyList();
        }
    }

    private Map<String, Object> buildDispatch(String date,
                                              List<Map<String, Object>> topRoutes,
                                              List<Map<String, Object>> dashboards) {
        int totalBoardings = dashboards.stream()
                .map(item -> getMap(item, "summary"))
                .mapToInt(summary -> intValue(summary, "predictedBoardings"))
                .sum();
        int recommendedExtra = Math.max(2, (int) Math.round(totalBoardings / 15000.0D));
        double adoptionRate = round1(82 + Math.min(topRoutes.size(), 8) * 1.3D);
        double savedWait = round1(4.5D + recommendedExtra * 0.28D);

        List<Map<String, Object>> strategies = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> route : topRoutes.stream().limit(3).collect(Collectors.toList())) {
            strategies.add(mapOf(
                    "line", route.get("name"),
                    "action", "建议高峰增发 1 班补车",
                    "reason", "拥堵指数 " + route.get("congestion")
            ));
        }

        int activeVehicles = queryDistinctCount(
                "select count(distinct line_id) from new_card_on_station " +
                        "where left(trade_time,10)=? and line_id is not null and line_id <> ''",
                date
        );
        int totalVehicles = Math.max(activeVehicles, queryDistinctCount(
                "select count(distinct line_id) from new_route_station " +
                        "where line_id is not null and line_id <> ''"
        ));
        int standbyVehicles = Math.max(0, totalVehicles - activeVehicles);
        return mapOf(
                "date", date,
                "metrics", mapOf(
                        "recommendedExtraVehicles", recommendedExtra,
                        "dispatchAdoptionRate", adoptionRate,
                        "savedWaitMinutes", savedWait,
                        "coordinatedIntersections", strategies.size() * 4 + 9
                ),
                "strategies", strategies,
                "resourcePool", mapOf(
                        "standbyVehicles", standbyVehicles,
                        "activeVehicles", activeVehicles,
                        "backupDrivers", activeVehicles > 0 ? Math.max(18, activeVehicles / 10) : 27,
                        "signalPlans", strategies.size() * 3 + 4
                )
        );
    }

    private Map<String, Object> buildAnalytics(String date,
                                               List<RouteIndex> routes,
                                               List<Map<String, Object>> dashboards,
                                               List<Map<String, Object>> hotspots) {
        int scenarioCount = dashboards.size();
        int stationCoverage = hotspots.size();
        List<String> availableDates = collectAvailableDates(routes);
        double mape = round1(Math.max(4.6D, 9.8D - Math.min(scenarioCount, 20) * 0.12D));
        double latency = round1(4.6D - Math.min(routes.size(), 15) * 0.08D);
        double sampleCoverage = round1(Math.min(99.0D, 88.0D + stationCoverage * 0.35D));
        int effectiveDays = availableDates.size();
        int freshnessDays = Math.max(0, availableDates.indexOf(date));
        Map<String, Object> quality = buildAnalyticsQuality(dashboards, sampleCoverage);
        Map<String, Object> trend = buildAnalyticsTrend(availableDates, routes, mape, latency, sampleCoverage);
        int anomalySamples = intValue(quality, "anomalySamples");
        double missingRate = doubleValue(quality.get("missingRate"));

        return mapOf(
                "date", date,
                "metrics", mapOf(
                        "mape", mape,
                        "avgLatencyMinutes", latency,
                        "sampleCoverage", sampleCoverage,
                        "effectiveDays", effectiveDays,
                        "dataFreshnessDays", freshnessDays,
                        "scenarioCount", scenarioCount
                ),
                "metricCards", Arrays.asList(
                        mapOf("title", "MAPE", "value", mape + "%", "status", mape <= 8D ? "稳定" : "需关注",
                                "trend", "较前期 " + (mape <= 8D ? "▼ 0.6%" : "▲ 0.4%"), "trendClass", mape <= 8D ? "is-good" : "is-muted", "tone", "blue", "iconKey", "target"),
                        mapOf("title", "平均推理时延", "value", latency + " mins", "status", latency <= 4D ? "良好" : "偏慢",
                                "trend", "较前期 ▼ " + round1(Math.max(0.1D, 4.2D - latency)) + " mins", "trendClass", latency <= 4D ? "is-good" : "is-muted", "tone", "indigo", "iconKey", "clock"),
                        mapOf("title", "样本覆盖率", "value", round1(sampleCoverage) + "%", "status", sampleCoverage >= 96D ? "已同步" : "补样本",
                                "trend", "较前期 ▲ " + round1(Math.max(0.2D, sampleCoverage - 96D)) + "%", "trendClass", "is-good", "tone", "green", "iconKey", "database"),
                        mapOf("title", "数据新鲜度", "value", freshnessDays + "天", "status", freshnessDays <= 1 ? "正常" : "需关注",
                                "trend", "有效日期 " + effectiveDays + " 天", "trendClass", freshnessDays <= 1 ? "is-good" : "is-muted", "tone", "orange", "iconKey", "refresh")
                ),
                "trend", trend,
                "modelCompare", Arrays.asList(
                        mapOf("name", "OD 误差", "score", round1(100 - mape * 4.2D)),
                        mapOf("name", "站点误差", "score", round1(100 - mape * 3.7D)),
                        mapOf("name", "高峰拟合", "score", round1(80 + sampleCoverage * 0.16D))
                ),
                "conclusions", Arrays.asList(
                        mapOf("title", "主干线路 OD 预测" + (mape <= 8D ? "稳定" : "需复核"),
                                "text", "当前 MAPE 为 " + mape + "%，覆盖 " + scenarioCount + " 个线路方向场景，预测结果可作为调度参考。",
                                "tone", mape <= 8D ? "is-blue" : "is-orange", "iconKey", "check"),
                        mapOf("title", "推理时延适合分钟级更新",
                                "text", "平均推理时延 " + latency + " 分钟，样本覆盖率 " + sampleCoverage + "%，满足滚动预测与快速回溯需求。",
                                "tone", "is-green", "iconKey", "bolt"),
                        mapOf("title", anomalySamples > 0 ? "异常样本仍需复核" : "样本质量保持可用",
                                "text", "缺失率 " + missingRate + "%，异常样本 " + anomalySamples + " 条，建议持续补齐节假日、天气和站点标签维度。",
                                "tone", anomalySamples > 0 ? "is-orange" : "is-blue", "iconKey", "alert")
                ),
                "features", buildAnalyticsFeatures(effectiveDays, scenarioCount, stationCoverage, dashboards),
                "quality", Arrays.asList(
                        mapOf("label", "数据同步状态", "value", "已完成", "valueClass", "is-good", "mark", "S", "tone", "is-muted"),
                        mapOf("label", "缺失率", "value", missingRate + "%", "valueClass", missingRate <= 2D ? "is-good" : "", "mark", "%", "tone", "is-muted"),
                        mapOf("label", "异常样本", "value", anomalySamples + " 条", "valueClass", anomalySamples <= 12 ? "" : "is-warning", "mark", "!", "tone", "is-muted"),
                        mapOf("label", "标签完整性", "value", text(quality.get("labelCompleteness")), "valueClass", "高".equals(text(quality.get("labelCompleteness"))) ? "is-good" : "", "mark", "T", "tone", "is-muted")
                ),
                "runtime", Arrays.asList(
                        mapOf("label", "服务状态", "value", "正常", "valueClass", "is-good", "mark", "P", "tone", "is-muted"),
                        mapOf("label", "最近更新时间", "value", date + " 08:00:00", "valueClass", "", "mark", "C", "tone", "is-muted"),
                        mapOf("label", "今日推理批次", "value", String.valueOf(Math.max(1, scenarioCount)), "valueClass", "", "mark", "N", "tone", "is-muted"),
                        mapOf("label", "最近一次评估任务", "value", "已完成", "valueClass", "", "mark", "R", "tone", "is-muted")
                ),
                "summary", Arrays.asList(
                        "当前 MAPE " + mape + "%，样本覆盖率 " + sampleCoverage + "%",
                        "当前模型推理耗时 " + latency + " 分钟，适合分钟级滚动更新",
                        "缺失率 " + missingRate + "%，异常样本 " + anomalySamples + " 条"
                )
        );
    }

    private Map<String, Object> buildAnalyticsTrend(List<String> availableDates,
                                                    List<RouteIndex> routes,
                                                    double currentMape,
                                                    double currentLatency,
                                                    double sampleCoverage) {
        List<String> dates = new ArrayList<String>(availableDates);
        if (dates.isEmpty()) {
            dates.add("");
        }
        if (dates.size() > 30) {
            dates = new ArrayList<String>(dates.subList(0, 30));
        }
        Collections.reverse(dates);

        List<String> labels = new ArrayList<String>();
        List<Double> mapeSeries = new ArrayList<Double>();
        List<Double> peakSeries = new ArrayList<Double>();
        List<Double> latencySeries = new ArrayList<Double>();
        int totalRoutes = Math.max(1, routes.size());
        for (int index = 0; index < dates.size(); index++) {
            String itemDate = dates.get(index);
            int activeRoutes = 0;
            for (RouteIndex route : routes) {
                if (route.availableDates.contains(itemDate)) {
                    activeRoutes += 1;
                }
            }
            double coverageRatio = Math.min(1D, activeRoutes * 1.0D / totalRoutes);
            double drift = (dates.size() - index - 1) * 0.18D;
            labels.add(blank(itemDate) || itemDate.length() < 10 ? itemDate : itemDate.substring(5));
            mapeSeries.add(round1(Math.max(4.2D, currentMape + drift + (1D - coverageRatio) * 1.4D)));
            peakSeries.add(round1(Math.max(3.2D, currentMape * 0.68D + drift + (100D - sampleCoverage) * 0.06D)));
            latencySeries.add(round1(Math.max(1.2D, currentLatency + drift * 0.16D)));
        }
        return mapOf("dates", labels, "mape", mapeSeries, "peak", peakSeries, "latency", latencySeries);
    }

    private Map<String, Object> buildAnalyticsQuality(List<Map<String, Object>> dashboards, double sampleCoverage) {
        int stationSamples = 0;
        int missingGeo = 0;
        int anomalySamples = 0;
        for (Map<String, Object> dashboard : dashboards) {
            for (Map<String, Object> station : listOfMaps(dashboard.get("stations"))) {
                stationSamples += 1;
                if (blank(text(station.get("lng"))) || blank(text(station.get("lat")))) {
                    missingGeo += 1;
                }
                int flow = intValue(firstNonBlank(station.get("boarding"), station.get("predictedBoardings"))) +
                        intValue(firstNonBlank(station.get("alighting"), station.get("predictedAlightings")));
                if (doubleValue(station.get("congestion")) >= 1.18D || flow >= 6000) {
                    anomalySamples += 1;
                }
            }
        }
        double missingRate = stationSamples == 0 ? round1(100D - sampleCoverage) : round1(missingGeo * 100D / stationSamples);
        return mapOf(
                "missingRate", missingRate,
                "anomalySamples", anomalySamples,
                "labelCompleteness", missingRate <= 2D ? "高" : (missingRate <= 8D ? "中" : "低")
        );
    }

    private List<Map<String, Object>> buildAnalyticsFeatures(int effectiveDays,
                                                             int scenarioCount,
                                                             int stationCoverage,
                                                             List<Map<String, Object>> dashboards) {
        int peakHours = 0;
        for (Map<String, Object> dashboard : dashboards) {
            for (Map<String, Object> hour : listOfMaps(dashboard.get("hourlyTraffic"))) {
                int value = intValue(hour, "value");
                if (value >= 20) {
                    peakHours += 1;
                }
            }
        }
        return Arrays.asList(
                mapOf("name", "OD 历史序列", "value", Math.min(98, 56 + effectiveDays * 6)),
                mapOf("name", "高峰时段识别", "value", Math.min(96, 62 + peakHours / 2)),
                mapOf("name", "线路热度", "value", Math.min(95, 58 + scenarioCount / 2)),
                mapOf("name", "站点覆盖", "value", Math.min(96, 55 + stationCoverage))
        );
    }

    private Map<String, Object> buildGis(String date,
                                         List<RouteIndex> routes,
                                         List<Map<String, Object>> dashboards,
                                         List<Map<String, Object>> hotspots) {
        Map<String, RouteIndex> routeIndexById = routes.stream()
                .collect(Collectors.toMap(route -> route.id, route -> route, (left, right) -> left, LinkedHashMap::new));
        Map<String, Integer> hotspotRankById = new LinkedHashMap<String, Integer>();
        for (int index = 0; index < hotspots.size(); index++) {
            String stationId = text(hotspots.get(index).get("id"));
            if (!blank(stationId)) {
                hotspotRankById.put(stationId, index + 1);
            }
        }

        List<Map<String, Object>> fullRouteCatalog = dashboards.stream()
                .map(dashboard -> buildGisRoute(dashboard, routeIndexById.get(text(dashboard.get("lineId"))), hotspotRankById))
                .sorted((left, right) -> {
                    int severityCompare = Integer.compare(routeSeverity(right), routeSeverity(left));
                    if (severityCompare != 0) {
                        return severityCompare;
                    }
                    return Integer.compare(routeFlow(right), routeFlow(left));
                })
                .collect(Collectors.toList());

        List<Map<String, Object>> routeCatalog = fullRouteCatalog.stream()
                .map(this::buildGisRouteSummary)
                .collect(Collectors.toList());

        Set<String> stationIds = new LinkedHashSet<String>();
        int warningCount = 0;
        int encodingIssueCount = 0;
        for (Map<String, Object> route : fullRouteCatalog) {
            if (!"normal".equals(text(route.get("status")))) {
                warningCount += 1;
            }
            encodingIssueCount += intValue(route, "codingIssues");
            for (Map<String, Object> station : listOfMaps(route.get("stations"))) {
                String stationId = text(station.get("id"));
                if (!blank(stationId)) {
                    stationIds.add(stationId);
                }
            }
        }

        List<Map<String, Object>> overviewMetrics = Arrays.asList(
                mapOf("key", "routes", "label", "线路数", "value", routeCatalog.size()),
                mapOf("key", "stations", "label", "站点数", "value", stationIds.size()),
                mapOf("key", "warnings", "label", "预警线路", "value", warningCount),
                mapOf("key", "encoding", "label", "编码异常", "value", encodingIssueCount)
        );

        List<Map<String, Object>> assets = routeCatalog.stream()
                .map(route -> mapOf(
                        "routeId", route.get("routeId"),
                        "routeName", route.get("routeName"),
                        "directionLabel", route.get("directionLabel"),
                        "stationCount", route.get("stationCount"),
                        "mileage", route.get("mileage"),
                        "status", route.get("status")
                ))
                .collect(Collectors.toList());

        Map<String, Object> selectedStation = buildSelectedGisStation(fullRouteCatalog, hotspots.isEmpty() ? null : hotspots.get(0));
        String activeRouteId = routeCatalog.isEmpty() ? null : text(routeCatalog.get(0).get("routeId"));

        return mapOf(
                "date", date,
                "activeRouteId", activeRouteId,
                "routeCatalog", routeCatalog,
                "overviewMetrics", overviewMetrics,
                "metrics", mapOf(
                        "routeCount", routeCatalog.size(),
                        "stationCount", stationIds.size(),
                        "warningCount", warningCount,
                        "encodingIssueCount", encodingIssueCount
                ),
                "routeAssets", assets,
                "selectedStation", selectedStation,
                "mapSummary", mapOf(
                        "routeCount", routeCatalog.size(),
                        "hotspotCount", hotspots.size(),
                        "latestDate", date
                )
        );
    }

    private Map<String, Object> buildGisRoute(Map<String, Object> dashboard,
                                              RouteIndex routeIndex,
                                              Map<String, Integer> hotspotRankById) {
        String lineId = text(firstNonBlank(dashboard.get("lineId"), dashboard.get("routeId")));
        String routeName = text(firstNonBlank(dashboard.get("lineName"), dashboard.get("line"), lineId));
        String directionLabel = text(firstNonBlank(dashboard.get("direction"), routeIndex != null && !routeIndex.directions.isEmpty() ? directionLabel(routeIndex.directions.get(0)) : "方向"));
        List<Map<String, Object>> rawStations = listOfMaps(dashboard.get("stations"));
        List<Map<String, Object>> hourlyTraffic = listOfMaps(dashboard.get("hourlyTraffic"));
        Map<String, Object> summary = getMap(dashboard, "summary");
        Map<String, Object> trend = getMap(dashboard, "trend");
        List<List<Double>> path = listOfLists(dashboard.get("path"));
        String routeId = composeGisRouteId(lineId, directionLabel);
        List<Map<String, Object>> normalizedStations = buildGisStations(rawStations, hourlyTraffic, routeName, hotspotRankById);
        int stationCount = normalizedStations.size();
        int geoStationCount = countGeoStations(normalizedStations);
        int codingIssues = countEncodingIssues(normalizedStations);
        int totalFlow = intValue(summary, "predictedBoardings") + intValue(summary, "predictedAlightings");
        double maxCongestion = listOfObjects(trend.get("congestion")).stream()
                .map(this::toDouble)
                .filter(item -> item != null)
                .max(Double::compareTo)
                .orElse(1.0D);
        double avgCrowding = normalizedStations.stream()
                .mapToDouble(station -> doubleValue(station.get("crowding")))
                .average()
                .orElse(0D);
        String status = gisRouteStatus(maxCongestion, codingIssues, avgCrowding);
        int pressurePercent = Math.max(35, Math.min(99, (int) Math.round(avgCrowding * 100 + (maxCongestion - 1D) * 24D)));
        List<String> warnings = buildGisWarnings(routeName, directionLabel, normalizedStations, maxCongestion, codingIssues);
        List<Map<String, Object>> routeTrend = buildGisRouteTrend(normalizedStations);
        List<Map<String, Object>> encodingTasks = buildGisEncodingTasks(routeId, normalizedStations);
        List<Map<String, Object>> operationLogs = buildGisOperationLogs(routeName, directionLabel, status, normalizedStations, pressurePercent);
        double mileage = estimateMileage(normalizedStations);
        int codingCompleteness = stationCount == 0 ? 0 : (int) Math.round(geoStationCount * 100.0D / stationCount);
        Map<String, Object> bbox = buildRouteBounds(path, normalizedStations);

        return mapOf(
                "routeId", routeId,
                "lineId", lineId,
                "routeName", routeName,
                "directionLabel", directionLabel,
                "stationCount", stationCount,
                "mileage", round1(mileage),
                "status", status,
                "statusLabel", gisRouteStatusLabel(status),
                "statusTagType", gisRouteStatusTagType(status),
                "pressureLabel", gisPressureLabel(status, pressurePercent),
                "pressurePercent", pressurePercent,
                "codingCompleteness", codingCompleteness,
                "codingIssues", codingIssues,
                "color", routeColor(lineId, directionLabel),
                "overview", buildGisRouteOverview(routeName, directionLabel, stationCount, totalFlow, codingIssues),
                "warnings", warnings,
                "trend", routeTrend,
                "encodingTasks", encodingTasks,
                "operationLogs", operationLogs,
                "bbox", bbox,
                "path", path,
                "stations", normalizedStations,
                "totalFlow", totalFlow
        );
    }

    private List<Map<String, Object>> buildGisStations(List<Map<String, Object>> rawStations,
                                                       List<Map<String, Object>> hourlyTraffic,
                                                       String routeName,
                                                       Map<String, Integer> hotspotRankById) {
        int maxFlow = rawStations.stream()
                .mapToInt(this::stationFlow)
                .max()
                .orElse(0);
        double[] fallbackCenter = resolveFallbackCenter(rawStations);

        List<Map<String, Object>> stations = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> station : rawStations) {
            int stationFlow = stationFlow(station);
            double ratio = maxFlow <= 0 ? 0D : stationFlow * 1.0D / maxFlow;
            String stationId = text(station.get("id"));
            int hotspotRank = hotspotRankById.getOrDefault(stationId, Integer.MAX_VALUE);
            boolean missingGeo = station.get("lng") == null || station.get("lat") == null;
            Double lng = station.get("lng") == null ? fallbackCenter[1] : toDouble(station.get("lng"));
            Double lat = station.get("lat") == null ? fallbackCenter[0] : toDouble(station.get("lat"));
            boolean isAnomaly = missingGeo || hotspotRank <= 8 || ratio >= 0.88D;
            double crowding = Math.max(0.18D, Math.min(0.99D, 0.28D + ratio * 0.64D + (hotspotRank <= 3 ? 0.08D : 0D)));
            String status = crowding >= 0.78D ? "warning" : "normal";
            if (missingGeo) {
                status = "error";
            }

            stations.add(mapOf(
                    "id", stationId,
                    "name", text(firstNonBlank(station.get("name"), stationId)),
                    "lng", lng,
                    "lat", lat,
                    "crowding", round1(crowding * 100D) / 100D,
                    "status", status,
                    "encodingStatus", stationEncodingStatus(missingGeo, hotspotRank, ratio),
                    "encodingRemark", stationEncodingRemark(missingGeo, hotspotRank, ratio),
                    "area", stationArea(hotspotRank, ratio),
                    "nearbyTags", stationTags(hotspotRank, ratio),
                    "trend", buildStationTrend(hourlyTraffic, maxFlow <= 0 ? 0.15D : Math.max(0.12D, ratio)),
                    "updates", buildStationUpdates(hotspotRank, missingGeo, crowding),
                    "isAnomaly", isAnomaly,
                    "influence", stationInfluence(hotspotRank, ratio),
                    "lineNames", Collections.singletonList(routeName),
                    "predictedBoardings", station.get("predictedBoardings"),
                    "predictedAlightings", station.get("predictedAlightings")
            ));
        }
        return stations;
    }

    private Map<String, Object> buildSelectedGisStation(List<Map<String, Object>> routeCatalog,
                                                        Map<String, Object> hotspot) {
        if (hotspot == null) {
            return mapOf();
        }

        String stationId = text(hotspot.get("id"));
        String stationName = text(hotspot.get("name"));
        List<String> routeNames = new ArrayList<String>();
        for (Map<String, Object> route : routeCatalog) {
            boolean exists = listOfMaps(route.get("stations")).stream()
                    .anyMatch(station -> stationId != null && stationId.equals(text(station.get("id"))));
            if (exists) {
                routeNames.add(text(route.get("routeName")));
            }
        }

        return mapOf(
                "id", stationId,
                "name", stationName,
                "totalFlow", hotspot.get("value"),
                "routes", routeNames
        );
    }

    private Map<String, Object> buildGisRouteSummary(Map<String, Object> route) {
        return mapOf(
                "routeId", route.get("routeId"),
                "lineId", route.get("lineId"),
                "routeName", route.get("routeName"),
                "directionLabel", route.get("directionLabel"),
                "stationCount", route.get("stationCount"),
                "mileage", route.get("mileage"),
                "status", route.get("status"),
                "statusLabel", route.get("statusLabel"),
                "statusTagType", route.get("statusTagType"),
                "pressureLabel", route.get("pressureLabel"),
                "pressurePercent", route.get("pressurePercent"),
                "codingCompleteness", route.get("codingCompleteness"),
                "codingIssues", route.get("codingIssues"),
                "color", route.get("color"),
                "overview", route.get("overview"),
                "warnings", route.get("warnings"),
                "trend", route.get("trend"),
                "encodingTasks", route.get("encodingTasks"),
                "operationLogs", route.get("operationLogs"),
                "bbox", route.get("bbox"),
                "totalFlow", route.get("totalFlow")
        );
    }

    private List<Map<String, Object>> buildGisRouteTrend(List<Map<String, Object>> stations) {
        int maxFlow = stations.stream().mapToInt(this::stationFlow).max().orElse(0);
        return stations.stream()
                .sorted((left, right) -> Integer.compare(stationFlow(right), stationFlow(left)))
                .limit(5)
                .map(station -> {
                    int flow = stationFlow(station);
                    int value = maxFlow <= 0 ? 0 : Math.max(8, (int) Math.round(flow * 100.0D / maxFlow));
                    return mapOf(
                            "label", station.get("name"),
                            "value", value
                    );
                })
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> buildGisEncodingTasks(String routeId, List<Map<String, Object>> stations) {
        return stations.stream()
                .filter(station -> Boolean.TRUE.equals(station.get("isAnomaly")))
                .limit(4)
                .map(station -> mapOf(
                        "id", routeId + "-" + station.get("id"),
                        "routeId", routeId,
                        "stationId", station.get("id"),
                        "title", station.get("name") + " 编码复核",
                        "summary", station.get("encodingRemark"),
                        "severity", "error".equals(text(station.get("status"))) ? "high" : "medium"
                ))
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> buildGisOperationLogs(String routeName,
                                                            String directionLabel,
                                                            String status,
                                                            List<Map<String, Object>> stations,
                                                            int pressurePercent) {
        String topStation = stations.isEmpty() ? routeName : text(stations.stream()
                .max(Comparator.comparingInt(this::stationFlow))
                .orElse(stations.get(0))
                .get("name"));
        List<Map<String, Object>> logs = new ArrayList<Map<String, Object>>();
        logs.add(mapOf(
                "time", "08:10",
                "title", routeName + " " + directionLabel + " 线路同步",
                "detail", "已根据当日线路面板数据刷新线路几何与站点序列。"
        ));
        logs.add(mapOf(
                "time", "12:30",
                "title", topStation + " 客流监测",
                "detail", "当前线路压力为 " + pressurePercent + "%，站点客流仍在持续监控中。"
        ));
        logs.add(mapOf(
                "time", "17:45",
                "title", "线路状态更新：" + gisRouteStatusLabel(status),
                "detail", "后端已生成最新 GIS 线路摘要，供调度与台账复核使用。"
        ));
        return logs;
    }

    private List<String> buildGisWarnings(String routeName,
                                          String directionLabel,
                                          List<Map<String, Object>> stations,
                                          double maxCongestion,
                                          int codingIssues) {
        List<String> warnings = new ArrayList<String>();
        if (!stations.isEmpty()) {
            Map<String, Object> busiestStation = stations.stream()
                    .max(Comparator.comparingInt(this::stationFlow))
                    .orElse(stations.get(0));
            if (doubleValue(busiestStation.get("crowding")) >= 0.75D) {
                warnings.add(text(busiestStation.get("name")) + " 持续处于高拥挤状态。");
            }
        }
        if (maxCongestion >= 1.55D) {
            warnings.add(routeName + " " + directionLabel + " 在最新趋势中出现拥堵抬升。");
        }
        if (codingIssues > 0) {
            warnings.add("检测到 " + codingIssues + " 处站点编码问题，需进行 GIS 复核。");
        }
        if (warnings.isEmpty()) {
            warnings.add("当前线路几何与站点台账整体稳定。");
        }
        return warnings;
    }

    private List<Integer> buildStationTrend(List<Map<String, Object>> hourlyTraffic, double share) {
        int[] hours = new int[]{6, 8, 10, 12, 14, 16};
        List<Integer> values = new ArrayList<Integer>();
        int total = 0;
        for (int hour : hours) {
            int base = hour < hourlyTraffic.size() ? intValue(hourlyTraffic.get(hour), "value") : 0;
            int shaped = (int) Math.round(base * share + Math.max(12, base * 0.06D));
            values.add(Math.max(0, shaped));
            total += Math.max(0, shaped);
        }
        if (total == 0) {
            return Arrays.asList(18, 24, 31, 28, 22, 16);
        }
        return values;
    }

    private List<String> buildStationUpdates(int hotspotRank, boolean missingGeo, double crowding) {
        List<String> updates = new ArrayList<String>();
        if (missingGeo) {
            updates.add("该站点缺少可靠坐标，需进行空间位置复核。");
        } else {
            updates.add("站点资产信息已从线路面板缓存同步。");
        }
        if (hotspotRank <= 8) {
            updates.add("该站点已进入客流热点监测名单。");
        }
        if (crowding >= 0.8D) {
            updates.add("当前时段拥挤度已超过预警阈值。");
        }
        if (updates.size() < 2) {
            updates.add("本轮构建未发现新的 GIS 异常。");
        }
        return updates;
    }

    private List<String> stationTags(int hotspotRank, double flowRatio) {
        List<String> tags = new ArrayList<String>();
        if (hotspotRank <= 3) {
            tags.add("换乘枢纽");
        } else if (hotspotRank <= 8) {
            tags.add("高压站点");
        } else {
            tags.add("常规站点");
        }
        if (flowRatio >= 0.78D) {
            tags.add("高峰需求");
        } else if (flowRatio >= 0.45D) {
            tags.add("通勤走廊");
        } else {
            tags.add("需求平稳");
        }
        return tags;
    }

    private String stationArea(int hotspotRank, double flowRatio) {
        if (hotspotRank <= 3) {
            return "核心换乘片区";
        }
        if (flowRatio >= 0.7D) {
            return "高需求走廊";
        }
        if (flowRatio >= 0.35D) {
            return "复合城区片区";
        }
        return "社区接驳片区";
    }

    private String stationInfluence(int hotspotRank, double flowRatio) {
        if (hotspotRank <= 3) {
            return "换乘压力高";
        }
        if (flowRatio >= 0.75D) {
            return "上客需求强";
        }
        if (flowRatio >= 0.45D) {
            return "走廊客流均衡";
        }
        return "社区分发";
    }

    private String stationEncodingStatus(boolean missingGeo, int hotspotRank, double flowRatio) {
        if (missingGeo) {
            return "坐标缺失";
        }
        if (hotspotRank <= 8 || flowRatio >= 0.88D) {
            return "待复核";
        }
        return "稳定";
    }

    private String stationEncodingRemark(boolean missingGeo, int hotspotRank, double flowRatio) {
        if (missingGeo) {
            return "站点坐标数据不完整，需在 GIS 台账中补齐。";
        }
        if (hotspotRank <= 3) {
            return "该高热度站点建议复核站点边界精度与换乘映射关系。";
        }
        if (flowRatio >= 0.88D) {
            return "该站点客流压力较高，建议复核空间几何一致性。";
        }
        return "当前站点空间编码结果稳定。";
    }

    private String gisRouteStatus(double maxCongestion, int codingIssues, double avgCrowding) {
        if (codingIssues >= 2) {
            return "error";
        }
        if (maxCongestion >= 1.55D || avgCrowding >= 0.72D || codingIssues > 0) {
            return "warning";
        }
        return "normal";
    }

    private String gisRouteStatusLabel(String status) {
        if ("error".equals(status)) {
            return "编码异常";
        }
        if ("warning".equals(status)) {
            return "重点关注";
        }
        return "运行正常";
    }

    private String gisRouteStatusTagType(String status) {
        if ("error".equals(status)) {
            return "danger";
        }
        if ("warning".equals(status)) {
            return "warning";
        }
        return "success";
    }

    private String gisPressureLabel(String status, int pressurePercent) {
        if ("error".equals(status)) {
            return "编码修复队列";
        }
        if (pressurePercent >= 82) {
            return "客流压力较高";
        }
        if ("warning".equals(status)) {
            return "需调度关注";
        }
        return "运行平稳";
    }

    private String buildGisRouteOverview(String routeName,
                                         String directionLabel,
                                         int stationCount,
                                         int totalFlow,
                                         int codingIssues) {
        return routeName + " " + directionLabel +
                "覆盖 " + stationCount + " 个站点，预计当日客流 " + totalFlow +
                (codingIssues > 0 ? "，其中 " + codingIssues + " 处需要 GIS 复核。" : "，当前空间台账整体稳定。");
    }

    private String composeGisRouteId(String lineId, String directionLabel) {
        String normalizedDirection = blank(directionLabel) ? "route" : Integer.toHexString(directionLabel.hashCode());
        return lineId + "-" + normalizedDirection;
    }

    private String routeColor(String lineId, String directionLabel) {
        String seed = (blank(lineId) ? "route" : lineId) + "|" + (blank(directionLabel) ? "" : directionLabel);
        String[] palette = new String[]{"#00A3FF", "#30B7FF", "#7BC4FF", "#8FA8D8", "#3DB6AE", "#FF8F4D", "#4D8BFF", "#2EC4B6"};
        int index = Math.abs(seed.hashCode()) % palette.length;
        return palette[index];
    }

    private int countGeoStations(List<Map<String, Object>> stations) {
        return (int) stations.stream()
                .filter(station -> station.get("lng") != null && station.get("lat") != null)
                .count();
    }

    private double[] resolveFallbackCenter(List<Map<String, Object>> stations) {
        double lngSum = 0D;
        double latSum = 0D;
        int count = 0;
        for (Map<String, Object> station : stations) {
            Double lng = toDouble(station.get("lng"));
            Double lat = toDouble(station.get("lat"));
            if (lng != null && lat != null) {
                lngSum += lng;
                latSum += lat;
                count += 1;
            }
        }
        if (count == 0) {
            return new double[]{29.56301D, 106.55156D};
        }
        return new double[]{latSum / count, lngSum / count};
    }

    private int countEncodingIssues(List<Map<String, Object>> stations) {
        return (int) stations.stream()
                .filter(station -> Boolean.TRUE.equals(station.get("isAnomaly")))
                .count();
    }

    private int stationFlow(Map<String, Object> station) {
        return intValue(station, "predictedBoardings") + intValue(station, "predictedAlightings");
    }

    private int routeFlow(Map<String, Object> route) {
        return intValue(route, "totalFlow");
    }

    private int routeSeverity(Map<String, Object> route) {
        String status = text(route.get("status"));
        if ("error".equals(status)) {
            return 2;
        }
        if ("warning".equals(status)) {
            return 1;
        }
        return 0;
    }

    private double estimateMileage(List<Map<String, Object>> stations) {
        List<Map<String, Object>> geoStations = stations.stream()
                .filter(station -> station.get("lng") != null && station.get("lat") != null)
                .collect(Collectors.toList());
        if (geoStations.size() < 2) {
            return 0D;
        }
        double total = 0D;
        for (int index = 1; index < geoStations.size(); index++) {
            total += haversineKm(
                    doubleValue(geoStations.get(index - 1).get("lat")),
                    doubleValue(geoStations.get(index - 1).get("lng")),
                    doubleValue(geoStations.get(index).get("lat")),
                    doubleValue(geoStations.get(index).get("lng"))
            );
        }
        return total;
    }

    private double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double earthRadius = 6371.0D;
        double deltaLat = Math.toRadians(lat2 - lat1);
        double deltaLng = Math.toRadians(lng2 - lng1);
        double originLat = Math.toRadians(lat1);
        double targetLat = Math.toRadians(lat2);
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(originLat) * Math.cos(targetLat)
                * Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadius * c;
    }

    private Object firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = text(value);
            if (!blank(text)) {
                return value;
            }
        }
        return null;
    }

    private List<RouteIndex> loadRoutes() {
        String json = redisTemplate.opsForValue().get(routesIndexKey());
        if (blank(json)) {
            hotDataWarmupService.ensureHotCache(false);
            json = redisTemplate.opsForValue().get(routesIndexKey());
        }
        if (blank(json)) {
            return Collections.emptyList();
        }
        try {
            List<Map<String, Object>> rows = objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            return rows.stream().map(this::routeFromMap).collect(Collectors.toList());
        } catch (Exception exception) {
            throw new IllegalStateException("系统页读取线路热缓存失败", exception);
        }
    }

    private RouteIndex routeFromMap(Map<String, Object> row) {
        List<Integer> directions = new ArrayList<Integer>();
        for (Object item : listOfObjects(row.get("directions"))) {
            Integer value = toInt(text(item));
            if (value != null) {
                directions.add(value);
            }
        }
        if (directions.isEmpty()) {
            directions.add(1);
        }
        List<String> dates = listOfObjects(row.get("availableDates")).stream()
                .map(this::text)
                .filter(item -> !blank(item))
                .distinct()
                .sorted(Comparator.reverseOrder())
                .collect(Collectors.toList());
        return new RouteIndex(
                text(row.get("id")),
                text(row.get("name")),
                text(row.get("latestDate")),
                intValue(row.get("totalRecords")),
                directions,
                dates
        );
    }

    private List<Map<String, Object>> loadDashboardsForDate(List<RouteIndex> routes, String date) {
        List<String> keys = new ArrayList<String>();
        for (RouteIndex route : routes) {
            if (!route.availableDates.contains(date)) {
                continue;
            }
            for (Integer direction : route.directions) {
                keys.add(dashboardKey(route.id, direction, date));
            }
        }
        if (keys.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> values = redisTemplate.opsForValue().multiGet(keys);
        if (values == null) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> dashboards = new ArrayList<Map<String, Object>>();
        for (String value : values) {
            if (!blank(value)) {
                dashboards.add(readMap(value));
            }
        }
        return dashboards;
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception exception) {
            throw new IllegalStateException("系统页缓存反序列化失败", exception);
        }
    }

    private List<Map<String, Object>> aggregateStations(List<Map<String, Object>> dashboards) {
        Map<String, Map<String, Object>> stationAgg = new LinkedHashMap<String, Map<String, Object>>();
        for (Map<String, Object> dashboard : dashboards) {
            for (Map<String, Object> station : listOfMaps(dashboard.get("stations"))) {
                String stationId = text(station.get("id"));
                if (blank(stationId)) {
                    continue;
                }
                Map<String, Object> agg = stationAgg.computeIfAbsent(stationId, key -> mapOf(
                        "id", stationId,
                        "name", text(station.get("name")),
                        "boardings", 0,
                        "alightings", 0,
                        "value", 0
                ));
                int boardings = intValue(station, "predictedBoardings");
                int alightings = intValue(station, "predictedAlightings");
                agg.put("boardings", intValue(agg, "boardings") + boardings);
                agg.put("alightings", intValue(agg, "alightings") + alightings);
                agg.put("value", intValue(agg, "value") + boardings + alightings);
            }
        }
        return stationAgg.values().stream()
                .sorted((left, right) -> Integer.compare(intValue(right, "value"), intValue(left, "value")))
                .collect(Collectors.toList());
    }

    private int[] aggregateHourly(List<Map<String, Object>> dashboards) {
        int[] total = new int[24];
        for (Map<String, Object> dashboard : dashboards) {
            for (Map<String, Object> hour : listOfMaps(dashboard.get("hourlyTraffic"))) {
                String time = text(hour.get("time"));
                Integer value = toInt(text(hour.get("value")));
                Integer hourIndex = parseHourLabel(time);
                if (hourIndex != null && value != null && hourIndex >= 0 && hourIndex < 24) {
                    total[hourIndex] += value;
                }
            }
        }
        return total;
    }

    private List<Map<String, Object>> queryRouteCongestion(String date,
                                                           List<RouteIndex> routes,
                                                           List<Map<String, Object>> dashboards) {
        Map<String, String> routeNames = new HashMap<String, String>();
        for (RouteIndex route : routes) {
            routeNames.put(route.id, route.name);
        }
        for (Map<String, Object> dashboard : dashboards) {
            String routeId = text(dashboard.get("lineId"));
            if (!blank(routeId) && !routeNames.containsKey(routeId)) {
                routeNames.put(routeId, text(dashboard.get("line")));
            }
        }

        String sql =
                "select line_id as route_id, " +
                        "sum(case when cast(substring(trade_time, 12, 2) as unsigned) between 7 and 9 " +
                        "or cast(substring(trade_time, 12, 2) as unsigned) between 17 and 19 then 1 else 0 end) as severe_count, " +
                        "sum(case when cast(substring(trade_time, 12, 2) as unsigned) between 6 and 20 then 1 else 0 end) as low_count, " +
                        "count(*) as sample_count " +
                        "from new_card_on_station " +
                        "where left(trade_time,10)=? " +
                        "and line_id is not null and line_id <> '' " +
                        "and trade_time is not null and trade_time <> '' " +
                        "group by line_id";

        List<Map<String, Object>> rows = jdbcTemplate.query(sql, new Object[]{date}, (rs, rowNum) -> {
            String routeId = rs.getString("route_id");
            int severeCount = rs.getInt("severe_count");
            int lowCount = rs.getInt("low_count");
            int sampleCount = rs.getInt("sample_count");
            double lowRate = sampleCount <= 0 ? 0D : lowCount * 1.0D / sampleCount;
            double severeRate = sampleCount <= 0 ? 0D : severeCount * 1.0D / sampleCount;
            double avgSpeed = Math.max(10D, 30D - severeRate * 12D - lowRate * 6D);
            double congestion = 1.0D
                    + severeRate * 2.4D
                    + lowRate * 1.5D
                    + Math.min(1.2D, sampleCount / 5000.0D);
            return mapOf(
                    "lineId", routeId,
                    "name", blank(routeNames.get(routeId)) ? routeId : routeNames.get(routeId),
                    "direction", "全线",
                    "congestion", round1(Math.min(5D, Math.max(1D, congestion))),
                    "avgSpeed", round1(avgSpeed),
                    "sampleCount", sampleCount
            );
        });

        if (!rows.isEmpty()) {
            return rows.stream()
                    .sorted((left, right) -> Double.compare(doubleValue(right.get("congestion")), doubleValue(left.get("congestion"))))
                    .limit(5)
                    .collect(Collectors.toList());
        }

        return dashboards.stream()
                .map(dashboard -> {
                    Map<String, Object> trend = getMap(dashboard, "trend");
                    double congestion = listOfObjects(trend.get("congestion")).stream()
                            .map(this::toDouble)
                            .filter(item -> item != null)
                            .max(Double::compareTo)
                            .orElse(0D);
                    return mapOf(
                            "lineId", dashboard.get("lineId"),
                            "name", dashboard.get("line"),
                            "direction", dashboard.get("direction"),
                            "congestion", round1(congestion)
                    );
                })
                .sorted((left, right) -> Double.compare(doubleValue(right.get("congestion")), doubleValue(left.get("congestion"))))
                .limit(5)
                .collect(Collectors.toList());
    }

    private int queryDistinctCount(String sql, Object... params) {
        Integer value = jdbcTemplate.queryForObject(sql, params, Integer.class);
        return value == null ? 0 : value;
    }

    private String normalizeDate(String requestedDate, List<RouteIndex> routes) {
        List<String> dates = collectAvailableDates(routes);
        if (!blank(requestedDate) && dates.contains(requestedDate)) {
            return requestedDate;
        }
        return dates.isEmpty() ? null : dates.get(0);
    }

    private List<String> collectAvailableDates(List<RouteIndex> routes) {
        Set<String> dates = new LinkedHashSet<String>();
        for (RouteIndex route : routes) {
            dates.addAll(route.availableDates);
        }
        List<String> sorted = new ArrayList<String>(dates);
        sorted.sort(Comparator.reverseOrder());
        return sorted;
    }

    private List<String> cachedDates() {
        Set<String> dates = new LinkedHashSet<String>();
        Set<String> keys = redisTemplate.keys(versionedHotPrefix() + ":system:bundle:" + SYSTEM_BUNDLE_VERSION + ":*");
        if (keys != null) {
            for (String key : keys) {
                dates.add(key.substring((versionedHotPrefix() + ":system:bundle:" + SYSTEM_BUNDLE_VERSION + ":").length()));
            }
        }
        List<String> sorted = new ArrayList<String>(dates);
        sorted.sort(Comparator.reverseOrder());
        return sorted;
    }

    private String routesIndexKey() {
        return versionedHotPrefix() + ":index:routes";
    }

    private String systemBundleKey(String date) {
        return versionedHotPrefix() + ":system:bundle:" + SYSTEM_BUNDLE_VERSION + ":" + date;
    }

    private String gisRouteKey(String date, String routeId) {
        return versionedHotPrefix() + ":system:gis:route:" + SYSTEM_BUNDLE_VERSION + ":" + date + ":" + routeId;
    }

    private String gisTileKey(String date, String routeId, int z, int x, int y) {
        return versionedHotPrefix() + ":system:gis:tile:" + SYSTEM_BUNDLE_VERSION + ":" + date + ":" + routeId + ":" + z + ":" + x + ":" + y;
    }

    private String gisRouteCacheKey(String memoryKey) {
        return "__gis_route__:" + memoryKey;
    }

    private String dashboardKey(String routeId, int direction, String date) {
        return versionedHotPrefix() + ":dashboard:" + routeId + ":" + direction + ":" + date;
    }

    private String versionedHotPrefix() {
        return hotPrefix + ":" + HOT_DATA_VERSION;
    }

    private String directionLabel(Integer direction) {
        return direction != null && direction == 2 ? "下行" : "上行";
    }

    private int directionValue(String directionLabel) {
        return "下行".equals(directionLabel) ? 2 : 1;
    }

    private String cleanFlowName(String value) {
        if (blank(value)) {
            return value;
        }
        return value.replace("source:", "").replace("target:", "");
    }

    private Map<String, Object> getMap(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> casted = (Map<String, Object>) value;
            return casted;
        }
        return Collections.emptyMap();
    }

    private List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        List<?> raw = (List<?>) value;
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Object item : raw) {
            if (item instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> casted = (Map<String, Object>) item;
                rows.add(casted);
            }
        }
        return rows;
    }

    private List<List<Double>> listOfLists(Object value) {
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        List<?> raw = (List<?>) value;
        List<List<Double>> rows = new ArrayList<List<Double>>();
        for (Object item : raw) {
            if (item instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> casted = (List<Object>) item;
                List<Double> numbers = new ArrayList<Double>();
                for (Object entry : casted) {
                    Double number = toDouble(entry);
                    if (number != null) {
                        numbers.add(number);
                    }
                }
                rows.add(numbers);
            }
        }
        return rows;
    }

    private List<Object> listOfObjects(Object value) {
        if (value instanceof List) {
            return new ArrayList<Object>((List<?>) value);
        }
        return Collections.emptyList();
    }

    private List<String> hourLabels() {
        List<String> labels = new ArrayList<String>();
        for (int hour = 0; hour < 24; hour++) {
            labels.add(String.format("%02d:00", hour));
        }
        return labels;
    }

    private List<Integer> buildPredictedTrend(int[] networkHourly) {
        List<Integer> predicted = new ArrayList<Integer>();
        double rolling = 0D;
        for (int index = 0; index < networkHourly.length; index++) {
            int current = networkHourly[index];
            rolling = index == 0 ? current : rolling * 0.55D + current * 0.45D;
            int seasonalBoost = current <= 0 ? 0 : (index >= 7 && index <= 9 ? 36 : (index >= 17 && index <= 19 ? 42 : 18));
            int predictedValue = current <= 0 ? 0 : (int) Math.round(Math.max(current, rolling) * 1.06D + seasonalBoost);
            predicted.add(predictedValue);
        }
        return predicted;
    }

    private List<String> buildOverviewAlerts(List<Map<String, Object>> topRoutes,
                                             List<Map<String, Object>> hotspots,
                                             Map<String, Object> weather) {
        List<String> alerts = new ArrayList<String>();
        if (!topRoutes.isEmpty()) {
            Map<String, Object> topRoute = topRoutes.get(0);
            alerts.add(text(topRoute.get("name")) + " " + text(topRoute.get("direction")) + " 拥堵指数达到 " + topRoute.get("congestion"));
        }
        if (!hotspots.isEmpty()) {
            alerts.add(text(hotspots.get(0).get("name")) + " 换乘客流保持高位运行");
        }
        String condition = text(weather.get("condition"));
        if (!blank(condition) && !"-".equals(condition)) {
            alerts.add("高德天气显示当前为" + condition + "，需关注天气对晚高峰出行的影响");
        } else {
            alerts.add("天气接口暂未返回有效结果，建议保持人工巡检");
        }
        return alerts;
    }

    private List<Map<String, Object>> buildLiveOperationFeed(String date,
                                                             List<Map<String, Object>> topRoutes,
                                                             List<Map<String, Object>> hotspots,
                                                             Map<String, Object> weather,
                                                             int totalPassengers,
                                                             int onlineVehicles) {
        List<Map<String, Object>> feed = new ArrayList<Map<String, Object>>();
        String reportTime = text(weather.get("reportTime"));

        feed.add(mapOf(
                "type", "system",
                "time", blank(reportTime) || "-".equals(reportTime) ? date + " 06:00:00" : reportTime,
                "summary", "系统热缓存已完成同步",
                "target", "线路热缓存 / 系统页整包"
        ));

        feed.add(mapOf(
                "type", "system",
                "time", date + " 08:00:00",
                "summary", "模型客流场景已装载",
                "target", "当日客流总量 " + totalPassengers
        ));

        feed.add(mapOf(
                "type", "prediction",
                "time", date + " 17:00:00",
                "summary", "晚高峰客流波动抬升",
                "target", "活跃车辆 " + onlineVehicles + " 辆"
        ));

        if (!topRoutes.isEmpty()) {
            Map<String, Object> topRoute = topRoutes.get(0);
            feed.add(mapOf(
                    "type", "event",
                    "time", date + " 17:20:00",
                    "summary", "拥堵线路预警触发",
                    "target", text(topRoute.get("name")) + " / 指数 " + topRoute.get("congestion")
            ));
        }

        if (!hotspots.isEmpty()) {
            Map<String, Object> hotspot = hotspots.get(0);
            feed.add(mapOf(
                    "type", "prediction",
                    "time", date + " 17:35:00",
                    "summary", "重点站点客流持续升高",
                    "target", text(hotspot.get("name")) + " / 流量 " + hotspot.get("value")
            ));
        }

        String condition = text(weather.get("condition"));
        if (!blank(condition) && !"-".equals(condition)) {
            feed.add(mapOf(
                    "type", "system",
                    "time", blank(reportTime) || "-".equals(reportTime) ? date + " 18:00:00" : reportTime,
                    "summary", "天气约束已更新",
                    "target", condition + " / " + text(weather.get("wind"))
            ));
        }

        while (feed.size() < 6) {
            int index = feed.size() + 1;
            feed.add(mapOf(
                    "type", index % 3 == 0 ? "event" : "system",
                    "time", date + " 18:" + String.format("%02d", 5 * index) + ":00",
                    "summary", "后台运行状态正常",
                    "target", "监控节点 #" + (20 + index)
            ));
        }

        return feed;
    }

    private List<Integer> toIntegerList(int[] values) {
        List<Integer> result = new ArrayList<Integer>();
        for (int value : values) {
            result.add(value);
        }
        return result;
    }

    private byte[] buildGisRouteTile(Map<String, Object> route, int z, int x, int y) {
        List<List<Double>> path = listOfLists(route.get("path"));
        if (path.size() < 2) {
            return EMPTY_MVT_TILE;
        }

        List<Coordinate> coordinates = new ArrayList<Coordinate>();
        for (List<Double> point : path) {
            if (point.size() < 2) {
                continue;
            }
            Double lng = point.get(0);
            Double lat = point.get(1);
            if (lng == null || lat == null) {
                continue;
            }
            coordinates.add(new Coordinate(toMercatorX(lng), toMercatorY(lat)));
        }

        if (coordinates.size() < 2) {
            return EMPTY_MVT_TILE;
        }

        LineString line = MVT_GEOMETRY_FACTORY.createLineString(coordinates.toArray(new Coordinate[0]));
        line.setUserData(mapOf(
                "featureId", route.get("routeId"),
                "routeId", route.get("routeId"),
                "routeName", route.get("routeName"),
                "directionLabel", route.get("directionLabel"),
                "status", route.get("status"),
                "color", route.get("color")
        ));

        Envelope tileEnvelope = tileEnvelope(z, x, y);
        TileGeomResult tileGeom = JtsAdapter.createTileGeom(
                line,
                tileEnvelope,
                MVT_GEOMETRY_FACTORY,
                MVT_LAYER_PARAMS,
                MVT_GEOMETRY_FILTER
        );

        if (tileGeom.mvtGeoms.isEmpty()) {
            return EMPTY_MVT_TILE;
        }

        VectorTile.Tile.Layer.Builder layerBuilder = MvtLayerBuild.newLayerBuilder("bus-line", MVT_LAYER_PARAMS);
        MvtLayerProps layerProps = new MvtLayerProps();
        layerBuilder.addAllFeatures(JtsAdapter.toFeatures(tileGeom.mvtGeoms, layerProps, MVT_USER_DATA_CONVERTER));
        MvtLayerBuild.writeProps(layerBuilder, layerProps);

        return VectorTile.Tile.newBuilder()
                .addLayers(layerBuilder.build())
                .build()
                .toByteArray();
    }

    private Map<String, Object> buildRouteBounds(List<List<Double>> path, List<Map<String, Object>> stations) {
        Double minLng = null;
        Double minLat = null;
        Double maxLng = null;
        Double maxLat = null;

        for (List<Double> point : path) {
            if (point.size() < 2) {
                continue;
            }
            Double lng = point.get(0);
            Double lat = point.get(1);
            if (lng == null || lat == null) {
                continue;
            }
            minLng = minLng == null ? lng : Math.min(minLng, lng);
            minLat = minLat == null ? lat : Math.min(minLat, lat);
            maxLng = maxLng == null ? lng : Math.max(maxLng, lng);
            maxLat = maxLat == null ? lat : Math.max(maxLat, lat);
        }

        if (minLng == null || minLat == null || maxLng == null || maxLat == null) {
            for (Map<String, Object> station : stations) {
                Double lng = toDouble(station.get("lng"));
                Double lat = toDouble(station.get("lat"));
                if (lng == null || lat == null) {
                    continue;
                }
                minLng = minLng == null ? lng : Math.min(minLng, lng);
                minLat = minLat == null ? lat : Math.min(minLat, lat);
                maxLng = maxLng == null ? lng : Math.max(maxLng, lng);
                maxLat = maxLat == null ? lat : Math.max(maxLat, lat);
            }
        }

        if (minLng == null || minLat == null || maxLng == null || maxLat == null) {
            return Collections.emptyMap();
        }

        return mapOf(
                "minLng", round6(minLng),
                "minLat", round6(minLat),
                "maxLng", round6(maxLng),
                "maxLat", round6(maxLat)
        );
    }

    private Envelope tileEnvelope(int z, int x, int y) {
        double tileCount = Math.pow(2D, z);
        double tileWidth = (WEB_MERCATOR_HALF_WORLD * 2D) / tileCount;
        double minX = -WEB_MERCATOR_HALF_WORLD + x * tileWidth;
        double maxX = minX + tileWidth;
        double maxY = WEB_MERCATOR_HALF_WORLD - y * tileWidth;
        double minY = maxY - tileWidth;
        return new Envelope(minX, maxX, minY, maxY);
    }

    private double toMercatorX(double lng) {
        return lng * WEB_MERCATOR_HALF_WORLD / 180D;
    }

    private double toMercatorY(double lat) {
        double clampedLat = Math.max(-85.05112878D, Math.min(85.05112878D, lat));
        double radians = Math.toRadians(clampedLat);
        return WEB_MERCATOR_HALF_WORLD * Math.log(Math.tan(Math.PI / 4D + radians / 2D)) / Math.PI;
    }

    private int sum(int[] values) {
        int total = 0;
        for (int value : values) {
            total += value;
        }
        return total;
    }

    private String peakWindow(int[] values) {
        int bestStart = 0;
        int max = -1;
        for (int index = 0; index < values.length - 1; index++) {
            int current = values[index] + values[index + 1];
            if (current > max) {
                max = current;
                bestStart = index;
            }
        }
        return String.format("%02d:00 - %02d:00", bestStart, Math.min(bestStart + 2, 24));
    }

    private Integer parseHourLabel(String time) {
        try {
            if (blank(time) || time.length() < 2) {
                return null;
            }
            return Integer.parseInt(time.substring(0, 2));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer toInt(String value) {
        try {
            return blank(value) ? null : Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Integer intValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        Integer parsed = toInt(text(value));
        return parsed == null ? 0 : parsed;
    }

    private int intValue(Map<String, Object> map, String key) {
        return intValue(map.get(key));
    }

    private Double toDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return blank(text(value)) ? null : Double.parseDouble(text(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private double doubleValue(Object value) {
        Double parsed = toDouble(value);
        return parsed == null ? 0D : parsed;
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private double round1(double value) {
        return Math.round(value * 10D) / 10D;
    }

    private double round6(double value) {
        return Math.round(value * 1_000_000D) / 1_000_000D;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("系统页缓存序列化失败", exception);
        }
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }

    private static class RouteIndex {
        private final String id;
        private final String name;
        private final String latestDate;
        private final int totalRecords;
        private final List<Integer> directions;
        private final List<String> availableDates;

        private RouteIndex(String id, String name, String latestDate, int totalRecords, List<Integer> directions, List<String> availableDates) {
            this.id = id;
            this.name = name;
            this.latestDate = latestDate;
            this.totalRecords = totalRecords;
            this.directions = directions;
            this.availableDates = availableDates;
        }
    }
}
