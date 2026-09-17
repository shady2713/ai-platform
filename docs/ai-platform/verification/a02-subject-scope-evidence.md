# A02 外部主体与范围映射证据（2026-09-17）

本记录是 [A02 实现外部主体与范围映射](../tasks/A02.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 主体存储 | 迁移 `V51__ai_subject.sql`：`ai_subject`（唯一键 `(application_id, subject_type, external_user_id)`、状态 ACTIVE/DISABLED、`scope_source`、`scope_version`、乐观锁、对 `ai_application` 的物理外键）；快照同步 |
| 身份词汇 | `domain/identity/AiSubjectType`（APP/USER，无平台角色维度） |
| 范围契约 | `domain/identity/SubjectScope`（组织/对象白名单 + 来源 + 版本；**空集合即 DENY**）、`domain/identity/SubjectScopeResolver`（可信解析 SPI，输入只含服务端事实与业务侧提示） |
| 服务 | `service/subject/AiSubjectService(+Impl)`：`syncSubject`（幂等登记/同步，范围版本只前进）、`disableSubject`（业务侧撤销同步）、`findActiveSubject`、`resolveScope`（DENY 语义） |
| 测试 | `AiSubjectServiceImplTest`(8)、`AiSubjectPersistenceIT`(4，真实 MySQL) |
| 台账 | `data-lifecycle.json`（逻辑删除 + 外键）、`data-permission-exemptions.json`（`subject-bound` 豁免 + Mapper/Service 证据） |

## 2. 与卡片逐步实施的对应

1. **APP/USER 主体唯一键与状态**：唯一键含 `application_id`，同一 `externalUserId` 在不同应用下是不同主体
   （单测与 IT 均断言两应用下 `alice` 各自独立、范围来源互不影响）；APP 主体的 `external_user_id` 归一化为空串，
   使同一唯一键同样成立；状态机只有 ACTIVE/DISABLED。
2. **可信 externalUserId 与业务组织/对象范围解析接口**：`SubjectScopeResolver.SubjectScopeRequest` 只含
   应用、主体类型、可信 `externalUserId`、范围来源/版本与服务端拼装的对象提示；解析结果 `SubjectScope`
   显式给出组织与对象白名单。
3. **禁止映射成 ADMIN 或接受浏览器 roles/deptIds**：主体类型枚举没有平台角色维度，请求契约里不存在
   roles/deptIds 字段；范围只能来自解析器（单测用 ArgumentCaptor 断言只传服务端事实）。
4. **DENY 语义**：`resolveScope` 的五条路径——主体不存在/停用、解析器未装配、解析器返回空、解析器抛异常、
   结果为空集合、超过预算（`SCOPE_BUDGET = 10000`）——全部返回 `denied=true` 与稳定原因码；
   `SubjectScope.isDeny()` 在空集合时为真，消费方不得把"空范围"当成"不过滤"。
5. **记录外部授权来源与范围版本、业务侧撤销可同步**：`scope_source` 与 `scope_version` 随主体保存，
   版本只允许前进（IT 断言旧版本同步不回退）；`disableSubject` 即业务侧撤销的同步入口，
   撤销后 `findActiveSubject` 为空、`resolveScope` 立即 DENY。

## 3. 关键约束与安全语义

- **默认拒绝**：没有装配解析器时返回 `RESOLVER_UNAVAILABLE` 并拒绝，而不是放行为全部数据。
- **解析失败不外泄业务内容**：解析器异常只记录应用与主体类型（不记录异常正文与对象提示）。
- **伪造范围无法生效**：范围来自解析器结果，不来自请求体；调用方即使提交任意角色字段也无处生效。
- **缺少 USER 范围时数据集拒绝执行**：`resolveScope` 返回 `denied=true`，消费方（运行/工具/知识库）据此拒绝。

## 4. 验证结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约、台账（生命周期/数据权限/错误码映射）、敏感字段与接口授权策略检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 全量后端构建、单元测试与架构门禁通过 |
| `sh .harness/verify.sh integration` | 0（集成用例部分） | Testcontainers（MySQL 8.4）**全部集成用例通过**（含本卡新增用例）；命令末尾的覆盖率棘轮检查在登记基线前报"未登记"，登记后复验通过 |
| `node scripts/check-coverage-ratchet.mjs --update` | 0 | 新增文件基线登记（新文件下限 80%，只升不降） |
| `node scripts/check-coverage-ratchet.mjs all` | 0 | 前后端单文件基线全部通过 |
| `sh .harness/verify.sh frontend` | 0 | 前端门禁（check/lint/test:coverage/构建/前端棘轮）通过（本卡未改动前端，沿用 M06 收口结果） |

## 5. 未验证项

1. **真实业务范围解析器**：仓库内只有接口与测试实现；生产接入由业务系统提供实现（A03/A04 的应用端链路）。
2. **范围缓存与失效通知**：本卡只定义 `scope_version` 约定，跨节点缓存失效随运行链路（D/O 系列）实现。
3. **管理页面**：主体与范围的只读展示页面属后续前端卡。
