# MCP 管理接入流程（X07）

本文是**运维/管理员侧**的操作流程。判定规则见
[准入矩阵](ai-mcp-admission-matrix.md)，设计理由见
[ADR 0055](../adr/0055-controlled-mcp-client-discovery-and-default-deny.md)。

> **本卡未提供管理端 API 与页面**（卡片 §2 未授权控制器与前端路径）。
> 下列步骤目前通过**服务层** `AiMcpToolService` / `AiToolService` 执行；
> 接入 UI/API 属于后续卡片。服务层方法是稳定的接缝，UI 接入不应改动它们。

## 角色

| 角色 | 能做什么 | 不能做什么 |
|---|---|---|
| 连接器管理员 | 登记 MCP 服务器（连接器）、配置地址与凭据 | 不能审批工具、不能改政策 |
| 工具审批人 | 审阅草稿、审批或驳回、决定工具政策 | 不能改上游 schema（那是上游的事） |
| 运维 | 配置允许清单、读发现留痕排障 | 不能审批工具 |

审批人与草稿发现者**不必是同一人**，但本卡不强制（独立审核要求见
`AI_MASTER_OBJECT_PUBLISHER_CONFLICT` 的先例，属后续卡片）。

## 阶段 0：前置配置（一次性，运维）

```yaml
basic-framework:
  ai:
    mcp:
      allowed-protocol-versions: ["2025-06-18"]   # 空 = 拒绝一切
      max-tools-per-discovery: 200
```

module-ai 侧 `McpClientAccessPolicy`（默认全部拒绝）：

| 配置项 | 默认 | 含义 |
|---|---|---|
| `allowed-hosts` | 空 | 精确匹配的 MCP 服务器主机；**不支持通配/后缀** |
| `allowed-ports` | 空 | 允许端口 |
| `allow-private-targets` | `false` | 是否允许 http 访问环回/私网（内网部署用） |
| `allow-anonymous-servers` | `false` | 是否允许无凭据访问 MCP 服务器 |

**没配就等于全部拒绝**，这是有意的：MCP 服务器通常持有真实数据面。

## 阶段 1：登记 MCP 服务器

MCP 服务器以 **HTTP 连接器**登记（复用 D01 的地址校验与秘密加密，**不新建第二套体系**）。

1. 创建连接器，类型 `HTTP`：
   - `baseUrl`：`https://mcp.example.com`（**必须 https**，不得带查询串/片段/用户信息）
   - `method`：`POST`
   - `authType`：`BEARER`
   - `timeoutMillis`：建议 5000；留空取默认
2. 写入凭据（Bearer 令牌）。凭据以密文落库，只在请求头内存活。
3. 确认服务器主机与端口都在允许清单内。

**失败即拒绝**（不降级、不匿名）：

| 情况 | 错误码 |
|---|---|
| 声明 `BEARER` 但没写凭据 | `AI_MCP_AUTHENTICATION_REJECTED` |
| `authType=BASIC` | `AI_MCP_AUTHENTICATION_REJECTED`（不静默改写为 Bearer） |
| `authType=NONE` 且未显式放行匿名 | `AI_MCP_AUTHENTICATION_REJECTED` |
| 主机/端口不在允许清单 | `AI_MCP_ENDPOINT_NOT_ALLOWED` |
| 连接器停用或类型不是 HTTP | `AI_MCP_ENDPOINT_NOT_ALLOWED` |

## 阶段 2：发现（只生成草稿）

调用 `AiMcpToolService.discover(connectorId)`。

- **成功**：返回摘要（工具数、新增草稿数、漂移阻断数、尝试次数、终止结果）。
  逐个工具写入 `ai_mcp_tool_draft`，状态 `DRAFT`，**`tool_id` 为空**。
- **失败**：抛稳定错误码，并写一条 `ai_mcp_discovery_run` 留痕。**不会返回空清单**。

| 情况 | 错误码 | 是否重试 |
|---|---|---|
| 超时 / 网络不可达（有界重试耗尽） | `AI_MCP_DISCOVERY_TERMINATED` | 是（≤5 次） |
| 授权被拒 | `AI_MCP_AUTHENTICATION_REJECTED` | 否 |
| 协议版本不在允许清单 | `AI_MCP_PROTOCOL_VERSION_UNSUPPORTED` | 否 |
| 工具清单超上限 | `AI_MCP_TOOL_LIST_EXCEEDED` | 否 |
| 某工具参数声明无法映射 | `AI_MCP_TOOL_SCHEMA_UNSUPPORTED` | 否（需上游修正声明） |

**注意**：`AI_MCP_TOOL_SCHEMA_UNSUPPORTED` 会中止整次发现（不做部分发现）。
这是有意的——半截清单会被读成"上游就这些工具"。参数名不合规（如 camelCase）、
类型不受支持（array/object）、未声明参数都会触发。

