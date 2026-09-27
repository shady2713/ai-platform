# Q08 升级演练方案与一次实际演练记录

| 项目 | 内容 |
|---|---|
| 任务卡 | [Q08 建立升级台账与兼容回归包](../ai-platform/tasks/Q08.md) |
| 本文件回答什么 | 一次可照着复跑的"升级 + 回退"演练：**改什么 / 跑什么 / 看什么 / 何时回退 / 怎么回退（精确到命令）**，外加**迁移编号碰撞**的规则、检测与处置；并如实记录 2026-09-27 本机实际执行到哪一步 |
| 演练对象 | 与冻结台账一致：Spring AI 1.1.8（后端）、`@antv/g2` 5.4.8（前端）；外加 Flyway 迁移编号碰撞 |
| 本切片边界 | 本切片**只写文档与台账**，不写代码、不跑 Maven/vitest（另两个切片负责测试与执行）。因此"版本置换"类步骤在本文件中标注**未执行**，只给可复跑步骤；实际执行的只有只读核验与静态检测（§2） |
| 关键前置结论 | 2026-09-27 探测：两个上游**当前都没有更高候选**（§1），因此本轮演练的实际内容是"**回退方向 + 碰撞阻断**"，而不是"升上去再降回来" |
| 关联 | 清单：[ai-platform-compatibility-artifact-ledger.md](../integrations/ai-platform-compatibility-artifact-ledger.md)；验收：[q08-acceptance-evidence.md](q08-acceptance-evidence.md) |

## 1. 候选可用性探测（2026-09-27 实测，决定演练走哪个方向）

| 探测 | 命令 | 实测结果 |
|---|---|---|
| AntV/G2 是否有更高补丁 | `curl -sS https://registry.npmjs.org/@antv%2Fg2` | `dist-tags.latest = 5.4.8`，5.x 版本止于 **5.4.8**（即当前冻结值）；5.4.8 的 tarball `integrity = sha512-IvgIpwmT4M5/QAd3Mn2WiHIDeBqFJ4WA2gcZhRRSZuZ2KmgCqZWZwwIT0hc+kIGxwYeDoCQqf//t6FMVu3ryBg==`，与 `pnpm-lock.yaml` 第 1643 行逐字一致 |
| Spring AI 1.1 线是否有新补丁 | `curl -sS https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/maven-metadata.xml` | 1.1.x 止于 **1.1.8**（无 1.1.9）；`latest=release=2.1.0-M1`（2.x 需 Boot 4，registry 已判 `no-go`）；`lastUpdated=20260924091844` |
| 本机出网状态 | `curl -sS -o /dev/null -w '%{http_code}' --max-time 8 <上面两个 URL>` | npm registry **200**（5.39 s）、Maven Central **200**（0.58 s）。**今天有出网**，"无出网"不是本切片未执行升级的原因；未执行的原因是本切片的纪律边界（只写文档，不跑构建/测试） |

结论：**补丁级"升级"当前无候选可升**；两个上游都在其维护线末版。升级触发条件已登记在 registry
（`upgradeTriggers`）——上游一发新补丁，就按 §3/§4 的步骤执行。本轮把可离线完成的部分（回退材料、碰撞阻断）
做成一次**实际演练（R1，§2）**，其余标记未执行。

## 2. 演练 R1（已执行）：冻结对的回退材料盘点 + 迁移编号碰撞静态检测

执行时间/环境：2026-09-27，`/home/ctyun/桌面/zhongtai/ai-platform`；只读命令，未改动任何文件、未跑构建。

### 2.1 步骤与实测输出

