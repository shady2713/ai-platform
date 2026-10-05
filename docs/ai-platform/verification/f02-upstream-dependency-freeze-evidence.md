# F02 上游依赖冻结：复验与交接证据

本文件是 [F02 验证并冻结第一组上游依赖](../tasks/F02.md) 的完成证据，格式按
[09-model-handoff.md](../09-model-handoff.md) §6 交接模板与 §8 完成证据要求。
兼容矩阵与 Go/No-Go 结论见 [ai-platform-upstream-candidates.md](../../integrations/ai-platform-upstream-candidates.md)（§1–§9 首次冻结、§10 复验）；
精确坐标/许可证/完整性/回退材料台账见 [upstream-registry.yaml](../../integrations/upstream-registry.yaml)。

## 1. 任务ID与状态

- 任务ID：**F02**（阶段 P0；需求 FR-01 / FR-19 / FR-34；依赖 F01）。
- 本会话状态：**待复核（REVIEW）**。`docs/ai-platform/tasks/index.json` 中 F02 的 `status` 仍是 `PLANNED`；
  该字段是规划事实源，不由本卡改写（09 §8）。
- 结论：第一组上游（Spring AI 1.1.8 / Qdrant v1.19.1 + 客户端 1.13.0 / Tika 3.2.3 + PDFBox 3.0.5 + POI 5.4.1）
  **冻结值成立（Go）**；1 项验收（HIGH/CRITICAL 扫描阻断）**未验证**，原因见 §7。

## 2. 工作副本与基线

- 工作副本（绝对路径）：`/home/ctyun/桌面/zhongtai/ai-platform`
- 基线 commit：`d4d14aa7d6250fdf4680e977acf1d3f9d7df0a97`（2026-09-26 21:16:57 +08:00，Q04）
- 本会话开始时工作树已有他人未提交改动（`AiErrorCodeConstants.java` 修改、`docs/acceptance/`、
  `.../service/publish/`、前端 `api/ai/evaluation` 与 `views/ai/evaluation`）。本卡**原样保留**，未触碰。
- 本卡允许修改范围（卡片 §2 原文摘录）：
  > - `后端代码/basic-framework-boot/basic-framework-dependencies/pom.xml`
  > - `docs/integrations`
  > - `.harness`
  >
  > 补充允许：同一变更对应的src/test、同目录*.test.ts/组件测试、所属模块README、必要的Convert映射，
  > 以及新增字段/权限的现有契约台账。这不授权改变其他模块内部实现。修改依赖版本、父POM、全局请求器或
  > Harness拓扑，必须已明确列入本卡，否则先更新任务范围。DB仅允许领取新迁移号和同步最新快照，禁止改历史迁移。

## 3. 变更文件清单

| 文件 | 动作 | 说明 |
|---|---|---|
| `docs/integrations/upstream-registry.yaml` | 新增 | 06 §3 要求的接入台账：8 个条目（Spring AI、2.x 候选、Spring AI Alibaba、Qdrant 服务端、Qdrant Java 客户端、tika-core、tika-parsers-standard-package、PDFBox/POI 链），逐条含坐标/版本/许可证来源/完整性证据/适配器/兼容测试/公告入口/升级触发/数据迁移/补丁/替代路径/回退材料，以及 `openItems` 与再验证触发条件 |
| `docs/integrations/ai-platform-upstream-candidates.md` | 修改 | 摘要表按复验结论修正（Qdrant Java 客户端「Go（坐标）／产品未消费」；Tika「Go（core）／parsers 未采用」）；§8 增补未验证项；新增 §10 复验记录（10.1–10.8） |
| `docs/integrations/f02-upstream-dependency-freeze-evidence.md` | 新增 | 本文件 |

未修改：`basic-framework-dependencies/pom.xml`（冻结值已在提交 `e26d968` 引入，K04 提交 `3e4fa13` 追加
pdfbox 3.0.5 / poi 5.4.1；本会话复验通过，无证据支持变更）、
`.harness/**`、任何共享文件（迁移、SQL 快照、`docs/contracts/**`、`enums/AiErrorCode*`、`PersistenceLifecycleIT`）。

工作副本内非交付的 Scratch（`.gitignore` 第 43 行 `/.local-state/`，Git 不跟踪）：
`.local-state/f02-upstream/`（本会话新增 `server-tree-20260926.txt`、`offline-probe/`、`sbom-generation*.log`、
`probe-20260926.log`、`mock-20260926.log`、`mock-requests.jsonl`；并保留 2026-09-16 归档）。
实验目录 `/tmp/f02-experiment`（卡片授权的实验目录，不进入交付仓库）。

