# AI 审计、隐私日志与运维诊断（Q01）

本文定义 AI 中台"什么进日志、什么绝不进日志、运维能看到什么"的边界，并给出事件目录与告警映射。
权威 Schema 见 [`docs/contracts/ai/audit-event.schema.json`](../contracts/ai/audit-event.schema.json)，
Java 实现在 `module-ai` 的 `service/audit`。

## 1. 审计事件只记结构化事实

| 字段 | 内容 | 为什么不记别的 |
|---|---|---|
| `eventType` | 闭集类型（运行受理/终态、工具动作、资源访问、文件访问、运维诊断） | 让日志平台能按类型建看板与告警，而不是靠文本匹配 |
| `runRef` | 运行标识（含步骤时 `run_xxx#step`） | 能对上一次具体执行，不涉及内容 |
| `subjectRef` | 应用编号 + 主体标识摘要 | 可对账到主体，但不写姓名/邮箱（避免把日志变成身份数据副本） |
| `resourceRefs` | `类型:不透明标识`（如 `REPORT:rpt_ab12`、`FILE:7`、`STAGE:MODEL_CALL`） | 能回答"碰过哪些资源"，不写标题与正文 |
| `action` | 与 scope 目录同词表（`READ`/`EXECUTE`/`EXPORT`…） | 与授权判定同口径，便于"授权 vs 实际访问"比对 |
| `outcomeCode` | 成功 `AI_OK`，失败为**稳定错误码常量名** | 上游异常正文里常有连接串/SQL/响应体，因此只留码 |
| `durationMs` | 耗时（未知 -1） | 性能排查 |
| `occurredAt` | 发生时间 | 审计时效 |

**没有的字段**：问题正文、提示词、模型输入输出、知识片段、SQL 语句与参数、文件内容、凭据与票据。
需要正文的场景一律走受权接口（引用片段、文件读取），并在那里按当前 ACL 再鉴权。

## 2. 绝不出现在日志里的内容（AT-058）

实现上有两道防线（都可单测）：

1. **字段白名单 + 长度上限**：审计事件只有上表字段，超限字段整体替换为 `[已脱敏]`（不截断——前缀足以定位账号）；
2. **模式识别**：`AiAuditLogSanitizer` 命中票据前缀（`aitkt_`）、应用密钥前缀（`aiapp_`）、
   `password/secret/token/apiKey/authorization/credential` 赋值、连接串（`jdbc:`/`redis:`/`mysql:`…）、
   `Bearer` 令牌、SQL 语句形态（`SELECT … FROM`、`WHERE x = y`）与私钥块时，整段替换。

上游异常**只留摘要**：走框架的 `SafeExceptionLogUtils.format`（有界长度 + 有界 cause 链），
且只进诊断通道（logger `com.basicframework.module.ai.service.audit.AiAuditRecorder`），
**不进审计通道**（logger `AI_AUDIT`）。

## 3. 通道分离与授权

| 通道（logger） | 内容 | 谁能看 |
|---|---|---|
| `AI_AUDIT` | 结构化审计事件（无正文、无秘密） | 审计/安全角色；事件本身可用于对账与告警 |
| `…service.audit.AiAuditRecorder` | 上游失败的**安全摘要**（异常类名、有界栈） | 运维诊断；仍不含响应体与参数 |
| 业务访问日志（框架） | 请求路径、状态码、耗时 | 运维排查 |
| **业务正文**（会话消息、片段、报表数据、文件内容） | 只存数据库/对象存储 | 只有按当前 ACL 的受权读取（应用端接口），**不在任何日志里** |

越权运维看不到正文：日志通道里根本没有正文字段；正文只能经应用端受权接口读取，
管理端没有跨主体读取入口（见 `ai-sensitive-endpoints.md`）。

## 4. 事件 → 信号与告警映射

指标名沿用既有安全信号（见 `security-signals.md`，本文件不新增指标，避免与门禁的"三方一致"冲突）：

| 审计事件 | 关联信号 | 告警动作 |
|---|---|---|
| `RUN_FINISHED` 且 `outcomeCode=AI_MODEL_CALL_FAILED` 激增 | `basic_framework_auth_*` 之外的**日志度量**（按事件类型计数） | 观察模型/网络可用性，必要时切换端点 revision |
| `RESOURCE_ACCESS` 且 `outcomeCode=AI_REPORT_SCOPE_CHANGED` | 无直接指标 | 安全关注：授权范围变更后仍访问旧产物 |
| `FILE_ACCESS` 且 `outcomeCode` 为越权类码 | 无直接指标 | 安全关注：可能是枚举探测，按主体与时间窗聚合 |
| `OPS_DIAGNOSTIC` | 无 | 审计留痕：谁在什么时候看了哪个运行的脱敏诊断 |
| 票据/密钥相关异常（识别到脱敏占位） | `basic_framework_auth_refresh_replays_total` 等既有指标 | 按 `security-signals.md` 的 runbook 处置 |

> 说明：本轮没有新增 Prometheus 指标——指标常量在 `module-system`（不在 Q01 允许路径内），
> 且 `check-security-signals` 要求指标源码、文档与告警规则三方一致，跨模块新增会越界。
> 因此本卡交付的是**事件目录 + 映射**，指标化留给后续（Q03 监控看板或专门的安全信号卡）。

## 5. 运维诊断清单（脱敏后可看）

| 问题 | 允许的查询 | 禁止的做法 |
|---|---|---|
| 某次运行为什么失败 | 按 `runRef` 查审计事件（结果码、阶段、耗时） | 直接看模型请求体/响应体 |
| 上游是否不稳定 | 按 `eventType=RUN_FINISHED` + 时间窗统计结果码分布 | 打印上游响应正文 |
| 某用户是否越权 | 按 `subjectRef` 聚合 `RESOURCE_ACCESS` 的结果码 | 打开业务正文核对内容 |
| 限额是否异常 | 用量账本（Q02）与既有配额指标 | 从日志里抓问题正文做业务分析 |

## 6. 本卡的日志断言（`AiAuditRecorderTest`）

1. 审计行只含结构化字段（断言不含 `question`/`prompt`/`snippet`/`message=`）；
2. 未知事件类型与超量资源引用被拒绝（闭集与上限）；
3. 敏感字段值整体替换为 `[已脱敏]`，且长字段不保留前缀；
4. 脱敏器覆盖票据、应用密钥、口令赋值、连接串、Bearer、SQL 形态与私钥块；
5. 上游失败只留结果码与阶段（断言不含 `aitkt_`、不含上游地址）。
