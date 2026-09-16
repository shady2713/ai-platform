# 01 框架事实与工程约束

## 1. 只读调查基线

- 来源：`E:\kuangjia\2026-main`。
- Git HEAD：`23a7edb375939a84bdfd01e8d1a68e7b016aab59`。
- 提交时间：2026-09-13 12:32:44 +08:00。
- 调查日期：2026-09-16；开始检查时 `git --no-optional-locks status --short` 无输出。
- 仅执行列目录、文本搜索、读取文件和无可选锁的 Git 查询；未执行构建、安装、格式化、迁移或生成代码。
- 当前目录是原框架，不是可写开发目标。计划中的路径均相对于未来授权的工作副本。

## 2. 已核实的技术栈

| 项目 | 框架事实 | 权威位置（相对源根目录） |
|---|---|---|
| Java / Boot | Java 17 / Spring Boot 3.5.16 | `后端代码/basic-framework-boot/pom.xml` 和 dependencies/pom.xml |
| 后端组织 | Maven 多模块、唯一 server 装配入口 | 同上，`basic-framework-server` |
| 数据访问 | MyBatis Plus 3.5.15、MySQL 8、Flyway | dependencies/pom.xml、根 README |
| 缓存 | Redis 7 | 根 README；redis starter |
| 前端 | Vue 3、TypeScript、Vben 工作区、Element Plus | `前端代码/basic-framework-admin/apps/web-ele/package.json` |
| 前端工具 | pnpm 10.28.2，Node >=20.19，Turbo，Vite | 前端 package.json、pnpm-workspace.yaml |
| 前端版本约束 | Vue ^3.5.27 / Element Plus ^2.13.1 / TS ^5.9.3 / Vite ^7.3.6 | pnpm-workspace.yaml；精确解析版本以锁文件为准 |
| 数据迁移 | 当前最高 V46；存在历史版本号间隙 | server/src/main/resources/db/migration |
| 组件依赖管理 | 后端 BOM 集中版本；前端 catalog + pnpm-lock.yaml | dependencies/pom.xml、pnpm-workspace.yaml |
| 测试 | JUnit5/Mockito、Testcontainers MySQL/Redis、Vitest | AGENTS.md、docs/development-guide.md |
| 浏览器冒烟 | ADR 已接受，尚未落地 | docs/adr/0046-browser-smoke-gate.md |

不要把目录中历史 migration（例如早期 CRM、代码生成、MFA）误认为现有功能；后续迁移已经移除部分能力。

## 3. 可复用能力与明确缺口

| 领域 | 可复用 | 集成时必须补充 |
|---|---|---|
| 管理后台 | 用户、角色、菜单、部门、会话、审计 | AI 管理菜单、权限点和资源授权 |
| 模块边界 | system-api / infra-api 薄契约 | AI 薄契约；新增 FileCommonApi；同步显式 ArchUnit 允许清单 |
| 文件 | 私有文件、所有者、路径与归档校验、失败补偿 | AI 文档/附件业务授权接缝；原 FileService 未作为跨模块 API 发布 |
| 加密 | CredentialCipher，AES-256-GCM、随机 nonce、AAD | 模型/API/数据库凭据通过此接口存储；禁止复制密码学代码 |
| 防护 | RateLimiter、Idempotent、Lock4j | 应用/服务/用户配额与任务幂等语义 |
| 数据权限 | DeptDataPermissionRuleCustomizer | AI 资源按 app/subject 控制；外部业务数据库不能自动沿用本地部门插件 |
| 异步 | Quartz、JobHandler、受管执行器 | 可恢复 AI 任务表、有限重试、租约和取消；当前没有 MQ starter |
| Web | CommonResult、语义 HTTP 状态、OpenAPI、JSON 上限、脱敏 | 公开能力 API、流式协议、SDK 文档、媒体/任务协议 |
| 认证 | ADMIN 会话，MEMBER 扩展点 | AI 外部主体 Provider、应用换票；不能将外部调用变成管理员会话 |
| 浏览器登录 | access token 内存；refresh token 同站 HttpOnly Cookie | 跨站嵌入采用短期换票，保持后台登录规则 |
| iframe | 现有响应为 X-Frame-Options SAMEORIGIN | 独立 embed 页面响应策略和精确 frame-ancestors；不可全局关掉保护 |
| AI/RAG/报表 | 尚未发现已实现的对应业务模块 | 按本包新增；不把上游示例当成已有能力 |

## 4. 必须遵守的代码标准

以下为任务执行索引，原规则仍以源仓库文件为准：

### 4.1 Java 与模块

- 生产依赖使用构造器注入；新文件不能新增字段注入。
- Controller 处理协议、校验、鉴权；Service/DAL 不引用 controller VO。新增 CreateReqVO / UpdateReqVO 分离。
- 跨模块只访问已发布 CommonApi + DTO，禁止访问对方 Service、Mapper、DO。新增契约类加入 ModuleBoundaryArchitectureTest 显式清单；AI 模块也要纳入双向隔离规则。
- 异常使用模块 ErrorCodeConstants；禁止裸异常正文进入响应或日志。记录诊断使用 SafeExceptionLogUtils。
- 管理端使用 `@PreAuthorize("@ss.hasPermission('ai:资源:动作')")`，每个映射恰好一种访问策略；接口参数完整校验。
- 业务配置进入受验证 Properties；管理维护的秘密走高风险接口和 CredentialCipher；部署主密钥来自 Secret/环境。
- 公共写服务事务按仓库规范；外部模型和远程 API 调用不要占用长数据库事务。
- 逻辑源码单文件 <=800 行；200–400 行是拆分参考。不得把建议复杂度阈值描述成现有强制门禁。