| # | 步骤 | 命令 | 实测结果 |
|---|---|---|---|
| 1 | 后端旧包是否在本地仓库 | `ls ~/.m2/repository/org/springframework/ai/spring-ai-bom/`、`…/spring-ai-model/` | 只有 `1.1.8`；本地仓库 21 个 `org.springframework.ai` 构件全部只有 1.1.8 |
| 2 | 冻结字节核验 | `sha1sum` 三个关键 jar | `spring-ai-model-1.1.8`=`9320c4c8…`、`tika-core-3.2.3`=`4b1b82f8…`、`io.qdrant:client:1.13.0`=`4ca2a42f…`，与 registry 记录一致 |
| 3 | 容器与旧镜像 | `docker images --digests \| grep qdrant`、`docker ps` | `qdrant/qdrant:v1.19.1` digest 与 registry 一致；无运行中容器；**没有更旧镜像** |
| 4 | 前端锁定与体积基线 | `grep -n "@antv/g2" pnpm-lock.yaml`、`ls -l apps/ai-chat/dist/assets/` | 锁文件 5.4.8 integrity 与 npm 公布值一致；`vendor-antv-1_Bud82i.js = 1,309,384 B`（sha256 `3bb51648…`，`gzip -c \| wc -c` = 387,181 B）——与 F04 §3.1 的字节数完全一致，可直接用作升级体积基线 |
| 5 | 前端能否离线回到当前版本 | `ls node_modules/.pnpm/ \| grep '^@antv+g2'` | `@antv+g2@5.4.8` 在本地，回退到**当前版本**可离线完成；回退到**其他版本**需要 store 里有对应 tarball（当前没有） |
| 6 | 迁移编号碰撞静态检测 | `ls 后端代码/.../db/migration/*.sql \| sed 's/.*\/V\([0-9]*\)__.*/\1/' \| sort -n \| uniq -d` | 输出为空（无重复编号）；共 **82** 个迁移文件，最大编号 **V83** |
| 7 | 快照声明与迁移链一致 | `grep -n "Snapshot note" 数据库文件/basic_framework.sql` | `-- Snapshot note: aligned with the authoritative Flyway migration chain through V83.` 与最大编号一致 |
| 8 | SDK 产物与基线夹具 | `sha256sum packages/ai-embed-sdk/dist/ai-embed-sdk-5.6.0.js`；读 `fixtures/n-1/baseline.json` | 产物=`55d9c284…`（2026-09-27 12:43 重建）≠夹具冻结值 `09090b4b…`；夹具测试只做格式断言（清单 §3.5） |

### 2.2 R1 结论

1. **回退到"当前冻结版本"的路径成立**（jar/镜像/前端包都在本机且哈希一致）；**回退到"上一个版本"的路径不成立**：
   - 后端：本地 m2 没有 1.1.7/1.1.6 —— registry 的 `rollback.materials` 表述与现状不符，须更正或在有网环境预置；
   - 前端：只有 5.4.8；
   - Qdrant：没有旧镜像与旧集合快照（registry `pendingDigest`）。
2. **迁移链当前无碰撞**（编号唯一、快照同步），碰撞检测机制见 §5；但"已执行迁移被改写会被阻断"目前只有
   Flyway 配置与 history 断言在防，**没有一条专门的拒绝测试**（这是 §5.4 的待补项，也是 AT-072 的注入演示位）。
3. 因此"可执行回退材料"清单需要按 §3.5/§4.5 的写法补齐，否则发布物清单里的"恢复方案"名不副实。

## 3. 演练 R2（方案就绪，未执行）：AntV/G2 补丁级升级与回退

选择理由：完全在本地 monorepo 内、无需 MySQL/Redis/模型端点、失败面清晰（体积/CSP/渲染/契约），是最适合做常规演练的一项。**本轮未执行**（无候选版本 + 本切片不跑前端命令）。

### 3.1 改什么（只允许改两处）

| 文件 | 改动 | 约束 |
|---|---|---|
| `前端代码/basic-framework-admin/pnpm-workspace.yaml` | catalog 行 `'@antv/g2': 5.4.8` → 目标版本（该行注释已写明"升级需重跑 ChartRenderer 验证（CSP/体积/生命周期）"） | 只改这一行；不允许各包各自写版本 |
| `前端代码/basic-framework-admin/pnpm-lock.yaml` | 由 `pnpm install` 自动重算（`@antv/g2@x.y.z` 的 `resolution.integrity` 必须变化） | 必须同步提交锁文件，否则 lockfile 门禁失败 |

