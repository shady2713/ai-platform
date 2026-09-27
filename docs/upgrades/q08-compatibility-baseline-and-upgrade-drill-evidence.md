# Q08 兼容性基线与升级演练（后端切片）证据

| 项目 | 内容 |
|---|---|
| 任务卡 | [Q08](../ai-platform/tasks/Q08.md) |
| 范围 | 后端切片：模块化兼容回归包（AT-065/AT-072）+ 阻断演示 + AT-049 基线夹具 |
| 工作副本 | `/home/ctyun/桌面/zhongtai/ai-platform`（后端目录 `后端代码/basic-framework-boot`） |
| 状态 | 代码与命令均已实际执行；未验证项见 §6 |
| 日期 | 2026-09-27 |

## 1. 交付内容与证据层级

本切片不把"编译通过"当兼容结论：结论来自**真实执行**的兼容回归用例，输入是仓库里的真实材料
（冻结契约 Schema/样例、上游台账、依赖 BOM、Flyway 迁移目录），输出是退出码与用例断言。

| 交付物 | 位置 |
|---|---|
| 模型侧协议升级回归包（AT-065） | `basic-framework-module-ai/src/test/java/com/basicframework/module/ai/compatibility/ModelProtocolUpgradeRegressionTest.java`（9 例） |
| 冻结线级夹具与黄金值 | 同目录 `FrozenUpstreamWireFixtures.java`、`ProtocolBaselineGolden.java` |
| 迁移碰撞演练（AT-072） | 同目录 `MigrationCollisionUpgradeDrillTest.java`（6 例）+ `MigrationHistoryValidator.java`（测试内校验器） |
| 阻断演示①：删必需字段 | 同目录 `ContractRequiredFieldBlockingTest.java`（14 例） |
| 阻断演示②：新增危险依赖 | 同目录 `UpstreamDependencyBlockingTest.java`（6 例）+ `UpstreamDependencyPolicy.java` |
| AT-049 旧 ReportSpec 基线加载 | 同目录 `ReportSpecBaselineCompatibilityTest.java`（3 例） |
| 仓库材料定位（只读） | 同目录 `CompatibilityRepositorySupport.java` |

约定：命令统一先 `umask 022 && export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`。

**为什么夹具是"基线"而不是"N-1"**：首发没有已发布的历史产物（Spring AI 1.1.8 是平台首个冻结候选），
按 Q08 卡 §4 以基线夹具报告；首次真实升级时用已发布旧产物替换/补充，回归包与黄金值保持不变。

## 2. 逐条验收结果

### 2.1 最终绿跑（主工作副本）

```bash
cd /home/ctyun/桌面/zhongtai/ai-platform/后端代码/basic-framework-boot
./mvnw -q -o -pl basic-framework-module-ai spotless:apply -DspotlessFiles='.*compatibility.*\.java'   # 退出码 0
./mvnw -o -pl basic-framework-module-ai test \
  -Dtest='com.basicframework.module.ai.compatibility.*Test' -DfailIfNoTests=false
```

退出码 **0**，关键输出：

