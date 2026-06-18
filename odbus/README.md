# odbus

Java 后端工程，直接从 Redis 读取公交数据，并在内存里维护短时快照缓存。

## 设计

- 不走伪造 Mock 数据
- 使用 `SCAN` 代替 `KEYS`
- 使用 pipeline 批量 `HGETALL`
- 只在缓存过期或手动刷新时全量重建一次快照
- 请求阶段按线路和方向直接走内存索引，避免重复扫 Redis

## 启动

1. 安装 JDK 8+
2. 安装 Maven 3.8+
3. 确认本机 Redis 在 `127.0.0.1:6379`
4. 在 `odbus` 目录执行 `mvn spring-boot:run`

默认端口：`8080`

## 接口

- `GET /api/health`
- `GET /api/lines`
- `GET /api/dashboard?line=105路&direction=上行`
- `GET /api/routes`
- `GET /api/routes/{routeId}/dashboard?direction=1`
- `POST /api/reload`
