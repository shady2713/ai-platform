# S01 服务草稿与资源绑定证据（2026-09-19）

本记录是 [S01 实现服务草稿与资源绑定](../tasks/S01.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 持久化 | 迁移 `V56__ai_service_draft.sql`：`ai_service`（草稿：端点/提示词/输入输出 Schema/所需能力/运行主体类型，乐观锁 + draftRevision）、`ai_service_release`（发布版本，S02 写入，含 contentHash 与端点配置版本冻结）、`ai_service_resource`（releaseId 为空表示草稿绑定）；菜单与权限 4030–4035；快照同步 |
| 服务层 | `service/serviceconfig/AiServiceService(+Impl)`：草稿 CRUD（CAS + 修订号递增）、`markReady`（能力门禁）、`checkCapabilities`、`bindResource`（越权校验）、`unbindResource`、绑定与版本查询；服务层不依赖 VO |
| 控制面 API | `controller/admin/serviceconfig/AiServiceController`：`/ai/service/{create,update,delete,mark-ready,check-capabilities,bind-resource,unbind-resource,resources,get,page}`，权限码 `ai:service:{query,create,update,delete,bind,publish}` |
| 边界测试 | `AiServiceServiceImplTest`(8)、`AiServiceControllerTest`(5)、`AiServiceDraftIT`(2，真实 MySQL) |
| 台账 | `data-lifecycle.json`（三表软删除 + 三个外键）、`data-permission-exemptions.json`（控制面豁免 + 逐表证据） |

## 2. 与卡片逐步实施的对应

1. **建立 service/release/resource 表**：三表 + 外键（服务→应用、发布版本→服务、绑定→服务）；
   发布版本表在 S01 只建表与实体，写入由 S02 负责。
2. **草稿绑定模型、输入 schema、提示词及允许资源**：草稿保存端点、提示词、输入/输出 Schema（必须是可解析的
   JSON 对象）、所需能力词表、运行主体类型；资源绑定按"资源类型 × 资源标识 × 动作"登记。
3. **校验资源授权和类型，Service 内部不依赖 VO**：
   - 类型：资源类型与动作都必须在目录词汇内（`AiResourceType`/`AiAction`），Schema 必须是 JSON 对象；
   - 资源授权：绑定前用 A03 以**服务所属应用的 APP 主体**逐动作判定，任一动作不通过即整体拒绝（越权绑定拒绝）；
   - 分层：Controller 负责 VO↔DTO，服务层只认 DTO（ArchUnit 边界规则继续生效）。

## 3. 关键约束与安全语义

- **越权绑定拒绝**：绑定要求"应用级主体 + 该资源 + 该动作"的 ACTIVE 授权；应用未登记 APP 主体或未授予资源时一律拒绝
  （IT 断言：先拒绝、授予后成功）。
- **能力不足不能保存为可发布**：`markReady` 要求 M04 探测确认的能力覆盖服务所需能力（声明 ∩ 确认），
  缺失即 `AI_MODEL_CAPABILITY_UNSUPPORTED`，草稿仍可保存但状态保持 DRAFT；`check-capabilities` 给出缺失清单。
- **并发编辑 409**：所有写操作带乐观锁版本；配置变更会递增 `draftRevision` 并把状态退回 DRAFT（发布前必须重新确认）。
- **删除保护**：存在草稿绑定时不允许删除服务（先解绑）；服务被发布版本引用时由外键 RESTRICT 兜底。

## 4. 验证结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -pl basic-framework-module-ai verify` | 0 | 164 例通过（S01 新增 13 例） |
| `./mvnw -Pintegration -pl basic-framework-server -am verify -Dit.test=AiServiceDraftIT` | 0 | 2 例通过（真实 MySQL：草稿生命周期、越权绑定、能力门禁、CAS 冲突） |
| `sh .harness/verify.sh contracts` / `backend` / `integration` + 棘轮 | 见交接记录 | 与本批卡片同批执行 |

## 5. 顺带修复的依赖缺口

`AiModelEndpointServiceImpl` 的可声明能力白名单历史上是硬编码的 `List.of("TEXT","EMBEDDING")`，
M03/M04 新增的 `TEXT_STREAM`/`STRUCTURED_OUTPUT`/`TOOL_CALLING` 因此无法配置到端点上
（本卡 IT 在创建带 STRUCTURED_OUTPUT 的端点时暴露）。已改为直接取接缝的 `ModelCapability` 枚举词汇，
新增能力不需要再同步这份清单。该修改超出本卡列出的路径范围，但为满足"能力门禁"验收所必需，在此登记。

## 6. 未验证项

1. **发布与回退**：`ai_service_release` 的写入、候选/生效切换、内容摘要与评测门槛属 S02/S03。
2. **服务调试**：调试接口与上下文预算构造属 S04。
3. **管理页面**：服务配置页面属 S05。
4. **运行主体为 USER 的服务**：绑定校验使用应用级主体；USER 服务的逐次运行范围仍由 A02/A03 在运行期校验。
