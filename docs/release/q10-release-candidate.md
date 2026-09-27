# Q10 发布候选清单（试点系统 → 候选发布评审）

| 项目 | 内容 |
|---|---|
| 任务卡 | [Q10 完成单业务系统端到端验收与候选发布评审](../ai-platform/tasks/Q10.md)，本文件是**第二片**：发布候选清单（交付物/升级回退/已知限制/部署前置/评审检查点） |
| 与另一片的分工 | AT 逐条验收结论由 Q10 并行切片交付，文件为 [q10-v1-acceptance-matrix.md](q10-v1-acceptance-matrix.md)；本文件只做**候选发布清单**，AT 结论以"引用文件名"的方式使用 |
| 候选标识 | 交付包候选 `basic-framework-ai-platform-2026.01-SNAPSHOT-2e208de48e19-dirty`（`deploy/dist/`，**dirty 构建，不可作正式发布物**，见 §2 注与风险 R-24） |
| 版本事实（来自交付包 `version.json`） | 后端 `2026.01-SNAPSHOT`；admin/chat/embedSdk 均 `5.6.0`；嵌入入口 `index-DdIwpx_z.js` / `index-CW18rxrW.css`；嵌入资产 4 个；SBOM 已含；构建 Node `v22.23.2` |
| 迁移事实（本文件核对） | `db/migration` **82 个文件、最大 V83**、编号唯一；快照 `数据库文件/basic_framework.sql` 声明 `through V83` |
| 口径 | 全部引用仓库相对路径；引用前已 `ls`/`grep` 核对存在性，核对不到写"待补"。**本文件不构成生产发布授权**：部署/发布动作另按用户授权执行（Q09 §4、Q10 §9） |
| 路径约定 | 前端 `packages/**`、`apps/**`、`tests/**` 相对 `前端代码/basic-framework-admin`（沿用 F04/Q06/Q07 证据的写法）；"交付包内路径"相对 `deploy/dist/<交付名>/` |
| 风险与未验证登记 | 见 [q10-risk-and-defect-register.md](q10-risk-and-defect-register.md)（编号 R-xx 在下文直接引用） |

## 1. 交付物清单

清单口径来自 [06 §9 发布物清单](../ai-platform/06-upstream-upgrade.md)，落点为
[deploy/README.md](../../deploy/README.md) §1–§2 与 [deploy/package-delivery.mjs](../../deploy/package-delivery.mjs)（本文件不新增第二事实源）。

### 1.1 交付包内产物（一条命令生成）

```bash
# 仓库根，JDK 17 + pnpm 就位
node deploy/package-delivery.mjs --offline
```