## 4. 逐步实施对应（卡片 §3）

| 卡片步骤 | 本会话动作 | 结果 |
|---|---|---|
| 1. 核实 Spring AI 兼容维护线及安全公告，不默认 2.x | 拉取 Maven Central 元数据与 starter POM；GitHub API 逐条复核 16 条 spring-ai GHSA | 1.1.8 是 1.1 线末版；2.x 需 Boot 4.1.1 → No-Go（§10.2） |
| 2. 解析候选依赖树并做 Java17/Boot3.5 启动、文本/嵌入 mock 协议实验 | 对真实产品模块跑离线 `dependency:tree`；复跑实验工程启动与 mock 探针 | 退出码 0；启动 3.355s；请求体/请求头与首次记录一致（§10.3、§10.6） |
| 3. 评估 Qdrant 客户端与服务端组合、解析器依赖，输出版本/digest/license/SBOM 候选台账 | 镜像 digest/标签/实跑版本核验；客户端离线解析探针；module-ai CycloneDX SBOM | digest 一致、服务端 1.19.1 当前最新；SBOM 170 组件含全部冻结坐标（§10.4、§10.5、§10.7） |

## 5. 验证命令、目录、退出码

工作目录除注明外均为 `/home/ctyun/桌面/zhongtai/ai-platform/后端代码/basic-framework-boot`；
`JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`（Java 17.0.20）。

| # | 命令 | 退出码 | 关键输出 / 归档 |
|---|---|---|---|
| 1 | `./mvnw -o -pl basic-framework-server dependency:tree -Dverbose` | 0 | 817 行；spring-ai 全 1.1.8、Boot 全 3.5.16、Jackson 2.21.4（annotations 2.21）、Netty 全 4.2.17.Final、tika-core 3.2.3、pdfbox 3.0.5、poi 5.4.1；无 gRPC/protobuf/qdrant 客户端；冲突收敛 6 类。归档 `.local-state/f02-upstream/server-tree-20260926.txt` |
| 2 | `./mvnw -o -f .local-state/f02-upstream/offline-probe/qdrant/pom.xml dependency:resolve` | 0 | `io.qdrant:client:1.13.0` 离线可解析（gson 2.10.1 经 grpc-core 传递且有 jar） |
| 3 | 同上（tika 探针）`-f .../offline-probe/tika/pom.xml` | 1 | `Cannot access aliyun-public ... in offline mode and the artifact org.apache.tika:tika-parsers-standard-package:jar:3.2.3 has not been downloaded` |
| 4 | `./mvnw -o -f /tmp/f02-experiment/probe/pom.xml spring-boot:run`（后台） | 启动成功 | `Started ProbeApplication in 3.355 seconds`，端口 18091；`GET /probe/text` → `pong from mock`；`GET /probe/embed` → `dim=4 values=0.125,-0.25,0.5,0.75` |
| 5 | mock 侧记录 `/tmp/f02-experiment/mock-requests.jsonl` | — | `Transfer-encoding: chunked`、`Authorization: Bearer <redacted>`；chat 体 `{messages:[{content:"ping",role:"user"}],model:"probe-model",stream:false,temperature:0.0}`；embeddings 体 `{input:["hello embedding"],model:"probe-embedding"}` |
| 6 | `./mvnw -o org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom -pl basic-framework-module-ai ...` | 1 | `Goal requires online mode for execution but Maven is currently offline` |
| 7 | 同上去掉 `-o`（联网重试一次） | 0 | `basic-framework-module-ai/target/f02-ai-bom.json`（CycloneDX 1.6，170 组件；spring-ai 1.1.8 / tika-core 3.2.3 / pdfbox 3.0.5 / poi 5.4.1 均 Apache-2.0） |
| 8 | `curl repo1.maven.org/.../spring-ai-bom/maven-metadata.xml` | 0 | 1.1.8 后无 1.1.x；latest/release=2.1.0-M1；lastUpdated=20260924 |
| 9 | `curl .../spring-ai-starter-model-openai/2.0.1/...pom` 与 `.../1.1.8/...pom` | 0 | 2.0.1 → `spring-boot-starter-webclient/restclient:4.1.1`；1.1.8 → `spring-boot-starter:3.5.15` |
| 10 | `gh api /advisories/GHSA-...` ×16（spring-ai）+ 2（tika）+ 4 个包查询 | 0 | 16 条 spring-ai 公告全部存在且区间/修复阈值与台账一致；tika 两个 critical 于 3.2.2 修复；`io.qdrant:client` 无公告；pdfbox/poi 区间不覆盖 3.0.5/5.4.1 |
| 11 | `docker images --digests` + `docker image inspect qdrant/qdrant:v1.19.1` | 0 | digest `sha256:12364fe851b9f17356fc88189fc06d1b521262e04659ec7345975b00c9246a10`；label `image.version=v1.19.1` |
| 12 | `docker run -d --rm -p 16333:6333 qdrant/qdrant:v1.19.1` + `curl /` | 0 | `{"title":"qdrant - vector search engine","version":"1.19.1","commit":"6ab21cac18ebb6f4ae29102c7f8f5cc11affd5de"}`；容器已删除 |
| 13 | `gh api repos/qdrant/qdrant/releases/latest` | 0 | v1.19.1（2026-09-04）＝当前最新稳定版 |
| 14 | `sha1sum` 本地 jar vs `repo1.maven.org/<artifact>.sha1` | 0 | spring-ai-model、spring-ai-openai、io.qdrant:client、tika-core 四包 sha1 三方一致（本地/m2 记录/Central 公布） |

