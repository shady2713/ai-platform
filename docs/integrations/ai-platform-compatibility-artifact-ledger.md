# AI 中台兼容产物清单（Q08 台账切片）

| 项目 | 内容 |
|---|---|
| 任务卡 | [Q08 建立升级台账与兼容回归包](../ai-platform/tasks/Q08.md) |
| 本文件回答什么 | 冻结的上游坐标 → 适配器落点 → **兼容回归入口**（可执行命令/测试路径）的一对一映射，并逐条标注**验证类别** |
| 不重复登记 | 坐标、版本、digest、license 来源、公告入口、回退材料以 [upstream-registry.yaml](upstream-registry.yaml) 为唯一事实源；本文只补"适配器位置 + 回归入口 + 验证类别"，不复制其字段 |
| 配套证据 | F02：[f02-upstream-dependency-freeze-evidence.md](f02-upstream-dependency-freeze-evidence.md)；F04：[f04-frontend-chart-integration-evidence.md](f04-frontend-chart-integration-evidence.md)；本轮演练与 AT 结论：[docs/upgrades/q08-upgrade-rehearsal-and-rollback.md](../upgrades/q08-upgrade-rehearsal-and-rollback.md)、[docs/upgrades/q08-acceptance-evidence.md](../upgrades/q08-acceptance-evidence.md) |
| 编写时间 / 工作副本 | 2026-09-27，`/home/ctyun/桌面/zhongtai/ai-platform` |

## 1. 验证类别口径（先读，决定每条结论能不能写"已验证兼容"）

按 FR-34（[02-product-requirements.md](../ai-platform/02-product-requirements.md) 第 284–288 行）：

- **基线夹具验证**：首次发布没有真实历史（N-1）产物，只能冻结**首发候选**的协议行为/坐标作为未来兼容夹具。报告口径必须写成"**首发基线验证**"，不得声称"已验证历史发布版本"。
- **真实旧产物验证**：拿**实际已发布过的旧版本产物**（旧 jar/镜像/锁文件/SDK 文件）与新版本联调。需要仓库/制品库中真实存在该旧产物。
- **版本/装配验证**：只证明"当前冻结坐标可解析、装配正确、单版本收敛"，不证明跨版本兼容，也不等于真实模型端点回归。

> **本清单当前结论（2026-09-27）**：全部条目属于「基线夹具验证」或「版本/装配验证」，
> **没有任何条目属于「真实旧产物验证」**——首发仓库里不存在已发布的旧版本产物（`@vben/ai-embed-sdk` 只构建过 5.6.0；
> 本地 Maven 仓库只有 spring-ai 1.1.8，见 §3.1）。首次真实升级（上游发布下一个版本）之后，本清单才可能出现第二种结论。

## 2. 台账（按 upstream-registry.yaml 的 componentId 对应）

下表"回归入口"列出的路径均为仓库现存文件（已逐一核实存在）；标"待补"的条目见 §4。

### 2.1 后端上游

