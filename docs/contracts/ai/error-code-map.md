# AI 错误码与 HTTP 映射（F08 冻结）

错误码区间由 `backend` 侧的 `AiErrorCodeRanges` / `AiErrorCodeConstants` 定义，本表是**手工同步的映射契约**：
新增错误码必须同时更新两侧，编号一经发布不得改语义（错误码是长期协议）。

## 区间分配

| 区间 | 能力域 | 说明 |
|---|---|---|
| `1_003_001_xxx` | 通用/协议 | 入参、幂等、状态冲突、资源不可见、权限、配额 |
| `1_003_002_xxx` | 模型中心 | 端点、凭据、调用、能力 |
| `1_003_003_xxx` | 应用与授权 | 应用、外部主体、票据、范围 |
| `1_003_004_xxx` | 运行与任务 | 运行、任务、取消、重试 |
| `1_003_005_xxx` | 知识库 | 文档、解析、索引、检索授权 |
| `1_003_006_xxx` | 数据与工具 | 连接器、查询计划、工具执行 |
| `1_003_007_xxx` | 报表与 Chat | 报表、主题、嵌入会话 |
| `1_003_008_xxx` | 服务配置 | 服务发布、评测门槛、运行快照、版本回退 |

框架与基础设施占用 `1_001_xxx_xxx`，system 模块占用 `1_002_xxx_xxx`；AI 中台独占 `1_003`，不与既有区间交叉。

## HTTP 映射

| 错误码 | 名称 | HTTP | 语义 |
|---|---|---|---|
| 1_003_001_000 | AI_REQUEST_INVALID | 400 | 入参非法（长度、枚举、格式） |
| 1_003_001_001 | AI_IDEMPOTENCY_CONFLICT | 409 | 同键不同摘要 |
| 1_003_001_002 | AI_STATE_CONFLICT | 409 | 当前状态不允许该操作 |
| 1_003_001_003 | AI_RESOURCE_NOT_FOUND | 404 | 不存在或无权访问（同语义，防枚举） |
| 1_003_001_004 | AI_ACCESS_DENIED | 403 | 已认证但缺少范围/权限 |
| 1_003_001_005 | AI_QUOTA_EXCEEDED | 429 | 超配额或限流 |
| 1_003_002_000 | AI_MODEL_ENDPOINT_NOT_FOUND | 404 | 端点不存在 |
| 1_003_002_001 | AI_MODEL_ENDPOINT_DISABLED | 409 | 端点停用 |
| 1_003_002_002 | AI_MODEL_CAPABILITY_UNSUPPORTED | 400 | 端点不支持所需能力 |
| 1_003_002_003 | AI_MODEL_CALL_FAILED | 502 | 上游模型失败（不外泄上游正文） |
| 1_003_002_004 | AI_MODEL_ENDPOINT_NAME_DUPLICATE | 400 | 端点名称重复 |
| 1_003_002_005 | AI_MODEL_EMBEDDING_DIMENSION_CHANGED | 409 | 嵌入维度与既有索引记录不一致，拒绝写入既有索引 |
| 1_003_002_006 | AI_MODEL_OUTBOUND_BLOCKED | 403 | 资源等级不允许外发到该端点（策略拒绝先于网络调用） |
| 1_003_003_000 | AI_APPLICATION_NOT_FOUND | 404 | 应用不存在 |
| 1_003_003_001 | AI_APPLICATION_CODE_DUPLICATE | 409 | 应用标识重复（appCode 唯一且不可改） |
| 1_003_003_002 | AI_APPLICATION_ORIGIN_INVALID | 400 | Origin 非法（只接受精确来源） |
| 1_003_003_003 | AI_APPLICATION_CREDENTIAL_INVALID | 401 | 客户端凭据无效（不存在/未启用/错误/已吊销同语义） |
| 1_003_003_007 | AI_RESOURCE_GRANT_NOT_FOUND | 404 | 资源授权不存在 |
| 1_003_003_008 | AI_RESOURCE_GRANT_DUPLICATE | 409 | 同一主体对同一资源类型的同一资源已有授权 |
| 1_003_003_009 | AI_AUTHORIZATION_DENIED | 403 | 授权判定拒绝（应用/主体/范围/授权/动作任一不满足） |
| 1_003_003_010 | AI_TICKET_INVALID | 401 | 访问票据无效或已过期（不存在/撤销/过期/应用或主体不可用同语义） |
| 1_003_004_000 | AI_RUN_NOT_FOUND | 404 | 运行不存在或无权访问 |
| 1_003_004_001 | AI_RUN_ALREADY_TERMINAL | 409 | 运行已终态 |
| 1_003_008_000 | AI_SERVICE_NOT_READY | 409 | 服务未标记可发布，不能创建发布候选 |
| 1_003_008_001 | AI_SERVICE_EVAL_MISSING | 409 | 缺少与候选内容匹配的评测结果（内容/端点配置变化后必须重新评测） |
| 1_003_008_002 | AI_SERVICE_EVAL_BELOW_THRESHOLD | 409 | 评测得分未达发布门槛 |
| 1_003_008_003 | AI_SERVICE_NOT_PUBLISHED | 409 | 服务没有生效的发布版本，新运行拒绝 |
| 1_003_008_004 | AI_SERVICE_RESOURCE_UNAVAILABLE | 409 | 发布版本依赖的资源绑定已解除或不可用，新运行拒绝 |
| 1_003_008_005 | AI_SERVICE_ENDPOINT_CONFIG_CHANGED | 409 | 端点配置版本已变化，需重建候选并重新评测 |
| 1_003_008_006 | AI_SERVICE_RELEASE_NOT_PUBLISHED | 409 | 发布版本尚未发布，不能用于运行解析或作为回退目标 |
| 1_003_008_007 | AI_SERVICE_RELEASE_PIN_STALE | 409 | 会话固定的发布内容已不一致，需要显式迁移会话 |

HTTP 语义遵循 [ADR 0003](../../adr/0003-http-status-semantics.md)；认证与授权边界见
[ADR 0049](../../adr/0049-ai-open-identity-and-security-extension-boundaries.md)。