| # | 交付物 | 交付包内路径 / 仓库来源 | 生成方式 | 校验判据 | 状态 |
|---|---|---|---|---|---|
| 1 | 后端可执行 jar | `backend/basic-framework-server.jar`（源：`后端代码/basic-framework-boot/basic-framework-server`） | `./mvnw clean package`（打包脚本内） | `SHA256SUMS`；jar 健康由 `PackagedJarBootSmokeIT` 承担（AT-064 的 jar 部分），空库/旧基线迁移由 `ReleasedBaselineUpgradeIT` 承担（AT-063） | 候选已产出（待从干净提交重建，R-24） |
| 2 | Flyway 迁移集副本 | `backend/db-migration/`（源：`basic-framework-server/src/main/resources/db/migration`） | 脚本复制 | `migrations/manifest.json` 记录版本/描述/字节/sha256 | 就绪（82 文件 / max V83，本文件核对） |
| 3 | 管理端前端产物 | `frontend/admin/`（源：`apps/web-ele/dist`） | `pnpm -F @vben/web-ele run build` | `no-undef` 校验（生产 JS 232 文件，F04 §4.2）；部署期替换 `_app.config.js` 基址 | 就绪 |
| 4 | Chat 前端产物 | `frontend/chat/`（源：`apps/ai-chat/dist`） | `pnpm -F @vben/ai-chat run build` | `node apps/ai-chat/scripts/verify-built-chat.mjs`（Exit 0）；`check-public-cdn.mjs`（313 文件 0 命中） | 就绪（F04 §4） |
| 5 | 嵌入页自托管产物 | `frontend/embed-assets/`（`asset-manifest.json` + `assets/`） | `pnpm -F @vben/ai-chat run build:embed`（= build + `stage-embed-assets.mjs`） | 清单即白名单；单文件 ≤ `ai.embed.max-asset-bytes`；缺清单 → 422 `AI_EMBED_ASSETS_NOT_STAGED`（[embed-page.md](../deployment/embed-page.md) §1/§2） | 就绪（暂存目录 `apps/ai-chat/embed-assets/` 存在） |
| 6 | 版本化嵌入 SDK | `frontend/sdk/ai-embed-sdk-5.6.0.js` | `packages/ai-embed-sdk` 构建 | gzip 23,824 B（NFR-06 ≤100 KB，Q07 前端 §2.3）；产物 digest 漂移登记在 `tests/compatibility/fixtures/at-057/artifact-digest-ledger.json` | 就绪 |
| 7 | SBOM | `sbom/backend-bom.json`（CycloneDX 聚合）、`sbom/frontend-licenses.json`（`pnpm licenses` 原始输出） | 脚本内 Maven CycloneDX / pnpm | `MANIFEST.json` 的 `versions.sbomIncluded=true`（本文件核对） | 已含（**漏洞扫描未跑**，R-31） |
| 8 | LICENSE/NOTICE | `NOTICE`（脚本生成，勿手改） | 由上述两份清单聚合 | 后端 324 组件（22 个 UNKNOWN）、前端 833 包（3 个 UNKNOWN）——UNKNOWN 代表**需人工复核**，不代表无许可 | 已产出；UNKNOWN 段待人工复核 |
| 9 | 迁移清单 | `migrations/manifest.json`、`migrations/MIGRATIONS.md` | 脚本生成 | 同时记录快照声明版本（`snapshotDeclared=83`） | 就绪 |
| 10 | 配置模板（无秘密） | `config/app.env.example`；仓库内 `.env.example` | 手写维护 | 只含 `replace-with-*` 占位；`CREDENTIAL_ENCRYPTION_KEY`/`PROMETHEUS_SCRAPE_TOKEN`/`SMS_CALLBACK_TOKEN` 必须为空或占位；`sh .harness/verify.sh contracts` 含 `scripts/secret-scan.mjs` | 就绪；**AI 接缝键未包含**（R-29） |
| 11 | 交付清单/校验和/版本事实 | `MANIFEST.json`（371 个文件条目，含 `path`/`bytes`/`sha256`）、`SHA256SUMS`（372 条校验行）、`version.json` | 脚本生成（幂等，重跑重算） | 核对 `SHA256SUMS` 与 `MANIFEST.json`；`version.json` 版本事实必须与实际构建一致 | 已产出；候选为 dirty 构建（R-24） |

### 1.2 交付包外的规范与台账（随仓库交付）

| # | 交付物 | 路径 | 状态 |
|---|---|---|---|
| 12 | 开放 API 规范与样例 | `docs/integrations/open-api/ai-open-api.json` + `examples/` | 就绪 |
| 13 | 协议 Schema（v1.0） | `docs/contracts/ai/{run-event,result-block,chart-spec,report-spec,theme-tokens,audit-event,query-plan}.schema.json` + `samples/` | 就绪（F07 冻结） |
| 14 | 上游注册表（冻结坐标/许可/回退材料/触发条件） | `docs/integrations/upstream-registry.yaml` | 就绪；`rollback.materials` 表述与现状不符（R-22） |
| 15 | 兼容矩阵（组件 → 适配器 → 回归入口 → 验证类别） | `docs/integrations/ai-platform-compatibility-artifact-ledger.md` | 就绪（全条目为"基线夹具验证/版本装配验证"，见 R-33） |
| 16 | 备份恢复手册 | [部署与回退说明 §3](../deployment/deployment-and-rollback.md)、[Runbook §4](../deployment.md)、[恢复演练记录](../operations/q09-restore-drill.md) | 就绪（演练为单机容器口径，R-40） |
| 17 | 变更说明 | **待补**：仓库无独立变更说明文档；候选变更点分散在各卡证据（Q01–Q09 的"变更文件清单"） | 待补（责任：Q10 发布负责人） |
| 18 | 测试证据摘要 | 由 **Q10 另一片（AT 总表）**交付；原始证据在 `docs/ai-platform/verification/*`、`docs/acceptance/*`、`docs/upgrades/*` | 待另一片 |
| 19 | 已知限制 | [q10-risk-and-defect-register.md](q10-risk-and-defect-register.md) §2–§4（发布说明直接引用） | 就绪 |

