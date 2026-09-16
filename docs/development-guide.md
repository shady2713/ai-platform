# 业务开发指南

> 定位：在本框架上开发新功能的唯一入口文档。规则本身不在这里重复定义——
> 工程规则看根 `AGENTS.md`，HTTP 状态码语义看 `docs/adr/0003-http-status-semantics.md`，
> 字段规则看 `docs/contracts/field-catalog.yaml`（已建立，改动会被 CI 漂移检查拦截），
> 产品与运行范围看 `docs/adr/0006-greenfield-product-operational-baseline.md`，
> 安全红线看根 `AGENTS.md` 与 `docs/security/`。本文只讲"怎么把一件事从头到尾做对"。

## 0. 当前生效口径

- 错误协议走语义化 HTTP 状态码（ADR 0003，双端已同步）；新代码不得退回 HTTP 200 + `code/msg` 裸返回。
- VO 命名：存量 `SaveReqVO` 属技术债，随 service 层治理逐步消解；新增功能一律 Create/Update 分离。
- 资源 ID：现状为 `NumberSerializer` 仅对超出 2^53 的 Long 转字符串；"一律十进制字符串"的目标契约尚未落地，新接口暂维持现状，待字段目录层面统一决策。
- 当前产品范围按 ADR 0006：单租户、无富文本、无外部开放 API、基础框架不收集身份证/银行卡。会话支持分级撤销。

## 1. 新增一个业务实体（标准 CRUD）的完整路径

按顺序执行，每一步都有明确的"放哪里、叫什么名"：

1. **数据库迁移**：在后端 `basic-framework-server/src/main/resources/db/migration/` 新增 `V{n}__<说明>.sql`。表、列、索引 snake_case；约束命名 `pk_/uk_/idx_`。先按[生命周期策略](data-lifecycle.md)选择删除方式：通用审计字段为 `creator/create_time/updater/update_time`，只有软删除表增加 `deleted` 并继承 `SoftDeletableDO`。已在共享环境执行的迁移文件永不修改；首发前合并基线也必须先写 ADR。
2. **字段契约登记**：每个字段在字段目录登记语义类型（名称/手机号/金额/……）、长度、必填、敏感级别。不允许拍脑袋写 `varchar(255)`。
3. **搭建骨架**：按现有模块结构手工建立 DO/Mapper/Service/Controller 和前端页面，复制相邻业务文件时只保留当前实体需要的字段、权限与行为，不引入示例业务。应用端接口通过 `controller.app` 扩展点按业务鉴权和资源归属规则实现；接入微信小程序、APP 或 H5 会员体系时，应用模块还必须注册声明 MEMBER 类型的 `UserSessionCommonApi` Provider，未接入时 `/app-api` 认证按关闭失败处理。
4. **后端分层**：
   - DTO/VO：Create/Update/PageReqVO/RespVO 分开（存量 SaveReqVO 见第 0 节口径）；Controller 只做协议映射、边界校验、鉴权声明。
   - Service：公共写方法加 `@Transactional(rollbackFor = Exception.class)`；业务失败 `throw exception(ErrorCode)`，错误码进所属模块 `ErrorCodeConstants`；禁止 hutool Assert 做业务校验。
   - 跨模块调用只走对方模块的 `api` 包，禁止碰别人的 Mapper/DO/ServiceImpl。
