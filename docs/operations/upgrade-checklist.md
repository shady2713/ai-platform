# 升级检查单（平台基线升级）

| 项目 | 内容 |
|---|---|
| 任务卡 | [Q09 交付安装包、配置模板与恢复演练](../ai-platform/tasks/Q09.md)（文档切片） |
| 本文件回答什么 | 把**一个已发布基线升级到下一个发布**的逐步检查单：升级前（备份/迁移编号/快照台账/兼容回归）、升级中（**MySQL → 文件 → 密钥版本 → 索引**的执行顺序与停机判据）、升级后（授权/引用/报表/评测/观测验证）与回退触发条件。每步给**命令 + 判据**，可直接照做 |
| 不写什么 | 操作细节不重复：[部署 Runbook](../deployment.md)（compose/健康检查/Flyway 失败处置/备份恢复）、[部署与回退说明](../deployment/deployment-and-rollback.md)（交付包→运行、回退命令）、[恢复演练报告模板](drill-report-template.md) |
| 与上游依赖升级的关系 | **上游依赖**（Spring AI / AntV / Qdrant / Tika 等）版本置换走 [Q08 升级演练方案](../upgrades/q08-upgrade-rehearsal-and-rollback.md) §3/§4；本单处理的是**平台自身发布**（新迁移 + 新二进制 + 新产品前端）的上线，两者可同窗执行但材料与判据不同 |
| 执行状态 | **本切片只写文档，未执行任何升级**（无生产授权、无第二套环境）。下文命令均标注来源；凡本卡另两片应产出的材料，写"待补：见 Q09 证据 §…"，不编造内容 |
| 版本落点（2026-09-27 本机） | 迁移链 83 个文件、最大 V84、快照声明 `through V84`；后端 `basic-framework-server.jar`；前端 `apps/web-ele/dist` + `apps/ai-chat/dist`（嵌入产物另暂存） |

## 0. 角色与前置

| 角色 | 职责 |
|---|---|
| 发布负责人 | 决定执行/回退；批准停机窗口；对"回退触发"拍板 |
| DBA | 备份与恢复、迁移账号操作、V45 类只读预检、SQL 处置 |
| 应用运维 | 交付包校验、容器/进程启停、健康与观测检查 |
| 安全/密钥管理 | 主密钥与令牌的注入与轮换；确认秘密未进入模板 |

前置硬条件（任一不满足即**停止**）：

1. 交付包由脚本产出并校验（交付包切片已交付 `node deploy/package-delivery.mjs --offline`，
   产物与 `MANIFEST.json`/`SHA256SUMS`/`version.json` 见 [deploy/README.md](../../deploy/README.md)），
   且与实际构建版本对应（[06 §9 发布物清单](../ai-platform/06-upstream-upgrade.md)）。
   **待补：见 Q09 证据 §交付包**（最近一次出包的实跑命令、退出码与清单摘要）。
2. 一次**真实恢复演练**已完成且记录在案：**待补：见 Q09 证据 §恢复演练**（恢复演练切片产出后回填演练编号、耗时与抽验结果）。
3. `sh .harness/verify.sh contracts|backend|frontend|integration` 在本发布 commit 上退出码 0；
   `dependencies` 若因环境缺口未跑，必须在发布说明中标注（本机即如此，见 §6）。
4. 用户已明确授权本次生产动作；无授权不执行客户升级（Q09 卡 §4）。
5. **compose 可用性**：本机 `docker compose ... config` 因 `docker-compose.yaml` L65 的 YAML 缩进错误退出码 1
   （详见[部署与回退说明 §2.0](../deployment/deployment-and-rollback.md)）。本单的 compose 命令在修复前不可用，
   可用"直接进程/直接容器"路径替代（同节 §2.2 方式 B）。

## 1. 升级前

### 1.1 备份（RPO 落点）

```bash
cd 后端代码/basic-framework-boot            # 下文 compose 命令均在此目录
backup_file="backup-$(date +%F-%H%M).sql"
docker compose exec -T mysql sh -c \
  'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers basic_framework' \
  > "$backup_file" && test -s "$backup_file"
```

