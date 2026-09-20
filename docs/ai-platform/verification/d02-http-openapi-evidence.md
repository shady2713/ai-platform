# D02 声明式 HTTP 与 OpenAPI 导入证据（2026-09-20）

本记录是 [D02 实现声明式HTTP和OpenAPI导入](../tasks/D02.md) 的验收证据。
依赖 [D01](../tasks/D01.md)（连接器配置与秘密）、[F07](../tasks/F07.md)（协议冻结）、[F10](../tasks/F10.md)（夹具）
均已有证据文档（`d01-connector-evidence.md`、`f07-protocol-freeze-evidence.md`、`f10-fixtures-evidence.md`），
本卡只消费其公开契约，未新增第二套身份或存储。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 持久化 | 迁移 `V67__ai_connector_operation.sql`：`ai_connector_operation`（声明式操作草稿/发布 + 乐观锁）；菜单 4055/4056（`ai:connector:import`、`ai:connector:operation`）；快照同步至 V67（逻辑删除表 35 → 36） |
| OpenAPI 导入器 | `service/connector/importer/AiOpenApiImporter(+Impl)`：限定范围解析 OpenAPI 3 文档，产出 DRAFT 操作草稿与逐条跳过原因 |
| 操作服务 | `service/connector/importer/AiConnectorOperationService(+Impl)`：导入落库、重导入回到 DRAFT、发布（DRAFT + 乐观锁 + 仅 HTTP 连接器）、按连接器查询 |
| 认证头构造 | `adapter/connector/http/AiConnectorAuthHeaders`：请求头**只能**由连接器自身配置生成（BEARER/BASIC），秘密只在内存解密 |
| HTTP 执行器 | `adapter/connector/http/AiHttpConnectorExecutor`：固定 Origin + path 模板 + 有界分页 + 响应提取，结论三态 COMPLETE/PARTIAL/FAILED |
| 控制面 API | `controller/admin/connector/AiConnectorController` 新增 `POST /{id}/import`、`GET /{id}/operations`、`POST /operation/publish`、`POST /operation/execute` 与 5 个 VO（请求体里没有 url/headers 字段） |
| 错误码 | `1_003_006_005`–`009`：`AI_CONNECTOR_OPERATION_NOT_FOUND`/`NOT_PUBLISHED`/`IMPORT_INVALID`/`ORIGIN_MISMATCH`/`ARGUMENT_INVALID`，与 `docs/contracts/ai/error-code-map.md` 两侧同步 |
| 台账同步 | `data-lifecycle.json`（新表进软删除 + 外键 `fk_ai_connector_operation_connector`）、`data-permission-exemptions.json`（`ai-connector-config` 豁免扩展本表并补逐表证据）、`PersistenceLifecycleIT` 预期表清单、`数据库文件/basic_framework.sql` 快照 |
| 测试 | `AiOpenApiImporterImplTest`(4)、`AiHttpConnectorExecutorTest`(4)、`AiConnectorAuthHeadersTest`(2)、`AiConnectorControllerTest`(5)、`AiConnectorOperationIT`(3，真实 MySQL) |

## 2. 与卡片逐步实施的对应

1. **限定 OpenAPI 文档与 operation 导入草稿**：只接受 OpenAPI 3 文档的 `paths` 下 `get`/`post` 操作，
   文档上限 512 KB、操作上限 200、每操作参数上限 32；导入结果一律 `DRAFT`，
   必须显式发布（`POST /operation/publish`）才可执行——`AiConnectorOperationIT` 断言"未发布执行 → 409
   `AI_CONNECTOR_OPERATION_NOT_PUBLISHED`"，发布后才可执行；重导入把已发布操作**退回 DRAFT**，需重新发布。
2. **参数映射/响应提取/分页终止规则，禁脚本与外部 ref 自动抓取**：
   - 参数只保留 `name`/`in`/`required`/`type` 四要素（白名单键），文档里出现的 `example`/`default`/`schema` 细节
     与 `x-` 扩展一律不落库；header/cookie 位置的参数**丢弃并记录跳过原因**（请求头不能由模型或文档注入）。
   - 响应提取是 JSON 指针列表（限定深度与条数），不是脚本；导入拒绝任何脚本类字段。
   - `$ref` 只解析**文档内** `#/` 引用；出现外部引用（http/file 等）的操作用**跳过并记录原因**代替自动抓取，
     从不发起网络请求解析引用（AT-041）。
   - 分页规则落库为 `NONE`/`PAGE`/`CURSOR` + 参数名 + 页数上限 + 游标字段，执行期不解释任何表达式。
