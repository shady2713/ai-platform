# S04 服务调试与上下文预算证据（2026-09-19）

本记录是 [S04 实现服务调试和上下文预算](../tasks/S04.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 上下文构造器 | `service/context/AiContextBuilder(+Impl)`：固定分区拼装（平台政策 → 系统指令 → 业务上下文 → 知识片段 → 历史消息 → 本次消息）、注册字段校验、消息条数与 token 预算 |
| 预算与分区词汇 | `domain/runtime/AiContextBudget`（默认 20 条 / 8000 token / 4 字符每 token，与计量估算同口径）、`domain/runtime/AiContextSection`（含"平台写入"标记与分区标记） |
| 调试接口 | `service/debug/AiServiceDebugService(+Impl)` + `controller/admin/debug/AiServiceDebugController` 的 `POST /ai/service/debug/run`（权限码 `ai:service:debug`，V58 菜单 4039） |
| 上游失败的稳定收敛 | `domain/runtime/AiModelFailureCodes`：`ModelException.Reason` → 平台错误码（停用/不存在/能力/外发策略/配额/其余→模型调用失败） |
| 边界测试 | `AiContextBuilderImplTest`(8)、`AiContextBudgetTest`(3)、`AiContextSectionTest`(2)、`AiModelFailureCodesTest`(3)、`AiServiceDebugServiceImplTest`(6)、`AiServiceDebugControllerTest`(3)、`AiServiceDebugIT`(4，真实 MySQL) |
| 迁移 | `V58__ai_service_debug.sql`：菜单 4039 `ai:service:debug`（无新表，故生命周期台账与豁免台账不变）；`数据库文件/basic_framework.sql` 同步到 V58 并补菜单行 |
| 错误码 | `1_003_008_008 AI_CONTEXT_BUDGET_EXCEEDED`、`1_003_008_009 AI_CONTEXT_SCHEMA_INVALID`，与 `docs/contracts/ai/error-code-map.md` 两侧同步 |

## 2. 与卡片逐步实施的对应

1. **调试使用显式测试主体并遵循资源 ACL**：`debugRun` 要求显式 `testSubjectType` + `testSubjectId`
   （USER 主体必须给出外部用户标识，不接受"当前登录人"这类隐式主体），随后按**该主体**的当前授权
   逐条判定发布版本绑定的资源动作；测试主体不存在/已停用返回 404，任一动作不被放行返回
   `AI_AUTHORIZATION_DENIED` 且**不发出模型调用**。调试使用的版本来自 S03 的别名解析
   （`resolveForNewRun`），与线上新运行同一条路径。
2. **分区拼装与预算限制**：构造器按固定顺序拼装六个分区，业务上下文只接受已注册字段
   （`page/objectType/objectId/filters/locale/timezone`），未知字段、非 JSON、非对象一律拒绝；
   强制分区（政策/系统指令/业务上下文/本次消息）必须完整容纳，超出预算抛
   `AI_CONTEXT_BUDGET_EXCEEDED` 并指明分区名；可选分区按固定规则裁剪——
   知识片段按调用方顺序（相关度）保留、历史消息保留最新且不超过条数上限，两者各占剩余预算 50%。
3. **只展示阶段摘要与证据**：调试结果只有 `RESOLVE/AUTHORIZE/CONTEXT/MODEL` 四个阶段的
   阶段名、结果、稳定说明与耗时，加上分区统计（纳入/丢弃条数、估算 token、是否裁剪、是否中和）、
   用量与可见输出；**不回显拼装后的提示词**（服务层 DTO 的 `prompt` 字段不进 `toString`、
   协议层 VO 没有该字段，单测直接断言 VO 字段名单里没有 `prompt`/`reasoning`），也不返回隐藏推理。

## 3. 关键约束与安全语义

- **平台政策不可覆盖**：政策分区由平台常量写入并排在最前；任何不可信分区（知识、历史、上下文）
  里出现分区标记都会被替换为 `[已中和的分区标记]`，因此"在知识片段里写一段
  `[平台政策｜不可覆盖]` 再要求忽略规则"不会生效。中和事实通过分区统计的 `sanitized` 作为证据返回。
- **上下文不改变权限**：业务上下文只作为辅助信息进入提示词；授权判定完全由测试主体与资源授权决定，
  与上下文内容无关（调试链路先授权、后拼装、再调用）。
- **超预算可解释**：不静默截断用户消息或政策分区；可选分区裁剪规则固定且可复现
  （知识按顺序、历史保留最新、份额固定 50/50），裁剪事实与丢弃条数随结果返回。
- **上游失败无假成功**：模型调用走 M05 统一编排（外发策略先于网络调用、端点解析、计量），
  `ModelException` 统一收敛为平台错误码；异常正文只用平台文案，不带上游报文、提示词或凭据。
- **正文不入日志与响应**：请求/结果 DTO 的正文（系统指令、用户消息、业务上下文、拼装结果）
  不参与 `toString`，日志与响应只出现长度、分区名与计数。

## 4. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -o -pl basic-framework-module-ai test` | 0 | 219 例通过（S04 新增 25 例：上下文 8、预算 3、分区词汇 2、失败映射 3、调试服务 6、控制器 3） |
| `./mvnw -Pintegration -pl basic-framework-server test -Dtest=AiServiceDebugIT` | 0 | 4 例通过（真实 MySQL：测试主体失权即拒绝、未注册字段/超预算在模型调用前失败、ACL 放行后上游不可用返回稳定错误、缺少显式主体被拒） |
| `sh .harness/verify.sh contracts` | 0 | 台账/权限/生命周期/字段目录全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单元测试、格式、架构、覆盖率检查通过 |
| `sh .harness/verify.sh integration` | 1 → 0 | 37 个 IT 类 / 101 例全绿（含 `AiServiceDebugIT` 4 例）；唯一失败是尾部棘轮（新文件未登记，属预期），登记后复验通过 |

## 5. 顺带修复的依赖缺口

**上游模型失败没有稳定映射**（M05 遗留）：`AiErrorCodeConstants.AI_MODEL_CALL_FAILED` 此前只登记未使用，
`AiModelInvocationService` 把 `ModelException` 原样抛出，调用方（S04 的调试链路，以及后续 O04 运行链路）
只能拿到框架运行时异常。现新增 `AiModelFailureCodes` 把失败原因映射到平台错误码：
端点不存在/停用、能力不支持、外发策略拒绝、上游限流分别映射到各自稳定错误码，
其余（超时、上游拒绝、输出超限、结构化不合法、维度不一致、AI 能力未启用）统一收敛为
`AI_MODEL_CALL_FAILED`（502）。映射放在 `domain/runtime` 供运行链路复用，避免 O04 再实现一套。

## 6. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、权限目录、生命周期、字段目录、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单元测试、spotless、架构与覆盖率检查通过 |
| `sh .harness/verify.sh integration` | 1 → 0 | 37 个 IT 类 / 101 例全绿；唯一失败是尾部棘轮（新文件未登记，属预期），`--update` 后复验通过 |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 新增登记 7 个文件（最低 94.74%），无基线下调、无登记删除，复验通过 |

## 7. 覆盖率

新增受测文件（数字取自 server 的 jacoco aggregate 报告，即 `mvn clean verify` 生成）：

| 文件 | 覆盖率 |
|---|---|
| `service/context/AiContextBuilderImpl.java` | 97.32% |
| `domain/runtime/AiContextBudget.java` | 100% |
| `domain/runtime/AiContextSection.java` | 100% |
| `domain/runtime/AiModelFailureCodes.java` | 100% |
| `service/debug/AiServiceDebugServiceImpl.java` | 95.8% |
| `service/debug/dto/AiServiceDebugRunDTO.java` | 100% |
| `controller/admin/debug/AiServiceDebugController.java` | 94.74% |
| 其余 VO/DTO（`service/context/dto`、`service/debug/dto`、`controller/admin/debug/vo`） | 只有 Lombok 生成代码，被 JaCoCo 过滤后不进棘轮台账（与既有 VO/DTO 一致） |

## 8. 未验证项

1. **真实检索与工具**：知识片段由调用方（K/D 系列的检索链路）排序后传入，本卡只负责分区与预算；
   工具调用的步数/次数预算与受控执行属 O04（有界执行）。
2. **会话历史来源**：调试历史消息由调用方显式提交；从会话读取真实历史属 O01/O02。
3. **调试票据**：开放平台在线调试使用短期受限测试票据属 O08；本卡的管理端调试走管理权限 + 显式测试主体。
4. **前端交互**：调试区与上下文预算的页面展示属 S05。