> 注（R-24）：现有交付包 `deploy/dist/basic-framework-ai-platform-2026.01-SNAPSHOT-2e208de48e19-dirty` 的
> `version.json` 标记 `git.dirty=true`，且工作树有 29 项未提交变更（含 compose 修复与 Q09 测试）。
> **正式发布物必须在发布提交上重跑打包脚本重建**；现有产物仅可作候选演练与结构核对。

## 2. 升级与回退材料

### 2.1 平台自身发布的升级（新迁移 + 新二进制 + 新前端）

| 材料 | 路径 | 内容要点 |
|---|---|---|
| 升级检查单 | [docs/operations/upgrade-checklist.md](../operations/upgrade-checklist.md) | 角色与前置（§0）、升级前备份/迁移编号/台账/兼容回归/恢复材料/停机上界（§1）、升级中顺序 **MySQL → 文件 → 密钥版本 → 索引**（§2）、升级后验证 V1–V10（§3）、回退触发条件（§3 末） |
| 部署与回退说明 | [docs/deployment/deployment-and-rollback.md](../deployment/deployment-and-rollback.md) | 交付包 → 运行的最小步骤（§2）、回退顺序（§3.2 反向执行：前端产物 → 索引 → 密钥 → 文件 → MySQL）、**数据损失窗口**（§3.3） |
| 恢复演练记录 | [docs/operations/q09-restore-drill.md](../operations/q09-restore-drill.md) | 同机容器实测 RTO 39–56 s、RPO 窗口 0.2–0.6 s（**不可外推**）；恢复顺序的直接证据；索引快照限制与缺口（§6） |
| 演练报告模板 | [docs/operations/drill-report-template.md](../operations/drill-report-template.md) | 每次升级/恢复的实跑记录字段（含 AT-064 顺带登记行） |

**升级前硬前置（升级检查单 §0，任一不满足即停止）**：交付包由脚本产出并校验且与实际构建版本对应；
一次真实恢复演练在案；`contracts|backend|frontend|integration` 在发布 commit 上退出码 0（`dependencies` 若因环境缺口未跑必须在发布说明标注——本机即如此，R-31）；用户明确授权；compose 可用性（R-25）。

### 2.2 上游依赖升级（Spring AI / AntV / Qdrant / Tika）

| 材料 | 路径 | 内容要点 |
|---|---|---|
| 升级演练与回退方案 | [docs/upgrades/q08-upgrade-rehearsal-and-rollback.md](../upgrades/q08-upgrade-rehearsal-and-rollback.md) | 候选探测结论（当前无更高补丁，§1）；R2 AntV 改哪里/跑什么/看什么/回退命令（§3）；R3 Spring AI 同构（§4）；迁移编号碰撞规则与处置决策表（§5） |
| 验收证据（口径与未验证项） | [docs/upgrades/q08-acceptance-evidence.md](../upgrades/q08-acceptance-evidence.md) | AT-049/057/065/072 结论与**报告口径**：首发只能写"基线夹具验证"（§1）；未验证项总表（§3） |
| 后端兼容回归包（可执行） | `后端代码/basic-framework-boot/basic-framework-module-ai/src/test/java/com/basicframework/module/ai/compatibility/` | 5 个套件 **38 例**：模型协议线级/平台级黄金值、迁移碰撞演练（6 场景）、删必需字段拒绝（14 例）、危险依赖策略（6 例）、旧 ReportSpec 基线（3 例）。命令见 [Q08 后端切片证据](../upgrades/q08-compatibility-baseline-and-upgrade-drill-evidence.md) §2.1 |
| 前端兼容回归包 | `前端代码/basic-framework-admin/tests/compatibility/` | AT-049/057/065 的 vitest 用例 + AT-065 浏览器渲染用例 + 产物 digest 漂移台账；命令按升级检查单 §1.4（`pnpm exec vitest run tests/compatibility`） |
| 回退材料现状 | 同上两文档 + 兼容产物清单 §3 | 见下表 |

**回退材料现状（核对面，详见 [兼容产物清单](../integrations/ai-platform-compatibility-artifact-ledger.md) §3、
[升级检查单](../operations/upgrade-checklist.md) §1.5）**：

