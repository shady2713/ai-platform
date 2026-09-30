# V2 跨系统链验收报告（Y06 收口）

| 项目 | 内容 |
|---|---|
| 任务卡 | [Y06](../ai-platform/tasks/Y06.md)（V2 跨系统链收口：验收、兼容与升级手册） |
| 验收范围 | AT-049 / AT-057 / AT-063 / AT-070 / AT-071 / AT-072 + 两条专项（黄金指标一致、单系统无回退） |
| 链上四环 | Y02 主数据映射 → Y03 跨源口径 → Y04 有界跨源执行 → Y05 跨源授权 |
| 本卡边界 | **不写生产代码**。§2 允许路径只有 `docs/acceptance`、`docs/upgrades` 与两个 `compatibility` 测试目录；本次交付**未改动任何 service / controller / 前端页面** |
| 证据 | 每条结论的命令、退出码与测试计数见 [y06-cross-source-acceptance-evidence.md](../ai-platform/verification/y06-cross-source-acceptance-evidence.md) |
| 手册 | [cross-source-deployment-and-upgrade-runbook.md](../upgrades/cross-source-deployment-and-upgrade-runbook.md) |
| 结论摘要 | 6 条 AT **全部通过**（其中 3 条为沿用既有资产的重跑复核），两条专项**通过**；**1 项已知缺口未修**（§7），**5 类未验证项已如实登记**（§6） |

## 1. 逐条 AT 结论

### AT-049 旧 ReportSpec 加载 → N-1 正确迁移或明确不支持

| 项 | 内容 |
|---|---|
| 结论 | **通过** |
| 本卡动作 | 沿用既有资产重跑，**未新增**该 AT 的用例（Q08 已有 7 条且覆盖旧版本规格显式拒绝） |
| 命令 | `npx vitest run tests/compatibility/`（C8） |
| 输出 | `Test Files 4 passed (4)` / `Tests 26 passed (26)`，退出码 **0** |
| 判据 | 旧版本规格（`schemaVersion: '0.9'`）显式拒绝渲染、不静默按新版本出结果；错误码与提示文案未变 |

### AT-057 真实 N-1 SDK 连接新后端 → 主流程正确，无静默协议错误

| 项 | 内容 |
|---|---|
| 结论 | **通过（按"冻结候选协议重放"等价物）** |
| 本卡动作 | 沿用既有资产重跑，**未新增**该 AT 的用例 |
| 命令 | 同 C8（`at-057-baseline-fixture-compat-not-real-n1.test.ts` 6 条在内） |
| 输出 | 退出码 **0** |
| 限定 | 首发**没有**历史版本 SDK 产物，"真实旧 SDK 连新后端"在本环境**无法执行**。等价物=冻结首发候选协议行为、每次升级重放并证明新实现仍接受旧消息。此限定**沿用既有结论**，不是本卡新增的降级 |
| 浏览器项 | 真实 Chromium 已跑通（见 AT-063 行的浏览器列） |

### AT-063 空库与旧版本库迁移 → 可启动且 Schema/快照一致

| 项 | 内容 |
|---|---|
| 结论 | **通过** |
| 本卡动作 | 沿用既有迁移碰撞演练 + 全量 Flyway 链重跑；V2 链的 V93/V94/V95 在真实全量迁移链上执行成功 |
| 命令 | C2/C5（`MigrationCollisionUpgradeDrillTest` 6 条在 50 条之内）+ C9（Playwright）+ C10/C11（`basic-framework-server` 启动需 Flyway 全量迁移成功） |
| 输出 | `Tests run: 50, Failures: 0` / `Tests run: 41, Failures: 0` / `3 passed`，退出码均 **0** |
| 判据 | 迁移版本唯一且严格递增；同号不同文件（编号碰撞）被阻断；重写旧 SQL（内容摘要变化）被阻断；追加新迁移放行且已执行历史只增不改 |
| 浏览器项 | **已真跑**：Chromium 1243 + Playwright 1.63.0，`3 passed (14.2s)`。未使用 `--ignore-snapshots`，未删断言 |

### AT-070 跨系统同名不同实体 → 未映射不关联，映射后统计正确

