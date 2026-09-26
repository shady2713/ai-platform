# Q02 实现用量账本与可恢复配额控制 — 完成证据

| 项目 | 内容 |
|---|---|
| 任务卡 | [Q02](../tasks/Q02.md) |
| 状态 | DONE（账本、配额占位、聚合与运维查询；账本接线与指标化见"未验证项"） |
| 需求 | FR-25/FR-26（用量与限额，见 AT-059/060） |
| 依赖 | M05（外发策略与计量记录，已有证据）、O06（运行链路）、A08（安全信号）、F08（错误码） |
| 工作副本 | `/home/ctyun/桌面/zhongtai/ai-platform` |
| 变更范围 | `module-ai/service/{usage,quota}`、`dal/{dataobject,mysql}/usage`、`controller/admin/usage`、V81 迁移 + 快照 + 三处台账 |

## 1. 变更文件清单

- 迁移 `V81__ai_usage_ledger_and_quota.sql`：`ai_usage_ledger`（`invocation_id` 唯一 + 按应用/服务/时间的索引）
  与 `ai_quota_lease`（`lease_key` 唯一 + 活动索引），并落地 `ai:usage:query` 菜单种子（4115）。
- `dal/dataobject/usage/AiUsageLedgerDO.java`、`AiQuotaLeaseDO.java`：账本与占位行（都只有计量元数据）。
- `dal/mysql/usage/AiUsageLedgerMapper.java`（按调用/运行查询、分页、按来源/服务聚合 SQL）、`AiQuotaLeaseMapper.java`
  （按占位键查询、活动占位数、CAS）。
- `service/usage/AiUsageLedgerService(+Impl)`：`record` 按调用去重（唯一键 + 冲突吞掉，幂等）、
  `listByRun`/`page`、`summaryBySource`（显式给出"来源未知"条数）、`summaryByService`。
- `service/usage/AiUsageLedgerRecorder.java`：实现 M05 的 `AiModelUsageRecorder` 接缝——把调用计量落到账本；
  **无应用归属时不写假行**（留告警），计量失败不抛断业务链。
- `service/quota/AiQuotaService(+Impl)` + `dto/AiQuotaAcquireDTO`：带租约的并发占位（获取/续租/释放/活动数），
  同一调用重复申请=续租（不叠占），到期占位不计入并发（懒回收）。
- `controller/admin/usage/AiUsageController.java` + `vo/`（4 个只读端点：分页、按来源聚合、按服务聚合、当前占位数），
  权限码 `ai:usage:query` 与 V81 种子一致。
- 台账：`data-lifecycle.json`（两表登记为 **append-retention**）、`data-permission-exemptions.json`（新增 `ai-usage-metering` 组，
  function-permission + 控制器证据）、`数据库文件/basic_framework.sql`（V81 建表/菜单 + 快照声明更新为 `through V81`）。
- 测试：`AiUsageLedgerServiceTest`(4)、`AiQuotaServiceTest`(7)、`AiUsageLedgerRecorderTest`(4)、`AiUsageControllerTest`(4)、
  `AiUsageLedgerIT`(2，真实 MySQL)。

## 2. 与卡片逐步实施的对应

| 卡步骤 | 实现 | 验证 |
|---|---|---|
| 1. 建立账本、按 invocationId 唯一、按 run/task 聚合、来源如实标注 | 表 + 服务 + 聚合 SQL | `AiUsageLedgerServiceTest`（去重、UNKNOWN 不得携带 token、聚合口径）、`AiUsageLedgerIT`（真实库去重与聚合） |
| 2. 配额占位有过期与续租，重复写入不重复累计，重试用新 invocationId | `ai_quota_lease` + `AiQuotaServiceImpl` | `AiQuotaServiceTest`（上限、幂等重入、过期回收、续租失败语义、释放幂等）、`AiUsageLedgerIT`（到期占位回收后可再次获取） |
| 3. 按应用/服务/时间查询聚合；异常与取消释放配额；端点密钥不进统计 | 控制器 4 端点 + 服务聚合 + `validate` 拒绝地址形态的端点引用 | `AiUsageControllerTest`（权限码与端点、响应字段白名单）、`AiUsageLedgerServiceTest`（端点只接受引用） |

