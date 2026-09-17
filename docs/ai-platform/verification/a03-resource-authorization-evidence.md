# A03 资源授权证据（2026-09-17）

本记录是 [A03 实现资源授权](../tasks/A03.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 授权目录持久化 | 迁移 `V52__ai_resource_grant.sql`：`ai_resource_grant`（唯一键含主体身份与资源类型、动作白名单、`authz_revision`、乐观锁、对应用的物理外键）；菜单/权限 4020–4023；快照同步 |
| 动作/资源词汇 | `domain/policy/AiAction`（READ/EXECUTE/EXPORT）、`domain/policy/AiResourceType`（REPORT/KNOWLEDGE_BASE/FILE/TOOL/DATASET） |
| 统一授权入口 | `service/authorization/AiAuthorizationService(+Impl)`：`authorize(...)` 与 `reauthorizeHistorical(...)`；判定要素与应用/主体/范围/授权/动作五要素 |
| 授权管理 | `service/authorization/AiResourceGrantService(+Impl)`、`controller/admin/grant/AiResourceGrantController`（权限 `ai:grant:{query,create,update,revoke}`） |
| 错误码 | `1_003_003_007`（授权不存在 404）、`1_003_003_008`（重复授权 409）、`1_003_003_009`（授权拒绝 403）+ 映射同步 |
| 测试 | `AiAuthorizationServiceImplTest`(5)、`AiResourceGrantServiceImplTest`(3)、`AiResourceGrantControllerTest`(3) |

## 2. 与卡片逐步实施的对应

1. **资源 grant 目录与动作白名单**：`actions` 只接受 `READ/EXECUTE/EXPORT`，未知动作在写入时 400
   （单测覆盖）；查询按 (应用, 主体类型, 外部用户, 资源类型, 资源标识) 精确匹配，**不同类型同 ID 不串权**
   （用例：同 ID 的 FILE 授权查不到 REPORT 授权）。
2. **应用/服务/主体求交与当前版本检查**：判定顺序为"应用启用 → 主体范围解析命中 → 该类型该资源有
   ACTIVE 授权 → 动作在白名单"，任一不满足即拒绝并给出稳定原因码（`APPLICATION_DISABLED`、
   `SUBJECT_SCOPE_DENIED`、`GRANT_NOT_FOUND`、`ACTION_NOT_GRANTED`）。
3. **撤销更新 authz revision、不引入跨请求旧授权缓存**：授权修改/撤销都会递增 `authz_revision`
   （单测断言 4→5），判定每次重新读取授权与范围并重新计算指纹，没有任何判定缓存。
4. **统一 authorize(context, action, resource) 入口**：`AiAuthorizationService.authorize` 是唯一入口；
   命中时返回动作白名单与范围指纹，未命中时不返回任何范围信息。
5. **结果 scope 指纹与历史资源再鉴权**：`reauthorizeHistorical` 比较调用方持有的历史指纹与当前指纹，
   不一致时返回 `SCOPE_FINGERPRINT_CHANGED` 并拒绝——即使数据集仍获授权，范围收窄后也不得读取旧聚合
   （用例：组织集合从 {10} 变为 {10,11} 且授权版本变化时旧指纹失效）。

## 3. 关键约束与安全语义

- **不串权**：授权查询与判定都以"应用 + 主体 + 资源类型 + 资源标识"为键，跨应用、跨主体、跨类型都不命中。
- **拒绝优先**：任一要素缺失即拒绝；拒绝结果不含范围信息，避免把"拒绝"当成"空范围"继续执行。
- **动作白名单**：授权是白名单语义，未列入的动作一律拒绝（含 EXPORT 这类高敏动作）。
- **授权目录管理面**：创建/修改/撤销都要求 `ai:grant:*` 权限（控制器用例断言权限码与迁移种子一致）。

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

1. **真实业务资源目录**：grant 的 `resource_key` 由业务系统提供（报表 ID、知识库 ID 等），
   本卡只提供目录与判定，不实现业务资源同步任务。
2. **范围指纹的载体**：指纹随票据（A04）与运行产物携带；产物侧"记录生成时指纹"的实现随运行链路（D/O 系列）。
3. **应用上限不足拒绝**：当前以"应用启用 + 授权目录"表达上限；配额型上限（每应用调用/资源上限）
   属计量与配额卡（M05/Q02）。
