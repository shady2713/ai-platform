# ADR 0030：移除零消费的 starter-mq，starter-biz-ip 更名为 basic-framework-biz-ip

- 状态：已接受

## 背景

能力接缝审计确认两处名实不符：

1. **starter-mq 零消费且非被动**：`basic-framework-spring-boot-starter-mq`
   交付 Redis Stream / PubSub 消息队列抽象（`RedisMQTemplate`、
   `AbstractRedisStreamMessage` 等），全仓 grep 确认 module-system、
   module-infra、server 均无任何消费代码、配置键或文档外引用；但
   `basic-framework-server/pom.xml` 仍将其打进可执行 jar，且
   `BasicFrameworkRedisMQConsumerAutoConfiguration` 类级 `@EnableScheduling`
   无条件激活调度线程与 Redisson Stream 消费基础设施——它不是 ADR 0028
   保留的 `@Idempotent` 那种"不启用即零成本"的被动注解缝，而是每次部署都
   常驻运行。ADR 0028 第 3 条约定：长期无消费者且无下游引用的缝按常规
   死代码规则复核删除，本审计即该复核的落锤。
2. **starter-biz-ip 不是 starter**：`basic-framework-spring-boot-starter-biz-ip`
   无 `AutoConfiguration.imports`、无 `Properties`，只是 `AreaUtils` /
   `IPUtils` / `AreaConvert` 工具类加内置数据文件（`area.csv`、
   `ip2region.xdb`），数据经 classpath 静态加载，不存在待配置项。
   为它补 auto-configuration 只会制造能力目录明令禁止的"配置假象"。

## 决策

1. 删除 starter-mq 整个模块及其 README、单元/集成测试；从
   `basic-framework-core` 的 `<modules>`、`basic-framework-server` 装配依赖、
   BOM 的 dependencyManagement 中移除该工件；`coverage-baseline.json` 同步
   删除其 15 条基线。BOM 中无任何消费者的 `jedis-mock` 版本属性与托管条目
   （Redis 内嵌测试桩，历史 mq 测试遗产）一并清除。
2. starter-biz-ip 更名为 `basic-framework-biz-ip`（去掉 starter 前缀），
   与 `basic-framework-common` 同列为纯工具组件：core 父 pom、BOM、
   module-system 依赖、`coverage-baseline.json` 五条路径同步更名，README
   保留并改为工具组件表述；`check-starter-documentation.mjs` 按目录名模式
   扫描，更名后自动不再对其施加 starter 文档契约。
3. 未来确有消息队列需求时，按"starter = 能力缝"的既有标准重建
   （成对 auto-configuration + `Properties` 契约 + 契约测试），而不是
   恢复本模块。

## 后果

- 可执行 jar 不再携带无条件 `@EnableScheduling` 的 MQ 消费者装配；
  SBOM 与 Trivy 扫描面同步缩小。
- `basic-framework.mq.redis.*` 配置键整体失效；该前缀未出现在任何
  部署配置中，无迁移成本。
- ADR 0028 不受影响：`@Idempotent` 缝继续保留，本 ADR 是其第 3 条
  复核条款的首次执行。
- 下游项目若曾自行依赖 `basic-framework-spring-boot-starter-mq` 或
  `basic-framework-spring-boot-starter-biz-ip` 坐标，升级时需改用
  `basic-framework-biz-ip` 坐标或自建 MQ 集成；仓库内无此类引用。