不允许：改 `packages/ai-chat-ui` 适配代码去"适配"新版本行为（那是升级的一部分，需单独评审）；引公共 CDN。

### 3.2 跑什么（工作目录 `前端代码/basic-framework-admin`，按顺序）

```bash
pnpm install                                   # 重算锁文件；integrity 变化必须出现在 diff 里
pnpm -F @vben/ai-chat run build                # 生产产物
node apps/ai-chat/scripts/verify-built-chat.mjs # 分包/体积/DOM 真实渲染（脚本自带断言与退出码）
node apps/ai-chat/scripts/check-public-cdn.mjs  # 禁公共 CDN（含未登记外部域名）
pnpm exec vitest run --dom packages/ai-chat-ui/src/chart packages/ai-chat-ui/src/report
pnpm run check:type                             # 38 个包 typecheck 契约
node scripts/check-typecheck-contract.mjs       # 前端仓库内路径：前端代码/basic-framework-admin/scripts/
```

（`tests/compatibility` 由前端切片补齐后，把该目录一起加进 vitest 目标。）

### 3.3 看什么（断言与基线数字）

| 观察项 | 通过标准 | 当前基线（2026-09-27 实测） |
|---|---|---|
| 图表分包存在且懒加载 | `verify-built-chat.mjs` 第 2 段断言通过（vendor-antv 非空、由入口动态 import、未被预加载） | `vendor-antv-1_Bud82i.js` = 1,309,384 B；注意 C02 曾出现 **0.00 kB 空 chunk** 的真实回归，必须看字节数而不是"文件存在" |
| 体积预算 | vendor-antv 增幅在评审通过的预算内（建议 ≤5% 且必须有解释）；gzip 同步对比 | 1,309,384 B / `gzip -c` 387,181 B（F04 报告口径为 388,290 B，压缩实现不同，比较时用同一命令） |
| 渲染与降级 | DOM 断言：图表块渲染或按适配层降级为可读表格 | `verify-built-chat.mjs` 第 3 段（当前 DOM 环境无 canvas → 降级表格） |
| CSP | 产物中不新增 `eval(`；`new Function(` 命中数不增（现为 d3-dsv 1 处，数据路径不可达，F04 §3.3） | 0 处 `eval(`，1 处 `new Function(` |
| 恶意标题/注入 | 标题类文本仍走文本插值，无脚本执行 | `chart-adapter.test.ts` 现有断言 |
| 契约 | ChartSpec/ReportSpec 夹具与类型不变 | `protocol-fixtures.test.ts`、`report-view.test.ts` |
| 禁公共 CDN | 扫描退出码 0，无未登记外部域名 | 313 文件 0 命中（F04 §3.2） |

### 3.4 回退条件（任一命中即回退，不许"带病发布"）

1. `verify-built-chat.mjs` 或 `check-public-cdn.mjs` 退出码非 0；
2. vendor-antv 体积超预算，或分包结构改变（不再懒加载）；
3. `vitest` 图表/报表用例失败；`check:type` 失败；
4. 产物新增 `eval(` 或 CSP 相关回归；
5. 锁文件 integrity 未变化（说明装的还是旧包，升级没生效）。

### 3.5 回退步骤（精确命令）

```bash
cd 前端代码/basic-framework-admin
git checkout -- pnpm-workspace.yaml pnpm-lock.yaml   # 只回退这两处（升级改动必须只在这两处）
pnpm install --frozen-lockfile --ignore-scripts       # lockfile 门禁口径
pnpm -F @vben/ai-chat run build
node apps/ai-chat/scripts/verify-built-chat.mjs       # 产物回到基线（vendor-antv 1,309,384 B）
node apps/ai-chat/scripts/check-public-cdn.mjs
```

可离线完成（`@antv+g2@5.4.8` 在本地 store，§2.1 步骤 5 已核）；回退不涉及数据库、不涉及服务端。
**窗口说明**：前端资源回退只影响静态产物，晚到的浏览器可能仍缓存新 chunk（产物文件名带 hash，风险有界）；
无需数据补偿。

