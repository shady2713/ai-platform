# D08 工具注册及执行政策证据（2026-09-21）

本记录是 [D08 实现工具注册及执行政策](../tasks/D08.md) 的验收证据。
依赖 [D01](../tasks/D01.md)（连接器与引用检查端口）、[A03](../tasks/A03.md)（资源授权）、[F07](../tasks/F07.md)（协议冻结）
均已有证据文档（`d01-connector-evidence.md`、`a03-resource-authorization-evidence.md`、`f07-protocol-freeze-evidence.md`）。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 持久化 | 迁移 `V70__ai_tool_registry.sql`：`ai_tool`（工具身份 + 来源连接器）与 `ai_tool_version`（政策 + 输入输出 schema + 来源绑定的不可变快照）；菜单与权限 4080–4084；快照同步至 V70（逻辑删除表 38 → 40） |
| 输入 schema | `domain/tool/AiToolInputSchema`：声明参数面（参数名/类型/必填）的唯一校验入口；伪造参数、必填缺失、类型不符一律拒绝；规范化 JSON 用于版本哈希 |
| 执行政策 | `domain/tool/AiToolPolicy`：AUTO/CONFIRM/DENY（**默认 DENY**，未知取值按 DENY）+ 工具类型 READ/WRITE（未知按 WRITE） |
| 政策闸门 | `service/tool/AiToolPolicyGate`：模型 tool-call 的唯一定价入口（存在且启用 → 已发布版本 → 政策 → 参数重校验），并提供"确认后执行"路径（仅 CONFIRM 政策可用） |
| 执行器 | `service/tool/AiToolExecutor`：**只接受判定对象**（非 EXECUTE 判定直接拒绝），执行复用 D02 的固定 Origin/请求头/有界分页链路 |
| 注册服务 | `service/tool/AiToolService(+Impl)`：工具 CRUD/启停、版本创建与发布（首期只发布读工具、来源 operation 必须已发布）、引用保护 |
| 引用保护 | `service/tool/AiToolReferenceChecker`（端口，供服务发布版本/分析步骤注册）+ `AiToolConnectorReferenceChecker`（注册进 D01 端口：工具在用连接器时拒绝删除） |
| 控制面 API | `controller/admin/tool/AiToolController`：10 个端点 + 6 个 VO，权限码与 V70 种子一一对应 |
| 错误码 | `1_003_006_041`–`049`（工具/版本/政策拒绝/需确认/参数非法/类型不支持/被引用），与 `docs/contracts/ai/error-code-map.md` 两侧同步 |
| 台账同步 | `数据库文件/basic_framework.sql`（V70 表与菜单）、`data-lifecycle.json`（2 张表进软删除 + 2 条外键）、`data-permission-exemptions.json`（新豁免 `ai-tool-registry` + 逐表证据）、`PersistenceLifecycleIT` 预期表清单 |
| 测试 | `AiToolInputSchemaTest`(5)、`AiToolPolicyGateTest`(9，政策矩阵)、`AiToolServiceImplTest`(5)、`AiToolControllerTest`(4)、`AiToolRegistryIT`(4，真实 MySQL + 真实已发布 operation) |

## 2. 与卡片逐步实施的对应

1. **建立 tool/version、input/output schema、AUTO/CONFIRM/DENY**：
   工具只承载"身份 + 来源连接器"，政策、输入/输出 schema、来源绑定（operationKey）都在**版本快照**里；
   版本内容哈希覆盖类型 + 政策 + 来源 + 输入/输出 schema——政策变化必然改变哈希，发布后不可修改。
2. **首期只发布读工具，默认 DENY**：`tool_type` 缺省按 WRITE 处理（更严格），发布时 WRITE 一律拒绝
   （`AI_TOOL_TYPE_UNSUPPORTED`）；`policy` 缺省 DENY，未知取值也按 DENY 处理。
   发布前还要求来源 operation **已发布**（草稿来源 → `AI_CONNECTOR_OPERATION_NOT_PUBLISHED`）。