未执行（遵硬约束）：`.harness/verify.sh`（整套门禁）、`mvn clean verify`、`-Pintegration` 集成测试。

## 6. 兼容矩阵与 Go/No-Go（结论）

| 上游 | 冻结值 | 结论 | 关键依据 |
|---|---|---|---|
| Spring AI | 1.1.8（bom import） | **Go** | 1.1 线末版；随平台 Boot 3.5.16 解析单版本；1.1 线 16 条公告最高修复阈值即 1.1.8 |
| Spring AI 2.x | 2.0.1（候选） | **No-Go** | starter POM 要求 Boot 4.1.1，与平台 Boot 3.5 线不兼容 |
| Qdrant 服务端 | qdrant/qdrant:v1.19.1@sha256:12364fe… | **Go** | 当前最新稳定版；digest 本机核验一致；实跑版本 1.19.1 |
| Qdrant Java 客户端 | io.qdrant:client:1.13.0 | **Go（坐标）／产品未消费** | Spring AI 1.1.8 钉定；离线可解析；产品走 REST（K01）。与 K01 文档的 gson 缺口记载不一致，待主管复核 |
| Tika core | 3.2.3 | **Go** | 依赖树/SBOM 单一版本；两个 CRITICAL 已覆盖；jar 完整性经 Central 校验和比对 |
| tika-parsers-standard-package | 3.2.3（BOM 声明） | **不可用（pending）** | 离线不可解析且未被消费；正文解析用 PDFBox 3.0.5 + POI 5.4.1 |
| Boot/Jackson/Netty/gRPC 冲突 | — | **Go** | 产品树 Boot/Jackson/Netty 无版本分裂；gRPC 仅在未消费的客户端坐标探针中为单一 1.65.1 |
| HIGH/CRITICAL 扫描阻断 | — | **未验证** | Trivy 漏洞库镜像不可达，`dependencies` 门禁未执行 |

## 7. 逐项验收结果（卡片 §4）

1. **Boot/Jackson/Netty/gRPC 版本冲突检测** — 通过（命令 1、2）。
   产品依赖树 Boot 3.5.16 单一、Jackson 2.21.4 + annotations 2.21 单一、Netty 全部 4.2.17.Final；
   冲突收敛 6 类（error_prone_annotations 2.49.0→2.41.0、objenesis 3.4→3.3 为注解/反射类降级，无运行时影响；
   commons-io→2.20.0、commons-compress→1.27.1、mybatis-spring→3.0.5 为常规收敛）。
   gRPC 不在产品树中（AI 模块不消费 Qdrant 客户端），其版本一致性在客户端坐标探针中验证为 1.65.1 全线统一。
2. **HIGH/CRITICAL 扫描阻断** — **未验证**。`dependencies` 门禁在本机因漏洞库镜像不可达无法运行（环境缺口，主管已知）；
   本卡以 GitHub Advisory 逐条核对作为部分替代（全部 1.1 线公告修复阈值 ≤1.1.8；tika 两个 critical 于 3.2.2 修复；
   qdrant 客户端无公告；pdfbox/poi 不受影响区间覆盖）。这不等价于 Trivy 的 HIGH/CRITICAL 阻断结论。
3. **所有待定版本不可标 verified** — 通过。台账中 `tika-parsers-standard-package` = `pending`，
   `spring-ai-2x` = `no-go`，`spring-ai-alibaba` = `not-adopted`，Qdrant Java 客户端 = verified(坐标)/pending(消费通道)，
   `openItems.trivy-scan` = `pending`；其余条目标 verified 且均附可复现证据。

## 8. 契约与影响差异