## 4. 演练 R3（方案就绪，未执行）：Spring AI 1.1.x 补丁级升级与回退

### 4.1 改什么（只允许改一处）

| 文件 | 改动 |
|---|---|
| `后端代码/basic-framework-boot/basic-framework-dependencies/pom.xml` | 第 64 行 `<spring-ai.version>1.1.8</spring-ai.version>` → 目标补丁（BOM import 统一生效；业务 POM 不得各自写版本） |

### 4.2 跑什么（工作目录 `后端代码/basic-framework-boot`，`JAVA_HOME` 用 JDK17）

```bash
export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64
./mvnw -o -pl basic-framework-server dependency:tree -Dverbose   # 单一版本收敛、无 Boot/Jackson/Netty 分裂
./mvnw -o -pl basic-framework-core/basic-framework-spring-boot-starter-ai test   # 端隔离/流/工具/结构化/嵌入/重试
./mvnw -o -pl basic-framework-module-ai test                                     # 消费者侧（M03/O04 路径）
./mvnw -o -pl basic-framework-module-ai-api test                                 # 协议 DTO/夹具
# 有 Docker 与测试数据时：
./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiModelEndpointPersistenceIT,AiModelProbePersistenceIT'
# 阶段门禁（需要漏洞库可达）：
sh .harness/verify.sh backend
sh .harness/verify.sh dependencies
```

### 4.3 看什么

| 观察项 | 通过标准 |
|---|---|
| 依赖收敛 | `dependency:tree` 中 `org.springframework.ai` 全树单一版本；Boot 3.5.16 / Jackson / Netty 不分裂（现有基线见 F02 §10.3） |
| 适配器行为 | starter-ai 全部用例通过；结构化输出/嵌入维度/工具参数/取消与错误映射不回归 |
| 固定问题集对比 | 同一固定问题集输出行为差异可解释（M03/O04 的评测口径） |
| 泄漏面 | 日志/数据库无 token/secret/正文（既有安全断言） |
| 门禁 | `backend` 通过；`dependencies` 无 HIGH/CRITICAL（当前 Trivy 环境缺口，见验收证据 §3） |

### 4.4 回退条件与步骤

回退条件：任一后端用例失败、依赖树出现版本分裂、结构化输出/流式协议改变、`dependencies` 出现 HIGH/CRITICAL。

```bash
cd 后端代码/basic-framework-boot
# 把 pom.xml 第 64 行版本改回 1.1.8（或 git checkout -- basic-framework-dependencies/pom.xml）
./mvnw -o -pl basic-framework-core/basic-framework-spring-boot-starter-ai test
./mvnw -o -pl basic-framework-module-ai test
./mvnw -o -pl basic-framework-server dependency:tree -Dverbose | grep -c 'spring-ai.*1.1.8'
```

**回退窗口与限制（必须写进发布说明）**：Spring AI 是编译期依赖，回退=重新构建发布新补丁包，
不是运行期开关；已发生的对话/索引数据不回滚；升级若同时包含嵌入模型变化，向量空间按 06 §8 走新 generation。
**当前阻断**：本地 m2 只有 1.1.8（§2.1 步骤 1），离线回退到 1.1.7 不可行——执行本演练前必须先把目标旧包
预置进本机仓库/私有镜像：在**联网**环境下把目标版本写进 `pom.xml` 后跑一次不带 `-o` 的
`./mvnw -pl basic-framework-server -am dependency:go-offline`（或 `dependency:resolve`），
让该版本的 BOM 与全部 `spring-ai-*` 构件进入本地仓库，再补 registry 的 `rollback.materials`。

### 4.5 未执行部分与原因

| 未执行 | 原因 | 有环境时的执行顺序 |
|---|---|---|
| 版本置换、`dependency:tree`、后端用例、`verify.sh backend/dependencies` | 本切片纪律：只写文档与台账，不跑 Maven/vitest | §4.1 → §4.2 → §4.3，全部退出码写入验收证据 |
| 真实模型端点/流式/工具回归 | 需模型凭据与端点（M03/O04 范围） | 先跑 mock 协议，再在预发布环境跑真实端点 |