```text
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0 -- ContractRequiredFieldBlockingTest
Tests run:  9, Failures: 0, Errors: 0, Skipped: 0 -- ModelProtocolUpgradeRegressionTest
Tests run:  6, Failures: 0, Errors: 0, Skipped: 0 -- MigrationCollisionUpgradeDrillTest
Tests run:  6, Failures: 0, Errors: 0, Skipped: 0 -- UpstreamDependencyBlockingTest
Tests run:  3, Failures: 0, Errors: 0, Skipped: 0 -- ReportSpecBaselineCompatibilityTest
Tests run: 38, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

（定向单类命令同样已执行，如 `-Dtest=ReportSpecBaselineCompatibilityTest` → 退出码 0，3 例通过。）

测试报告路径（本机）：`后端代码/basic-framework-boot/basic-framework-module-ai/target/surefire-reports/TEST-com.basicframework.module.ai.compatibility.*.xml`
（五个套件分别 `tests="14|9|6|6|3" errors="0" failures="0"`）。

### 2.2 AT-065 Spring AI/AntV 候选升级 → 模型侧协议回归

**结论：通过（基线模式，模型侧协议）**；AT-065 的"固定用例、权限、UI 和包体无未解释回退"中的
UI/包体部分不在后端切片，见 §6。

回归包分两层，两层都对准真实代码：

1. **线级**：冻结的 OpenAI 兼容响应 JSON 必须能被候选上游**自身的线级模型**
   （`org.springframework.ai.openai.Api.OpenAiApi$ChatCompletion/ChatCompletionChunk`，1.1.8）
   按原字段名解析；字段改名/缺失立即暴露。
2. **平台级**：解析结果送入**真实适配器** `SpringAiModelClient`，把平台契约对象规范化后与
   `ProtocolBaselineGolden` 逐字比对（文本、流式事件序列、工具调用聚合、结构化输出、用量、错误映射）。

覆盖的请求-响应契约（对应 06 §4.1 的升级回归清单，本切片取模型侧）：

| 用例 | 断言 |
|---|---|
| `recordedUpstreamRequestShapeIsFrozenAndPromptPassesThroughVerbatim` | F02 实测记录的请求体形状（messages/role/content/model/stream=false/temperature）不变；平台提示词以 USER 消息逐字直通；不注册厂商工具回调（`Prompt.getOptions()` 为 null） |
| `textContractIsUnchanged` | `text=pong from mock; finishReason=stop; usage=7/3/estimated=false; tools=0` |
| `streamContractIsUnchanged` | 事件序列逐字等于黄金值：两个 `DELTA` → 一个聚合后的 `TOOL_CALL`（分片参数合并为 `{"value":"ping"}`）→ 一个 `COMPLETED`（usage 7/5、finishReason stop） |
| `structuredOutputContractIsUnchanged` | 带 Markdown 围栏的输出经有界修复后为 `{"metric":"八月华东净销售额","value":740}`；Schema 作为输出契约嵌入上游提示词 |
| `structuredOutputArrayIsRejectedInsteadOfAccepted` | 上游返回 JSON 数组时 `INVALID_STRUCTURED_OUTPUT`（不放行非对象） |
| `usageMissingIsUnknownNotFakeZero` | 上游缺失 usage → `prompt=null; completion=null; total=null; known=false`（AT-060，不写假 0） |
| `upstreamFailureMappingIsStableAndDoesNotLeak` | 失败收敛为 `UPSTREAM_FAILED`，消息不含上游报文金丝雀与 `sk-` |
| `candidateWireContractChangeIsDetected` | **负向对照**：候选把 `content` 改名成 `text` 后，线级模型读不到 content，平台快照与基线不一致（红侧证明） |
| `candidateStreamChunkChangeIsDetected` | **负向对照**：候选流式分片丢掉 `delta` 后，工具调用不再聚合，事件序列与基线不一致（红侧证明） |

**先红后绿（在隔离的演练副本 `/tmp/q08-scratch/ai-platform` 执行，避免与并行的全量门禁互相干扰）**：

```bash
# 红：把候选响应夹具内容改成 "pong from mock v2"（模拟升级后行为变化），黄金值不动
./mvnw -o -pl basic-framework-module-ai test -Dtest='ModelProtocolUpgradeRegressionTest' -Djacoco.skip=true
```

退出码 **1**：

```text
textContractIsUnchanged:252 [文本请求-响应契约必须与冻结基线一致]
expected: "text=pong from mock; finishReason=stop; usage=7/3/estimated=false; tools=0"
 but was: "text=pong from mock v2; finishReason=stop; usage=7/3/estimated=false; tools=0"