3. **执行固定 Origin/path 模板和有限分页**：执行时 URL 由连接器 `baseUrl` + 操作 `path_template` 拼出，
   路径模板必须以 `/` 开头且不得含 `..`（否则 `AI_CONNECTOR_ORIGIN_MISMATCH`）；
   拼接结果再次做同源校验（scheme/host/port 全等）后才交给出站边界；
   分页上限 `MAX_PAGES_LIMIT = 20`（声明值再取 `min`），命中**重复游标/重复页**立即停止（`repeated-cursor`），
   达到页数上限结论是 `PARTIAL` 而不是"取完了"；条目数与单条长度也有上限（1000 条 / 4000 字符）。

## 3. 关键约束与安全语义

- **header/URL 不能由模型替换**（本卡核心安全语义）：执行请求体（`AiConnectorExecuteReqVO`）与
  执行请求 DTO 里**没有** url/header/credential 字段，只有 `connectorId`/`operationKey`/参数值；
  请求头只来自 `AiConnectorAuthHeaders`，且其输入只有连接器行与连接器配置。
  `AiHttpConnectorExecutorTest.usesFixedOriginAndConnectorAuthHeadersOnly` 断言实际发出的请求头集合
  与连接器配置生成的完全一致；`rejectsForeignOriginUnsafeArgumentsAndUndeclaredParameters` 断言
  绝对 URL 形式的 path 模板被拒（`AI_CONNECTOR_ORIGIN_MISMATCH`）。
- **SSRF 防线（AT-040）**：同源校验 + 路径模板约束 + 出站统一经 F09 冻结的受控出站客户端（默认拒绝一切目标）；
  导入期即拒绝 `..` 与不以 `/` 开头的路径。重定向一律不跟随：出站边界固定 `Redirect.NEVER`，
  执行器把**一切非 2xx（含 3xx）**都判为 `FAILED` + `HTTP_<status>`——若把 302 当成"空页"，
  会得出"已取完、共 0 条"的错误结论，正是 AT-039 禁止的"宣称总额完整"。
- **参数值只接受安全标量**：`NAME_PATTERN`/`VALUE_PATTERN` 限定参数名与取值形态，
  含查询语法（`&`/`?`/`#`/空格等）的取值被拒（`AI_CONNECTOR_ARGUMENT_INVALID`），避免参数拼接改变请求语义。
- **分页结论不得夸大（AT-038/039）**：`COMPLETE` 只在确认"没有下一页"时给出；
  页数上限截断 → `PARTIAL` + `stoppedReason = page-limit`；上游失败 → `FAILED` + 稳定 `detailCode`，
  绝不返回"看似完整"的总额；结果 DTO 只带裁剪后的条目，`items` 不进 `toString`。
- **导入是声明式、有界的（AT-041）**：文档/操作/参数三层上限 + 只解析文档内 `$ref` + 跳过原因可追溯
  （`AiOpenApiImporterImplTest.skipsUnsupportedOperationsAndRecordsReasons`）。
- **秘密不回显**：执行链路只把解密结果放进本次请求头；响应 VO 字段名单里没有秘密/密文，
  `AiConnectorControllerTest.responsesNeverCarrySecretOrCiphertext` 逐字段断言。

## 4. 验收用例对照

| 用例 | 覆盖点 | 证据 |
|---|---|---|
| AT-038 | 全部分页参与统计，最终 `COMPLETE` | `AiHttpConnectorExecutorTest.paginationStopsOnRepeatedCursorAndMarksPartialAtPageLimit`（无下一页 → COMPLETE） |
| AT-039 | 截断/失败必须 `PARTIAL`/失败，不能宣称总额完整 | 同测试（页数上限 → `PARTIAL` + `page-limit`）；`rejectsDisabledConnectorDraftOperationAndUpstreamFailures`（上游失败 → 稳定原因码） |
| AT-040 | 未授权目标被拒（含重定向） | `rejectsForeignOriginUnsafeArgumentsAndUndeclaredParameters`（异源与绝对 URL 模板）、`rejectsDisabledConnectorDraftOperationAndUpstreamFailures`（302 → `HTTP_302`，不跟随）、`AiOpenApiImporterImplTest.derivesOperationKeyWhenMissingAndRejectsUnsafePaths`（导入期 `..` 拒绝） |
| AT-041 | 有界解析，不自动访问外部资源 | `AiOpenApiImporterImplTest.skipsUnsupportedOperationsAndRecordsReasons`（外部 `$ref` 跳过）、`rejectsDocumentsWithoutImportableOperationsOrWithInvalidShape`（上限/形状） |