判据：命令退出码 0 **且** `test -s` 通过；备份间隔不长于 **1 小时**（对齐 ADR 0006 的 RPO，
见 [Runbook §4](../deployment.md)）。文件非空≠可恢复——可恢复性由 §0 前置 2 的演练证明。

### 1.2 迁移编号领取（本发布新增迁移）

规则（F08 冻结，见 [data-lifecycle.md](../data-lifecycle.md)「AI 中台表策略」）：
与框架**共用同一 Flyway 序列**，领取**下一个可用编号**；一卡一号、禁止预占未来编号、**禁止修改历史迁移**。

```bash
cd /home/ctyun/桌面/zhongtai/ai-platform
# 编号唯一性：输出为空 = 无碰撞；再看最大编号（本机 V84）
ls 后端代码/basic-framework-boot/basic-framework-server/src/main/resources/db/migration/*.sql \
  | sed 's/.*\/V\([0-9]*\)__.*/\1/' | sort -n | uniq -d
ls 后端代码/basic-framework-boot/basic-framework-server/src/main/resources/db/migration/*.sql | wc -l
grep -n "Snapshot note" 数据库文件/basic_framework.sql     # 期望：through V<N>，N=最大编号
```

判据：`uniq -d` 空、快照声明编号 = 最大编号、迁移文件数 = 本发布新增数 + 上版本数。
失败含义与处置：[Q08 演练方案 §5.3 决策表](../upgrades/q08-upgrade-rehearsal-and-rollback.md)。

### 1.3 快照与台账同步（同一变更内完成）

| 文件 | 要求 |
|---|---|
| `数据库文件/basic_framework.sql` | 快照声明与表/列/外键集合同步到新编号 |
| `docs/contracts/data-lifecycle.json` | 新表登记生命周期策略与逻辑删除列 |
| `docs/contracts/data-permission-exemptions.json` | 新表登记权限分类（禁止"未分类表"） |
| `docs/contracts/ai/error-code-map.md` ↔ `AiErrorCodeConstants` | 新错误码两侧同步（F08 规则） |

判据：`node scripts/check-data-lifecycle.mjs` 退出码 0；`sh .harness/verify.sh contracts` 退出码 0。
（F08 证据 §3 记录过一次真实拒绝：临时迁移未同步快照 → 3 项 FAIL。）

### 1.4 兼容回归入口（本发布必须全绿）

```bash
cd 后端代码/basic-framework-boot
export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64
# 兼容套件（Q08 交接口径：38 例通过，退出码 0）
./mvnw -o -pl basic-framework-module-ai test \
  -Dtest='com.basicframework.module.ai.compatibility.*Test' -DfailIfNoTests=false
# 迁移/打包冒烟（真实 MySQL/Redis）——承载 AT-063（空库与旧版本库迁移）与 AT-064（jar 健康）
sh .harness/verify.sh integration
# 其中与本单直接相关的用例：
#   ReleasedBaselineUpgradeIT：空库全链迁移、已发布基线升级、快照 Schema 一致（AT-063）
#   PackagedJarBootSmokeIT   ：打包 jar + 真实 MySQL/Redis + 健康端点（AT-064）
# 交付包内的嵌入产物直出：deploy/README.md §4 计划由 PackagedJarEmbedAssetsIT 覆盖，
#   该用例 2026-09-27 尚未在仓库中出现 —— 待补：见 Q09 证据 §交付包
```

前端（工作目录 `前端代码/basic-framework-admin`）：

```bash
pnpm exec vitest run tests/compatibility        # Q08 前端兼容包；命令按 vitest 位置过滤书写
node apps/ai-chat/scripts/check-public-cdn.mjs  # 禁公共 CDN 静态扫描（AT-067 构建期）
node apps/ai-chat/scripts/verify-built-chat.mjs # 体积/分包/DOM 真实渲染（AT-065 前端侧）
```

判据：后端 38 例、`verify.sh integration` 退出码 0；前端套件无失败、两个脚本退出码 0。
口径提醒：当前**没有已发布的旧版本产物**，兼容结论只能是"首发基线夹具验证"，
不得写成"已验证历史版本"（[Q08 验收证据 §1](../upgrades/q08-acceptance-evidence.md)）。

### 1.5 恢复材料（与交付包一起清点）

