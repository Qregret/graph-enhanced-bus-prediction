package com.odbus.controller;

import com.odbus.service.TransitDashboardService;
import com.odbus.service.TransitSystemService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TransitDashboardController {

    private final TransitDashboardService transitDashboardService;
    private final TransitSystemService transitSystemService;

    public TransitDashboardController(TransitDashboardService transitDashboardService,
                                      TransitSystemService transitSystemService) {
        this.transitDashboardService = transitDashboardService;
        this.transitSystemService = transitSystemService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return transitDashboardService.health();
    }

    @GetMapping("/lines")
    public List<Map<String, Object>> lines() {
        return transitDashboardService.listLines();
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(@RequestParam(required = false) String line,
                                         @RequestParam(required = false) String direction,
                                         @RequestParam(required = false) String date) {
        return transitDashboardService.getDashboard(line, direction, date);
    }

    @GetMapping("/routes")
    public Map<String, Object> routes() {
        return java.util.Collections.<String, Object>singletonMap("routes", transitDashboardService.listRoutes());
    }

    @GetMapping("/routes/{routeId}/dashboard")
    public Map<String, Object> routeDashboard(@PathVariable String routeId,
                                              @RequestParam(required = false) Integer direction,
                                              @RequestParam(required = false) String date) {
        return transitDashboardService.getDashboardByRouteId(routeId, direction, date);
    }

    @PostMapping("/reload")
    public Map<String, Object> reload() {
        return transitDashboardService.reloadDataset();
    }

    @GetMapping("/system/meta")
    public Map<String, Object> systemMeta() {
        return transitSystemService.getMeta();
    }

    @GetMapping("/system/pages")
    public Map<String, Object> systemPages(@RequestParam(required = false) String date) {
        return transitSystemService.getPages(date);
    }

    @GetMapping("/system/launchpad")
    public Map<String, Object> systemLaunchpad(@RequestParam(required = false) String date) {
        return transitSystemService.getLaunchpad(date);
    }

    @GetMapping("/system/overview")
    public Map<String, Object> systemOverview(@RequestParam(required = false) String date) {
        return transitSystemService.getOverview(date);
    }

    @GetMapping("/system/forecast")
    public Map<String, Object> systemForecast(@RequestParam(required = false) String date) {
        return transitSystemService.getForecast(date);
    }

    @GetMapping("/system/dispatch")
    public Map<String, Object> systemDispatch(@RequestParam(required = false) String date) {
        return transitSystemService.getDispatch(date);
    }

    @GetMapping("/system/analytics")
    public Map<String, Object> systemAnalytics(@RequestParam(required = false) String date) {
        return transitSystemService.getAnalytics(date);
    }

    @GetMapping("/system/gis")
    public Map<String, Object> systemGis(@RequestParam(required = false) String date) {
        return transitSystemService.getGis(date);
    }

    @GetMapping("/system/gis/route")
    public Map<String, Object> systemGisRoute(@RequestParam(required = false) String date,
                                              @RequestParam String routeId) {
        return transitSystemService.getGisRoute(date, routeId);
    }

    @GetMapping(value = "/system/gis/tiles/{z}/{x}/{y}.mvt", produces = "application/vnd.mapbox-vector-tile")
    public ResponseEntity<byte[]> systemGisTiles(@RequestParam(required = false) String date,
                                                 @RequestParam String routeId,
                                                 @PathVariable int z,
                                                 @PathVariable int x,
                                                 @PathVariable int y) {
        byte[] payload = transitSystemService.getGisRouteTile(date, routeId, z, x, y);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.mapbox-vector-tile"))
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=300")
                .body(payload);
    }

    @GetMapping("/system/gis/station")
    public Map<String, Object> systemGisStation(@RequestParam(required = false) String date,
                                                @RequestParam String routeId,
                                                @RequestParam String stationId) {
        return transitSystemService.getGisStation(date, routeId, stationId);
    }

    @PostMapping("/system/precompute")
    public Map<String, Object> precomputeSystemPages(@RequestParam(defaultValue = "false") boolean force) {
        return transitSystemService.precompute(force);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(java.util.Collections.<String, Object>singletonMap("message", exception.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(java.util.Collections.<String, Object>singletonMap("message", exception.getMessage()));
    }
}