## 5. 迁移编号碰撞：规则、检测与处置（引用既有规则，不新造）

### 5.1 平台规则（事实源）

- 迁移编号流程（F08 冻结，见 [f08-error-code-migration-evidence.md](../ai-platform/verification/f08-error-code-migration-evidence.md) §2 第 3 条与
  [docs/data-lifecycle.md](../data-lifecycle.md)"AI 中台表策略"）：AI 表与框架**共用同一 Flyway 序列**，领取**下一个可用编号**
  （首个 AI 建表迁移从 V48 起）；**一卡一号、禁止预占未来编号、禁止修改历史迁移**；每次新增迁移必须在同一变更内同步
  `数据库文件/basic_framework.sql` 快照，否则 contracts 门禁失败。
- 基础框架同步（[06-upstream-upgrade.md](../ai-platform/06-upstream-upgrade.md) §6 第 4 条）：检查迁移版本碰撞；
  **仅尚未发布的迁移允许重编号；已执行迁移内容与编号冻结**，按新 ADR 规划后续迁移；禁止 ours/theirs 整片覆盖。
- Q08 卡 §3 第 5 条与 §4：对已执行迁移碰撞做**新增迁移设计，不重写旧 SQL**；"错误迁移覆盖、删必需字段或新增危险依赖能被阻断"。

### 5.2 检测（三层，当前状态）

| 层 | 机制 | 现状 |
|---|---|---|
| 静态（本切片已执行） | 编号唯一性：`ls .../db/migration/*.sql \| sed 's/.*\/V\([0-9]*\)__.*/\1/' \| sort -n \| uniq -d`（空=无碰撞）；快照声明必须等于最大编号 | 82 文件 / max V83 / 无重复 / 快照 `through V83` 一致（§2.1 步骤 6–7） |
| 门禁 `contracts` | `node scripts/check-data-lifecycle.mjs`：快照声明版本必须与最新 Flyway 迁移一致，表/逻辑删除列/物理外键集合逐项比对；`node --test scripts/check-data-lifecycle.test.mjs` 是它的拒绝测试 | 规则与失败消息见 `scripts/check-data-lifecycle.mjs` 第 120–134 行；F08 §3 已记录一次真实拒绝演示（临时迁移未同步快照 → 3 项 FAIL） |
| 启动/集成期 | Flyway `validate-on-migrate: true`、`clean-disabled: true`、`baseline-on-migrate: false`（`application.yaml` 第 26–32 行）：**已执行迁移的校验和变化或编号重复会让启动失败**；`PersistenceLifecycleIT` 断言 `flyway_schema_history` 成功条数 = 迁移文件数、最后版本 = 最大文件版本（`MigrationTestSupport` 从 classpath 资源推导，不维护常量） | 机制存在；**尚未有专门针对"改写已执行迁移"的拒绝测试**（§5.4） |

### 5.3 碰撞时怎么处置（决策表）

前提：碰撞 = 上游基线带来的迁移与本地 AI 迁移占用了同一编号（或同一编号内容不同）。

| 情形 | 处置 | 禁止 |
|---|---|---|
| 本地该编号迁移**尚未发布/未在任何环境执行** | 本地迁移重编号为"下一个可用编号"，同步快照与台账（同一变更内完成），README/证据里记录旧→新编号映射 | 直接覆盖上游文件；改已经开始使用的编号 |
| 本地该编号迁移**已发布/已执行** | 编号与内容冻结：保留历史，改为**新增迁移**承载所需变更（新编号 = 下一个可用）；若上游文件占用了同一编号，把冲突上报框架提供方（用户），由其在**只读源框架**侧重编号——本仓库不改只读源 | 改写历史 SQL、删 `flyway_schema_history`、用 `clean` 重来、ours/theirs 整片覆盖 |
| 上游迁移与本地迁移**内容相同但编号不同** | 保留双方各自编号（Flyway 按编号执行，重复内容无害），不合并 | 手工删其中一条 |
| 上游删除了本地依赖的表/列 | 按 06 §6 第 5 条：确认 AI 依赖是否仍合法；不通过"恢复已删除死代码"维持编译 | 用历史迁移把上游删除动作"改回来" |