| 项 | 内容 |
|---|---|
| 结论 | **通过** |
| 本卡动作 | 沿用 Y02/Y03 资产重跑（`AiMasterMappingAcceptanceIT` 6 条 + `AiCrossSourceMetricAcceptanceIT` 12 条）；**本卡新增**单系统侧的无回退对照 |
| 命令 | C11 |
| 输出 | `AiMasterMappingAcceptanceIT` 6 条（15.18 s）、`AiCrossSourceMetricAcceptanceIT` 12 条（0.963 s），`Tests run: 41, Failures: 0`，退出码 **0** |
| 判据 | 未映射的业务对象不参与关联；映射后按映射版本统计正确；同一映射版本内多来源按主键粒度预聚合后才可求和（AT-034 扇出阻断） |

### AT-071 跨系统部分源无权/故障 → 不泄漏，结果完整性明确

| 项 | 内容 |
|---|---|
| 结论 | **通过（服务端不泄漏成立）；UI 侧完整性提示有已知缺口，见 §7** |
| 本卡动作 | 沿用 Y05 资产重跑（`AiCrossSourceAuthorizationAcceptanceIT` 12 条）；**本卡新增**前端反向用例证明"字段缺失不会误清空旧结果" |
| 命令 | C11 + C8 + C9 |
| 输出 | 12 条（68.48 s）全过；前端 7 条 + 浏览器 2 条全过，退出码均 **0** |
| 判据 | 部分源无权时**整份结果不出具**（不泄漏条数、不泄漏来源名）；部分源故障时完整性状态明确。真实链路上**不泄漏由服务端错误码保证**（前端拿不到结果块）；前端 `WITHHELD` 分支经单测 + 真实浏览器验证可用，但**后端目前不产出该字段**（§7） |

### AT-072 基础框架升级迁移碰撞 → 保留已执行历史，测试阻断错误覆盖

| 项 | 内容 |
|---|---|
| 结论 | **通过** |
| 本卡动作 | 沿用 `MigrationCollisionUpgradeDrillTest`（6 条）；并为 V2 链的实际迁移序列 V92→V95 编写部署/升级手册 |
| 命令 | C2/C5 |
| 输出 | `MigrationCollisionUpgradeDrillTest` `Tests run: 6, Failures: 0`，退出码 **0** |
| 判据 | 演练读取**真实仓库**迁移目录作为"已执行历史"快照（版本号 + SHA-256 内容摘要），候选集合在内存构造，**不写任何历史 SQL**；编号碰撞与重写旧 SQL 均被阻断，追加放行 |

## 2. 专项一：黄金指标完全一致

| 项 | 内容 |
|---|---|
| 结论 | **通过** |
| 要求 | 同一组黄金输入下，V2 链产出的指标与既有单系统黄金集**逐位一致** |
| 黄金集基线 | [d11-golden-set-evidence.md](../ai-platform/verification/d11-golden-set-evidence.md) 与夹具 `AiGoldenSetFixture.EXPECTED_*`：华东 8 月净额合计 **740.00**、C002 **450.00**、C001 **290.00**、C001 回款 **190.00** |
| 证据 1（V2 上线后重跑同一黄金集，真实 MySQL） | C10：`AiGoldenSetAcceptanceIT` `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`（73.23 s），退出码 **0** |
| 证据 2（两条链逐位比对） | C4 用例 1：单系统链路产出 C001=290.00 / C002=450.00 / 合计=740.00，与跨源链同输入产出**逐位相同** |
| 断言强度说明 | 用 `BigDecimal.equals`（**含标度**）而非 `compareTo`——`450.0` 与 `450.00` 用 compareTo 会判等，那就不是"逐位一致"了 |
| 限定 | 跨源侧为**单来源口径**下的等价性证明（V2 链在多来源场景的正确性由 AT-070 的 IT 覆盖） |

## 3. 专项二：旧单系统服务行为和授权无回退（本卡核心价值）

> **只跑新功能 happy path 等于本条没做。** 以下全部是**反向**用例：断言的是"旧路径的结论没变"。

### 3.1 单系统查询结果与 V2 之前逐字段相同