### 卡片验收项

| 验收项 | 结论 | 证据 |
|---|---|---|
| AT-059 并发配额与异常释放（不超发、不永久占位，429 可解释） | 通过：上限判定与查询同口径（未释放且未到期）；到期占位不计入并发，可被新申请占用；`acquire` 返回 false 让调用方回 429 并说明原因；终态 `release` 幂等 | `AiQuotaServiceTest`、`AiUsageLedgerIT.quotaLeasesExpireAndAreReclaimedInsteadOfBlockingForever` |
| AT-060 上游 usage 缺失显示 UNKNOWN/ESTIMATED、非假 0 | 通过：来源闭集（REPORTED/ESTIMATED/UNKNOWN）；UNKNOWN 时 token 必须为空（服务层拒绝携带）；读侧显式给出 `unknownInvocations` 条数 | `AiUsageLedgerServiceTest`、`AiUsageLedgerRecorderTest`（三种来源映射） |
| 并发请求不突破配置 | 通过（服务层 + 唯一键兜底）：`countActive < limit` 才允许落占位，并发下唯一键判负即"没拿到" | `AiQuotaServiceTest`（并发 insert 判负）、`AiUsageLedgerIT` |
| 进程退出后占位可恢复 | 通过：租约到期即回收（无需清理任务） | `AiUsageLedgerIT`（把 `lease_until` 调到过去后 `activeCount` 归零并可重新获取） |

## 3. 关键约束落地

- **账本只含计量元数据**：端点存引用（`endpoint:3`）而非地址；地址形态在服务层直接拒绝；token 未知留空。
- **去重靠数据库**：`invocation_id` 唯一键 + 冲突吞掉（幂等），不依赖"先查后写"；重试真的再次请求模型时由调用方使用新 invocationId。
- **占位不永久**：租约 + 懒回收；`renew` 失败即告诉调用方"你已经不占位了"，避免误以为仍在配额内。
- **不写假数据**：没有应用归属的调用不落账本（留告警信号），而不是编一个应用编号让聚合失真。
- **生命周期口径**：两张表按"只追加 + 保留期清理"登记为 append-retention（无逻辑删除列），
  与"计量元数据不随业务对象删除而丢"的运维需求一致。

## 4. 实际执行的命令与结果

| 命令 | 退出码 | 结果 |
|---|---|---|
| `./mvnw -o -pl basic-framework-module-ai test -Dtest='AiUsageLedgerServiceTest,AiQuotaServiceTest'` | 0 | 11 例通过 |
| `./mvnw -o -pl basic-framework-module-ai test -Dtest='AiUsageControllerTest,AiUsageLedgerRecorderTest'` | 0 | 8 例通过 |
| `./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiUsageLedgerIT'` | 0 | **2 例通过**（真实 MySQL + V81 迁移） |
| `node scripts/check-data-lifecycle.mjs` / `check-data-permission.mjs` / `check-permission-catalog.mjs` | 0 / 0 / 0 | 两表登记（append-retention）、豁免组就位、权限码被控制器引用 |
| 门禁链（contracts / backend / integration / 棘轮） | 见第 6 节 | 本卡改了后端 + 迁移 + 台账 |

## 5. 新增/变化的对外契约与上游差异

- **表**：`ai_usage_ledger`（append-retention）、`ai_quota_lease`（append-retention）。
- **API**：`GET /ai/usage/page`、`/summary`、`/service-summary`、`/quota-active`（`ai:usage:query`，菜单 4115）。
- **接缝**：`AiModelUsageRecorder` 由 M05 定义、本卡实现（`AiUsageLedgerRecorder`）；同时给 M05 的记录补了
  可选的应用/服务/运行/任务/主体/模型字段（纯增量）。
