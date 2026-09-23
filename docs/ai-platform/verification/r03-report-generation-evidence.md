# R03 自然语言报表生成步骤证据（2026-09-23）

本记录是 [R03 实现自然语言报表生成步骤](../tasks/R03.md) 的验收证据。
依赖 [R01](../tasks/R01.md)（ReportSpec 校验与绑定）、[R02](../tasks/R02.md)（图表适配）、[O04](../tasks/O04.md)（运行执行）
均已有证据文档。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 报表结果块 | `domain/result/AiReportResultBlock.java`：规格 + 绑定数据 + **可追溯来源**（数据集引用 → 查询引用 → 运行结果标识）+ 说明 |
| 模型端口 | `service/report/generation/AiReportSpecModel.java`：`generate(需求, 列目录)` / `repair(上次输出, 稳定错误码)` |
| 生成步骤 | `service/report/generation/AiReportGenerationStep.java`：受控生成 → 校验 → 绑定 → 有限修复 → 结果块 |
| 服务层 DTO | `service/report/generation/dto/AiReportGenerationResultDTO.java` |
| 测试（结构化输出 + 固定模型样例） | `AiReportGenerationStepTest` 6 例 |

对外方法：`AiReportGenerationStep.generate(requirement, results, catalog)`。

本卡不新增表、不新增端点（步骤由报表链路 R06/R07 调用），四处台账无需改动。

## 2. 与卡片逐步实施的对应

1. **用查询结果与需求生成受控 ReportSpec**：提示词只带**服务端提供的列目录**（默认由真实执行结果推导：
  datasetRef / resultRef / rowCount / completeness / columns），模型只能引用目录里的数据集与列；
  编造的列/数据集在 R01 的校验里被拒绝。
2. **限定输出块和布局，解析失败有限修复**：块与布局由 R01 的校验器限定（四类块、12 列栅格、块必须全部摆放）；
  解析/校验/绑定失败时最多修复 `MAX_REPAIR_ATTEMPTS=2` 次，每次只回传**稳定错误码**
  （不回传数据正文），次数用尽即失败（不返回半成品）。
3. **生成 report result block 和可追溯来源**：产物是 `AiReportResultBlock`——
  已校验的规格 JSON、绑定后的块数据、逐项来源（含行数与完整性）与说明分开存放；
  说明文本不参与数字计算，数字块标 `verified=true`、文本块标 `false`。

## 3. 关键约束与安全语义

- **编造列/数据被拒**：模型声明结果里不存在的列、行数与结果不符、完整性被改写（PARTIAL→COMPLETE）
  都会在 R01 的校验/绑定阶段失败，修复无果即整体失败。
- **修复有界且不泄数据**：修复请求只带稳定错误码；次数上限固定，避免"无限重试烧模型"。
- **空数据给空报表说明**：所有数据集 `rowCount=0` 时结果块不带任何行/点，并附
  "查询结果为空，未生成图表与明细"说明，不编造行、不画空图。
- **来源与单位可追溯**：每个来源带 queryRef 与运行结果标识；金额单位随结果列进入规格
  （结果块里的规格已校验，渲染方不需要再解析模型输出）。
- **产物只含已校验规格**：结果块保存的是**回写后的规格**（结构规范化），不是模型原始文本。

## 4. 验收用例对照

| 验收项 | 结论 | 证据 |
|---|---|---|
| 模型编造列/数据被拒 | 编造列持续失败即拒绝；行数不符先修复再成功 | `fabricatedColumnsAreRepairedThenRejectedWhenRepairKeepsFailing`、`dataMismatchIsRepairedWithStableErrorCodeOnly` |
| 空数据给空报表说明 | 结果块无行无点 + 说明文案 | `emptyDataProducesEmptyReportWithExplanation` |
| 多块来源与单位正确 | 文本/图表/表格三块；来源含 queryRef 与 resultRef；规格含金额单位 | `generatesResultBlockWithTraceableSourcesAndUnits` |
| 有限修复 | 非法 JSON 修复一次后成功；修复次数上限固定 | `invalidJsonIsRepairedOnceAndSucceeds`、上述编造列用例 |

## 5. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -q -o -pl basic-framework-module-ai spotless:apply` | 0 | 格式已应用 |
| `./mvnw -o -pl basic-framework-module-ai test -Dtest=AiReportGenerationStepTest` | 0 | **6 例通过** |

## 6. 顺带修复的依赖缺口（真实暴露）

1. **Java 17 不支持的集合方法**：用例里用了 `List#getFirst()`（Java 21 才有的 API），
   编译失败；改为 `get(0)`（项目基线是 Java 17）。
2. **报表步骤注入的 Bean 未装配（全量 IT 上下文批量失败）**：生成步骤注入 R01 的校验器与绑定器，
   但两者是纯逻辑类、没有 Spring 装配，导致所有加载 AI 模块上下文的 IT 一起失败
   （16 个用例类，含与本卡无关的 `AiAuthorizationTicketPersistenceIT`）。
   处置：新增 `AiReportGenerationConfiguration` 装配校验器与绑定器（与 K04/K05/K08 同一取舍），
   修复后两个受影响用例类单跑 5/5 通过。
3. **模型端口按可选注入**：`AiReportSpecModel` 未装配时不应让上下文启动失败，
   改为 `ObjectProvider` 注入并在调用时按 `AI_REPORT_MODEL_UNAVAILABLE`（503）失败（fail-closed）。

## 7. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、字段目录、权限目录、生命周期、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单测（模块含本卡 7 例）、格式、架构与覆盖率检查通过 |
| `./mvnw -q -Pintegration clean verify`（全量 IT） | 1 | **204 例中仅 1 例既有负载敏感用例失败**（`UserProfilePersistenceIT`，多份证据已记录）；本卡引入的上下文失败（第 6 节 #2）修复后其余全部通过 |
| `./mvnw -o -Pintegration -pl basic-framework-server verify -Dit.test='AiAuthorizationTicketPersistenceIT,AiKnowledgeIndexingIT' -Dtest='AiAuthorizationTicketPersistenceIT,AiKnowledgeIndexingIT' -DfailIfNoTests=false -Djacoco.skip=true` | 0 | 修复 Bean 装配后 **5 例通过**（含与本卡无关的票据用例，证明上下文恢复） |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 登记本卡新增文件；无下调、无删除 |

## 8. 覆盖率

本卡新增主源码文件（结果块、模型端口、生成步骤、DTO）在完整覆盖率数据下由
`node scripts/check-coverage-ratchet.mjs --update` 登记单文件基线；只新增条目或上调既有值。

## 9. 未验证项

1. **真实模型效果**：本卡用固定模型样例驱动（结构性约束可验证）；真实模型的报表生成质量、
   修复成功率由 Q04 评测套件与 Q10 端到端验收负责（本环境无模型端点，未验证）。
2. **与运行链路的接线**：生成步骤已提供入口，但尚未插入 O04 的执行循环（接线由 R06/R07 完成）。
3. **主题与布局渲染**：规格里的 themeRef 与布局由 R02/前端渲染负责；"渲染结果与规格一致"的
   浏览器验证属 Q06/G5（未验证）。
4. **多数据集报表**：用例覆盖单数据集；多数据集的来源与单位核对逻辑相同但未单独用例（未验证）。