| 材料 | 现状 | 结论 |
|---|---|---|
| 当前冻结版 jar/镜像 | 本地 m2 只有 spring-ai 1.1.8；Qdrant 镜像 `v1.19.1@sha256:12364fe8…` 与台账 digest 一致；无运行中容器 | 可回到**当前版本** |
| 上一版本 jar/镜像 | 本地 m2 无 1.1.7/1.1.6；无更旧 Qdrant 镜像（Q08 §2.1 步骤 1/3） | **不可离线回退到上一版本**（R-22；需联网预置） |
| 前端上一版 | `@antv+g2@5.4.8` 在本地 store，可离线回到当前版本 | 可回到**当前版本**；其他版本需 store 有 tarball |
| 前端上一版产物 | `apps/web-ele/dist`、`apps/ai-chat/dist` 为构建产物（gitignore） | 升级前必须整目录归档（检查单 §1.5） |
| 数据库回退 | **已执行 Flyway 迁移不可回滚**；二选一：前滚修复或从备份恢复到独立库后割接 | 数据损失窗口：RPO 1 小时（ADR 0006），索引快照之后需重放/重建（[06 §4.3](../ai-platform/06-upstream-upgrade.md)、[06 §8](../ai-platform/06-upstream-upgrade.md)） |
| 索引回退 | 快照恢复（同实例已验证）或保留旧代 + 重建；**不支持激活已退役代** | 跨实例快照未验证（R-37）；无快照 API 生产封装 |
| 密钥版本 | 不轮换则无需旧值；轮换需人工逐条重加密，**无批量工具、未演练** | R-45 |

## 3. 已知限制与未验证清单

**完整登记与分级见 [q10-risk-and-defect-register.md](q10-risk-and-defect-register.md)**。发布说明必须包含（引用编号）：

- **必须写入"已知限制"的未修项**：R-09（`apps/web-ele` 29 处 lucide 字符串图标运行期外发风险）、
  R-10（`packages/icons` 的 mdi/ant-design，含登录后必渲染的全局搜索/头像菜单）、
  R-11（`icon-picker` 主动拉取，按设计保留）、R-13（保留期清理作业未实现）、R-14（限额配置未落地）、
  R-15（账本未接入运行链路）、R-16（配额占位未接入运行链路）、R-17（`RUN_STEP` 无常驻消费者）、
  R-21（AI 业务指标缺失，积压只能 SQL 判读）、R-28（`infra_file_config` 无种子行）、R-29（AI 接缝不在模板）。
- **不得声称已验证**：模型效果与评测数值（R-32）、真实 N-1 产物联调（R-33）、AT-056/AT-067 报表页（R-34/R-35）、
  Windows/CI 实跑（R-36）、索引快照跨实例（R-37）、真实网关头透传（R-38）、生产/预发布实跑（R-39）、
  生产规模 RTO/RPO 与 Redis 恢复（R-40）、8 vCPU/16 GB 容量复测（R-41）、真实 JVM 重启（R-42）、
  真实模型端点（R-32）、候选升级回归（R-44）、密钥轮换（R-45）。
- **对外表述口径（引用 FR-34/Q08 §1）**：所有兼容结论一律写"**首发基线夹具验证**"，不得写"已验证历史版本"；
  Mock/夹具结果与真实模型结果必须**分开报告**（Q10 §4、08 §1）。
- **AT-064 边界**：jar 健康与迁移有机器证据；"jar + MySQL/Redis + 浏览器三件在位跑真实生产产物"未验证；
  `deploy/README.md` §4 承诺的 `PackagedJarEmbedAssetsIT` **在仓库中不存在**（R-30）。
- **AT-065/AT-072 边界**：机制与基线夹具已建立，但"真实版本置换"与"真实 MySQL 拒绝演示"未执行（R-44）。

## 4. 部署前置条件（含核对到的真实缺口）

> 事实源：[配置手册](../deployment/configuration-manual.md)、[部署与回退说明](../deployment/deployment-and-rollback.md)、
> [升级检查单](../operations/upgrade-checklist.md)。以下"核对"列为本文件 2026-09-27 的只读核对结果。

### 4.1 环境与账号