| 用例 | 断言 | 载体 / 退出码 |
|---|---|---|
| 旧载荷解析后不凭空多出字段 | 旧表格块解析结果的键集合**逐个**比对，无 `crossSourceIntegrity` | C8（7 条）/ 0 |
| 每一行、每一列原样 | 金额保持**文本** `'450.00'`、`'290.00'`，不经浮点 | C8 / 0 |
| 真实浏览器里照旧渲染 | 表格可见、2 行数据、金额文本逐位、分页条数"共 2 条"、无任何跨源提示、**无 pageerror** | C9 / 0 |
| 标度不被改写 | `0.10 + 0.20` 仍是 `0.30` | C5 / 0 |
| 旧计划结构未变 | 计划哈希逐位可比（结构未变则哈希不变） | C5 / 0 |

### 3.2 单系统授权拒绝仍是原来的错误码

| 用例 | 断言 | 载体 / 退出码 |
|---|---|---|
| 越权仍是原编号 | 数据集级越权仍返回 `AI_QUERY_DATASET_NOT_ALLOWED` = **1_003_006_032** | C5 / 0 |
| 编号**未落进** V2 新子区间 | 单系统编号必须留在 `1_003_006_xxx`，且逐个排除 V2 新领的 4 个子区间（1_003_015/016/017/018） | C5 / 0 |
| 编号两两不同 | 单系统拒绝码与跨源拒绝码无交集；跨源码内部无重复 | C5 / 0 |
| 格式漂移仍是原编号 | 仍为 `AI_QUERY_RESULT_FORMAT_DRIFT`，**不是**任何跨源编号 | C5 / 0 |

### 3.3 单系统的 truncated / 缺口 / 时间点语义未被跨源口径改写

| 用例 | 断言 | 载体 / 退出码 |
|---|---|---|
| 三向齐全 | 上游确认取完且未触顶 → 仍 `COMPLETE`；触顶截断 → 仍 `PARTIAL` 且**不得**宣称完整统计；上游失败 → 仍 `FAILED` | C5 / 0 |
| 停止原因原样保留 | 截断原因 `page-limit` 不被改写 | C5 / 0 |
| 空结果 ≠ 跨源缺口 | 空结果仍 `COMPLETE`（取全了但没数据），不被改写成 `PARTIAL`/`FAILED` | C5 + C8 / 0 |
| 两种完整性互不顶替 | 只有 `completeness` 时不出现跨源图注；只有 `crossSourceIntegrity` 时不出现技术完整性图注 | C8 / 0 |

### 3.4 重放旧单系统请求不得因新增跨源判定被额外拦截

| 项 | 内容 |
|---|---|
| 行为断言 | 重放同一份合规旧请求，逐行结果、完整性状态、停止原因、Schema 码全部与首次**逐字段相同**，且仍宣称完整统计（C5，退出码 0） |
| **依赖图级**证据（更硬） | 单系统查询链路 6 个目录（`domain/query`、`service/query/api`、`service/query/compiler`、`service/query/planner`、`service/run`、`adapter`）对 V2 新类（`AiMetricSemantics` / `AiCrossSource*` / `service.query.crosssource` / `service.authorization.crosssource` / `service.queryplan`）的 import **全部零命中** |
| 为什么这条最重要 | 依赖图零引用意味着"要求补映射版本/口径版本"的跨源拦截在单系统路径上**在编译期就无处可挂**，不依赖运行期样本。这比"跑了 N 条用例都没拦"更强 |

## 4. 覆盖到 V2 四环的对照

| 环 | 资产 | 本卡复核方式 | 退出码 |
|---|---|---|---|
| Y02 主数据映射 | `AiMasterMappingAcceptanceIT` 6 条 | 重跑（C11） | 0 |
| Y03 跨源口径 | `AiCrossSourceMetricAcceptanceIT` 12 条 | 重跑（C11） | 0 |
| Y04 有界跨源执行 | `AiCrossSourceExecutionAcceptanceIT` 11 条 | 重跑（C11） | 0 |
| Y05 跨源授权 | `AiCrossSourceAuthorizationAcceptanceIT` 12 条 | 重跑（C11） | 0 |
| 单系统无回退 | 本卡新增：后端 12 条 + 前端 7 条 + 浏览器 2 条 | 新跑（C4/C5/C8/C9） | 0 |
| 黄金集 | `AiGoldenSetAcceptanceIT` 9 条 | 重跑（C10，真实 MySQL） | 0 |

