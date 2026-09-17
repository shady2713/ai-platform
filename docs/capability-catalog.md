# 能力目录

本页用于定位模块与公开契约。配置、默认值、失败语义和资源清理由所属 README 维护；
工程规则见 [AGENTS.md](../AGENTS.md)，新增业务流程见[开发指南](development-guide.md)。

## core 模块

下表路径均相对于 `后端代码/basic-framework-boot/basic-framework-core`。
业务代码只使用文档声明的接口、注解、工具或配置契约，不直接依赖内部实现。

| 模块 | 职责与契约入口 | 使用说明 |
| --- | --- | --- |
| `basic-framework-common` | 异常、校验、分页等基础类型；`CurrentUserProvider` 声明当前身份 SPI，由 security 实现 | 无自动配置；身份缺失的处理由消费者契约决定 |
| `basic-framework-spring-boot-starter-web` | API 前缀、HTTP 边界、序列化、日志、CORS、XSS、OpenAPI | [Web README](../后端代码/basic-framework-boot/basic-framework-core/basic-framework-spring-boot-starter-web/README.md) |
| `basic-framework-spring-boot-starter-security` | 认证 Provider、权限表达式与 `CredentialCipher` | [Security README](../后端代码/basic-framework-boot/basic-framework-core/basic-framework-spring-boot-starter-security/README.md) |
| `basic-framework-spring-boot-starter-redis` | Redis 序列化、Spring Cache 与 TTL | [Redis README](../后端代码/basic-framework-boot/basic-framework-core/basic-framework-spring-boot-starter-redis/README.md) |
| `basic-framework-spring-boot-starter-protection` | `@Idempotent`、`@RateLimiter` 与 Lock4j | [Protection README](../后端代码/basic-framework-boot/basic-framework-core/basic-framework-spring-boot-starter-protection/README.md) |
| `basic-framework-spring-boot-starter-mybatis` | 数据源、Mapper 扩展、审计字段与删除模型 | [MyBatis README](../后端代码/basic-framework-boot/basic-framework-core/basic-framework-spring-boot-starter-mybatis/README.md) |
| `basic-framework-spring-boot-starter-job` | `JobHandler`、Quartz 与受管异步执行器 | [Job README](../后端代码/basic-framework-boot/basic-framework-core/basic-framework-spring-boot-starter-job/README.md) |
| `basic-framework-spring-boot-starter-excel` | 文件读写、字典与领域转换器 | [Excel README](../后端代码/basic-framework-boot/basic-framework-core/basic-framework-spring-boot-starter-excel/README.md) |
| `basic-framework-spring-boot-starter-biz-data-permission` | `DeptDataPermissionRuleCustomizer` 与 SQL 行级过滤 | [Data Permission README](../后端代码/basic-framework-boot/basic-framework-core/basic-framework-spring-boot-starter-biz-data-permission/README.md) |
| `basic-framework-spring-boot-starter-ai` | 自有模型契约（`ModelPort`/`ModelCapability`）、装配期 fail-closed 校验；`provider.springai` 为唯一 Spring AI 引用区域 | [AI README](../后端代码/basic-framework-boot/basic-framework-core/basic-framework-spring-boot-starter-ai/README.md) |
| `basic-framework-biz-ip` | IP 归属地、行政区划与 Excel 地区转换；纯工具组件 | [IP README](../后端代码/basic-framework-boot/basic-framework-core/basic-framework-biz-ip/README.md) |

`@Idempotent` 是供下游按端点启用的被动能力，仓库内暂无生产消费点，保留依据见
[ADR 0028](adr/0028-framework-seams-may-have-zero-in-repo-consumers.md)。当前不交付 MQ starter，
移除与 IP 模块更名的兼容说明见 [ADR 0030](adr/0030-remove-mq-starter-and-rename-biz-ip.md)。

## 业务模块契约与装配

`basic-framework-module-system-api`、`basic-framework-module-infra-api` 与
`basic-framework-module-ai-api` 是薄契约模块，只承载 CommonApi 与 DTO；实现由各业务模块拥有。

| 契约模块 | 对外能力 | 实现与边界 |
| --- | --- | --- |
| `module-system-api` | Permission、UserSession、OperateLog、DictData | [system README](../后端代码/basic-framework-boot/basic-framework-module-system/README.md) |
| `module-infra-api` | ApiAccessLog、ApiErrorLog | [infra README](../后端代码/basic-framework-boot/basic-framework-module-infra/README.md) |
| `module-ai-api` | `AiRunCommonApi`（运行状态查询）与运行状态词汇 | [AI module README](../后端代码/basic-framework-boot/basic-framework-module-ai/README.md) |

core starter 和跨业务模块调用只消费已发布的薄 API 契约，不访问对方 Mapper、DO 或
ServiceImpl。ArchUnit 使用显式契约清单，不因包名含 `api` 自动放行；同步完整性校验加入调用方事务。

`basic-framework-dependencies` 统一管理依赖版本，业务 POM 不单独声明版本。
`basic-framework-server` 是唯一应用入口，负责业务模块与 starter 装配，运行配置集中在其
`src/main/resources/application*.yaml`。启动与部署从[根 README](../README.md)进入。