| componentId | 冻结值（精确） | 适配器落点 | 兼容回归入口（可执行） | 验证类别 |
|---|---|---|---|---|
| `spring-ai` | 1.1.8（`${spring-ai.version}`，BOM import） | `后端代码/basic-framework-boot/basic-framework-core/basic-framework-spring-boot-starter-ai/src/main/java/com/basicframework/framework/ai`（`provider.springai` 是唯一直接引用区） | `./mvnw -o -pl basic-framework-core/basic-framework-spring-boot-starter-ai test`（端隔离/流/结构化输出/重试/嵌入/探测）；`./mvnw -o -pl basic-framework-server dependency:tree`（单一版本收敛）；待补：`module-ai/.../compatibility`（§4.1） | 版本/装配验证（F02 §10.3/§10.6） |
| `qdrant-server` | `qdrant/qdrant:v1.19.1@sha256:12364fe851b9f17356fc88189fc06d1b521262e04659ec7345975b00c9246a10` | `后端代码/basic-framework-boot/basic-framework-module-ai/src/main/java/com/basicframework/module/ai/adapter/knowledge/QdrantRestKnowledgeIndexAdapter.java`（REST / `KnowledgeIndexPort`） | `./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test=AiKnowledgeIndexQdrantIT`（集合/维度/过滤/快照）；`node --test scripts/check-container-images.test.mjs`（digest 登记） | 当前版本组合验证（真实容器）；**旧镜像/旧集合回退未验证**（registry `pendingDigest`，本轮 §3.3 复核） |
| `qdrant-java-client` | `io.qdrant:client:1.13.0` | 无（产品未消费，仅 BOM 声明；K01 选 REST） | F02 离线坐标探针（`.local-state/f02-upstream/offline-probe/qdrant/`，Git 忽略；归档非交付物） | 坐标验证（离线可解析），无端到端消费者 |
| `tika-core` | `org.apache.tika:tika-core:3.2.3` | `后端代码/basic-framework-boot/basic-framework-module-ai`（K04 受限解析；Tika 只做类型识别，正文走 PDFBox/POI） | `./mvnw -o -pl basic-framework-module-ai test -Dtest=RestrictedDocumentParserTest`（10 例，K04 证据 §4） | 版本验证 + 真实文件夹具回归（K04） |
| `pdfbox-poi-parser-chain` | `org.apache.pdfbox:pdfbox:3.0.5`、`org.apache.poi:poi-ooxml:5.4.1` | 同上（`module-ai` 解析适配） | 同上 `RestrictedDocumentParserTest`；`dependency:tree` 单一版本 | 版本验证 + 真实文件夹具回归（K04） |

### 2.2 前端与协议

> 本表"回归入口"列的命令工作目录为 `前端代码/basic-framework-admin`，其中 `packages/…`、`apps/…` 均相对该目录。

| componentId | 冻结值（精确） | 适配器落点 | 兼容回归入口（可执行） | 验证类别 |
|---|---|---|---|---|
| `antv-g2`（前端图表） | `@antv/g2: 5.4.8`（`pnpm-workspace.yaml` catalog，带"升级需重跑 ChartRenderer 验证"注释；锁文件 integrity 见 §3.4） | `前端代码/basic-framework-admin/packages/ai-chat-ui/src/chart/`：`AiChart.vue` / `chartOptions.ts` / `specToRows.ts` / `theme.ts` / `useChartInstance.ts` / `fallback.ts`（GPT-Vis 不采用，F04 报告 §1） | `pnpm exec vitest run --dom packages/ai-chat-ui/src/chart`（适配/降级/生命周期）；`node apps/ai-chat/scripts/verify-built-chat.mjs`（产物分包+体积+DOM 渲染）；`node apps/ai-chat/scripts/check-public-cdn.mjs`（禁公共 CDN）；待补：`tests/compatibility`（§4.2） | **基线夹具验证**（F04 §3.1 固定样例：1,309,384 B / gzip 388,290 B；本轮复核一致，§3.4） |
| `ai-embed-sdk 产物`（自有版本化产物，非上游） | `ai-embed-sdk-5.6.0.js`，冻结时 `artifactSha256=09090b4b…` | `前端代码/basic-framework-admin/packages/ai-embed-sdk/`（`src/client.ts`、`src/bridge/host-bridge.ts`、`src/display/`） | `pnpm exec vitest run --dom packages/ai-embed-sdk/src/__tests__`（含 `n-1-baseline.test.ts` 基线重放）；夹具 `packages/ai-embed-sdk/fixtures/n-1/baseline.json` | **基线夹具验证（首发）**；产物字节与夹具哈希未绑定，§3.5 |
| `协议契约 v1`（自有） | `docs/contracts/ai/*.schema.json`（`schemaVersion` `const "1.0"`）；C10 冻结消息白名单/握手序列 | Java `basic-framework-module-ai-api` 的协议 DTO；TS `packages/ai-contracts` | `./mvnw -o -pl basic-framework-module-ai-api test`（`AiProtocolFixtureTest` 6 例）；`pnpm exec vitest run --dom packages/ai-contracts`（`protocol-fixtures.test.ts` 13 例） | **基线夹具验证**（F07：v1 样例保留为兼容基线） |
| `基础框架基线` | 标签 `framework-baseline-23a7edb37593` + `docs/framework-baseline.json` | 不适用（整仓基线） | 同步规则见 `docs/ai-platform/06-upstream-upgrade.md` §6；快照一致见 `node scripts/check-data-lifecycle.mjs` | 基线快照；无外部兼容承诺（06 §6 末段） |