5. **权限**：权限编码 `domain:resource:action`（如 `system:user:create`），Controller 方法标 `@PreAuthorize("@ss.hasPermission('...')")`；菜单和权限数据通过 migration 进入种子，不手工插库。
6. **前端**：API 按 `SystemXxxApi`/`InfraXxxApi` namespace 封装；表单规则从统一规则中心取（`field-rules.ts`，禁止手写正则）；增删改查交互用 `use-crud-actions` 组合式函数；VXE Grid 的操作工具栏使用 `#toolbar-tools`，连接表单的 `FormModal` 必须通过 `@success` 刷新列表；请求错误默认由 `errorMode: 'global'` 提示，字段/组件自行消费时显式传 `inline`，无需反馈的恢复路径传 `silent`，局部消费不得再触发全局 toast；所有用户反馈走 `utils/feedback.ts`，禁止直用 ElMessage/ElNotification。
7. **测试**（按 AGENTS.md 测试分层，缺一不可）：
   - Service 单测：正常、边界、null、并发/重复提交、错误路径。
   - Controller slice 测试：未认证 401、无权限 403、非法输入 400、成功 200。
   - 涉及真实 SQL 行为的走 Testcontainers 集成测试。
   - `./mvnw -Pintegration verify` 还会启动 package 阶段生成的可执行 jar，以真实 MySQL/Redis 验证生产配置 fail-closed、Flyway 空库迁移和 `/actuator/health`；不得用测试上下文或 mock 代替该边界。
8. **文档**：所属模块 README 同步；非平凡决策写 `docs/adr/`。

## 2. 规则速查（详情以归属文档为准）

| 场景 | 做法 |
|---|---|
| 字符串必填 | `@NotBlank`；集合必填 `@NotEmpty`；对象 `@NotNull` |
| 可写字符串 | 必须有长度上限，与字段目录一致 |
| 业务抛错 | `throw exception(MODULE_ERROR_CODE)`，文案入 ErrorCodeConstants，用 `{}` 占位 |
| 正则 | 只用 `ValidationUtils`（后端）/ `field-rules.ts`（前端）已登记的；新增规则先登记再使用 |
| Cron/URL | 用解析器或语义校验，禁止只用正则 |
| 日期入参 | `@DateTimeFormat` + `DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND` 常量 |
| 分页 | `pageNo >= 1`，`1 <= pageSize <= 200`；排序字段逐接口 allowlist |
| 枚举 | 稳定 code，未知值拒绝，禁止数据库 ordinal |
| 前端文件命名 | kebab-case；composable 用 `use-*.ts` |
| 新配置项 | 进所属 starter 的 `@ConfigurationProperties` + `@Validated`，禁止代码里散落默认值 |
| 新表、删除或关联 | 先更新 [`docs/data-lifecycle.md`](data-lifecycle.md) 及机器台账；逐表声明软删、硬删、追加保留或平台托管，物理 FK 与逻辑引用均不得隐式新增 |
| 浏览器会话 | access token 仅保存在页面内存并通过 `Authorization` 发送；refresh token 仅存在于 host-only、`HttpOnly`、`SameSite=Strict` Cookie，生产环境强制 `Secure`。刷新端点只读 Cookie，每次成功刷新必须轮换 refresh token 且旧值立即失效；refresh token 绝不能作为 access token 认证。前端请求客户端统一启用 credentials，页面重载最多尝试恢复一次会话；登出同时撤销 token family 并清除 Cookie。任何业务模块不得自行持久化、读取或转发 refresh token |
| 文件上传 | 统一走文件服务；元数据列宽、相对路径和失败补偿遵循 ADR 0012。新文件默认私有，公开展示必须显式传 `publicRead=true`；私有文件必须记录业务所有者并使用受控读取的存储配置，业务代码不得把不可猜路径当作授权。ZIP 在存储前校验条目路径、条目数、总展开量和压缩比，阈值由 `basic-framework.file.archive.*` 配置拥有；业务代码不得自行解压不可信压缩包 |
| 定时任务 | 实现 `JobHandler`，处理器必须可重入/幂等或持有业务唯一键；停机错过的 cron 默认跳过，不能依赖重启补跑；需要人工补偿时设计独立、有审计的命令 |
| 密钥/密码 | 部署级秘密只来自环境变量/Secret 管理；由管理员维护的客户端、渠道和基础设施凭据只经高风险写接口进入，按数据分级规则进行不可逆哈希或版本化加密。用户自选密码的设密边界见 ADR 0011：不 trim，至少 15 码点且最多 72 UTF-8 字节，所有设密服务入口还要执行上下文弱密码检查；登录入口为兼容历史哈希只校验非空。部署品牌保留词由 `basic-framework.security.password-policy.reserved-terms` 统一配置。任何秘密都不得有可运行默认值、进入日志或被普通查询接口回显 |
| 登录防爆破 | 客户端 IP 限流与账号级 Redis 失败计数同时生效。账号密码在配置窗口内达到阈值后短时锁定；正确的密码、密码重置以及经权限保护的管理员命令可清除状态。业务代码不得自行拼接锁定 Redis Key 或绕过 `LoginProtectionService` |

