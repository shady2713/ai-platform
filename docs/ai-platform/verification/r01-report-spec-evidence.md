# R01 ReportSpec 服务端校验与数据绑定证据（2026-09-23）

本记录是 [R01 实现 ReportSpec 服务端校验与数据绑定](../tasks/R01.md) 的验收证据。
依赖 [F07](../tasks/F07.md)（协议冻结，`report-spec.schema.json` 为权威契约）、[D11](../tasks/D11.md)（黄金集与结果契约）
均已有证据文档。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| ReportSpec 服务端模型与**结构**校验 | `domain/report/AiReportSpec.java`：键白名单、取值域、上限、脚本片段标记、四类块（metric/text/table/chart） |
| 语义校验器 | `service/report/validation/AiReportSpecValidator.java`：引用唯一且存在、布局不越界/不重叠/块全部摆放、图表与指标字段类型、行号有界 |
| 数据绑定器 | `service/report/validation/AiReportDataBinder.java`：绑定真实执行结果（列/行数/完整性核对）、数字取结果值、文本块标注未核验、投影只保留声明列 |
| 错误码 | `1_003_007_000`–`005`（报表子区间，F08 预留），与 `docs/contracts/ai/error-code-map.md` 两侧同步 |
| 测试（恶意/边界夹具） | `AiReportSpecValidatorTest` 8 例 |

对外方法：`AiReportSpec.parse(String)`、`AiReportSpecValidator.validate(AiReportSpec)`、
`AiReportDataBinder.bind(AiReportSpec, Map<String, ExecutionResult>)`。

本卡不新增表、不新增端点（校验器是纯函数，由报表链路 R02+ 调用），因此四处台账无需改动。

## 2. 与卡片逐步实施的对应

1. **校验布局/块类型/字段/图表类型/版本上限**：结构校验（键白名单、12 列栅格、gap 白名单、
   块/布局/引用/来源/文本长度上限、四类块各自的键集合、chartType 白名单、schemaVersion=1.0）；
   语义校验补齐布局与引用的合法性。
2. **绑定真实 run 结果和 queryRefs，禁止模型自造数据来源**：数据集引用必须指向已声明的查询
   （`queryRef`），绑定阶段按 `datasetRef.id` 取**真实执行结果**，缺结果即拒绝；
   声明与结果逐项核对（列存在、行数相等、完整性相等）。
3. **金额和统计口径留在计算结果中**：指标绑定必须是数字列且行号在结果行数之内；
   取值统一转十进制（不经过二进制浮点），本绑定不做任何计算。
4. **引用唯一且存在、布局不重叠越界、图表字段类型与结果 Schema 一致**：见第 3 节。
5. **结果数据与 Spec 分开绑定；文本不伪装经过确定性核验**：`BoundReport` 把规格与绑定结果分开返回，
   每个块带 `verified` 标记（数字块 true + 来源，文本块 false + 说明文案）。

## 3. 关键约束与安全语义

- **HTML/JS/CSS 一律拒绝**（AT-043）：`<script>`、`</script>`、`javascript:`、`<style>`、`<iframe>`、
  `on*=事件属性` 在解析阶段即拒绝，**不做"清理后使用"**；未知键（`style`/`onClick`/`href` 等）同样拒绝。
- **未知 dataset 拒绝**：模型自造的数据集标识、悬空的 `queryRef`、不存在的块引用都报
  `AI_REPORT_REFERENCE_INVALID`（不是"忽略并继续"）。
- **布局必须完整**：每个块恰好摆放一次；列+跨度 ≤ 12；同一行内列区间不得相交；
  未摆放的块（界面不可见）与重复摆放都拒绝。
- **缺字段图表不可生成**：图表类目/数值/系列字段必须在被引用数据集的结果列中存在且类型匹配
  （类目为文本/日期/布尔，数值为整数/小数，系列为文本）。
- **声明不得失真**：绑定阶段核对行数与完整性——把 `PARTIAL` 写成 `COMPLETE` 报
  `AI_REPORT_BINDING_MISMATCH`（报表不能"看起来是完整统计"）。
- **投影最小化**：表格与图表只带声明过的列，上游多余列（如内部毛利）不进入报表数据。

## 4. 验收用例对照