### 5.4 一次"错误覆盖被阻断"的注入演示（可复跑步骤，本切片未执行）

目的：给 AT-072 一条可复核的拒绝证据（当前只有机制、无演示）。需要一个 Docker/MySQL 环境与一次完整启动。

1. **改写已执行迁移**：把某个已应用的迁移文件追加一行无害注释 → `./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test=PersistenceLifecycleIT`
   预期：Flyway 校验和不匹配，启动失败（`Migration checksum mismatch`），用例不通过；
2. **编号碰撞**：临时新增 `V83__probe_collision.sql`（与既有 V83 同号）→ 同一命令预期：Flyway 报重复版本，启动失败；
3. **快照漂移**：`node scripts/check-data-lifecycle.mjs` 预期：`FAIL 快照声明版本必须与最新 Flyway 迁移 V84 一致，当前：83`（消息格式见脚本）；
4. 还原：`git checkout -- <被改文件>`、删除探针迁移，重跑 `node scripts/check-data-lifecycle.mjs` 与
   `./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test=PersistenceLifecycleIT` → 恢复绿色。

> 说明：第 1 步的"改写历史迁移"只允许在**一次性探针**中使用并立即还原；任何提交都不允许包含对已执行迁移的修改。
> 该演示命令同时是 AT-072 在 `-Pintegration` 环境下的正式执行路径。

### 5.5 新增迁移的同步清单（同一变更内必须一起改）

| 文件/台账 | 要求 |
|---|---|
| `后端代码/basic-framework-boot/basic-framework-server/src/main/resources/db/migration/V<N>__*.sql` | 只新增；编号唯一；不修改历史文件 |
| `数据库文件/basic_framework.sql` | 快照说明改为 `through V<N>`，表/列/外键与迁移一致（否则 `check-data-lifecycle.mjs` 失败） |
| `docs/contracts/data-lifecycle.json` | 新表登记生命周期策略与逻辑删除列 |
| `docs/contracts/data-permission-exemptions.json` | 新表登记权限分类（禁止"未分类表"） |
| `docs/contracts/ai/error-code-map.md` / `AiErrorCodeConstants` | 如新增错误码，两侧同步（F08 规则） |
| 集成测试期望 | `PersistenceLifecycleIT` 的表清单等**从文件推导**的断言无需改常量；显式列出的期望清单（若该卡新增）需同步 |
| 本文件 §5.2 静态检测 | 提交前跑一次编号唯一性命令，贴进交接记录 |

## 6. 执行/未执行总表与后续最短路径

| 项 | 状态 | 证据 |
|---|---|---|
| 候选版本探测（npm/Maven 元数据 + 出网） | **已执行** | §1 |
| 回退材料盘点（m2/镜像/前端 store） | **已执行** | §2.1 步骤 1–5 |
| 迁移碰撞静态检测 + 快照一致 | **已执行** | §2.1 步骤 6–7 |
| AntV/G2 版本置换（R2）与回退 | **未执行**（无候选 + 本切片不跑前端） | §3 |
| Spring AI 版本置换（R3）与回退 | **未执行**（无候选 + 本切片不跑 Maven；且本地无旧包，回退先要补材料） | §4 |
| 迁移碰撞注入演示 | **未执行**（需 MySQL/`-Pintegration`，且改历史迁移只允许一次性探针） | §5.4 |
| 上游发新补丁后的最短执行顺序 | 1) 更新 registry 的 `selected`/`integrity`/`compatTests`；2) 按 §3.2 或 §4.2 改一处版本并跑定向命令；3) 按 §3.4/§4.4 判定；4) 更新验收证据与产物清单的验证类别（首次出现"真实旧产物验证"） | — |

**口径提醒**：在上述置换真正执行之前，任何"已升级/已兼容历史版本"的表述都不成立；
兼容结论一律按 FR-34 报告为"**首发基线夹具验证**"（见 [q08-acceptance-evidence.md](q08-acceptance-evidence.md) §1）。
