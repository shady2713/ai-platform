# basic-framework-spring-boot-starter-protection

请求保护能力接缝，提供 Redis 幂等、Redis 限流和可选的 Lock4j 分布式锁。

## 使用约束

- `@Idempotent` 的 key 必须非空，超时时间必须大于 0。Redis 返回不确定结果时按加锁失败处理，不放行业务请求；业务异常默认释放 key，可通过注解显式保留。
- `@RateLimiter` 的 key 必须非空，次数和时间必须大于 0。时间精度为毫秒；小于 1 毫秒的配置会明确失败，不会截断为 0。限流器配置变化时会原地更新并同步过期时间。
- 默认、用户、客户端 IP 和节点级 key resolver 只以方法签名及其声明的身份维度划分桶，不采纳可变请求参数；需要按账号、手机号等字段限流时使用显式表达式 key resolver。
- 客户端 IP 解析遵循 web starter 的 fail-closed 约定：默认不采信代理头；只有把代理出口 IP 配置到 `basic-framework.web.trusted-proxies` 后，按 IP 限流才会取到真实客户端地址（见 starter-web README）。
- 自定义 key resolver 必须注册为 Spring Bean，并在注解中引用其具体类型；缺失 resolver 会在请求执行前失败。
- 分布式锁由 Lock4j 提供。获取失败统一转换为框架 `LOCKED` 业务错误。

## 验证

模块单元测试覆盖自动配置、切面成功/拒绝/异常路径、Redis key 前缀、配置复用与更新、空返回 fail-closed 及非法边界。JaCoCo 行覆盖率门槛为 99.3%，只升不降。