| 材料 | 本机现状 | 要求 |
|---|---|---|
| 上一版本 jar / 镜像 | **本地 Maven 仓库只有当前版本**（Q08 §2.1 步骤 1）；镜像需自行 tag 保留 | 升级前把在运镜像按 digest 另打 tag 并记录 |
| 前端上一版产物 | `apps/web-ele/dist`、`apps/ai-chat/dist` 为构建产物（gitignore） | 升级前整目录归档 |
| 旧 Qdrant 镜像与集合快照 | **无旧镜像**（Q08 §2.1 步骤 3）；快照能力已在 K01 实测（创建/恢复均 200） | 升级前对每个集合建快照并记录名称 |
| 主密钥旧值 | `CREDENTIAL_ENCRYPTION_KEY` **不轮换就不需要旧值**；轮换则必须先重加密（见 §2.3） | 变更前把旧密钥留在密钥管理系统内可回取 |

判据：清点表逐行有路径/摘要；缺失项在发布说明写明"回退到上一版本需先在联网环境预置材料"
（与 Q08 §4.4 的阻断说明同口径）。

### 1.6 停机上界

- 平台侧上界 = ADR 0006 的 **RTO 4 小时**（[Runbook §4](../deployment.md)）；恢复演练若实测 4h 不可达，
  必须先改设计或缩小发布范围，不能"带病发布"。
- Qdrant 单节点：升级/重建索引需要维护窗口，**不承诺零停机**（[06 §4.3](../ai-platform/06-upstream-upgrade.md)）。
- 停机上界内未完成 → 触发回退（§4），不得边查边改超过窗口。

## 2. 升级中（执行顺序：MySQL → 文件 → 密钥版本 → 索引）

> 顺序由 Q09 卡（另一切片执行）与 [06 §4.3](../ai-platform/06-upstream-upgrade.md) 的共同约束决定：
> **MySQL 是权威**（文档、ACL、版本、任务），文件/索引都是从属派生对象；密钥是打开 L3 凭据的前置。
> 每一步"进入下一步"前必须满足本步判据；不满足则停在上一步处置或回退。

### 2.1 MySQL（先库后码）

```bash
cd 后端代码/basic-framework-boot
# 前置：compose 编排当前因 docker-compose.yaml L65 的 YAML 缩进错误无法渲染（见 §0 前置 5）；
#       修复前用"直接进程/直接容器"路径代替 compose。
# 发布包就位后启动；Flyway 在启动期执行迁移（validate-on-migrate=true、baseline-on-migrate=false）
docker compose up -d --no-deps app
docker compose logs -f app        # 观察迁移与健康
curl -fsS http://127.0.0.1:48080/actuator/health
```

自定义 SQL/失败处置用 [Runbook §3](../deployment.md) 的 `run_flyway` 助手：先 `run_flyway info`（只读核对），
必要时 `repair` + `migrate` + `validate`。**禁止** `clean`、禁止改历史迁移、禁止在已有 Flyway 历史的库上 `baseline`。

判据：① `flyway_schema_history` 全部 success 且末版本 = 迁移文件最大编号；② `/actuator/health` = UP；
③ 关键业务只读查询（§3）通过。升级前若有 V45 类唯一性预检，按 [Runbook §1](../deployment.md) 先跑到空结果。

### 2.2 文件（对象存储/文件引用）

- 校验主文件存储配置仍在且可用（配置是 L3 密文，**密文归属主密钥**，本步只验证不解密重写）：
  管理端 `/infra/file-config` 保存一次即触发 `createOrUpdateFileClient`；或直接调一次上传/下载。
- S3 私有桶确认 `.pending/` 生命周期规则仍在生效（[Runbook §1](../deployment.md) 末尾）。
- 全文检索/知识库依赖的文件引用不受迁移影响，但要抽查"最后一个引用释放才真正删文件"的语义未变。

判据：上传成功、下载可读、`infra_file_config` 有且仅有一行 `master=1`；受控失败路径回稳定错误码。

### 2.3 密钥版本（`CREDENTIAL_ENCRYPTION_KEY`）