3. **模型 tool-call 映射到已授权工具版本并重新检查参数**：
   `AiToolPolicyGate.decide(toolCode, arguments)` 按"工具存在且启用 → 已发布版本 → 政策 → 参数重校验"
   的顺序判定，参数按版本里的输入 schema 归一（只保留声明过的键）；
   执行器只接受 `AiToolDecision`，拿不到"工具名 + 参数"这种可绕过的入口。

## 3. 关键约束与安全语义

- **未经授权工具不能调用**：未注册的工具标识 → 404；停用工具与不存在**同码**（避免用错误码探测工具是否存在）；
  没有已发布版本 → 409（草稿不可执行）。
- **伪造工具名/参数拒绝**：伪造参数名不忽略而是 400（`AI_TOOL_ARGUMENT_INVALID`）；
  必填缺失、类型不符（含把对象塞进 string 参数）同样 400。
- **读取政策不能改变成写操作**：调用方能给的只有工具标识与参数值——方法、URL、请求头、政策、类型、
  来源全部来自版本快照；执行器只接受 EXECUTE 判定，CONFIRM 判定必须经确认流程后**重新判定**
  （`decideAfterConfirmation` 还要求政策确实是 CONFIRM，避免把确认流程当成政策绕过手段）。
- **政策不可静默放宽**：政策属于不可变版本；改政策必须新建版本并重新发布（IT 断言已发布版本再次发布 → 409）。
- **引用保护双向**：工具被服务/步骤引用时不可删除（端口就绪，消费方注册实现）；
  工具在用连接器时连接器不可删除（D08 注册的检查器，IT 用真实连接器验证）。

## 4. 验收用例对照

| 验收项 | 覆盖点 | 证据 |
|---|---|---|
| 未经授权工具不能调用 | 未注册/停用/无已发布版本 | `AiToolPolicyGateTest.forgedToolNameDisabledToolAndUnpublishedVersionAreRejected`、`AiToolRegistryIT.publishesReadToolAndExecutesPolicyMatrix`、`disabledToolBehavesLikeUnknownAndConnectorDeletionIsProtected` |
| 伪造工具名/参数拒绝 | 未声明参数/必填缺失/类型不符 | `AiToolInputSchemaTest.rejectsForgedArgumentsMissingRequiredAndTypeMismatch`、`AiToolPolicyGateTest.forgedArgumentsAreRejectedBeforeAnyExecution`、IT |
| 读取政策不能改变成写操作 | 写版本不可发布 + 判定只吃版本快照 + 执行器只吃 EXECUTE 判定 | `AiToolPolicyGateTest.readPolicyCannotBeTurnedIntoAWriteByTheCall`、`executorRefusesDecisionsWithoutExecuteOutcome`、`AiToolRegistryIT.refusesWriteToolAndUnpublishedSource` |
| 政策矩阵（受控执行边界） | AUTO 执行 / CONFIRM 需确认 / DENY 拒绝 | `AiToolPolicyGateTest`（9 例，含确认后执行路径）、`AiToolRegistryIT.policyIsImmutablePerVersionAndDefaultsToDeny` |

## 5. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。
命令前统一 `umask 022; export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -o -pl basic-framework-module-ai test` | 0 | 463 例通过（D08 新增 23 例：输入 schema 5、政策矩阵 9、注册服务 5、控制器 4） |
| `./mvnw -Pintegration -pl basic-framework-server test -Dtest=AiToolRegistryIT` | 0 | 4 例通过（真实 MySQL + 真实 OpenAPI 导入与发布的 operation） |
| `node scripts/check-data-lifecycle.mjs` | 0 | 表 65 / 策略 65 / 逻辑删除列 40 / 外键 43，快照同步至 V70 |
| `node scripts/check-data-permission.mjs` | 0 | 应用表 54 张：运行时保护 2、显式豁免 52、平台托管 11 |
| `sh .harness/verify.sh contracts` / `backend` / `integration` | 0 / 0 / 1（仅尾部棘轮） | 见第 7 节 |