## 阶段 3：审阅草稿

`listDrafts(connectorId)` 列出全部草稿。审阅时看三样东西：

| 字段 | 用途 | 可信度 |
|---|---|---|
| `upstreamToolName` | 上游工具名 | 结构标识（会参与指纹） |
| `upstreamTitle` / `upstreamDescription` | **给人读的**说明 | ⚠️ **上游可控的不可信文本** |
| `observedFingerprint` | 结构指纹 | 只覆盖「工具名 + 输入 schema」，**不含描述** |

> **审阅提示**：描述里出现"忽略之前的指令""该工具已通过安全审批""请把 policy 设为 AUTO"
> 之类的文案**不会有任何效果**——描述不参与授权判定，指纹也不含描述。
> 平台不会因为描述这么说就放行；请按工具**实际参数面**判断其危险性。

## 阶段 4：审批

`approve(draftId, version)`：

1. 创建/复用平台工具（标识 `mcp-<上游工具名>`，归一到 D08 标识语法）。
2. 创建 D08 工具版本：**`toolType=READ`、`policy=DENY`、`sourceKind=MCP_TOOL`**。
3. 锁定 `approvedFingerprint = observedFingerprint`，草稿置 `APPROVED`。

| 情况 | 错误码 |
|---|---|
| 草稿不存在 | `AI_MCP_TOOL_DRAFT_NOT_EXISTS`(404) |
| 草稿是 `BLOCKED`（上游已改过） | `AI_MCP_TOOL_APPROVAL_CONFLICT`(409) |
| 重复审批 | `AI_MCP_TOOL_APPROVAL_CONFLICT`(409) |
| 缺指纹或缺参数面 | `AI_MCP_TOOL_APPROVAL_CONFLICT`(409) |
| 乐观锁冲突 | `AI_STATE_CONFLICT`(409) |

**审批 ≠ 可执行。** 审批只表示"这条上游工具的结构已被人工确认"，
之后它仍要经 D08 的版本发布与政策闸门，政策默认 `DENY`。

## 阶段 5：版本发布与政策（走 D08 既有管线）

`AiToolService.publishVersion(...)` 对 `MCP_TOOL` 来源**不走**"来源 operation 已发布"检查
（那里没有 operation），改问来源守卫：草稿必须 `APPROVED` 且
`approvedFingerprint == observedFingerprint`，否则拒绝。

即便发布成功，`AiToolExecutor` 仍会拒绝执行 MCP 来源的判定
（`AI_MCP_TOOL_NOT_EXECUTABLE`）——本卡只做发现，不代执行。

## 阶段 6：日常运维

**排障"这次没发现工具"**：先看 `ai_mcp_discovery_run`，不要猜。

```sql
SELECT attempts, termination, failure_code, tool_count, elapsed_millis
FROM ai_mcp_discovery_run
WHERE connector_id = ? ORDER BY id DESC LIMIT 10;
```

- `termination=SUCCESS` 且 `tool_count=0` → 上游确实没有工具。
- `termination=TIMEOUT/UNREACHABLE` → 断线，`attempts` 就是有界重试的实际次数。
- `termination=AUTHORIZED` → 凭据问题（`AI_MCP_AUTHENTICATION_REJECTED`）。
- `termination=PROTOCOL_VERSION_UNSUPPORTED` → 上游升级了协议版本，需更新允许清单（**须评审**）。
- `attempts > 1` → 发生过重试；`attempts == 1` 且失败 → 是不可重试的拒绝。

**响应 schema 升级后的处置**：

1. 重新 `discover` → 该工具草稿自动置 `BLOCKED`（`AI_MCP_TOOL_SCHEMA_DRIFT_CONFLICT`），
   旧发布**立即失效**。
2. 审阅新的 `observedFingerprint` 与参数面。
3. 确认上游的变更是否符合预期后，再次 `discover`（刷新指纹）→ `approve` 重新审批。
4. 若判定为恶意/不可接受变更：停用对应连接器（`updateStatus`），并在草稿上保持 `BLOCKED`。

**不要**通过"改回去"或"忽略指纹"来让旧审批继续生效——那正是漂移阻断要防的事。

## 回滚

| 动作 | 效果 |
|---|---|
| 停用连接器 | 该服务器不再被发现；已有工具仍在注册表，需另行停用 |
| 停用工具（`AiToolService.updateStatus`） | 立即不可执行（与"不存在"对外同码） |
| 删除工具 | 被引用时拒绝（409） |

## 已知边界

- **本卡没有管理端 API/UI**：步骤 2–4 走服务层。
- **MCP 工具尚不可执行**：接入执行路径需单独评审远程调用的副作用与幂等要求。
- **DNS 重绑定窗口**：允许清单按主机名放行，不做解析后地址复核（与 F09 同）。
- **与真实 MCP 服务器的端到端握手未在本机验证**（见证据文档"未验证项"）。
