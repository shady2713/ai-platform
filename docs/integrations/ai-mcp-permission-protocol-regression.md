# MCP 权限与协议回归（X07）

本文是**回归清单**：本卡对既有能力（D08 工具注册与执行政策、F09 出站边界、D01 连接器）
的影响范围，以及必须持续成立的断言。执行一次回归 = 跑下面列出的测试类 + 人工核对项。

配套：[准入矩阵](ai-mcp-admission-matrix.md)、[接入流程](ai-mcp-onboarding-process.md)、
[ADR 0055](../adr/0055-controlled-mcp-client-discovery-and-default-deny.md)。

## 一、对既有能力的回归

### 1.1 D08 工具注册与执行政策（本卡改动最重的既有子系统）

本卡改了三处 D08 代码，因此这三处是回归重点：

| 改动 | 位置 | 风险 | 回归断言 |
|---|---|---|---|
| `createVersion` 接受 `MCP_TOOL` 来源 | `AiToolServiceImpl.createVersion` | 误把未知来源放进来 | 未知 `sourceKind` 仍被拒（`AI_TOOL_TYPE_UNSUPPORTED`）；`AiToolServiceImplTest` 全部原样通过 |
| `publishVersion` 按来源分叉 | `AiToolServiceImpl.publishVersion` | HTTP 路径被改坏；MCP 路径变成"跳过检查" | HTTP 来源判定逐字保留在 `else` 分支；MCP 来源找不到守卫即拒绝 |
| `execute`/`executeWrite` 拒绝非 HTTP 来源 | `AiToolExecutor` | 误伤 HTTP 工具 | 判定来源不是 `HTTP_OPERATION` 才拒绝；D08 全部执行器用例通过 |
| 构造器新增 `List<AiToolSourceGuard>` | `AiToolServiceImpl` | 装配失败 | Spring 上下文启动成功（IT 证明）；两个 D08 测试类构造调用同步更新，**断言未删减** |

**必须持续成立的性质**：

- 政策默认 `DENY`，且在不可变版本里；
- 未注册/停用工具与"不存在"同码（404）；
- 草稿版本不可执行；
- 伪造参数名/必填缺失/类型不符一律拒绝；
- 写工具必须经确认入口，且绑定不可被"确认后改参数"绕过。

**回归命令**：

```sh
./mvnw -o -pl basic-framework-module-ai test -Dtest='AiTool*Test'
# 期望：55 例通过（D08/X06 既有用例），其中 AiToolServiceImplTest 7 例、AiToolPolicyGateTest 10 例
```

### 1.2 D08 审批管线复用（不是另造一套）

本卡**没有**新建审批管线。证据：

- 审批动作最终写的是既有表 `ai_tool` + `ai_tool_version`，走既有 `AiToolService.create/createVersion`；
- 政策字段由 D08 的 `AiToolPolicy.parse` 解析，缺省仍为 `DENY`；
- 执行判定仍由 `AiToolPolicyGate.decide` 做，本卡只在**发布**时加一道来源守卫。

**若以下任一发生，即视为回归失败**：

- MCP 工具绕过 `AiToolPolicyGate` 获得执行许可；
- 出现第二套"审批状态"字段（除 `ai_mcp_tool_draft.status` 这个**发现侧**记账外）；
- MCP 工具的 `policy` 能被设为非 `DENY` 而不经 D08 的人工决定。

### 1.3 D01 连接器与 F09 出站边界

| 性质 | 断言 |
|---|---|
| 不引入第二套地址/凭据体系 | MCP 服务器复用 `ai_connector`；凭据走 `CredentialCipher` 密文；查询接口不返回密文 |
| 地址校验复用而非重写 | 非 https / 带查询串的 `baseUrl` 在 `AiConnectorConfig` 层被拒（`AI_CONNECTOR_CONFIG_INVALID`） |
| 出站允许清单独立 | MCP 走 SDK 自带传输，不经 F09 请求器，因此有**自己**的 `McpEndpointPolicy`；两份清单不共享 |
| 秘密不进异常 | 凭据解密失败收敛为 `AI_MCP_AUTHENTICATION_REJECTED`，不含秘密原文（`AiMcpEndpointFactoryTest`） |
| 连接器引用保护 | 工具在用连接器时不可删除（D08 既有能力，MCP 工具同样受约束） |

