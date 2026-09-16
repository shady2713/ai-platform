# basic-framework-spring-boot-starter-redis

Redis 序列化与 Spring Cache 能力接缝，为限流和分布式协调能力提供统一连接基础。

## 提供的能力

- 提供键为字符串、值为 JSON 的统一 `RedisTemplate`。
- 为时间类型注册 Jackson Java Time 支持。
- 提供基于 Redis 的 Spring Cache 配置和可按缓存名声明 TTL 的 `TimeoutRedisCacheManager`。
- 使用 SCAN 批量清理缓存，批量大小由 `basic-framework.cache.redis-scan-batch-size` 管理，默认 30 且必须大于 0。

## 使用约束

- 业务模块使用统一 `RedisTemplate` 或 Spring Cache 抽象，不自行创建不兼容的序列化器。
- Redis key 必须包含明确的业务命名空间；部署级前缀通过 `spring.cache.redis.key-prefix` 配置。
- 自定义缓存 TTL 使用 `cacheName#正整数`，可选单位为 `d`、`h`、`m`、`s`，省略单位时按秒处理；零值、负值、未知单位与溢出值直接失败。
- 缓存不是事实来源；缓存未命中、失效或 Redis 不可用时，业务一致性由持久化层保证。
- 禁止将密码、令牌明文或无需缓存的敏感对象写入 Redis。

## 验证

本 starter 的单元测试验证序列化兼容性、TTL、缓存前缀和清理边界；模块 JaCoCo 行覆盖率门槛为 95.0%。配置错误在应用启动期失败。
