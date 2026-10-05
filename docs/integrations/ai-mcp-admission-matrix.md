# MCP 适配准入矩阵（X07）

本矩阵回答一个问题：**一个 MCP 服务器（或一个 MCP 工具）要满足什么条件才能被平台接受，
以及在每一层被拒绝时会发生什么。** 每一行都对应一个可执行的默认拒绝，而不是建议。

配套阅读：[ADR 0055](../adr/0055-controlled-mcp-client-discovery-and-default-deny.md)、
[证据文档](../ai-platform/verification/x07-mcp-client-evidence.md)。

## 一、服务器级准入（发现之前）

| # | 准入项 | 默认 | 拒绝时的稳定错误码 | 判定位置 | 反向用例 |
|---|---|---|---|---|---|
| S1 | 连接器存在 | 拒绝 | `AI_CONNECTOR_NOT_FOUND`(404) | `AiMcpEndpointFactory.build` | `AiMcpEndpointFactoryTest.unknownConnectorIsRejected` |
| S2 | 连接器类型为 HTTP | 拒绝 | `AI_MCP_ENDPOINT_NOT_ALLOWED`(422) | 同上 | `...disabledOrWrongTypeConnectorIsRejected` |
| S3 | 连接器处于 ENABLED | 拒绝 | `AI_MCP_ENDPOINT_NOT_ALLOWED`(422) | 同上 | 同上 |
| S4 | 声明式配置合法（https、无查询串/片段/用户信息） | 拒绝 | `AI_CONNECTOR_CONFIG_INVALID`(422) | 复用 D01 `AiConnectorConfig` | `...invalidConnectorConfigIsRejectedByTheSharedD01Validation` |
| S5 | 认证方式为 BEARER 且凭据存在 | 拒绝 | `AI_MCP_AUTHENTICATION_REJECTED`(422) | `AiMcpEndpointFactory` | `...declaredBearerAuthWithoutCredentialIsRejectedRatherThanDowngradedToAnonymous` |
| S6 | BASIC 一律拒绝（不静默改写为 Bearer） | 拒绝 | `AI_MCP_AUTHENTICATION_REJECTED`(422) | 同上 | `...basicAuthIsRejectedRatherThanSilentlyRewrittenAsBearer` |
| S7 | 匿名服务器默认拒绝，需显式放行 | 拒绝 | `AI_MCP_AUTHENTICATION_REJECTED`(422) | 同上 | `...anonymousServerIsDeniedUnlessExplicitlyAllowed` |
| S8 | 凭据解密失败收敛为稳定码，不泄漏秘密原文 | 拒绝 | `AI_MCP_AUTHENTICATION_REJECTED`(422) | `decryptQuietly` | `...credentialIsOnlyUsedForTheRequestHeaderAndNeverReturnedInErrors` |
| S9 | 主机在允许清单内（精确匹配，**无通配/后缀匹配**） | 空清单拒绝一切 | `AI_MCP_ENDPOINT_NOT_ALLOWED`(422) | `McpEndpointPolicy` | `McpEndpointPolicyTest.hostNotInAllowlistIsDenied`、`suffixLookalikeHostIsDeniedBecauseMatchingIsExact` |
| S10 | 端口在允许清单内 | 空清单拒绝一切 | `AI_MCP_ENDPOINT_NOT_ALLOWED`(422) | 同上 | `McpEndpointPolicyTest.emptyPortAllowlistDeniesEvenAllowedHost` |
| S11 | 协议为 https（公网 http 始终拒绝） | 拒绝 | `AI_MCP_ENDPOINT_NOT_ALLOWED`(422) | 同上 | `McpEndpointPolicyTest.publicHttpIsDeniedEvenWhenHostAllowed` |
| S12 | 私网/环回目标需 `allowPrivateTargets=true` | 拒绝 | `AI_MCP_ENDPOINT_NOT_ALLOWED`(422) | 同上 | `McpEndpointPolicyTest.privateTargetsRequireExplicitApproval` |
| S13 | baseUri 不携带用户信息/查询串/片段 | 拒绝 | `AI_MCP_ENDPOINT_NOT_ALLOWED`(422) | 同上 | `...addressCarryingUserInfoQueryOrFragmentIsDenied` |
| S14 | 协议版本在允许清单内 | 空清单拒绝一切 | `AI_MCP_PROTOCOL_VERSION_UNSUPPORTED`(422) | `McpClientAdapter` | `McpClientAdapterTest.emptyProtocolAllowlistDeniesEverythingBeforeAnyConnection` |

> S1–S8 全部发生在**任何网络动作之前**；S9–S14 同理。地址准入不通过时不会建立连接。

## 二、工具级准入（发现之后、进入注册表之前）

