# Q01 接入 AI 审计、隐私日志与安全信号 — 完成证据

| 项目 | 内容 |
|---|---|
| 任务卡 | [Q01](../tasks/Q01.md) |
| 状态 | DONE（审计事件、脱敏与文档；指标化与真实运维看板见"未验证项"） |
| 需求 | FR-29（审计与隐私日志，见验收 AT-058）、FR-40（全链路安全） |
| 依赖 | O06（运行链路）、A08（安全信号与威胁模型）、K08（引用校验）、D09（工具动作确认）——均已有证据 |
| 工作副本 | `/home/ctyun/桌面/zhongtai/ai-platform` |
| 变更范围 | `module-ai/service/audit`（新增）、`docs/contracts/ai/audit-event.schema.json`、`docs/security/ai-audit-and-diagnostics.md` |

## 1. 变更文件清单

- `service/audit/AiAuditEvent.java`：审计事件记录（事件类型、运行引用、主体引用、资源引用、动作、结果码、耗时、时间），
  **刻意没有自由文本字段**；`toLogLine()` 给出结构化日志行。
- `service/audit/AiAuditEventType.java`：闭集事件类型（运行受理/终态、工具动作、资源访问、文件访问、运维诊断），
  未知类型拒绝写入。
- `service/audit/AiAuditLogSanitizer.java`：脱敏器与自检器——票据/应用密钥前缀、口令赋值、连接串、
  Bearer、SQL 形态、私钥块；命中即**整段替换**为 `[已脱敏]`（不截断，避免留下可定位账号的前缀）。
- `service/audit/AiAuditRecorder.java`：审计记录器（logger `AI_AUDIT`）+ 便捷入口（受理/终态/上游失败）；
  每个字段再过一次脱敏；上游异常只走框架 `SafeExceptionLogUtils.format` 且只进**诊断通道**。
- `docs/contracts/ai/audit-event.schema.json`：审计事件 v1 权威 Schema（`additionalProperties: false` + 闭集类型 + 字段上限）。
- `docs/security/ai-audit-and-diagnostics.md`：事件目录、禁止项（AT-058 清单）、通道分离与授权、
  事件→信号/告警映射、运维诊断清单。
- `AiAuditRecorderTest`（7 例）。

## 2. 与卡片逐步实施的对应

| 卡步骤 | 实现 | 验证 |
|---|---|---|
| 1. 按 run/步骤记录主体、资源引用、动作、结果码与耗时，不保存问题正文或秘密 | `AiAuditEvent` 的字段集（无自由文本）+ `AiAuditRecorder` 的三个便捷入口 | `AiAuditRecorderTest`（结构化字段断言、不含 `question/prompt/snippet/message=`） |
| 2. 复用既有日志 API 与 SafeExceptionLogUtils，新增信号目录与告警映射 | 上游失败走 `SafeExceptionLogUtils.format`（有界摘要）+ 诊断通道；事件→信号映射写在 `ai-audit-and-diagnostics.md`（复用既有 4 个指标，不新增指标源码） | `AiAuditRecorderTest`（上游失败只留结果码与阶段）、`check-security-signals`（三方一致仍通过） |
| 3. 定义有权运维查看的脱敏诊断与业务正文分离策略 | 文档第 3 节（通道表：审计/诊断/访问日志/业务正文各自的可见性）+ 第 5 节运维诊断清单 | 文档 + `ai-sensitive-endpoints.md`（管理端无跨主体正文入口） |

### 卡片验收项

| 验收项 | 结论 | 证据 |
|---|---|---|
| AT-058 日志全链路检查（无 token/secret/SQL 参数/问题正文） | 通过（实现与单测层）：脱敏器覆盖票据/密钥/口令/连接串/Bearer/SQL/私钥；审计事件无正文字段；上游失败只留码与阶段 | `AiAuditRecorderTest` 第 3/4/5 例 |
| AT-061 新模块/新表/权限未登记 → 门禁变红 | 未涉及（本卡不新增表与权限码）；审计事件契约由 `docs/contracts/ai/` 的 Schema 登记 | `check-source-quality`、`check-security-signals` 均通过 |
| 捕获成功、超时、SQL 错误、模型拒绝、文件解析的全部日志验证秘密与正文不出现 | 覆盖"成功/失败/上游异常"三类留痕路径的**实现与单测**；具体业务路径（SQL 错误、文件解析失败）复用同一记录器与脱敏器，其调用点接线属各业务卡（O06/D 系列/K 系列已交付的日志点沿用框架安全工具） | 见第 8 节未验证项 |
| 越权运维不能查询业务正文 | 通过（结构层）：日志通道无正文字段；正文只在应用端受权接口 | `docs/security/ai-audit-and-diagnostics.md` 第 3 节 + `ai-sensitive-endpoints.md` |

## 3. 关键约束落地