- **不轮换**（常态）：确认部署 Secret 的值与本环境 L3 凭据的加密密钥一致——即"能解密已有配置"。
  判据：模型端点"连接测试"通过、文件上传成功、短信渠道（若启用）测试发送不报解密错。
- **轮换**（罕见）：密文格式 `v1.<iv>.<ciphertext>` **不带密钥标识**（`CredentialCipher.java`），
  所以正确顺序是"**先用旧密钥解密读出 → 换新密钥 → 用新密钥重写全部 L3 凭据 → 再重启应用**"。
  当前仓库**没有批量重加密工具**：需人工通过管理端逐条重存（模型端点凭据轮换动作、文件配置保存、
  短信渠道保存）。**未做此演练**，见 §6。

判据：应用启动无"凭据解密失败"；逐条 L3 配置的连接测试通过。

### 2.4 索引（Qdrant / index generation）

- 升级前：暂停或隔离索引写入、记录水位（`ai_knowledge_index_generation` 的 ACTIVE 代与
  `ai_run.event_seq` 等消费位点）、保存 MySQL 文档/ACL 版本、集合配置与快照（[06 §4.3](../ai-platform/06-upstream-upgrade.md)）。
- 建快照（命令形状取自 K01 的 `AiKnowledgeIndexQdrantIT`，容器验证已通过）：

```bash
QDRANT=https://<qdrant-host>          # 生产只接受 https；API Key 走请求头，不进 URL/日志
curl -fsS -X POST -H "api-key: $QDRANT_API_KEY" -H 'Content-Type: application/json' \
  -d '{}' "$QDRANT/collections/<collection>/snapshots"          # → result.name
```

- 恢复（演练用，同源自 K01 用例；`location` 指向服务端快照目录）：

```bash
curl -fsS -X PUT -H "api-key: $QDRANT_API_KEY" -H 'Content-Type: application/json' \
  -d '{"location":"file:///qdrant/snapshots/<collection>/<snapshot>","priority":"snapshot"}' \
  "$QDRANT/collections/<collection>/snapshots/recover"
```

判据（承载 **AT-030**）：快照恢复后**内容 / ACL / 版本正确**、旧别名/旧代恢复可用——
K01 已验证"创建快照→清空→恢复→重新可检索"（4 例通过）；K07 的 `auditOrphans` 报告退役代残留。
**限制（必须写进发布说明）**：旧代与切片保留是"回退窗"，但索引代状态机只允许 `BUILDING → ACTIVE`，
**不支持把已退役的一代重新激活**；回退 = 保留数据 + 重建（K07 证据 §9.2）。
换嵌入模型/切片算法时，**必须新建 generation**，不同向量空间不得混写（[06 §8](../ai-platform/06-upstream-upgrade.md)）。

### 2.5 应用与前端产物切换

- 后端：镜像/jar 已随 §2.1 切换；前端：`apps/web-ele/dist` 部署到站点根、按需替换 `_app.config.js`
  （模板见产物 `前端代码/basic-framework-admin/apps/web-ele/dist/_app.config.js`，部署时改为实际 API 基址）。
- 嵌入页：重新构建并暂存产物到 `basic-framework.ai.embed.assets-directory`（[embed-page.md](../deployment/embed-page.md)）。

判据：管理端真实页面无关键错误；嵌入入口 200；`sh .harness/verify.sh smoke`（真实浏览器套件）通过。
**本机边界**：AT-056 与 AT-067 报表页无后端时 skipped（Q06 证据 §2）。

## 3. 升级后验证清单