### 1.4 错误码与契约

| 项 | 断言 |
|---|---|
| 编号不重复 | `1_003_019_xxx` 未被其它域占用（`ErrorCodeUniquenessTest` 全库扫描） |
| HTTP 派生 | `_NOT_EXISTS` → 404；含 `CONFLICT` → 409；其余 422（`docs/contracts/ai/error-code-map.md` 同步） |
| 拒绝消息不泄漏 | 本区间 `ErrorCode` 消息体不含 `{}` 占位符；不携带主机名、上游正文、令牌片段 |

**回归命令**：

```sh
./mvnw -o -pl basic-framework-server test -Dtest='ErrorCodeUniquenessTest'
```

## 二、协议回归（MCP 侧）

| 性质 | 断言 | 覆盖 |
|---|---|---|
| 只做客户端 | 会话接口无 `callTool`；`McpSdkSessionFactory` 不调用它 | 代码结构 + `McpClientSession` javadoc |
| 有界重试 | 尝试次数 ≤ `McpServerEndpoint.MAX_ATTEMPTS_CEILING`（类型夹紧，非约定） | `McpClientAdapterTest` |
| 不可重试项不重试 | 授权被拒/协议错误/地址被拒/超限只试一次 | `McpClientAdapterTest.nonRetryableFailuresStopOnTheFirstAttempt` |
| 不得静默降级 | 失败抛错且不产出工具清单；`toolsOrEmpty()` 失败时为空 | `McpToolDescriptorTest.discoverySnapshotNeverExposesToolsOnFailure` |
| 超限不截断 | 工具条数超限整体拒绝 | `McpClientAdapterTest.oversizedToolListIsRejectedRatherThanTruncated` |
| 版本漂移不降级 | 协商版本不在清单即拒绝 | `McpClientAdapterTest.protocolVersionDriftIsRejectedWithoutDowngrade` |
| 空允许清单拒绝一切 | 协议版本清单为空时**建连之前**拒绝 | `McpClientAdapterTest.emptyProtocolAllowlistDeniesEverythingBeforeAnyConnection` |
| 空清单不等于无工具 | 成功且真空清单 ≠ 失败 | `McpClientAdapterTest.emptySuccessfulToolListIsPreservedAsAnActualEmptyCatalog` |
| 地址默认拒绝 | 空清单拒绝一切；精确匹配；https；端口清单；无用户信息/查询串 | `McpEndpointPolicyTest`（13 例） |
| 传输异常收敛 | 各类异常映射到枚举终止原因，且不泄漏上游正文 | `McpSdkSessionFactoryTest` |
| 令牌只进请求头 | 定制器只加 `Authorization`，不碰传输层保留头 | `McpSdkSessionFactoryTest.bearerCustomizerAddsOnlyTheAuthorizationHeader` |

**协议回归命令**：

```sh
./mvnw -o -pl basic-framework-core/basic-framework-spring-boot-starter-ai test -Dtest='Mcp*Test'
# 期望：49 例通过
```

## 三、提示注入回归（验收 2 的持续防线）

这是本卡最容易被"顺手改坏"的性质，因此单列。

| 攻击形态 | 期望结果 | 覆盖 |
|---|---|---|
| 描述写"policy 设为 AUTO" | 无效果：草稿仍 `DRAFT`、政策仍 `DENY` | IT `anInjectedToolDescriptionCannotObtainAnyExtraPrivilege` |
| 描述写"已通过安全审批" | 无效果：绑定闸门仍拒 | `AiMcpToolServiceTest.anInjectedDescriptionCannotTurnAnUnapprovedDraftIntoAnApprovedOne` |
| 描述写"忽略之前的指令" | 无效果：映射结果与无害描述逐字节相同 | `AiMcpToolSchemaMapperTest.injectedDescriptionDoesNotInfluenceTheMappedSchema` |
| 描述改成诱导文案 | 指纹不变（描述不进指纹） | `McpToolDescriptorTest.descriptionDoesNotAffectTheStructuralFingerprint` |
| 描述超长 | 截断，不影响结构判定 | `McpToolDescriptorTest.injectedDescriptionIsRetainedVerbatimForHumanReviewButTruncated` |