- API / 字段 / 权限 / 状态机 / 迁移：**无变化**（本卡只冻结与复验上游坐标，未新增或修改任何契约文件）。
- 上游依赖版本差异：无（冻结值 2026-09-16 已落地，本会话复验未改 POM）。
- 新增本平台工程约束（来自实验观测，供 F09 等后续卡使用）：Spring AI OpenAI 客户端以
  `Transfer-Encoding: chunked` 发送请求体，受控外部 HTTP 边界与任何网关不得假设固定 `Content-Length`。

## 9. 未验证项、阻塞与剩余风险

| 项 | 状态 | 原因 |
|---|---|---|
| Trivy HIGH/CRITICAL 扫描（`dependencies` 门禁） | 未验证 | 本机漏洞库镜像不可达（环境缺口） |
| Qdrant 多租户 ACL 物理隔离、跨实例/跨版本快照恢复、TLS 端到端 | 未验证 | K01 §5 / K02 范围 |
| Qdrant gRPC 通道端到端 | 未验证 | 产品未消费该通道（K01 选 REST） |
| 真实模型端点、流式、工具调用、结构化输出 | 未验证 | M03/O04 范围；本卡仅 mock 协议 |
| PDFBox/POI 等解析链 jar 完整性 | 未验证 | 未逐包与 Central 校验和比对（仅 4 个关键包比对） |
| 发布物 LICENSE/NOTICE 归档 | 未验证 | 打包阶段处理 |
| K01 文档与复验结论不一致（gson 缺口） | 待主管复核 | 超出本卡允许路径，未修改 K01 文档 |

剩余风险：Spring AI 1.1 线若停止安全维护，将缺少受支持的 1.1 后继版本（届时必须立项评估 Boot 4 + Spring AI 2.x
迁移，不能静默切换）；Qdrant 服务端未升级演练前不得升级，且禁止旧镜像挂载已升级数据目录。

## 10. 需要主管串行完成的共享文件改动

- 本卡**未**修改任何共享文件（迁移、`数据库文件/basic_framework.sql`、`docs/contracts/*`、
  `enums/AiErrorCode*`、`PersistenceLifecycleIT`），因此**无必需的共享补丁**。
- 可选（非阻塞）：若主管希望证据文档落在标准位置 `docs/ai-platform/verification/`（该路径不在卡片 §2 允许范围内，
  故本卡写在允许的 `docs/integrations/`），可执行：
  `cp docs/integrations/f02-upstream-dependency-freeze-evidence.md docs/ai-platform/verification/f02-upstream-dependency-freeze-evidence.md`
  并按仓库约定补充该目录的 README/索引条目。
- 建议（不属本卡）：复核并统一 `docs/integrations/ai-platform-knowledge-index-qdrant.md` §1 关于
  `io.qdrant:client:1.13.0`「离线缺 gson jar」的记载与本次复验结论（§10.4）。

## 10.b 主管复核记录（2026-09-27）

- 本证据由 F02 执行代理产出，主管复核后归档到 `docs/ai-platform/verification/`（卡片 §2 允许的是
  `docs/integrations`，标准证据目录由主管按仓库约定代管）。
- 主管独立抽查：`basic-framework-dependencies/pom.xml` 的 `spring.boot.version=3.5.16`、
  `spring-ai.version=1.1.8` 与台账一致；`upstream-registry.yaml` 结构完整（`components` 8 条 +
  `openItems` + `revalidationTriggers`，非占位文件）。
- 门禁：本卡只改文档，`sh .harness/verify.sh contracts` 在本卡产物存在时两次运行均 **exit 0**
  （含厂商/锁文件/生命周期/字段目录等检查）。
- **未验证项（阻断"完全 DONE"）**：`sh .harness/verify.sh dependencies`（Trivy HIGH/CRITICAL 扫描阻断）
  在本机因漏洞库镜像不可达无法执行；GHSA 逐条核对只是部分替代，不等价。该项需在有漏洞库的环境补齐，
  补齐前本卡按"证据齐备 + 扫描未验证"归档。

## 11. 下一张可领取任务及其前置证据

- 前置证据（本卡已提供）：冻结坐标与 Go/No-Go 结论、台账 `upstream-registry.yaml`、复验证据与未验证清单。
- 可领取：**F03**（新增后端 AI 模块及边界门禁）依赖 F02 —— 其消费的 spring-ai/tika/pdfbox/poi 坐标已由本卡验证；
  后续 K01/K05 若启用 Qdrant Java 客户端通道，必须先重跑组合验证并更新 `upstream-registry.yaml` 的
  `qdrant-java-client` 条目。
- 未通过项（Trivy 扫描）应在具备漏洞库的环境由主管在阶段门禁处补齐。