> 说明：管理端 `apps/web-ele` **不引入图表库**（自研 SVG 渲染器，F04 证据 §4.2 第 5 条），
> 因此 AntV 升级的回归面是 `packages/ai-chat-ui` + `apps/ai-chat` 产物，不涉及 `apps/web-ele` 运行时。

## 3. 本轮实际核验（只读检查，命令可复跑）

工作目录 `前端代码/basic-framework-admin` 或仓库根目录，按命令注明；所有输出为 2026-09-27 实测。

### 3.1 后端回退材料盘点（发现台账不一致）

```bash
ls ~/.m2/repository/org/springframework/ai/spring-ai-bom/        # → 只有 1.1.8
ls ~/.m2/repository/org/springframework/ai/spring-ai-model/      # → 只有 1.1.8
```

结果：21 个 `org.springframework.ai` 构件的本地仓库版本**全部只有 1.1.8**。
`upstream-registry.yaml` 的 `spring-ai.rollback.materials` 写的是"本地 Maven 仓库中的 1.1.7/1.1.6 包"，
**在本机不成立**（可能是"Central 上仍可下载"被写成了"本地已有"）。→ 待主管更正；离线回退 `1.1.8 → 1.1.7` 在补齐本地包之前不可执行（§4.3）。

### 3.2 冻结字节核验（与台账值一致）

```bash
sha1sum ~/.m2/repository/org/springframework/ai/spring-ai-model/1.1.8/spring-ai-model-1.1.8.jar \
        ~/.m2/repository/org/apache/tika/tika-core/3.2.3/tika-core-3.2.3.jar \
        ~/.m2/repository/io/qdrant/client/1.13.0/client-1.13.0.jar
```

| 构件 | 本机 sha1 | registry 值 | 结论 |
|---|---|---|---|
| `spring-ai-model-1.1.8.jar` | `9320c4c81692b3702391423a7e9a3db6a1ca3d94` | 同 | 一致 |
| `tika-core-3.2.3.jar` | `4b1b82f8cce72c9bd3676532c8b613e24041d96c` | 同 | 一致 |
| `client-1.13.0.jar` | `4ca2a42ff4c0a645f5c03aa2da0b12fd8387ed35` | 同 | 一致 |

### 3.3 容器镜像与旧镜像

```bash
docker images --digests | grep qdrant
# qdrant/qdrant v1.19.1 sha256:12364fe851b9f17356fc88189fc06d1b521262e04659ec7345975b00c9246a10
docker ps    # 无运行中容器
```

digest 与 registry 一致；**本机没有更旧的 qdrant 镜像**（与 registry `pendingDigest` 记录相符）→ Qdrant 回退材料仍是待补项。

### 3.4 前端锁定值与体积基线

