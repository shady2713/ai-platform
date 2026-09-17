# M03 文本流与结构化输出适配证据（2026-09-17）

本记录是 [M03 实现文本流与结构化输出适配](../tasks/M03.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 事件与流契约 | `starter-ai` `core/model`：`ModelEvent`（`DELTA`/`TOOL_CALL`/`COMPLETED`）、`ModelStream`（`hasNext`/`next`/`close`/`idleTimeout`）、`ModelToolCall` |
| 用量词表 | `core/model/ModelUsage`：`null` = UNKNOWN、`estimated=true` = 估算值；**禁止用假 0 表达缺失**（AT-060） |
| 结构化输出契约 | `core/model/StructuredModelRequest`、`StructuredModelResult`、`StructuredJsonOutput`（有界修复 + 对象校验） |
| 端口扩展 | `ModelPort.stream(ModelRequest)`、`ModelPort.generateStructured(StructuredModelRequest)`，默认实现按能力缺失拒绝并指明能力名 |
| 调用护栏配置 | `config/AiModelProperties`（`basic-framework.ai.model.*`）：`max-attempts`、`retry-backoff`、`max-output-chars`、`stream-idle-timeout`、`stream-queue-capacity`、`max-repair-steps` |
| 文本流适配 | `provider/springai/SpringAiModelStream`：增量下发、工具调用分片聚合、空闲超时、输出上限、`close()` 取消订阅 |
| 客户端扩展 | `provider/springai/SpringAiModelClient`：`stream`、`generateStructured`、有界重试、`mapFailure` 增加限流/拒绝映射、工具调用只作为数据 |
| 装配 | `BasicFrameworkAiAutoConfiguration` 注册 `AiModelProperties` 并把护栏下发到工厂创建的每个客户端 |
| 契约夹具 | `src/test/resources/ai/fixtures/`：合法、围栏+前后文字、末尾逗号、不可修复四份模型输出夹具 |
| 测试 | `SpringAiModelStreamTest`(9)、`SpringAiStructuredOutputTest`(7)、`SpringAiModelRetryTest`(5)、`StructuredJsonOutputTest`(9)、`StructuredOutputFixturesTest`(4)、`ModelEventTest`(5)、`ModelPortDefaultsTest`(4)、`AiModelPropertiesTest`(3)，以及跟随用量词表调整的 `SpringAiModelClientCoverageTest`(6)、`ModelContractTest`(4) |

## 2. 与卡片逐步实施的对应

1. **上游文本/usage/tool-call → 自有事件**：`SpringAiModelStream` 订阅厂商流并把文本增量转成 `DELTA`；
   用量从响应元数据映射为 `ModelUsage`；工具调用参数在厂商侧是**分片增量**，适配层按调用标识聚合后
   在流结束前一次性给出 `TOOL_CALL`。厂商类型不出 `provider.springai` 包（ArchUnit vendor 规则仍生效）。
2. **结构化 JSON 校验、能力缺失明确报错**：`generateStructured` 先校验请求 Schema 是可解析 JSON 对象
   （`INVALID_STRUCTURED_INPUT`），再要求端点声明 `STRUCTURED_OUTPUT` 能力，缺失时
   `CAPABILITY_UNSUPPORTED` 且消息包含缺失能力名；模型返回经 `StructuredJsonOutput` 有界修复后
   必须是 JSON 对象，否则 `INVALID_STRUCTURED_OUTPUT`。平台把 Schema 作为输出契约嵌入提示词
   （`SpringAiStructuredOutputTest.sendsSchemaAsOutputContractToUpstream` 断言）。
3. **限制输出大小、重试与流式超时，不自动执行工具**：
   - 输出大小：文本增量累计超过 `max-output-chars` 立即中断订阅并抛 `OUTPUT_LIMIT_EXCEEDED`；
   - 重试：`withRetry` 只重发 `TIMEOUT`/`RATE_LIMITED`/`UPSTREAM_FAILED`，次数由 `max-attempts` 限定，
     其余原因（上游拒绝、能力缺失、结构化输入/输出非法、超限）一律不重发；
   - 流式超时：事件间等待超过 `stream-idle-timeout`（可被请求超时收紧）抛 `TIMEOUT` 并关闭流；
   - 工具：接缝不注册任何工具回调或厂商选项（`neverRegistersToolCallbacksSoToolsAreNotExecuted` 断言
     `Prompt.getOptions()` 为空），`TOOL_CALL` 只作为数据交给平台工具层。

## 3. 关键约束与安全语义

- **假 0 防护**：Spring AI 用 `EmptyUsage` 表达"上游没有给用量"，其 getter 会把缺失计数报成 `0`；
  `SpringAiModelClient.toUsage` 显式把 `EmptyUsage`/`null` 还原为 `ModelUsage.UNKNOWN`，
  并在消息与文档中禁止把 UNKNOWN 写成 0（AT-060）。
- **失败收敛**：`mapFailure` 按异常类型映射 —— 类名含 `timeout` → `TIMEOUT`；
  `NonTransientAiException` → `UPSTREAM_REJECTED`（不重发）；`TransientAiException` → `RATE_LIMITED`；
  其余 → `UPSTREAM_FAILED`。消息不含厂商报文（用例断言泄漏串 `sk-must-not-leak` 不出现）。
- **修复有界且不改写合法输出**：只有解析失败才依次尝试三种确定性修复（去围栏 → 取最外层对象 →
  去末尾逗号），每步计入 `max-repair-steps`（0 表示不修复）；不补字段、不猜语义、不自动重问上游
  （夹具用例断言畸形输出只调用上游一次）。
- **连接释放**：空闲超时、输出越界、上游错误与显式 `close()` 都会 `dispose()` 上游订阅；
  `close()` 幂等并用闩锁用例断言取消回调确实触发。
- **背压与隔离**：事件进入容量为 `stream-queue-capacity` 的有界队列（默认 64），订阅在
  `boundedElastic` 调度线程上建立，消费慢时形成背压而不是堆内存。

## 4. 验证结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -pl basic-framework-core/basic-framework-spring-boot-starter-ai verify` | 0 | 71 例通过；新文件行覆盖率均 ≥ 80% |
| `./mvnw -pl basic-framework-module-ai -am verify` | 0 | 依赖链全绿，`module-ai` 未受契约扩展影响（Mockito 桩走默认方法） |
| `SpringAiModelStreamTest` | 0 | 增量/工具聚合/UNKNOWN 用量/空闲超时/输出上限/上游失败/关闭释放连接/能力缺失 |
| `SpringAiStructuredOutputTest` + `StructuredJsonOutputTest` + `StructuredOutputFixturesTest` | 0 | 合法 JSON、三种修复、预算为 0、非对象、畸形输出、Schema 非对象、能力缺失 |
| `SpringAiModelRetryTest` | 0 | 429 重试后成功、上限截断、上游拒绝不重发、超时重试、可重试白名单 |
| `node scripts/check-starter-documentation.mjs` | 0 | starter README 章节契约通过 |

## 5. 门禁与棘轮

按卡片顺序执行（授权副本根目录 `/home/ctyun/桌面/zhongtai/ai-platform`）：

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约、字段目录、权限与静态检查通过 |
| `sh .harness/verify.sh backend` | 0 | 全量后端构建、单元测试（starter 120 例含 M03/M04 用例）与架构门禁通过 |
| `sh .harness/verify.sh integration` | 见交接记录（M04 交付时在同一树上一并复验） | Testcontainers 全链路；期间修复了 V49 迁移的菜单种子列数错误（MySQL 1136）后重跑 |
| `sh .harness/verify.sh integration` | 0 | Testcontainers（MySQL 8.4）全部集成用例通过，门禁末尾的覆盖率棘轮检查同时通过 |

集成门禁期间修复的两个真实缺陷：V49 迁移菜单种子列数不匹配（MySQL 1136）、
快照 `数据库文件/basic_framework.sql` 菜单 4004 行缺右括号（快照回放用例失败）。

**覆盖率棘轮（已收口）**：补测后 `node scripts/check-coverage-ratchet.mjs --update` 登记基线，
`node scripts/check-coverage-ratchet.mjs all` 复验通过（只升不降）。AI 文件的登记值：

| 文件 | 行覆盖率 |
|---|---|
| `SpringAiModelClient` | 98.45% |
| `SpringAiModelClientFactory` | 100% |
| `AiModelClientResolver` | 100% |
| `AiModelInvocationServiceImpl` | 97.83% |
| `core/model/*`（M03/M04 新增契约） | 100%（`StructuredJsonOutput` 94.44%） |
| `AiModelCapabilityProbeServiceImpl` | 95.08% |
| `SpringAiModelStream` | 86.26% |


## 6. 未验证项

1. **AT-002 的 Controller 侧 422**：本卡交付的是**服务层**能力缺失的明确错误（消息带缺失能力名）。
   发布接口返回 422 属应用与授权（A 系列）与运行编排卡的 API 层职责；另注意
   [错误码映射](../../contracts/ai/error-code-map.md) 中 `AI_MODEL_CAPABILITY_UNSUPPORTED` 记为 400，
   而 [验收目录](../08-testing-acceptance.md) 的 AT-002 写的是 422，两者需要在 API 层卡片中统一口径
   （本卡不擅自改动已冻结映射）。
2. **AT-060 的 UI 侧**：本卡只保证契约层区分 UNKNOWN/ESTIMATED；展示属模型管理页面（M06）与 Chat 前端（Q 系列）。
3. **请求级传输治理**：与 M02 一致，厂商 HTTP 传输尚未替换为 F09 的 `GuardedExternalHttpClient`，
   当前在创建期校验出站允许清单。
4. **真实厂商流式端点**：流式与结构化用例使用桩 `ChatModel` 与假 OpenAI 兼容端点；真实端点的
   增量时序、工具调用分片与用量字段差异属环境验收（需凭据）。
5. **非流式路径的工具调用后续轮次**：本卡只把工具调用作为数据暴露，工具执行与结果回填由工具层（D08/D09）实现。
