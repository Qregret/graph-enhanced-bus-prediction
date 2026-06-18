# Redis 热缓存键结构

这套键结构对应当前 `odbus` 后端的预热逻辑，原始明细保存在 MySQL，前端大屏只读这些 Redis 热键。

## 1. 路线索引

Key:
`cdbus:hot:index:routes`

Value:
JSON 数组，元素结构如下：

```json
{
  "id": "105",
  "name": "105路",
  "latestDate": "2024-04-07",
  "totalRecords": 12345,
  "directions": [1, 2],
  "availableDates": ["2024-04-07", "2024-04-06", "2024-04-05"]
}
```

## 2. 按日期的预热状态

Key:
`cdbus:hot:status:{date}`

示例:
`cdbus:hot:status:2024-04-07`

Value:

```json
{
  "date": "2024-04-07",
  "computedAt": 1770000000000,
  "routeCount": 38,
  "scenarioCount": 76,
  "status": "done"
}
```

用途:
- 后端启动时先检查这个键
- 已存在则跳过该日期的重复预计算
- 不存在才从 MySQL 聚合并写回 Redis

## 3. 单线路单方向单日期的大屏结果

Key:
`cdbus:hot:dashboard:{routeId}:{direction}:{date}`

示例:
`cdbus:hot:dashboard:105:1:2024-04-07`

Value:
完整 dashboard JSON，字段直接对齐前端 `/api/dashboard` 返回值，例如：

- `line`
- `lineId`
- `direction`
- `date`
- `summary`
- `trend`
- `hourlyTraffic`
- `topStations`
- `mirrorComparison`
- `odFlow`
- `stations`
- `path`

## 4. 数据流

1. MySQL 存原始 GPS、刷卡、线路站点、OD 预测等全量数据
2. Spring Boot 启动时查询最近 `app.redis.hot-days` 天的业务日期
3. 对每个日期先检查 `cdbus:hot:status:{date}`
4. 不存在则从 MySQL 聚合并写入 `cdbus:hot:dashboard:*`
5. 前端请求 `/api/lines` 和 `/api/dashboard` 时，后端直接读这些热键