### 4.2 Vue 与前端工作区

- 管理页面落在 apps/web-ele；API namespace 使用 AiXxxApi；文件 kebab-case；组合函数 use-*.ts。
- 字段规则从 adapter/field-rules.ts 获取；新规则先登记字段目录；反馈经过 utils/feedback.ts；CRUD 使用 use-crud-actions。
- 错误处理明确 global / inline / silent，避免重复弹窗。已有后台 request.ts 的令牌和刷新策略不可照搬到第三方嵌入端。
- 新增源码不得引入显式 any；组件覆盖 loading/empty/error/ready/disabled 状态。
- 禁止 `v-html`、`innerHTML` 和拼接 HTML 渲染用户/模型数据。Markdown 使用 AST + 允许的 Vue 节点渲染；代码仅按文本显示。
- 新 workspace 包有真实 typecheck；Vue 包用 vue-tsc，纯 TS 包用 tsc；新增包纳入覆盖率、依赖和生产构建检查。

### 4.3 数据与安全契约

- Flyway 是迁移权威；新增版本在实际开发时取下一个未用编号，不能修改已执行 V1–V46。
- 同步数据库快照与版本声明，更新 field-catalog.yaml、data-lifecycle.json、数据权限分类和 permission-catalog。
- BaseDO 不隐式包含软删除；每表明确 hard-delete / soft-delete / append-retention / platform-managed。软删必须有恢复或最终清理策略。
- 当前 Long 的 JSON 行为是安全整数为 number、超大值为 string；不能声称框架已经全量 string ID。开放API采用新公开字符串ID是本方案契约提案，须由F07/F08完成ADR与字段目录决策后实施；管理内部ID保持既有契约，详见05。
- ADMIN 授权不能跨请求缓存；AI 授权同样按当前有效策略判定；权限撤销覆盖会话、检索、任务、文件与报表读取。
- L3/L4 不进通用调用正文日志；token/密钥只在授权创建或轮换时返回一次。用户对话正文保存属于受控业务数据，不可复制到访问日志。

## 5. 必须形成新 ADR 的变化

现有 ADR 0006 明确基础框架无开放 API、无富文本。用户已经提出开放 API 和报表需求，因此实施副本中应新增 ADR，解释扩展后的边界；保留旧 ADR 历史，不通过局部特判绕过。

拟新增 ADR：AI 业务模块及适配边界、开放应用身份、文件业务资源授权、结构化报表、异步任务协议、向量索引策略、N/N-1 兼容与升级。数字编号实施时分配。

## 6. 验证门禁

在授权工作副本根目录运行 `& .\.harness\verify.ps1 all`；Linux 为 `sh .harness/verify.sh all`。现有六项：backend、integration、frontend、dependencies、contracts、lockfile；历史秘密扫描由 CI 独立 job 负责。

- 新文件覆盖率至少 80%，存量不得下降；先真实执行再更新 baseline，禁止先改数字。
- 新模块必须加入 basic-framework-coverage/pom.xml 的直接依赖，薄 API 模块同样生成覆盖报告。
- 新 check 脚本必须有代表性违规拒绝测试、双平台接线及阻断聚合。
- 浏览器集成需要新增真实门禁；现有 ADR 0046 不是已运行的 Playwright 测试。
- 原始只读调查期间未在源框架运行上述门禁，以避免产生构建文件；后续独立副本的初始化检查见[00](00-project-bootstrap.md)，文档包校验另见verification。

## 7. 关键源码定位

所有路径相对源框架根：

- `AGENTS.md`、`README.md`、`docs/development-guide.md`。
- `docs/security/data-classification.md`、`docs/security/high-risk-operations.md`。
- `docs/contracts/README.md`、`docs/data-lifecycle.md`。
- `.harness/README.md`、`.harness/AGENTS.md`。
- `后端代码/basic-framework-boot/basic-framework-server/src/test/java/com/basicframework/server/ModuleBoundaryArchitectureTest.java`。
- `后端代码/basic-framework-boot/basic-framework-core/basic-framework-spring-boot-starter-security/README.md`。
- `后端代码/basic-framework-boot/basic-framework-core/basic-framework-spring-boot-starter-web/README.md`。
- `后端代码/basic-framework-boot/basic-framework-module-system-api/src/main/java/com/basicframework/module/system/api/session/UserSessionCommonApi.java`。
- `后端代码/basic-framework-boot/basic-framework-module-infra/src/main/java/com/basicframework/module/infra/service/file/FileService.java`。
- `前端代码/basic-framework-admin/apps/web-ele/src/api/request.ts`。

后续源 HEAD 变化时，任务 F01 重新调查差异；不得假定本文件永远等于最新代码。