| # | 准入项 | 默认 | 拒绝时的稳定错误码 | 判定位置 | 反向用例 |
|---|---|---|---|---|---|
| T1 | 输入 schema 可映射为平台参数面 | 拒绝 | `AI_MCP_TOOL_SCHEMA_UNSUPPORTED`(422) | `AiMcpToolSchemaMapper` | `AiMcpToolSchemaMapperTest.camelCaseOrIllegalParameterNamesRejectTheWholeTool`、`unsupportedParameterTypesRejectTheWholeTool` |
| T2 | 至少声明一个参数 | 拒绝 | `AI_MCP_TOOL_SCHEMA_UNSUPPORTED`(422) | 同上 | `...toolsWithoutDeclaredParametersAreRejected` |
| T3 | 参数个数 ≤ 32 | 拒绝 | `AI_MCP_TOOL_SCHEMA_UNSUPPORTED`(422) | 同上 | `...tooManyParametersAreRejected` |
| T4 | 工具清单条数 ≤ 上限 | 拒绝（不截断） | `AI_MCP_TOOL_LIST_EXCEEDED`(422) | `McpClientAdapter` | `McpClientAdapterTest.oversizedToolListIsRejectedRatherThanTruncated` |
| T5 | 发现结果**一律**只落草稿表 | 不进注册表 | —（不产生工具行） | `AiMcpToolServiceImpl` | `AiMcpToolServiceTest.discoveredToolOnlyBecomesADraftAndNeverTouchesTheToolRegistry`、IT `discoveredToolStaysADraftAndIsNotExecutableUntilApprovedAndPublished` |
| T6 | 未审批草稿不得进入执行面 | 拒绝 | `AI_MCP_TOOL_NOT_APPROVED`(422) | `AiMcpToolDraftChecker` | `...anUnapprovedDraftIsRejectedByTheBindingGate` |
| T7 | 上游从未出现过的工具名 | 拒绝 | `AI_MCP_TOOL_NOT_APPROVED`(422) | 同上 | `...anUnknownUpstreamToolIsRejectedByTheBindingGate` |
| T8 | 审批后政策恒为 DENY | 拒绝自动执行 | — | `AiMcpToolServiceImpl.approve` | `...approvalAlwaysLocksTheFingerprintAndAlwaysLeavesPolicyAtDeny` |
| T9 | MCP 来源不得经 HTTP 执行路径执行 | 拒绝 | `AI_MCP_TOOL_NOT_EXECUTABLE`(422) | `AiToolExecutor` | IT `mcpToolCannotBeExecutedThroughTheHttpExecutionPathEvenAfterApproval` |
| T10 | 已审批项不得重复审批 | 拒绝 | `AI_MCP_TOOL_APPROVAL_CONFLICT`(409) | `AiMcpToolServiceImpl.approve` | `...repeatedApprovalOfAnAlreadyApprovedDraftConflicts` |
| T11 | 缺少指纹/参数面的草稿不得审批 | 拒绝 | `AI_MCP_TOOL_APPROVAL_CONFLICT`(409) | 同上 | `...draftWithoutFingerprintOrSchemaCannotBeApproved` |
| T12 | 乐观锁冲突必须冒泡 | 拒绝 | `AI_STATE_CONFLICT`(409) | 同上 | `...approvalCasConflictIsSurfacedAsStateConflict`、`...concurrentDriftUpdateConflictIsSurfacedAsStateConflict` |

## 三、漂移与断线

| # | 场景 | 默认 | 稳定错误码 | 反向用例 |
|---|---|---|---|---|
| D1 | 上游 schema 变化 | 草稿置 `BLOCKED`，旧审批**立即失效** | `AI_MCP_TOOL_SCHEMA_DRIFT_CONFLICT`(409) | IT `upstreamSchemaUpgradeBlocksTheOldPublishedVersion`、`...aBlockedDraftFailsTheBindingGateWithConflictEvenThoughItWasApprovedBefore` |
| D2 | 被阻断项试图沿用旧认知审批 | 拒绝 | `AI_MCP_TOOL_APPROVAL_CONFLICT`(409) | IT 同上末段 |
| D3 | 结构未变 | 保留既有审批，不重置为 DRAFT | —（正向对照） | IT `unchangedSchemaKeepsTheExistingApprovalWithoutResettingToDraft` |
| D4 | 断线（超时/不可达） | 有界重试后**明确终止**，抛错而非返回空清单 | `AI_MCP_DISCOVERY_TERMINATED`(422) | IT `disconnectTerminatesWithBoundedAttemptsAndDurableEvidence` |
| D5 | 授权被拒 | 不重试 | `AI_MCP_AUTHENTICATION_REJECTED`(422) | IT `rejectedAuthorizationIsNotRetriedAndLeavesItsOwnEvidence` |
| D6 | 瞬时失败后恢复 | 重试并记录尝试次数 | —（正向对照） | IT `transientFailureFollowedBySuccessIsRetriedAndRecorded` |
| D7 | 协议版本漂移 | 拒绝，不降级协商 | `AI_MCP_PROTOCOL_VERSION_UNSUPPORTED`(422) | IT `protocolVersionDriftIsRejectedAndLeavesEvidence` |

## 四、配置参考

```yaml
basic-framework:
  ai:
    mcp:
      allowed-protocol-versions: ["2025-06-18"]   # 空 = 拒绝一切版本
      max-tools-per-discovery: 200                 # 超限拒绝而非截断
```

出站地址/端口/主机清单（module-ai 侧 `McpClientAccessPolicy`）：
`allowed-hosts`（精确匹配）、`allowed-ports`、`allow-private-targets`（默认 false）、
`allow-anonymous-servers`（默认 false）。**全部默认拒绝一切。**

## 五、覆盖缺口

矩阵中 S1–S14、T1–T12、D1–D7 均有反向用例与一条正向对照。
**唯一未覆盖项**是与真实 MCP 服务器完成一次 `initialize` + `tools/list` 握手
（见证据文档"未验证项"）——矩阵判定的是平台侧准入，线路协议那一步本机无法构造对端。
