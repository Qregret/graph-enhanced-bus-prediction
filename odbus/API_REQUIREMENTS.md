# 智能公交调度平台后端 API 需求文档

## 1. 范围

本文档对应当前前端两类页面：

- 工作台系统页
  - 综合监控
  - 客流预测
  - 智能调度
  - 算法评估
  - GIS 管理
  - 大屏引导
- 全屏大屏页
  - 线路筛选
  - 地图轨迹与站点
  - 趋势图 / 小时车流 / OD 流向 / 站点排行 / 全线路对比

## 2. 数据分层

- MySQL
  - 存放原始明细与基础台账
  - 表来源：`dwd_od`、`ods_route_full`、`ods_route_station_full`、`ods_station_inc`、`ods_vehicle_full`、`ods_vehicle_gps_inc`
- Redis
  - 存放按日期预热后的热数据
  - 线路大屏热缓存：`cdbus:hot:dashboard:{routeId}:{direction}:{date}`
  - 线路索引：`cdbus:hot:index:routes`
  - 系统页整包缓存：`cdbus:hot:system:bundle:{date}`

## 3. 预计算原则

- 启动时先执行线路级热缓存预热
- 再基于线路热缓存构建系统页整包缓存
- 所有系统页接口优先命中 Redis
- Redis 缺失时自动重建并回写
- 综合监控页中的天气数据由后端调用高德 Web 服务天气查询接口后写入系统页整包缓存
- 综合监控页中的拥堵线路 Top5 由后端基于线路热缓存中的拥堵趋势字段统一排序后返回真实拥堵值

## 4. 现有大屏接口

### 4.1 健康检查

- `GET /api/health`

响应：

```json
{
  "status": "ok",
  "redis": true
}
```

### 4.2 线路列表

- `GET /api/lines`

用途：

- 左侧线路下拉
- 日期可选列表
- 方向可选列表

关键字段：

- `lineId`
- `lineName`
- `latestDate`
- `availableDates`
- `directions`

### 4.3 大屏单线路数据

- `GET /api/dashboard?line={lineIdOrLineName}&direction={上行|下行|1|2}&date={yyyy-MM-dd}`

用途：

- 地图轨迹与站点
- 站点预测上下车
- 车速与拥堵趋势
- 今日小时车流量
- 热门站点 TOP5
- 全线路上下车对比
- OD 流向

## 5. 新增系统页接口

### 5.1 系统页元信息

- `GET /api/system/meta`

用途：

- 工作台日期选择器
- 系统页初始化

响应字段：

- `availableDates`
- `latestDate`
- `routeCount`
- `cachedDates`

### 5.2 系统页整包接口

- `GET /api/system/pages?date={yyyy-MM-dd}`

用途：

- 一次拉取整套系统页数据
- 适合首页初始化或页面缓存

响应字段：

- `date`
- `availableDates`
- `launchpad`
- `overview`
- `forecast`
- `dispatch`
- `analytics`
- `gis`

### 5.3 大屏引导页

- `GET /api/system/launchpad?date={yyyy-MM-dd}`

字段：

- `connectedStationCount`
- `predictedPassengerCount`
- `confidence`
- `routeCount`
- `scenarioCount`
- `status`
- `updates`

### 5.4 综合监控页

- `GET /api/system/overview?date={yyyy-MM-dd}`

字段：

- `metrics.totalPassengers`
- `metrics.peakWindow`
- `metrics.onlineVehicles`
- `metrics.totalVehicles`
- `metrics.congestedStations`
- `trend.labels`
- `trend.actual`
- `trend.predicted`
- `topCongestedRoutes`
- `topCongestedRoutes[].congestion`
- `topCongestedRoutes[].direction`
- `weather`
- `weather.temperature`
- `weather.condition`
- `weather.wind`
- `weather.reportTime`
- `alerts`

### 5.5 客流预测页

- `GET /api/system/forecast?date={yyyy-MM-dd}`

字段：

- `odPairs`
  - `boardStation`
  - `alightStation`
  - `value`
- `hotspots`
- `insights`

### 5.6 智能调度页

- `GET /api/system/dispatch?date={yyyy-MM-dd}`

字段：

- `metrics.recommendedExtraVehicles`
- `metrics.dispatchAdoptionRate`
- `metrics.savedWaitMinutes`
- `metrics.coordinatedIntersections`
- `strategies`
- `resourcePool`

### 5.7 算法评估页

- `GET /api/system/analytics?date={yyyy-MM-dd}`

字段：

- `metrics.mape`
- `metrics.avgLatencyMinutes`
- `metrics.sampleCoverage`
- `metrics.effectiveDays`
- `modelCompare`
- `summary`

### 5.8 GIS 管理页

- `GET /api/system/gis?date={yyyy-MM-dd}`

字段：

- `routeAssets`
- `selectedStation`
- `mapSummary`

### 5.9 系统页预计算

- `POST /api/system/precompute?force={true|false}`

用途：

- 手动重建系统页 Redis 整包缓存

响应字段：

- `ok`
- `builtDates`
- `availableDates`

## 6. 字段来源说明

### 6.1 线路热缓存来源

- `dwd_od`
  - 上下车聚合
  - 小时客流
  - OD 流向
- `ods_route_station_full`
  - 站点顺序
- `ods_station_inc`
  - 站点名称与坐标
- `ods_vehicle_gps_inc`
  - 时速聚合

### 6.2 系统页来源

- 优先读取 Redis 中的线路热缓存，再做二次聚合
- 补充指标直接查询 MySQL：
  - 在线车辆数
  - 车辆资源池
  - 车辆总量
- 天气信息由后端调用高德 Web 服务天气查询接口：
  - 接口：`https://restapi.amap.com/v3/weather/weatherInfo`
  - 参数：`city`、`extensions=base`、`key`

## 7. 设计约束

- 所有接口默认按最新可用日期返回
- 日期参数非法时回退到最新日期
- 方向统一支持中文和数值两种传参
- 所有系统页接口响应必须可直接渲染，不要求前端二次聚合