```bash
grep -n "@antv/g2" 前端代码/basic-framework-admin/pnpm-lock.yaml
# 1643: '@antv/g2@5.4.8':  1644: resolution.integrity = sha512-IvgIpwmT4M5/QAd3Mn2WiHIDeBqFJ4WA2gcZhRRSZuZ2KmgCqZWZwwIT0hc+kIGxwYeDoCQqf//t6FMVu3ryBg==
sed -n '1643,1644p' 前端代码/basic-framework-admin/pnpm-lock.yaml   # 同上两行，逐字核对
ls -l 前端代码/basic-framework-admin/apps/ai-chat/dist/assets/
# vendor-antv-1_Bud82i.js = 1,309,384 B（与 F04 §3.1 记录一致，可作为升级体积对比基线）
ls 前端代码/basic-framework-admin/node_modules/.pnpm/ | grep "^@antv+g2"
# @antv+g2@5.4.8（离线可回到当前版本）
```

npm registry 公布的同版本 integrity 与锁文件逐字一致（探测见演练文档 §1）。

### 3.5 基线夹具与产物哈希漂移（待补）

```bash
sha256sum 前端代码/basic-framework-admin/packages/ai-embed-sdk/dist/ai-embed-sdk-5.6.0.js
# 55d9c284084ba22e9f684bc398377bc5999599b7864d8437bb3c5e8f04660e4d（2026-09-27 12:43 重建）
grep -n artifactSha256 前端代码/basic-framework-admin/packages/ai-embed-sdk/fixtures/n-1/baseline.json
# 09090b4b1f6662e411c1295a6d91995b92db53ae15d510bf9c08ed6502b28f1d（C10 冻结值）
```

`packages/ai-embed-sdk/src/__tests__/n-1-baseline.test.ts` 对 `artifactSha256` **只做 64 位十六进制格式断言**，
不与构建产物比对；`dist/` 被 `.gitignore`（第 10 行）忽略。因此"交付产物是否仍能通过冻结基线"目前**没有门禁绑定**，
且 C10 之后 `src/client.ts` 有改动（工作树未提交），重建产物哈希必然变化。→ §4.2 由前端切片补"重建后比对/重新冻结"的入口。

## 4. 待补项与去向（与另两个并行切片对接）

本清单中标注"待补"的回归入口由 Q08 的后端/前端测试切片产出；落到本文档后，把下表对应行的"验证类别"一次补齐。

| # | 待补内容 | 预期路径（Q08 卡片 §2 授权范围） | 补上后的载体 |
|---|---|---|---|
| 4.1 | 后端兼容回归包（旧 ReportSpec 加载、上游坐标/tree 快照断言、迁移碰撞拒绝） | `后端代码/basic-framework-boot/basic-framework-module-ai/src/test/java/com/basicframework/module/ai/compatibility/`（截至 2026-09-27 尚未创建） | AT-049/065/072 的后端承载，写入 [q08-acceptance-evidence.md](../upgrades/q08-acceptance-evidence.md) §2–§3 |
| 4.2 | 前端兼容回归包（N-1 基线重放、图表升级回归、产物哈希绑定） | `前端代码/basic-framework-admin/tests/compatibility/`（**2026-09-27 已开始落地**：先出现只读工具 `support/workspace.ts`；用例文件与 `fixtures/` 内容待补） | AT-057/065 的前端承载，写入 [q08-acceptance-evidence.md](../upgrades/q08-acceptance-evidence.md) §2–§3 |
| 4.3 | 回退材料更正：`spring-ai` 旧包（1.1.7/1.1.6）实际不在本机 m2；Qdrant 旧镜像 digest 未登记 | `docs/integrations/upstream-registry.yaml`（超出本切片路径则写汇报） | registry `rollback.materials` / `pendingDigest` |
| 4.4 | SDK 产物哈希门禁（重建后与基线比对或重新冻结） | `packages/ai-embed-sdk`（另一切片） | §2.2 第 2 行验证类别升级为"产物字节绑定" |

> §3.1/§3.3 的缺口不是"文档没写"，而是**材料确实不存在**；在补齐前，任何"可离线回退"的表述都不成立——
> 这与 registry 的 `pending`/`unknown` 阻止冻结完成的规则（06 §3）一致。