| # | 面 | 命令 / 入口 | 判据 |
|---|---|---|---|
| V1 | 健康 | `curl -fsS http://127.0.0.1:48080/actuator/health` | `{"status":"UP"}` |
| V2 | 迁移 | `run_flyway info`（[Runbook §3](../deployment.md)）或查 `flyway_schema_history` | 全部 success、末版本 = 最大编号、无 pending |
| V3 | 授权 | `./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiAuthorizationMatrixIT,AiQueryPermissionIT,AiRevocationIT,AiIdentityIsolationIT'` | 全部通过；撤销后新请求与产物读取被拒 |
| V4 | 引用/知识 | `./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiKnowledgeIndexingIT,AiKnowledgeLifecycleIT,AiKnowledgeIngestionIT'`（需 Docker + Qdrant 镜像） | 切片/向量回收正确、引用可核验、提示注入不改变权限 |
| V5 | 报表 | `./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiReportPersistenceIT,AiReportRefreshIT,AiReportRevisionIT'` | 生成/刷新/改版不覆盖、旧结果标注时间 |
| V6 | 评测 | `./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiEvalSuiteIT,AiGoldenSetAcceptanceIT'`；管理端 `/ai/evaluation` | 套件修订冻结、逐例判定可复核 |
| V7 | 观测 | `/actuator/prometheus`（带令牌）、管理端 `/ai/observability`、`/ai/usage`；SQL 判读见[队列文档](../operations/queue-backlog-and-resilience-observability.md) | 抓取 200、运行/用量页有数据、无未计量被写成 0 |
| V8 | 队列/任务 | `infra_job` id=32/33 的 `infra_job_log` | 恢复 Job 每分钟、清理 Job 每小时有留痕；积压可解释 |
| V9 | 文件 | 管理端上传/下载一次；`infra_file_config` master 行 | 成功；`.pending/` 规则仍生效 |
| V10 | 保密 | 抓取响应与日志抽查 | 无 token/secret/正文（AT-058 口径）；错误信息不含上游报文 |

### 回退触发条件（任一命中即回退，不允许"带病观察"）

1. `flyway_schema_history` 出现 failed 条目且无法按 [Runbook §3](../deployment.md) 在停机上界内修复；
2. `/actuator/health` 在停机上界内未恢复 UP，或健康但 V3/V4/V5 关键授权/引用/报表用例真实失败；
3. 索引快照恢复后**内容/ACL/版本不一致**，或退役代残留导致越权可见（AT-030/AT-028 语义被破坏）；
4. L3 凭据大面积解密失败（密钥版本不一致）；
5. 观测显示错误率/队列在过去 15 分钟持续超基线且无稳定趋势（口径见[队列文档 §3](../operations/queue-backlog-and-resilience-observability.md)）；
6. 发现秘密泄漏进日志/响应/模板。

## 4. 回退

回退条件对应的**精确步骤**在[部署与回退说明 §回退](../deployment/deployment-and-rollback.md)：原则是
**反向执行**（前端产物 → 索引 → 密钥 → 文件 → MySQL），且 MySQL **不做二进制回退**：
已执行的 Flyway 迁移不回滚，回退代码 + 前滚/从备份恢复二者择一，数据损失窗口必须写进发布说明
（[06 §8](../ai-platform/06-upstream-upgrade.md)）。

## 5. 记录

每次升级的实跑记录（命令、退出码、判据结果、处置人、时间）按
[恢复演练报告模板](drill-report-template.md) 的字段填写；升级检查单不保留一次性日志/容器 ID/临时端口
（[08 §6](../ai-platform/08-testing-acceptance.md) 的发布证据口径）。

## 6. 未验证与待补（不得当作已完成）

| # | 项 | 状态 |
|---|---|---|
| 1 | 本检查单的真实执行 | **未执行**：无生产授权、无第二套环境、本切片不跑构建 |
| 2 | 交付包**出包实跑**证据（命令/退出码/清单摘要/SHA256SUMS） | 脚本已就位（`node deploy/package-delivery.mjs --offline`，结构见 [deploy/README.md](../../deploy/README.md)）；**实跑结果待补：见 Q09 证据 §交付包** |
| 3 | 恢复演练实测记录（耗时/抽验/数据窗口） | **待补：见 Q09 证据 §恢复演练**（恢复演练切片） |
| 4 | AT-030 的真实 Qdrant 演练 | 未验证：K01 只在测试里做过同实例快照恢复；跨实例/跨版本恢复未验证（K01 §5.5） |
| 5 | 密钥轮换（批量重加密） | 无工具、无演练 |
| 6 | `dependencies` 门禁 | 本机 Trivy 漏洞库不可达，未跑（环境缺口） |
| 7 | 生产真实模型端点/流式/工具回归 | 需真实凭据，属 M03/O04 范围 |
| 8 | 浏览器层的升级回归 | `smoke` 门禁需构建产物 + 浏览器；本机无后端时相关用例 skipped |