**结构性保证**（改动时必须保持）：

1. `McpToolDescriptor.schemaFingerprint()` 只覆盖 `name + inputSchemaJson`；
2. `AiMcpToolServiceImpl` 中**没有任何分支读取 description 决定授权**；
3. 描述只出现在 `upstream_description`（供人读）与 DTO 展示。

> 若将来有人把 description 加进指纹，验收 2 立刻失效：上游就能通过改描述
> 触发或规避漂移阻断。**指纹字段变更需要 ADR 级别的评审。**

## 四、漂移回归（验收 4）

| 场景 | 期望 | 覆盖 |
|---|---|---|
| 结构变化 | 草稿置 `BLOCKED`，`approved_fingerprint` 清空，记 409 原因码 | IT `upstreamSchemaUpgradeBlocksTheOldPublishedVersion` |
| 阻断后闸门 | 旧发布立即失效（此前明明通过） | IT `...aBlockedDraftFailsTheBindingGateWithConflictEvenThoughItWasApprovedBefore` |
| 阻断后审批 | 拒绝沿用旧认知 | 同上末段 |
| 结构未变 | 保留审批，不重置为 `DRAFT`，不重复插入 | IT `unchangedSchemaKeepsTheExistingApprovalWithoutResettingToDraft` |
| 并发漂移 | 乐观锁冲突冒泡为 `AI_STATE_CONFLICT` | `AiMcpToolServiceTest.concurrentDriftUpdateConflictIsSurfacedAsStateConflict` |
| 工具改名 | 指纹变化（改名 = 换了一个工具） | `McpToolDescriptorTest.renamingTheToolAlsoChangesTheFingerprint` |

## 五、持久化回归

| 项 | 断言 |
|---|---|
| 唯一性 | `(connector_id, upstream_tool_name, deleted)` 唯一：并发发现不产生重复草稿 |
| 生命周期 | 两张表都是 soft-delete，已进 `data-lifecycle.json` 软删除策略 |
| 台账 | `data-permission-exemptions.json` 收录 `ai-mcp-client-discovery`（internal-only + 工具治理权限） |
| 快照 | `basic_framework.sql` 含 V96 两张表 |
| 预期表清单 | `PersistenceLifecycleIT` 含 `ai_mcp_tool_draft` / `ai_mcp_discovery_run` |

**回归命令**：

```sh
./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiMcpClientIT,PersistenceLifecycleIT'
```

## 六、人工核对项（无法自动化）

1. **依赖树**：确认 `spring-ai-mcp` / `spring-ai-autoconfigure-mcp-client-*` **不在**产品依赖树中。
   ```sh
   ./mvnw -o -pl basic-framework-core/basic-framework-spring-boot-starter-ai dependency:tree | grep -i mcp
   ```
   期望只出现 `io.modelcontextprotocol.sdk:*`。
2. **版本属性**：MCP 版本只在 `basic-framework-dependencies/pom.xml` 定义一次，模块 pom 不写死版本。
3. **配置默认拒绝**：未配置 `basic-framework.ai.mcp.*` 与 `McpClientAccessPolicy.*` 时，
   任一发现调用都应被拒绝。
4. **行长**：任何源文件（含测试）≤ 800 行；`AiErrorCodeConstants.java` 仍为 800 行且**未被追加常量**。

## 七、回归触发条件

- 升级 MCP Java SDK 或修改 `basic-framework.ai.mcp.*` 默认值；
- 修改 `McpToolDescriptor.schemaFingerprint()` 的覆盖范围（**必须重跑第三节全部用例**）；
- 修改 D08 的 `createVersion` / `publishVersion` / `AiToolExecutor`；
- 调整 F09 的出站策略或 D01 的连接器配置白名单；
- 新增任何允许 MCP 工具进入执行路径的代码（当前刻意不可执行）。