## 3. 禁止事项（Code Review 一票否决）

1. Controller 里写业务事务；Service/DAL 导入 controller VO（ArchUnit 规则 D 硬拦截，当前零违例，新增导入即失败）。
2. hutool Assert、裸 `IllegalArgumentException`、`exception0(code, "内联文案")` 承载业务分支。
3. `ex.getMessage()`、Spring/SQL 原始异常文案进入响应体。
4. 手写正则（手机号/密码/邮箱等）出现在权威文件之外。
5. 直用 `ElMessage`/`ElNotification`/`ElMessageBox` 绕过 feedback 和 use-crud-actions。
6. `v-html`、`innerHTML`、HTML 字符串拼接渲染用户数据。
7. 新增字段注入（`@Autowired` on field）、静态方式取 Bean。
8. 在业务代码硬编码 IP、密钥、超时、限流阈值等可调参数。
9. 用 `'`/`<`/`>` 字符黑名单防注入；正确做法是参数化 SQL 和上下文编码。
10. 提交运行日志、`.env`、锁文件解析结果以外的环境残留；`pnpm-lock.yaml` 必须随提交更新。
11. 无测试的 Service/Controller 新逻辑；注释掉的死代码；`// 前端已校验` 之类的借口。
12. 全平台通用的 `status` 更新接口让客户端跳任意状态——状态变化走显式命令。

## 4. PR 自检清单（提交前逐项过）

- [ ] 从仓库根目录执行[根 README](../README.md) 的完整 Harness 验证，记录结果；单独的单元测试不能替代覆盖率、构建和集成门禁
- [ ] 每个新参数校验覆盖：正常 / 边界 / null / 超长 / 特殊字符 / 类型错误
- [ ] 新接口有权限点，且种子菜单通过 migration 提交
- [ ] 错误码未与存量撞码；新文案中文、句式统一
- [ ] 行为变更涉及 README/契约文档的，同一 PR 更新
- [ ] 无新增 TODO 无 owner；无被忽略的测试
- [ ] diff 里无日志文件、无敏感信息、无与本 PR 无关的改动

## 5. 新增横切能力（认证方式、存储后端、消息渠道等）

不要改核心循环。按"能力接缝"三步走：

1. 在所属 starter 定义 Contract（接口 + Properties + 失败语义）。
2. 新增 Provider 实现并注册（条件装配，`@ConditionalOnProperty`）。
3. Consumer 只依赖 Contract。

完成后在该 starter README 的扩展点表登记一行。新增 Provider 若需要修改既有 Consumer，说明接缝设计有问题，先停下来讨论。

## 6. 新增定时任务

1. 在所属业务模块实现 `JobHandler`，Bean 名作为全局唯一调度标识；参数只承载小型、可版本化配置，不传秘密或大对象。
2. 先定义副作用幂等键、重复执行结果、失败重试边界和人工补偿方式。Quartz JDBC JobStore 解决调度记录持久化，不提供业务 exactly-once。
3. 通过管理端创建任务；数据库中的 `infra_job` 是业务配置源，`QRTZ_*` 是运行时调度状态。漂移时使用受权限保护的“同步任务”，禁止直接修改 Quartz 表。
4. 单测至少覆盖重复调用和失败重试；涉及 SQL、缓存、消息或外部副作用时，用 Testcontainers 验证真实边界，并包含关闭后重启场景。
5. 默认 misfire 为 `DO_NOTHING`，停机期间错过的 cron 不补跑。确有补跑需求时应作为独立能力设计，明确去重、限流、审计和最大追赶窗口，不得在业务代码中隐式改变全局策略。
