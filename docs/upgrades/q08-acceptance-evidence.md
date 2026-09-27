# Q08 验收证据：兼容性基线与升级演练（文档/台账切片）

| 项目 | 内容 |
|---|---|
| 任务卡 | [Q08 建立升级台账与兼容回归包](../ai-platform/tasks/Q08.md) |
| 本文件状态 | **文档/台账切片完成，验收结论待随测试切片与门禁执行补齐**（本切片不写代码、不跑 Maven/vitest） |
| 工作副本 | `/home/ctyun/桌面/zhongtai/ai-platform` |
| 切片边界 | 只改 `docs/integrations`、`docs/upgrades`。后端测试切片（`basic-framework-module-ai/src/test/.../compatibility`）与前端测试切片（`basic-framework-admin/tests/compatibility`）的产出由本文件 §5 对接 |
| 配套 | 演练方案与实测记录：[q08-upgrade-rehearsal-and-rollback.md](q08-upgrade-rehearsal-and-rollback.md)；兼容产物清单：[ai-platform-compatibility-artifact-ledger.md](../integrations/ai-platform-compatibility-artifact-ledger.md) |

## 1. 结论口径（强制）

FR-34（[02-product-requirements.md](../ai-platform/02-product-requirements.md) 第 288 行）："首次发布没有真实N-1时冻结首发候选产物作为未来兼容夹具，
明确报告'首发基线验证'，不声称已验证历史发布版本。"

因此本卡全部兼容结论按以下口径出：

- **首发阶段只能给出「基线兼容夹具验证」**：冻结首发候选的协议行为/坐标并每次门禁重放；
- 仓库当前**不存在任何已发布的旧版本产物**（SDK 只构建过 5.6.0；本地 Maven 仓库只有 spring-ai 1.1.8；
  没有更旧的 Qdrant 镜像），所以**没有任何一条结论可以写成「真实旧产物验证」**；
- 首次真实升级（上游发布下一版本）之后，本文件才能出现第二种口径；触发条件见
  [upstream-registry.yaml](../integrations/upstream-registry.yaml) 的 `upgradeTriggers` 与 `revalidationTriggers`。

## 2. 逐条验收结论（AT-049 / AT-057 / AT-065 / AT-072）

> 表中前端命令工作目录 `前端代码/basic-framework-admin`，后端命令工作目录 `后端代码/basic-framework-boot`；完整环境见 §4。