## 5. 发现的问题

**生产代码缺陷：0。** 本卡未发现需要修改生产代码的缺陷。

过程中出现的 2 处失败**全部是本卡新测试自身的夹具错误**，已修正并复跑通过，详情见证据文档 §4。
其中一处反而**佐证**了单系统链路的确定性：字段越权与数据集越权走**不同**错误编号
（`1_003_006_030` vs `1_003_006_032`），这正是"编号分流稳定"的体现。

## 6. 未验证项（必读）

| 项 | 状态 | 原因 |
|---|---|---|
| 真实跨源**端到端**（两套物理隔离的真实业务系统） | **未验证** | 既有 IT 跑在单个 Testcontainer MySQL 内的三张跨源表上，不是两套真实系统；本卡无外部系统可连 |
| 生产环境只读连接器账号的权限配置 | **未验证** | 手册 §3 给的是可复跑步骤，本卡未在任何真实环境执行过 |
| 真实 N-1 SDK 产物联调 | **未验证** | 首发无历史 SDK 产物（沿用既有结论） |
| 像素级视觉回归 | **未做，且刻意不做** | 沿用 ADR 0046 决策 2：只做结构断言 |
| 门禁与覆盖率棘轮 | **未跑** | 铁律 1/2：由主会话分两段串行执行 |

## 7. 已知缺口（Y06 无法修修，需范围决策）

**后端至今零产出 `crossSourceIntegrity` 字段；前端在字段缺失时 fail-open（照常显示数字）。**

| 项 | 内容 |
|---|---|
| 事实 | Y05 的完整性 UI（`ResultTable.vue:74-78` 的 `ai-message-table-cross-source` 图注）依赖后端给出 `crossSourceIntegrity`；后端 `CrossSourceExecutionResult` **没有产出该字段**。`blocks.ts:281` 的 `if (input.crossSourceIntegrity !== undefined)` 使字段缺失时不解析、图注不出现 |
| 影响面 | `WITHHELD` 这条**保护性**分支在真实链路上目前走不到。**不泄漏仍然成立**（授权拒绝由后端直接返回错误码，前端拿不到结果块）；但"已放行时向用户声明授权范围完整"是假象——界面什么都不说 |
| 为何不在本卡修 | 修它必须动生产代码（后端结果组装 + 表格块契约），而卡片 §2 允许路径**不含**任何生产代码目录。这是**范围问题，不是难度问题** |
| 补齐所需决策 | ① 后端由谁产出该字段（建议在 `CrossSourceExecutionResult` → 表格块组装处，依据 Y05 已算出的授权判定）；② 是否改 `docs/contracts/ai` 表格块契约（新增可选字段）；③ 是否把前端改为 fail-closed——**注意这会改变单系统行为，与本卡"无回退"结论直接冲突，必须单独走一次兼容性评估** |
| 上线建议 | 补齐前按"**UI 不显示授权完整性提示**"上文档与培训口径，**不要**对外宣称"界面会提示授权范围" |

## 8. 范围声明

- 是否越出 §2 允许路径：**否**。本次改动仅落在
  `docs/acceptance/`、`docs/upgrades/`、
  `module-ai/src/test/java/com/basicframework/module/ai/compatibility/`、
  `前端代码/basic-framework-admin/tests/compatibility/`。
  其中 `fixtures/chart-probe/entry.ts` 与 `page.html` 属**既有测试夹具**（位于允许的
  `tests/compatibility/` 目录内），且为**纯追加**（新增挂载点与桥接方法，未改动 AT-065 原有逻辑），
  AT-065 复跑通过。
- 已知缺口是否已如实写入报告：**是**，见 §7，并在证据文档 §6 同步登记。
- 未执行 git 提交/推送/清理/回退；门禁与覆盖率棘轮留给主会话。