## 5. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。
命令前统一 `umask 022; export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -o -pl basic-framework-module-ai test` | 0 | 336 例通过（D02 新增 15 例：导入器 4、执行器 4、认证头 2、控制器 5） |
| `./mvnw -Pintegration -pl basic-framework-server test -Dtest=AiConnectorOperationIT` | 0 | 3 例通过（真实 MySQL：导入落草稿、未发布不可执行、重导入退回草稿、非 HTTP 连接器与未知连接器拒绝） |
| `sh .harness/verify.sh contracts` | 0 | 契约台账、权限目录、生命周期、字段目录、安全检查通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单测、格式、架构与覆盖率检查通过 |
| `sh .harness/verify.sh integration` | 1 | 仅尾部分页棘轮因新文件未登记而失败；47 个 IT 类 / 138 例 **0 失败 0 错误**（含 `AiConnectorOperationIT` 3 例） |
| `node scripts/check-coverage-ratchet.mjs --update` | 0 | 登记 5 个新文件，无基线下调、无登记删除 |
| `node scripts/check-coverage-ratchet.mjs all` | 0 | 单文件基线全部通过 |

## 6. 覆盖率

| 文件 | 覆盖率 |
|---|---|
| `adapter/connector/http/AiConnectorAuthHeaders.java` | 90.91% |
| `adapter/connector/http/AiHttpConnectorExecutor.java` | 84.31% |
| `dal/mysql/connector/AiConnectorOperationMapper.java` | 100% |
| `service/connector/importer/AiConnectorOperationServiceImpl.java` | 91.38% |
| `service/connector/importer/AiOpenApiImporterImpl.java` | 86.51% |

（首次门禁 `AiConnectorAuthHeaders` 为 63.64%，因为只有 BEARER 分支被间接覆盖；
补齐 `AiConnectorAuthHeadersTest` 覆盖 NONE/无密文/BASIC 三个分支后达标，未下调任何基线。
执行器补上 3xx 失败分支后覆盖率上升，基线按实测登记。）

## 7. 顺带修复的依赖缺口

1. **唯一键只约束未删除行（沿用 D01 模式）**：`uk_ai_connector_operation_key` 使用 MySQL 8 函数索引
   `(if(deleted = b'1', NULL, concat(connector_id, ':', operation_key)))`，
   使同一连接器下同一 `operation_key` 可反复导入/删除而不撞历史已删除行。
2. **`AiConnectorControllerTest` 扩展为权限策略契约测试**：本卡新增的 4 个端点与既有端点一起断言
   "每个端点恰好一个鉴权策略注解"，并把权限码与 V67 菜单种子做双向比对（`permissionsMatchMigrationSeeds`）。
3. **`scripts/secret-scan.mjs`**：登记 `AiConnectorAuthHeadersTest` 中的一次性解密替身明文，
   避免假凭据字面量被提交钩子拦截。

## 8. 未验证项

1. **真实上游 HTTP 目标**：本机未配置出站允许清单与可访问的 HTTPS 上游，端到端只验证到
   "策略拒绝/上游失败 → 稳定原因码"与固定 Origin 拼接；真实连通性与重定向处理由部署环境验证。
2. **`CURSOR` 分页的真实游标语义**：三种分页模式均已实现并有单测（重复游标停止、页数上限 PARTIAL），
   但真实第三方游标字段的兼容性需对接真实系统时确认。
3. **连接器操作页面**：菜单 4055/4056 已随迁移落地，页面在 D10（连接器语义与工具管理页面）交付。
4. **OpenAPI 3.1 / Swagger 2.0**：本卡按 OpenAPI 3.0 形状解析；非 3.0 文档在导入期被拒绝并给出稳定错误码，
   其他版本支持未验证。
