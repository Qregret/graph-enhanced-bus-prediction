package com.odbus.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AmapWeatherService {

    private final ObjectMapper objectMapper;
    private final String weatherKey;
    private final String cityCode;
    private final long ttlMillis;
    private volatile long weatherLoadedAt;
    private volatile Map<String, Object> weatherCache = Collections.<String, Object>emptyMap();

    public AmapWeatherService(
            ObjectMapper objectMapper,
            @Value("${app.amap.weather-key:}") String weatherKey,
            @Value("${app.amap.weather-city:500102}") String cityCode,
            @Value("${app.amap.weather-cache-minutes:10}") long cacheMinutes
    ) {
        this.objectMapper = objectMapper;
        this.weatherKey = weatherKey;
        this.cityCode = cityCode;
        this.ttlMillis = Math.max(cacheMinutes, 1) * 60_000L;
    }

    public Map<String, Object> getCurrentWeather() {
        long now = System.currentTimeMillis();
        if (!weatherCache.isEmpty() && now - weatherLoadedAt < ttlMillis) {
            return weatherCache;
        }
        synchronized (this) {
            if (!weatherCache.isEmpty() && now - weatherLoadedAt < ttlMillis) {
                return weatherCache;
            }
            weatherCache = fetchWeather();
            weatherLoadedAt = now;
            return weatherCache;
        }
    }

    private Map<String, Object> fetchWeather() {
        if (blank(weatherKey)) {
            return fallbackWeather();
        }
        try {
            String url = "https://restapi.amap.com/v3/weather/weatherInfo?city="
                    + URLEncoder.encode(cityCode, "UTF-8")
                    + "&extensions=base&key="
                    + URLEncoder.encode(weatherKey, "UTF-8");
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Accept", "application/json");

            int code = connection.getResponseCode();
            if (code != 200) {
                return fallbackWeather();
            }

            StringBuilder builder = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            reader.close();

            Map<String, Object> root = objectMapper.readValue(builder.toString(), new TypeReference<Map<String, Object>>() {});
            List<?> lives = root.get("lives") instanceof List ? (List<?>) root.get("lives") : Collections.emptyList();
            if (lives.isEmpty() || !(lives.get(0) instanceof Map)) {
                return fallbackWeather();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> live = (Map<String, Object>) lives.get(0);
            return mapOf(
                    "temperature", text(live.get("temperature")) + "°C",
                    "condition", text(live.get("weather")),
                    "wind", text(live.get("winddirection")) + "风 " + text(live.get("windpower")) + " 级",
                    "humidity", text(live.get("humidity")),
                    "reportTime", text(live.get("reporttime")),
                    "city", text(live.get("city"))
            );
        } catch (Exception exception) {
            return fallbackWeather();
        }
    }

    private Map<String, Object> fallbackWeather() {
        return mapOf(
                "temperature", "-",
                "condition", "-",
                "wind", "-",
                "humidity", "-",
                "reportTime", "-",
                "city", cityCode
        );
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }
}