| AT | 预期结果 | 本切片结论 | 由谁承载（测试/文档） | 命令（工作目录见 §4） |
|---|---|---|---|---|
| **AT-049** 旧 ReportSpec 加载 | N-1 正确迁移**或明确不支持** | **通过（限定口径：明确不支持，无原地迁移）**。服务端要求 `schemaVersion == "1.0"`，其余版本以 `AI_REPORT_SPEC_INVALID` 拒绝；前端 `safeParseReportSpec` 对非 1.0 返回 `null` 并由页面给出提示；**不存在把旧 spec 原地升级为新版本的迁移器**，这一点必须在发布说明里写明 | 后端：`AiReportSpec.SCHEMA_VERSION`（`domain/report/AiReportSpec.java` 第 34 行）与拒绝分支（第 164–166 行）；`AiReportSpecValidatorTest.unknownKeysAndStructureViolationsAreRejected`（含 `schemaVersion:"2.0"` 拒绝用例）；`AiReportVersionDO.specJson`（"已校验"）、`AiReportDO.schemaVersion`（"旧版本加载时判定兼容性，AT-049"）。前端：`packages/ai-chat-ui/src/report/reportSpec.ts` 第 337 行；`AiReportView.vue`；R07 证据 §AT-049（组件级）。**待补**：旧版本产物的端到端加载回归（`module-ai/.../compatibility`，§5.1） | `./mvnw -o -pl basic-framework-module-ai test -Dtest=AiReportSpecValidatorTest`；`pnpm exec vitest run --dom packages/ai-chat-ui/src/report` |
| **AT-057** 真实 N-1 SDK 连接新后端 | 主流程正确，无静默协议错误 | **首发基线兼容夹具验证：通过；真实 N-1 双产物联调：未验证**（无历史产物：C10 只构建过 `ai-embed-sdk-5.6.0.js`；Q06 已登记为环境未验证项）。夹具每次门禁重放：13 种消息类型白名单不缩水、协议主版本 1.0 一致、完整握手序列可解析并走到 `INITIALIZED` | `packages/ai-embed-sdk/src/__tests__/n-1-baseline.test.ts`（3 例）+ `packages/ai-embed-sdk/fixtures/n-1/baseline.json`；C10 证据 §2/§8 第 3 条；`docs/integrations/ai-host-integration.md` 第 69 行；Q06 证据 §4/§5 第 1 条（未验证原因）。**待补**：前端兼容包（`tests/compatibility`，§5.2）与产物哈希绑定（清单 §3.5） | `pnpm exec vitest run --dom packages/ai-embed-sdk/src/__tests__` |
| **AT-065** Spring AI/AntV 候选升级 | 固定用例、权限、UI 和包体无未解释回退 | **未验证（无候选可升）**。2026-09-27 探测：`@antv/g2` 最新即 5.4.8、spring-ai 1.1 线止于 1.1.8（演练文档 §1），因此"候选升级回归"没有可执行对象。可复用的回归入口与基线数字**已建立**：后端 starter-ai 用例集 + `dependency:tree` 单版本收敛；前端 `chart`/`report` 用例 + `verify-built-chat.mjs` + `check-public-cdn.mjs`，体积基线 `vendor-antv` = 1,309,384 B（2026-09-27 复核与 F04 一致）。上游一发补丁即按演练文档 §3/§4 执行 | F02 证据 §10.3/§10.6（后端坐标/组装）；F04 证据 §3.1/§3.2/§4.2（体积、CDN、真实渲染）；清单表 §2.1/§2.2。**待补**：两侧兼容包（§5） | 后端：`./mvnw -o -pl basic-framework-core/basic-framework-spring-boot-starter-ai test`；`./mvnw -o -pl basic-framework-server dependency:tree -Dverbose`；前端：`node apps/ai-chat/scripts/verify-built-chat.mjs`、`node apps/ai-chat/scripts/check-public-cdn.mjs` |
| **AT-072** 基础框架升级迁移碰撞 | 保留已执行历史，测试阻断错误覆盖 | **部分通过**：静态检测已执行——82 个迁移文件、编号唯一（`uniq -d` 为空）、最大 V83、快照声明 `through V83` 一致；阻断机制存在——Flyway `validate-on-migrate: true`/`clean-disabled: true`/`baseline-on-migrate: false`（`application.yaml` 第 26–32 行）会让"改写已执行迁移"与"编号重复"在启动期失败，`PersistenceLifecycleIT` 断言 history 条数=文件数、末版本=最大文件版本（`MigrationTestSupport` 从 classpath 推导）。**未执行**：注入演示（改写历史迁移/编号碰撞/快照漂移 → 期望变红）需要 MySQL 与 `-Pintegration`，步骤见演练文档 §5.4 | 规则：F08 证据 §2 第 3 条 + `docs/data-lifecycle.md`"AI 中台表策略" + 06 §6 第 4 条 + Q08 卡 §3 第 5 条；检测：`scripts/check-data-lifecycle.mjs` 第 120–134 行、`PersistenceLifecycleIT`、`MigrationTestSupport`；先例：F08 证据 §3（临时迁移未同步快照被 3 项 FAIL 拒绝） | 已执行（只读）：`ls .../db/migration/*.sql \| sed 's/.*\/V\([0-9]*\)__.*/\1/' \| sort -n \| uniq -d`；待执行：`node scripts/check-data-lifecycle.mjs`、`./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test=PersistenceLifecycleIT` |

### 2.5 卡片 §4 的其余验收句（非 AT 编号）

| 验收句 | 结论 | 依据 |
|---|---|---|
| 错误迁移覆盖能被阻断 | 机制在（同 AT-072）；**演示未执行** | 演练文档 §5.4 的注入步骤 1–4 |
| 删必需字段能被阻断 | 部分：契约夹具（`AiProtocolFixtureTest` / `protocol-fixtures.test.ts` 拒绝缺字段样例）与字段目录门禁（`node scripts/check-field-catalog.mjs`，F08 §3 记录过字段漂移拒绝演示）在防；**"已发布字段被删"的注入演示未执行** | F07 证据 §2；F08 证据 §3 |
| 新增危险依赖能被阻断 | **未验证**：`dependencies` 门禁（Trivy HIGH/CRITICAL）在本机因漏洞库镜像不可达无法运行（F02 §7 第 2 条，环境缺口） | F02 证据 §7、`openItems.trivy-scan` |