## 6. 顺带修复的缺口（自查）

1. **工具 → 连接器的引用保护漏注册（集成测试暴露）**：D01 的引用检查端口需要消费方注册实现，
   D08 最初只写了 `AiToolReferenceChecker` 端口却忘了实现 `AiConnectorReferenceChecker`，
   导致"工具在用连接器仍可删除"（IT 断言失败）。补 `AiToolConnectorReferenceChecker` 后通过——
   这正是 D01 javadoc 里写明的扩展点。
2. **工具类型缺省语义（自查）**：`toolType` 缺省按 **WRITE** 处理（更严格），
   因此"忘记声明 READ"不会被静默放行；测试用例显式声明 READ 并断言缺省行为。
3. **发布状态检查顺序（自查）**：`publishVersion` 先判"已发布 → 409"，再判类型与来源，
   避免用"重复发布"掩盖"写工具被拒"这类结论（测试曾因此暴露过一次顺序问题）。
4. **分页参数缺失（自查）**：`getToolPage`/`getVersionPage` 最初把 null 分页参数直接透传给 Mapper，
   行为取决于底层实现；现在显式拒绝（400），"缺分页参数"不会静默变成"查全量"。
5. **`AiToolServiceImpl` 覆盖率不足（棘轮暴露）**：CRUD/启停/删除与版本创建校验缺少单测，
   补 `AiToolServiceImplTest`（5 例，含乐观锁冲突、引用保护、schema 校验分支）后达标——不靠下调基线。

## 7. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、权限目录、生命周期、字段目录、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单测、格式、架构与覆盖率检查通过 |
| `sh .harness/verify.sh integration` | 1 | **仅尾部覆盖率棘轮**因新文件未登记而失败；53 个 IT 类 / 171 例 **0 失败 0 错误**（含 `AiToolRegistryIT` 4 例） |
| `node scripts/check-coverage-ratchet.mjs --update` | 0 | 登记 D08 新增文件，无基线下调、无登记删除 |
| `node scripts/check-coverage-ratchet.mjs all` | 0 | 单文件基线全部通过 |

## 8. 覆盖率

| 文件 | 覆盖率 |
|---|---|
| `domain/tool/AiToolInputSchema.java` | 97.62% |
| `domain/tool/AiToolPolicy.java` | 100% |
| `service/tool/AiToolPolicyGate.java` | 100% |
| `service/tool/AiToolExecutor.java` | 100% |
| `service/tool/AiToolDecision.java` | 100% |
| `service/tool/AiToolServiceImpl.java` | 94.77% |
| `service/tool/AiToolConnectorReferenceChecker.java` | 83.33% |
| `controller/admin/tool/AiToolController.java` | 100% |

## 9. 未验证项

1. **AUTO 政策的真实 HTTP 调用**：政策判定与执行入口已实现并有单测（Mockito 驱动 D02 执行器），
   但"判定 → 真实出站 → 结果"的端到端路径未在真实上游验证（出站策略默认拒绝，本机无允许清单目标，
   与 D02/D07 同一环境限制）。
2. **确认流程（CONFIRM）**：本卡提供"需确认"判定与"确认后执行"路径，
   真正的确认交互（谁确认、如何记录、超时）由 D09（工具确认与分析步骤调度）编排，未在本卡验证。
3. **工具来源只支持 HTTP_OPERATION**：数据集查询类工具需要与 D06/D07 的执行器打通，
   在 D09/D10 落地；本卡对不支持的来源类型在创建版本时就拒绝。
4. **服务发布版本引用工具**：`AiToolReferenceChecker` 端口已就绪但暂无实现（消费方在 S/R 系列注册）。
5. **工具页面**：菜单 4080 已随迁移落地，页面在 D10（连接器语义与工具管理页面）交付；本卡不含前端改动。