Tests run: 9, Failures: 1, Errors: 0, Skipped: 0
```

恢复夹具后同一命令退出码 **0**（`Tests run: 9, Failures: 0`）。即：候选行为变化会让回归包变红，
当前冻结候选（Spring AI 1.1.8 + Boot 3.5.16）与基线一致。

### 2.3 AT-072 基础框架升级迁移碰撞 → 保留已执行历史，测试阻断错误覆盖

**结论：通过（演练）**。演练的"已执行历史"直接读真实仓库迁移目录
（`basic-framework-server/src/main/resources/db/migration`）：**82 个迁移文件，版本 1–83（V7 缺失），
最高 V83__ai_evaluation.sql（SHA-256 `8ca56d6cfa569738449af2d518c4b8f20bad127da081b3f221e2593efd922ab2`）**。
候选集合在内存构造，**不改任何历史 SQL**。

| 场景 | 构造 | 断言（全部通过） |
|---|---|---|
| 真实历史合法 | 直接读取仓库 | 82 个文件版本唯一、严格递增；`validate(applied, applied)` 无违规 |
| 编号碰撞 | 框架升级分支带入 `V83__framework_hotfix.sql`（与已执行 V83 同号不同内容） | `DUPLICATE_VERSION`，阻断信息点名两个文件与版本号 |
| 重写旧 SQL | 内存中修改已执行 `V1__baseline.sql` 的内容 | `CHECKSUM_MISMATCH`（唯一违规），信息含文件名与"不允许改写" |
| 上游分支低位号 | 追加 `V79.1__upstream_hotfix.sql` | `OUT_OF_ORDER`（低于已执行水位 83） |
| 删除已执行迁移 | 候选里移除首个已执行迁移 | `MISSING_APPLIED` |
| 追加新迁移 | 追加水位之后的 `V1001__ai_compat_probe.sql` | 无违规；版本大于水位；已执行历史（版本+内容摘要）逐项不变 |

**先红后绿**：在演练副本里临时关闭 `DUPLICATE_VERSION` 规则（`if (false && …)`）：

```bash
./mvnw -o -pl basic-framework-module-ai test -Dtest='MigrationCollisionUpgradeDrillTest' -Djacoco.skip=true
```

退出码 **1**：

```text
frameworkUpgradeWithCollidingNewMigrationIsBlocked:113 [同号不同文件的迁移必须被阻断，而不是靠文件名顺序碰运气]
Expecting any element of: ... to satisfy the given assertions
Tests run: 6, Failures: 1, Errors: 0, Skipped: 0
```

恢复规则后退出码 **0**（6 例通过）。

生产侧同一语义的阻断点（只读引用，未改动）：`basic-framework-server/src/main/resources/application.yaml`
的 `spring.flyway.validate-on-migrate: true`、`clean-disabled: true`、`baseline-on-migrate: false` ——
已执行迁移的校验和不一致、或新增迁移乱序时，Flyway 在启动阶段失败而不是静默继续。

### 2.4 阻断演示①：删除必需字段能被检出

**结论：通过**（既有机制已覆盖：冻结 Schema 的 `required` + 生产校验器 `AiResultBlockDTO.validate()`/
`AiRunEventDTO.validate()`/`AiChartSpecDTO.validate()`/`AiThemeValidator.validateTokens()`）。

用例先读 `docs/contracts/ai/*.schema.json` 的 `required` 声明，再从
`docs/contracts/ai/samples/*.valid.json` 里逐个删除该字段，断言被拒绝且信息指认字段语义：

| 契约 | 删除的必需字段 | 拒绝结果 |
|---|---|---|
| run-event | schemaVersion / seq / runId / status / createdAt | `未知协议版本` / `事件序号` / `运行业务键` / `未知运行状态` / `创建时间` |
| theme-tokens | primaryColor / radius / fontFamily | `ServiceException`，错误码 `AI_THEME_TOKENS_INVALID` |
| chart-spec | type / categories / series | `未知图表类型` / `图表类目不能为空` / `图表系列不能为空` |
| result-block（判别联合） | kind / chart 分支的 spec / text 分支的 text | `结果块缺少 kind` / `chart 块必须且只能包含 spec 字段` / `text 块必须且只能包含 text 字段` |

**先红后绿**：在演练副本里临时移除生产校验器调用（只改测试内的调用行，`validate()` 不再执行）：

```bash
./mvnw -o -pl basic-framework-module-ai test -Dtest='ContractRequiredFieldBlockingTest' -Djacoco.skip=true
```

退出码 **1**，5 个参数化用例全部变红：

```text
droppingRunEventRequiredFieldIsRejected(String,String)[1..5]
Expecting code to raise a throwable.
Tests run: 14, Failures: 5, Errors: 0, Skipped: 0
```

恢复后退出码 **0**（14 例通过）。红确实来自生产校验器，不是反序列化副作用。

### 2.5 阻断演示②：新增危险依赖能被检出

**结论：通过（测试内策略判定）**。判定对象是依赖台账（冻结坐标+版本），策略见
`UpstreamDependencyPolicy`，台账来源为 F02 的 `docs/integrations/upstream-registry.yaml`
与 `ai-platform-upstream-candidates.md` §10.3 的真实依赖树，冻结值落在
`basic-framework-dependencies/pom.xml`。

| 违规场景（候选） | 检出规则 |
|---|---|
| `spring-ai-model:2.0.1` + `spring-boot:4.1.1`（F02 判 no-go：2.x 需 Boot 4.1.1） | `FORBIDDEN_COMPONENT` + `PLATFORM_LINE_MISMATCH` + `UNAPPROVED_VERSION_CHANGE` + `VERSION_SPLIT` |
| `com.alibaba.cloud.ai:spring-ai-alibaba-graph-core:1.1.2.2`（not-adopted） | `FORBIDDEN_COMPONENT` |
| `io.netty:netty-common` 4.2.17.Final → 4.2.16.Final（第三方 BOM 静默降级） | `UNAPPROVED_VERSION_CHANGE`，信息含新旧版本 |
| `jackson-databind` 2.21.4 与 2.20.0 同时在树（版本分裂） | `VERSION_SPLIT` |
| 冻结台账本身 | 无违规（绿侧） |

另外 `realBomAndUpstreamRegistryAgreeWithFrozenLedger` 直接对表**真实文件**：
BOM 属性 `spring-ai.version`/`qdrant-client.version`/`tika-core.version`/`pdfbox.version`/`poi.version`/
`spring.boot.version`/`netty.version`，以及上游台账 `spring-ai`/`qdrant-java-client`/`tika-core` 的
`selected.version` —— 任一侧未登记就改版本会让用例变红。

**先红后绿**：在演练副本里临时关闭 `FORBIDDEN_COMPONENT` 与 `UNAPPROVED_VERSION_CHANGE` 两条规则：

```bash
./mvnw -o -pl basic-framework-module-ai test -Dtest='UpstreamDependencyBlockingTest' -Djacoco.skip=true
```

退出码 **1**，3 个违规场景变红：

```text
springAiTwoLineCandidateIsBlocked:51 [违规必须被检出：FORBIDDEN_COMPONENT]
notAdoptedAlibabaCandidateIsBlocked:63 [违规必须被检出：FORBIDDEN_COMPONENT]
silentNettyDowngradeIsBlocked:76 [违规必须被检出：UNAPPROVED_VERSION_CHANGE]
Tests run: 6, Failures: 3, Errors: 0, Skipped: 0
```

恢复规则后退出码 **0**（6 例通过）。

**证据边界**：这是台账/版本策略判定，**不是** CVE 漏洞扫描；`dependencies` 门禁（Trivy HIGH/CRITICAL）
在本机仍不可用（F02 已记录的环境缺口），见 §6。

### 2.6 AT-049 旧 ReportSpec 加载（基线夹具）

**结论：通过（基线模式）**。`ReportSpecBaselineCompatibilityTest`（3 例，退出码 0）：

- 冻结样例 `docs/contracts/ai/samples/report-spec.valid.json` 被 `AiReportSpec.parse` 加载成功
  （title、blocks、datasetRefs、queryRefs 非空）；权威 Schema 的 `schemaVersion.const` 与
  `AiReportSpec.SCHEMA_VERSION` 一致（`1.0`）；
- `schemaVersion` 改成 `2.0` / `0.9` 或删除 → `AI_REPORT_SPEC_INVALID`，**明确拒绝**而不是静默按 v1 解析；
- 首发没有 N-1 历史 ReportSpec，因此没有迁移器可演练；首次结构升级时需补显式迁移器与回退影响。

## 3. 变更文件清单

新增（全部在 Q08 允许路径内，无生产代码/迁移/SQL 改动）：

```text
后端代码/basic-framework-boot/basic-framework-module-ai/src/test/java/com/basicframework/module/ai/compatibility/
├── CompatibilityRepositorySupport.java
├── FrozenUpstreamWireFixtures.java
├── ProtocolBaselineGolden.java
├── ModelProtocolUpgradeRegressionTest.java
├── MigrationHistoryValidator.java
├── MigrationCollisionUpgradeDrillTest.java
├── ContractRequiredFieldBlockingTest.java
├── UpstreamDependencyPolicy.java
├── UpstreamDependencyBlockingTest.java
└── ReportSpecBaselineCompatibilityTest.java
docs/upgrades/q08-compatibility-baseline-and-upgrade-drill-evidence.md（本文件）
```

未改动：`src/main/**`、`db/migration/**`、`数据库文件/**`、`docs/contracts/**`、`.harness/**`、
`.github/**`、`scripts/**`、依赖版本与父 POM。未新增 `scripts/check-*.mjs`。

## 4. 兼容产物清单（本次冻结/引用）

| 类别 | 产物 | 位置/取值 |
|---|---|---|
| 请求夹具（实测记录） | OpenAI 兼容文本/嵌入请求体 | F02 `.local-state/f02-upstream/mock-requests.jsonl`（逐字复制进 `FrozenUpstreamWireFixtures`） |
| 响应夹具（基线） | 文本响应、流式 5 分片、结构化输出 | `FrozenUpstreamWireFixtures`（首次升级时用旧产物替换/补充） |
| 行为黄金值 | 文本/流式/结构化快照 | `ProtocolBaselineGolden` |
| 冻结依赖台账 | spring-ai 1.1.8、qdrant-client 1.13.0、tika-core 3.2.3、pdfbox 3.0.5、poi-ooxml 5.4.1、Boot 3.5.16、netty 4.2.17.Final | `UpstreamDependencyPolicy.FROZEN_VERSIONS` ↔ `basic-framework-dependencies/pom.xml` ↔ `docs/integrations/upstream-registry.yaml`（用例对表） |
| 已执行迁移历史快照 | 82 个迁移文件的版本+摘要（最高 V83，SHA-256 见 §2.3） | 真实 `db/migration` 目录（用例实时读取，不落盘副本） |
| 契约夹具 | run-event/theme-tokens/chart-spec/result-block/report-spec 样例与 Schema | `docs/contracts/ai/**`（只读） |

## 5. 升级演练与回退条件

本次演练是**可执行演练**，不是真实版本升级（无出网、BOM 冻结；真实升级需主管决策，见 §6）：

1. **依赖升级流程**：改 BOM 版本 → 跑本回归包（`-Dtest='com.basicframework.module.ai.compatibility.*Test'`）
   → 跑契约/后端/集成门禁 → 对比黄金值差异；任何未解释差异按失败处理。
2. **回退条件与材料**：
   - 依赖回退：把 BOM 版本改回冻结值并重跑同一回归包；旧版本包在 Maven Central 永久可用
     （F02 台账 `rollback` 已记录），模型协议升级不改平台存储结构 → **无数据迁移**；
   - 迁移回退：**已执行迁移不可回退**（应用二进制回退不能假装回滚 Flyway）；错误迁移只能
     **新增修正迁移**，禁止重写历史 SQL（本演练的阻断点即为此）；
   - 回退窗口内已产生的对话/索引数据不回滚，按业务补偿/核对处理（06 §8）。
3. **升级触发条件**（沿用 F02 台账）：1.1 线新补丁、影响 1.1.8 的安全公告、评估 2.x（须伴随
   Boot 4 平台升级决策）、dependencies 门禁 HIGH/CRITICAL 命中。

## 6. 未验证项与原因

1. **真实上游版本升级未执行**：本机无出网、依赖 BOM 冻结；改版本属主管决策（§7）。因此 AT-065 的
   "升级前后对比"以基线夹具模式报告，不是已发生的升级。
2. **AT-057（真实 N-1 SDK 连接新后端）未验证**：首发没有已发布的旧 SDK 产物；真实浏览器/宿主侧
   证据属前端切片（`前端代码/basic-framework-admin/tests/compatibility` 与 Q06 浏览器门禁）。
3. **AT-065 的 UI 与包体部分未验证**：固定用例、权限、UI、包体预算需要前端构建与真实浏览器证据，
   不在后端允许路径内。
4. **Trivy HIGH/CRITICAL 扫描未执行**：`dependencies` 门禁依赖的漏洞库镜像在本机不可达（F02 §10.8
   已记录）；阻断演示②是台账/版本策略判定，不能替代 CVE 扫描结论。
5. **真实 MySQL 上的 Flyway validate 演练未执行**：Flyway 只在 server 模块装配（module-ai 测试
   classpath 无 Flyway/Testcontainers），server 的 integration 目录不在本卡允许路径内；本切片以
   测试内校验器 + 生产 `validate-on-migrate: true` 配置作为语义等价证据。
6. **Qdrant/Tika/AntV 升级回归不在本切片**：Qdrant 组合验证、Tika 解析回归、AntV 图表回归分别属
   K01/K04/F04/R02 的既有证据与后续升级卡；本切片只覆盖模型侧协议。
7. **`docs/upgrades` 之外无新增台账文件**：上游注册表已由 F02 维护，本切片只做一致性对表，未改台账。

## 7. 需要主管串行的补丁/门禁事项

1. **门禁接线（可选）**：本回归包随 module-ai 的 `test` 阶段执行（`mvn test`/`clean verify` 都会跑到），
   无需新脚本即可生效；若要把"升级回归包通过"登记为显式 Harness 门禁，需要新增 `scripts/check-*.mjs`
   + 同名拒绝测试 + 双 provider 接线（规则要求，属主管串行）。
2. **真实升级决策**：首次升级（Spring AI 1.1.9+ 或评估 2.x/Boot 4）需主管批准，并同步更新
   `docs/integrations/upstream-registry.yaml` 与版本冻结值；回归包与黄金值保持不变即可复用。
3. **Trivy 依赖扫描**：请在可联网环境补跑 `dependencies` 门禁，闭合 F02 的 pending 项。
4. **真实库 Flyway 演练**：如需"真实 MySQL 上执行迁移碰撞演练"，请在 server integration 层加一个
   用例（超出本卡允许路径）；本切片的校验器与配置证据可作为其前置。
5. **K01 与 F02 关于 Qdrant Java 客户端的表述差异**（F02 §10.4 已登记）仍待复核，不在本卡范围。

## 8. 与其他 Q08 切片的对接

本切片是 Q08 的**后端测试切片**；文档/台账切片已产出
[兼容产物清单](../integrations/ai-platform-compatibility-artifact-ledger.md)、
[q08-upgrade-rehearsal-and-rollback.md](q08-upgrade-rehearsal-and-rollback.md)、
[q08-acceptance-evidence.md](q08-acceptance-evidence.md)（本切片未改动这些文件，避免并行覆盖）。

本切片落地后，上述文档中以下"待补/未执行"项已具备可执行证据，建议由主管在验收文档中改为引用本文件：

| 原待补项 | 本切片证据 |
|---|---|
| 后端兼容回归包（`module-ai/.../compatibility`，清单 §4.1） | 已创建 10 个文件 / 38 例（§2.1） |
| AT-049 旧版本加载的端到端回归（验收文档 §2 待补） | `ReportSpecBaselineCompatibilityTest`（基线模式，3 例，§2.6） |
| AT-065 后端回归入口（验收文档 §2 待补） | `ModelProtocolUpgradeRegressionTest`（9 例，§2.2；候选升级仍无对象，口径不变） |
| AT-072 迁移碰撞注入演示"未执行"（验收文档 §2/§3） | `MigrationCollisionUpgradeDrillTest`（6 例，不需要 MySQL：测试内校验器 + 真实迁移历史，§2.3） |
| "删必需字段能被阻断"注入演示未执行（验收文档 §2.5） | `ContractRequiredFieldBlockingTest`（14 例，含先红后绿，§2.4） |
| "新增危险依赖能被阻断"未验证（验收文档 §2.5） | `UpstreamDependencyBlockingTest`（6 例，台账策略判定；Trivy CVE 扫描仍不可用，口径不变，§2.5） |

口径提醒：本切片没有把任何结论写成"真实旧产物验证"；AT-057 的后端侧不存在可执行对象（旧 SDK 属前端产物），
仍按验收文档的口径保持"未验证/基线夹具"。

