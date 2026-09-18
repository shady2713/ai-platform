# A06 撤销与执行上下文重建证据（2026-09-18）

本记录是 [A06 实现撤销与执行上下文重建](../tasks/A06.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 撤销命令 | `service/authorization/AiRevocationService(+Impl)`：`revokeApplication`（停用 + 撤销全部授权 + 撤销全部票据）/ `revokeSubject`（同主体维度），全程单事务 |
| 票据清理 | `service/auth/AiTicketService#cleanInvalidTickets`（按批逻辑删除失效票据）+ `service/auth/AiTicketService#revokeTicketsOfApplication` |
| 清理 JobHandler | `job/AiTicketCleanupJob`：批大小/批次数/保留期可配（`basic-framework.ai.ticket.cleanup.*`），幂等 |
| 执行上下文重建 | `domain/identity/AiExecutionContext`（不可变身份+范围快照，空范围即 DENY）+ `service/authorization/AiExecutionContextFactory(+Impl)`（每次重建都重新校验应用启用与主体范围） |
| 语义文档 | `docs/security/ai-revocation-semantics.md`：撤销覆盖的四条链路、**明确不保证**的部分（已发送内容无法回收、历史产物需重新鉴权、执行中的单次调用按超时收敛） |
| 生命周期 | `docs/data-lifecycle.md`：票据保留窗口与清理策略、主体停用不删除的说明 |
| 测试 | `AiRevocationServiceImplTest`(4)、`AiExecutionContextFactoryImplTest`(5)、`AiTicketCleanupTest`(4)、`AiRevocationIT`(3，真实 MySQL) |

## 2. 与卡片逐步实施的对应

1. **当前 token、主体、应用的撤销与到期清理**：撤销命令覆盖"应用/主体状态 + 授权目录 + 票据"三条链路；
   到期清理由 `cleanInvalidTickets` + `AiTicketCleanupJob` 承担（只清已撤销或过期超过保留期的票据，
   分批且有上限，重复执行无额外副作用）。
2. **异步任务按 app/subject 重建权限**：`AiExecutionContextFactory.rebuild` 每次重新校验
   应用是否启用、主体范围是否可解析；实现中没有任何 ThreadLocal/静态缓存，
   因此同一线程先后处理两个任务不会互相继承身份或范围（单测用两个主体在同一线程上验证）。
3. **撤销后的语义边界**：`docs/security/ai-revocation-semantics.md` 明确"已发送内容无法回收"，
   受限步骤必须以"每步重建上下文"为前提；重建失败（应用停用、主体撤销、范围 DENY）即抛
   `AI_AUTHORIZATION_DENIED`，调用方必须停止。

## 3. 关键约束与安全语义

- **原子性**：撤销在一个事务内完成——不会出现"应用已停用但票据仍可用"或"授权已撤但票据仍可换票"的中间态。
- **立即失效**：撤销不等待清理任务；旧票据在 `verify` 时立即 401，授权判定立即拒绝，执行上下文重建立即失败。
- **不可继承**：执行上下文只由当次重建产生；空范围即拒绝（不得把空范围当"不过滤"）。
- **有界清理**：批大小与批次数上限防止单次任务扫全表；保留窗口支持审计对账。
- **范围说明**：本卡实现清理查询时把 Wrapper 直接构造在服务层（DAL 的 `dal/mysql/token`
  不在卡片允许路径内），并为本卡新增的 `AiTicketCleanupJob` 给 `module-ai` 引入了
  `basic-framework-spring-boot-starter-job` 依赖（版本仍由 dependencies 统一管理，
  不修改版本号）。两处偏离均在此登记，后续卡片可把清理查询下沉到 Mapper。

## 4. 验证结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -pl basic-framework-module-ai verify` | 0 | 130 例通过（A06 新增 13 例） |
| `./mvnw -Pintegration -pl basic-framework-server verify -Dit.test=AiRevocationIT` | 0 | 3 例通过（应用撤销 / 主体撤销隔离 / 清理幂等） |
| `sh .harness/verify.sh contracts` / `backend` / `integration` + 棘轮 | 见交接记录 | 与本批后续卡片同批执行 |

## 5. 未验证项

1. **浏览器侧撤销体验**：AT-010 标注"集成+浏览器"；本卡交付集成证据，浏览器交互（撤销后页面提示）随 Q 系列补齐。
2. **执行中的单次上游调用中断**：按文档约定以超时收敛，不提供强中断（上游不支持时无法保证）。
3. **历史产物清理**：撤销不删除历史产物；产物侧的"记录指纹 + 读取前重新鉴权"随运行/产物卡片实现。
