# ADR 0004：DAL 查询入参模式

- 状态：已接受

## 上下文

Mapper 不得依赖 Controller VO；查询入参还必须满足函数参数不超过 5 个的工程约束。

## 决策

1. 查询条件不超过 4 个时使用显式散参；分页查询另传 `PageParam`，总参数不超过 5 个。
2. 查询条件达到 5 个时，在 `dal/mysql/<domain>/` 同包定义不可变 `XxxQuery`，
   Mapper 签名收敛为 `selectPage(PageParam pageParam, XxxQuery query)` 或等价形式。
3. Query 对象只表达持久层查询条件，不携带 Swagger、Controller 校验或序列化注解。
4. 查询字段、匹配方式和排序 allowlist 由 Mapper 拥有；Service 负责把协议对象转换为 DAL 参数。

ArchUnit 持续阻断 Service/DAL 对 Controller 包的依赖。新增查询不得把 VO 传入 Mapper，
也不得用无类型 `Map` 规避参数约束。