| # | 前置条件 | 核对结果 | 定性 |
|---|---|---|---|
| 1 | Linux + Docker（集成/备份路径）+ JDK 17；目标版本 MySQL 8.4 / Redis 7 | 本机 Docker 29.7.2 / Compose v5.5.0 可用；MySQL/Redis 未运行（无容器） | 已知 |
| 2 | 数据库账号分离：`DB_USERNAME != FLYWAY_USERNAME`（ADR 0009），prod 校验拒绝同名 | 模板与校验代码均在；**未在真实库演练** | 已知（校验会 fail-closed） |
| 3 | 空库启动让 Flyway 跑完整迁移链；或导入快照前先 `baseline`（禁止把快照按版本 1 接管） | 迁移链 82 文件 / max V83 / 快照 `through V83` 一致（核对通过） | 已知 |
| 4 | 种子管理员须一次性设置 `BOOTSTRAP_ADMIN_PASSWORD` 激活后删除 | 文档与模板均就位（配置手册 §2.3） | 已知/需人工 |

### 4.2 compose 与必填变量（**实际缺口：需人工 + 一次提交**）

| # | 前置条件 | 核对结果 | 定性 |
|---|---|---|---|
| 5 | `docker-compose.yaml` 可渲染 | **工作树已修（3 行缩进）/ HEAD 未提交**：`git diff` 可见修正；**直接复跑 HEAD 版本**（`docker compose -f <(git show HEAD:./docker-compose.yaml) config -q`）→ exit 1 `L65.C27 mapping values are not allowed`；带 dummy 变量对**工作树**复跑 → **exit 0** | **需人工**：修复必须包含在发布提交内（R-25） |
| 6 | 必填环境变量全部注入 | 本文件核对 `--env-file /dev/null` 渲染输出，**8 个 `:?` 强制变量**：`DB_USERNAME`、`DB_PASSWORD`、`FLYWAY_USERNAME`、`FLYWAY_PASSWORD`、`MYSQL_ROOT_PASSWORD`、`REDIS_PASSWORD`、`CREDENTIAL_ENCRYPTION_KEY`、`CORS_ALLOWED_ORIGIN`；缺任一项 compose 拒绝渲染 | 已知（模板已列；漏配即失败，非静默） |
| 7 | 主密钥为 32 字节 Base64；CORS 为精确 HTTPS Origin（拒绝通配/路径/示例域名/本机地址） | prod fail-closed 校验存在（配置手册 §2.3/§5） | 已知 |
| 8 | 观测（可选）：`PROMETHEUS_ENABLED=true` 时 `PROMETHEUS_SCRAPE_TOKEN` 必填 | 模板与规则文件就位（`ops/prometheus/`） | 已知 |

### 4.3 运行期配置（L3，管理端初始化）

| # | 前置条件 | 核对结果 | 定性 |
|---|---|---|---|
| 9 | 主文件存储必须先在 `/infra/file-config` 建主配置 | **快照无种子行**：`数据库文件/basic_framework.sql` 的 `infra_file_config` 只有 DROP/CREATE/LOCK、**无 INSERT**（核对通过）→ 未配置前上传不可用 | **已知/需人工**（部署步骤，R-28） |
| 10 | AI 接缝 `basic-framework.ai.*`（`enabled` + `capabilities` + `http.allowed-hosts` 等）必须显式注入 | **三处模板都没有**：`.env.example`、`docker-compose.yaml`、`deploy/config/app.env.example` 中 `grep "basic-framework.ai"` 无输出（核对通过） | **需人工**（须用 `SPRING_APPLICATION_JSON`/`-D` 注入；R-29） |
| 11 | 模型端点与凭据、AI 应用与允许域在管理端建立 | 属 L3 初始化；**无真实模型端点**（R-32） | 需人工/需环境 |
| 12 | 嵌入页产物暂存并指向 `ai.embed.assets-directory` | 暂存目录存在（`apps/ai-chat/embed-assets/`）；缺清单 → 422（fail-closed） | 已知 |

### 4.4 能力装配缺口（**部署前必须书面决策**）

| # | 前置条件 | 核对结果 | 定性 |
|---|---|---|---|
| 13 | 知识检索需要 Qdrant 装配 | **生产装配缺失**：`QdrantRestKnowledgeIndexAdapter` 在 `module-ai/src/main` 只有类定义，无 `@Bean/@Component/@Configuration` 装配点；消费方 `ObjectProvider#getIfAvailable` 未装配时静默降级（核对通过） | **需人工/需授权**：若发布范围含知识库则为阻断项（R-27） |
| 14 | 漏洞扫描结论 | `dependencies` 门禁（Trivy HIGH/CRITICAL）本机不可运行 | **环境缺口**（R-31；阻断发布评审结论） |
| 15 | 安全头与网关行为 | AT-056 需真实后端（skipped）；真实网关头透传未验证 | 环境缺口（R-34/R-38） |