- **审计只记事实**：事件是闭集字段记录，契约（JSON Schema）与 Java 记录一一对应，`additionalProperties: false`。
- **脱敏是"整段替换"**：不截断、不留前缀；脱敏器同时提供 `containsSensitiveContent` 供自检与断言。
- **通道分离**：`AI_AUDIT`（结构化事件）与诊断通道（安全格式化的异常摘要）分开，便于分别授权与保留。
- **不越界**：未新增 Prometheus 指标（指标常量在 `module-system`，不在本卡允许路径；且会破坏
  `check-security-signals` 的三方一致），因此交付"事件目录 + 映射 + 运维清单"，指标化留给后续卡。
- **不新增依赖、不改迁移**：纯新增包与文档。

## 4. 实际执行的命令与结果

| 命令 | 退出码 | 结果 |
|---|---|---|
| `./mvnw -o -pl basic-framework-module-ai test -Dtest='AiAuditRecorderTest'` | 0 | **7 例通过** |
| `node scripts/check-security-signals.mjs` | 0 | 指标 4 个，文档与告警规则三方一致（本卡未新增指标，保持通过） |
| `node scripts/check-source-quality.mjs` | 0 | 3185 个源码文件、逻辑源码上限 800 行均通过 |
| 门禁链（contracts / backend / integration / 棘轮） | 见第 6 节 | 本卡改了后端主代码（新增 Bean），故跑这三段 |

## 5. 新增/变化的对外契约与上游差异

- **契约**：新增 `docs/contracts/ai/audit-event.schema.json`（v1，闭集事件类型 + 8 个结构化字段）。
- **文档**：新增 `docs/security/ai-audit-and-diagnostics.md`。
- **上游差异（如实记录）**：
  1. **未给审计事件加跨语言夹具**：`docs/contracts/ai/samples/` 的夹具前缀由两侧测试枚举，
     TS 侧对未登记前缀会直接失败（`packages/ai-contracts` 不在本卡允许路径内），
     因此本卡只交付 Schema 与文档，夹具留给能同时改两侧的卡片。
  2. **未新增 Prometheus 指标**：原因见第 3 节最后一条；事件→信号映射在文档中给出。
  3. **审计事件暂未落库**：事件写专用日志通道（可按保留期采集），未新增审计表；
     若后续要求"可查询审计台账"，需要新迁移与查询接口（属新卡片）。

## 6. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 全部契约脚本通过（含安全信号三方一致、源码质量、生命周期/权限/字段台账） |
| `sh .harness/verify.sh backend` | 0 | 编译、单测（含本卡 7 例）、格式、架构与覆盖率检查通过 |
| `sh .harness/verify.sh integration` | 1 | **唯一失败项是尾部棘轮**：4 个新增审计类"尚未登记单文件覆盖率基线"（新文件的预期状态）；maven 段 0 失败（Spring 上下文加载正常，新 Bean 不破坏既有 IT） |
| `node scripts/check-coverage-ratchet.mjs --update` | 0 | 登记 4 个新文件基线（`AiAuditEvent`/`AiAuditEventType`/`AiAuditLogSanitizer`/`AiAuditRecorder`），**无既有条目下降** |
| `node scripts/check-coverage-ratchet.mjs all` | 0 | `all 单文件基线通过` |

## 7. 顺带修复的依赖缺口（真实暴露）

1. **`SafeExceptionLogUtils` 只有 `format`**：起草时按"logSafely"的直觉调用，编译期发现框架只提供
   有界格式化；改为 `DIAGNOSTIC_LOG.warn(..., SafeExceptionLogUtils.format(cause))`，
   语义不变（仍是有界、无负载的摘要），且审计通道保持纯净。
2. **前缀识别需要"无边界"匹配**：票据/密钥出现在 `run_aitkt_...` 这类拼接里时，`\b` 边界不成立会漏检；
   前缀本身足够独特，去掉边界后覆盖拼接场景。
3. **赋值形态要允许引号**：`"apiKey":"sk-live-..."` 这类 JSON 赋值在 `key` 与 `:` 之间有引号，
   正则加上可选引号后才覆盖（否则日志里的 JSON 体不会被脱敏）。

## 8. 未验证项与已知边界

1. **业务路径的日志断言未逐条覆盖**：本卡交付统一的记录器、脱敏器与单测；"成功/超时/SQL 错误/模型拒绝/
   文件解析失败"在各业务卡里是否都改用了这两件工具，需要一次跨模块的日志走查（Q03 的运行监控卡或
   专门的日志审计卡），本卡未做全仓扫描。
2. **指标化未落地**：事件→告警映射是文档约定，没有实际指标与告警规则（需要改 `module-system` 与 `ops/`）。
3. **审计事件的保留期与采集**：由日志平台按 logger 名配置，本卡未提供采集配置样例。
4. **真实运维看板与查询**：Q03（运行监控）承接。
5. **环境缺口（非本卡）**：`sh .harness/verify.sh dependencies` 因 Trivy 漏洞库镜像不可达仍失败。