| 验收项 | 结论 | 证据 |
|---|---|---|
| AT-043（非法结构/HTML 拒绝，无脚本执行） | HTML/脚本/样式/事件属性与未知键全部拒绝 | `scriptStyleAndHtmlFragmentsAreRejected`、`unknownKeysAndStructureViolationsAreRejected` |
| 未知 dataset 拒绝 | 自造数据集/悬空查询引用/幽灵块引用均拒绝 | `unknownOrDuplicateReferencesAreRejected` |
| 缺字段图表不可生成 | 字段不存在或类型不符即拒绝 | `chartAndMetricFieldsMustExistAndMatchResultSchema` |
| 布局不能重叠越界 | 越界、重叠、未摆放、重复摆放四种情形 | `layoutOverlapOverflowAndUnplacedBlocksAreRejected` |
| 数字来源为执行结果 | 指标取结果值、行数/完整性核对、文本块标注未核验 | `validSpecPassesBothValidationAndBinding`、`bindingRejectsDeclarationsThatDoNotMatchTheRealResult` |
| 结果与 Spec 分开绑定 | `BoundReport` 分离 + 投影只保留声明列 | `boundTablesAndChartsOnlyCarryDeclaredFields` |

## 5. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。
命令前统一 `umask 022; export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -q -o -pl basic-framework-module-ai spotless:apply` | 0 | 格式已应用 |
| `./mvnw -o -pl basic-framework-module-ai test -Dtest=AiReportSpecValidatorTest` | 0 | **8 例通过**（含恶意/边界夹具） |

## 6. 顺带修复的依赖缺口（真实暴露）

1. **测试夹具的转义**：恶意样本里的 `"` 在生成时被双重转义，导致 spotless 解析失败；
   改为标准 Java 转义后通过。
2. **快照菜单块的重复 `VALUES`（跨卡遗留）**：V75 的菜单 INSERT 头以 `VALUES` 结尾、
   值行又以 `VALUES (` 开头，拼起来是 `VALUES VALUES`，`AuthenticationMigrationIT` 加载快照时报语法错误。
   该形态由 K09 的快照生成脚本引入（当时只修了同一行内的重复，跨行形态仍在）；本次统一按
   "`VALUES` 换行后直接接元组"修正并重跑该用例通过。
3. **"块未摆放"用例的字符串替换不可靠**：原先靠 `replace` 从大夹具里删一段布局项，
   格式微调后就不再匹配（用例静默变成"合法规格"而失败）。改为在用例内显式构造
   "块比布局项多"的最小规格，避免依赖大字符串的精确匹配。

## 7. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、字段目录、权限目录、生命周期、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单测（模块含本卡 8 例）、格式、架构与覆盖率检查通过 |
| `./mvnw -q -Pintegration clean verify`（全量 IT） | 1 | **204 例中 1 例既有负载敏感用例失败**（`UserProfilePersistenceIT`，多份证据已记录）；另 1 例 `AuthenticationMigrationIT` 因**快照重复 VALUES**（第 6 节 #3）失败，修复后单跑 4/4 通过 |
| `./mvnw -o -Pintegration -pl basic-framework-server verify -Dit.test=AuthenticationMigrationIT -Dtest=AuthenticationMigrationIT -DfailIfNoTests=false -Djacoco.skip=true` | 0 | 快照修复后 **4 例通过** |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 登记本卡新增文件；无下调、无删除 |

## 8. 覆盖率

本卡新增主源码 3 个文件（模型、校验器、绑定器）在完整覆盖率数据下由
`node scripts/check-coverage-ratchet.mjs --update` 登记单文件基线；只新增条目或上调既有值。

## 9. 未验证项

1. **与运行链路的接线**：校验器/绑定器是纯函数，本卡未接入 R02+ 的报表生成与渲染链路
   （接线由后续卡片完成；本卡只保证校验与绑定语义）。
2. **主题（themeRef）与渲染**：主题标识与修订号只做形状校验，主题内容（颜色/字体白名单）由 F07 的
   `theme-tokens` 契约与前端渲染负责（未验证）。
3. **浏览器渲染与无脚本执行**：AT-043 的"无脚本执行"在服务端已由拒绝策略保证；
   真实浏览器里"渲染报表不执行脚本"的验证属 Q06/G5（未验证）。
4. **queryRefs 里查询计划的深度校验**：本卡只要求计划是 JSON 对象并保留原文；
   计划本身的语义校验由 D05 的校验器在报表链路里完成（未在本卡重复）。