- **上游差异（如实记录）**：
  1. **账本暂未在运行链路里自动写入**：M05 的计量记录在低层模型调用处产生，那里没有应用上下文；
     本卡实现了落账本适配器并在缺上下文时不写假行。把应用/运行上下文传到计量点需要改运行链路（O06 的调用点），
     不在本卡允许路径内 → 记为未验证项。
  2. **未新增 Prometheus 指标**：与 Q01 同一原因（指标源码在 `module-system`，不在允许路径）。
  3. 并发限额的**配置来源**（每个应用/服务多少并发）本卡未落地配置表：`acquire` 由调用方传入 `limit`。

## 6. 门禁结果

工作目录 `/home/ctyun/桌面/zhongtai/ai-platform`，`umask 022`、`JAVA_HOME=~/.local/opt/jdk17/...`、
`PATH` 含 `sudo` 垫片，逐条执行：

| 门禁 | 退出码 | 关键输出 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 敏感字段 toString、生命周期、数据权限、权限目录等全部通过 |
| `sh .harness/verify.sh backend` | 0 | `mvn -q clean verify`（含单测与架构规则）全绿 |
| `sh .harness/verify.sh integration` | 0 | `mvn -q -Pintegration clean verify`；末尾 `coverage-ratchet: backend 单文件基线通过` |
| `node scripts/check-coverage-ratchet.mjs --update` | 0 | `已更新单文件覆盖率基线`：本卡新增的 6 个后端文件登记 **100% 行覆盖**（`AiUsageController`、`AiUsageLedgerServiceImpl`、`AiUsageLedgerRecorder`、`AiQuotaServiceImpl`、`AiUsageLedgerMapper`、`AiQuotaLeaseMapper`） |
| `node scripts/check-coverage-ratchet.mjs all` | 0 | `all 单文件基线通过`（前端与后端基线都满足，未下调任何既有基线） |

定向测试：`basic-framework-module-ai` 单测 **19 例**（`AiUsageLedgerServiceTest` 4、`AiQuotaServiceTest` 7、
`AiUsageControllerTest` 4、`AiUsageLedgerRecorderTest` 4）；`AiUsageLedgerIT` **2 例**通过（真实 MySQL + V81 迁移）。

第一次跑门禁时合同门禁失败在 `check-sensitive-tostring`：`inputTokens`/`outputTokens` 含 `token` 命中敏感字段规则，
按 C04 先例给这三处加 `@ToString.Exclude`（DO 用类级 `exclude`），**未改门禁脚本**；随后重跑整条链全绿。

环境缺口（非本卡）：`sh .harness/verify.sh dependencies` 因 Trivy 漏洞库镜像不可达仍失败。

## 7. 顺带修复的依赖缺口（真实暴露）

1. **审计列与 `BaseDO` 的耦合**：`AiUsageLedgerDO` 继承 `BaseDO` 会插入 `creator/updater/create_time/update_time`，
   而账本表最初没有这四列 → 真实库直接报 `Unknown column 'create_time'`。已在迁移与快照里补齐（一次性，未改历史迁移）。
2. **断言必须按"文件真实形态"改**：同一处断言我改过两次才生效——`spotless` 会重排换行，用文本替换时不带
   `assert 锚点命中` 就会"跑了 2.5 分钟却什么都没改"。后续改测试断言一律先断言锚点存在。
3. **`ArgumentCaptor` 与多次调用**：同一用例里发生多次记录时 `verify(...)` 默认只允许一次，
   取"最近一次入参"应使用 `verify(..., atLeastOnce())`。

## 8. 未验证项与已知边界

1. **账本未接入运行链路**（见第 5 节第 1 条）：需要把应用/运行上下文传到 M05 的计量点（属 O06 的调用点，不在本卡路径）。
2. **限额配置未落地**：并发上限由调用方传入，未建"应用/服务 → 上限"的配置与运维页面（Q03 承接监控与限额调整）。
3. **保留期清理任务未实现**：表按 append-retention 登记，但"按保留天数分批物理清理"的作业由后续卡交付。
4. **429 响应体与重试提示**：调用方（运行链路）如何把 `acquire=false` 转成 429 与可解释文案属 O06/Q03。
5. **前端页面未交付**：菜单 4115 指向 `ai/usage/index`，页面属 Q03。
6. **环境缺口（非本卡）**：`sh .harness/verify.sh dependencies` 因 Trivy 漏洞库镜像不可达仍失败。