### 4.5 部署后最小判据（照抄 08/配置手册，不新增）

- `curl -fsS http://127.0.0.1:48080/actuator/health` → `{"status":"UP"}`；
- Flyway 末版本 = 迁移文件最大编号（当前 V83），无 pending；
- 管理端登录页可打开并触发登录；嵌入入口 `GET /app-api/ai/v1/embed/{appCode}` 200；
- 种子管理员校验：`SELECT username, status, must_change_password FROM system_users WHERE id = 1` → `status=1`、`must_change_password=1`。

## 5. 发布需产品安全评审的检查点清单

> 以下检查点**全部引用既有卡片/规范的 §4/§5 要求，不自造流程**；每条给"引用出处"与"本批次的证据状态"。

| # | 检查点 | 引用出处 | 本批次状态 |
|---|---|---|---|
| 1 | 交付候选、风险/缺陷/升级证据已整理并**交产品安全评审**；部署发布另按用户授权执行 | [Q10 §3 第 3 步](../ai-platform/tasks/Q10.md)、Q10 §9 停止条件 | 本文件 + 风险登记表即"整理"产物；**评审本身未发生** |
| 2 | V1 所有前置任务有完成证据 | Q10 §4 | 依赖卡的证据索引在 `docs/ai-platform/verification/*`、`docs/acceptance/*`、`docs/upgrades/*`；逐条结论由 AT 总表汇总 |
| 3 | **无阻断缺陷、未知鉴权结论或未通过关键门禁** | Q10 §4 | 本片判定见风险表 §6（R-27/R-31/R-23+R-24）；AT 侧判定（B1–B8）见 [AT 总表 §2.4](q10-v1-acceptance-matrix.md)；两片对应关系见[风险表 §6 末表](q10-risk-and-defect-register.md#6-分级汇总与阻断判定)——合并评审时并查，不各自宣布可发布 |
| 4 | 真实模型结果与 Mock 结果**分别报告** | Q10 §4、08 §1/§4 | Q05 首轮评测报告已分开；真实模型侧未验证（R-32） |
| 5 | 交付包、配置模板与恢复实测齐备（"可复现交付包 / 配置手册 / 恢复实测及升级检查单"） | [Q09 §5](../ai-platform/tasks/Q09.md) | 脚本/手册/升级检查单就绪；**出包实跑证据待补**（R-23），恢复演练已实测（q09-restore-drill） |
| 6 | 未获生产授权不执行客户升级 | Q09 §4 | 本批次未执行任何生产动作 |
| 7 | 浏览器验收：真实浏览器命令、关键截图/录像、双平台门禁与拒绝测试 | [Q06 §5](../ai-platform/tasks/Q06.md) | 套件与命令在 Q06 证据 §7；`smoke` 门禁双 provider 已接线；**Windows 与 CI 未实跑**（R-36） |
| 8 | 性能与韧性：可重复压测脚本、原始结果、故障注入记录与容量建议；达到已评审 NFR 或明确不通过 | [Q07 §4/§5](../ai-platform/tasks/Q07.md) | Q07 两片证据 + `docs/operations/q07-capacity-and-failure-injection.md`；AT-059 已由 R-06 修复闭环；硬件可比性限制见 R-41 |
| 9 | 升级：上游注册表、兼容产物清单、一次实际升级演练与回退条件；首发按"基线兼容夹具验证"报告 | [Q08 §4/§5](../ai-platform/tasks/Q08.md) | 三项文档就绪；真实置换未执行（R-44） |
| 10 | 发布门槛：故意注入失败可阻断发布、换模型必须新评测、不能只挑通过样例 | [Q05 §4/§5](../ai-platform/tasks/Q05.md) | 服务层判据与记录入口已验证（Q05 证据）；端到端演练需"有模型+可固定候选版本"环境（R-32/R-48） |
| 11 | 发布证据清单：基线 commit、依赖锁与 SBOM、fixture 版本、命令/退出码、Harness 报告、覆盖率、浏览器录像/关键截图、API 协议 diff、模型评测明细、性能结果、升级/恢复记录、操作者与时间 | [08 §6](../ai-platform/08-testing-acceptance.md) | 大部分已具；**缺**：交付包出包实跑记录（R-23）、dependencies 门禁报告（R-31）、真实模型评测明细（R-32）；**候选为 dirty 构建**（R-24） |
| 12 | 验收与失败分支的停止条件：依赖无证据、超允许路径、无授权安全/生产动作、上游候选无法验证时**停止该依赖链并报告** | Q10 §9 | 本批次严格执行；未授权项（如 Qdrant 装配、`packages/**` 图标修复）保持"未修/待授权"登记 |

## 6. 候选转正式发布的门槛（本文件的判定）

1. 在**干净发布提交**上重建交付包，并回填 Q09 证据 §交付包（R-23/R-24/R-25 一并随提交解决）；
2. `dependencies` 门禁在漏洞库可达环境跑出结论，或取得书面豁免并写入发布说明（R-31）；
3. 产品/安全对发布范围作出书面决策：知识库/RAG 是否随本发布上线（决定 R-27 是否阻断）；
4. 发布说明包含 §3 的"已知限制"与"未验证清单"（引用风险表编号），并按 FR-34 口径写兼容结论；
5. 按 §5 检查点完成产品安全评审并留记录（评审结论、操作者、时间）；
6. AT 侧的阻断项（模型评测未执行、`RUN_STEP` 消费者缺失、AT-067 未全覆盖、AT-056 未验证、配额未接线、AT-063/064 实跑证据、AT-002 口径）以 [AT 总表 §2.4](q10-v1-acceptance-matrix.md) 为准；
   两片合并后，门槛以**更严者**为准，两片各自不得单独宣布可发布。

## 7. 引用与核对命令

```bash
cd /home/ctyun/桌面/zhongtai/ai-platform

# 交付包结构与版本事实（R-24）
ls deploy/dist/*/ ; cat deploy/dist/*/version.json ; wc -l deploy/dist/*/SHA256SUMS
python3 -c "import json;d=json.load(open([p for p in __import__('glob').glob('deploy/dist/*/MANIFEST.json')][0]));print(d['migration'],d['notice'])"

# 迁移链与快照（与升级检查单 §1.2 同口径）
ls 后端代码/basic-framework-boot/basic-framework-server/src/main/resources/db/migration/*.sql | wc -l
ls 后端代码/basic-framework-boot/basic-framework-server/src/main/resources/db/migration/*.sql | sed 's/.*\/V\([0-9]*\)__.*/\1/' | sort -n | tail -1
grep -n "Snapshot note" 数据库文件/basic_framework.sql

# 兼容回归入口存在性
ls 后端代码/basic-framework-boot/basic-framework-module-ai/src/test/java/com/basicframework/module/ai/compatibility/
ls 前端代码/basic-framework-admin/tests/compatibility/

# 部署前置缺口（与 §4 一一对应）
grep -c "INSERT INTO \`infra_file_config\`" 数据库文件/basic_framework.sql                       # 0
grep -n "basic-framework.ai" 后端代码/basic-framework-boot/.env.example \
  后端代码/basic-framework-boot/docker-compose.yaml deploy/config/app.env.example               # 无输出
grep -rn "QdrantRestKnowledgeIndexAdapter" 后端代码/basic-framework-boot/*/src/main | head       # 仅类定义，无装配
git diff 后端代码/basic-framework-boot/docker-compose.yaml | head -20                            # R-25 的 3 行缩进修复
```

## 8. 与另一片（AT 总表）的接口

- 本文件**不列** AT 逐条结论：AT 总表见 [q10-v1-acceptance-matrix.md](q10-v1-acceptance-matrix.md)（含其 §2.4 阻断项 B1–B8）；
  原始证据：`docs/acceptance/q05-*.md`、`docs/acceptance/q07-*.md`、`docs/upgrades/q08-*.md`、`docs/operations/q09-restore-drill.md`、
  `docs/ai-platform/verification/q01..q06-*.md`、`docs/ai-platform/verification/f02-*.md`、`docs/ai-platform/verification/f04-*.md`。
- 若 AT 总表需要"发布候选/风险"侧的编号，直接引用本文件的 R-xx 编号；两片阻断项的对应关系见
  [风险登记表 §6 末表](q10-risk-and-defect-register.md#6-分级汇总与阻断判定)，避免两片各写一套。