## 3. 未验证项总表（如实列出，均不得当 pass）

| # | 未验证项 | 原因 | 补齐条件 |
|---|---|---|---|
| 1 | 真实 N-1 产物联调（AT-057 后半） | 首发无历史产物 | C10 之后首个 SDK/协议版本发布后 |
| 2 | 候选升级回归（AT-065） | 上游当前无更高补丁（探测见演练 §1） | 上游发布新补丁 |
| 3 | 迁移碰撞注入演示（AT-072 后半） | 需 MySQL/`-Pintegration` 与一次性探针；本切片不跑构建 | 有 Docker 环境时按演练 §5.4 |
| 4 | 两侧兼容回归包 | 由另两个切片产出，截至 2026-09-27 目标目录尚未创建 | 见 §5 |
| 5 | SDK 产物哈希与基线绑定 | `n-1-baseline.test.ts` 只做格式断言；`dist/` 被 gitignore | 前端切片补门禁或重新冻结（清单 §3.5） |
| 6 | `dependencies` 门禁（Trivy） | 漏洞库镜像不可达 | 有漏洞库的环境 |
| 7 | 完整 Harness / CI 实跑 | 本切片纪律：不跑 Maven/vitest、不改 `.harness/**` | 主管在 CI 跑 `.harness/verify.sh contracts/backend/frontend/integration/dependencies` |
| 8 | 真实模型端点、流式、工具、结构化输出回归 | M03/O04 范围，需真实凭据 | 预发布环境 |

## 4. 命令、工作目录与环境

| 用途 | 工作目录 | 前置 |
|---|---|---|
| 后端用例 | `后端代码/basic-framework-boot` | `export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`；离线加 `-o` |
| 前端用例/脚本 | `前端代码/basic-framework-admin` | 已 `pnpm install`（lockfile 门禁口径 `--frozen-lockfile --ignore-scripts`） |
| 契约/生命周期脚本 | 仓库根 | Node 可用（脚本自带退出码） |
| 完整门禁 | 仓库根 | `sh .harness/verify.sh <contracts\|backend\|frontend\|integration\|dependencies\|lockfile\|smoke>` |

本切片**实际执行**的命令只有演练文档 §2.1 的只读检查（`ls`/`sha1sum`/`sha256sum`/`docker images`/`grep`/编号唯一性扫描）
与演练文档 §1 的两次上游元数据探测；其余命令均标注为"待执行/未执行"。

## 5. 与另两个切片的接口（补齐后回填本文件）

| # | 待补产出 | 预期路径（Q08 卡 §2 授权范围） | 回填位置 |
|---|---|---|---|
| 5.1 | 后端兼容回归包：旧 ReportSpec 加载、上游坐标/tree 快照、迁移碰撞拒绝 | `后端代码/basic-framework-boot/basic-framework-module-ai/src/test/java/com/basicframework/module/ai/compatibility/`（2026-09-27 尚未创建） | §2 表 AT-049/065/072 的"承载"列 + 台账号 |
| 5.2 | 前端兼容回归包：N-1 基线重放、图表升级回归、产物哈希绑定 | `前端代码/basic-framework-admin/tests/compatibility/`（2026-09-27 已开始落地：`support/workspace.ts` 为只读工具；用例文件待补） | §2 表 AT-057/065 的"承载"列 + 台账号 |
| 5.3 | 一次真实置换演练（上游发布补丁后） | 不改代码路径，按演练文档 §3/§4 | 本文件新增"真实旧产物验证"行；清单验证类别升级 |

> 建议主管串行处理：① 把 §2 的 AT 结论在测试切片落地后并入 `docs/ai-platform/08-testing-acceptance.md` 的既有落点；
> ② 更正 `upstream-registry.yaml` 的 `spring-ai.rollback.materials`（本地无 1.1.7/1.1.6，清单 §3.1 有实测）；
> ③ 决定 SDK 产物哈希门禁归属（前端切片或重新冻结基线）。
