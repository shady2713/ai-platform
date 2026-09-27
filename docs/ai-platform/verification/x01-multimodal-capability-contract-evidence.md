# X01 扩展多模态能力契约与端点验证矩阵 — 完成证据（后端切片）

| 项目 | 内容 |
|---|---|
| 任务卡 | [X01](../tasks/X01.md)（**后端切片**：能力契约、准入矩阵、迁移、拒绝路径验证） |
| 状态 | **实现完成，待主管复核**（本切片不 commit；工作树另含并行代理的 `packages/ai-contracts` 变更） |
| 需求 | FR-35（图像）、FR-36（非实时语音）、FR-37（实时语音：**明确排除**，不预留占位）、FR-40 拒绝路径；AT-068 |
| 依赖 | Q10（已交付，见 [v1 验收矩阵](../../release/q10-v1-acceptance-matrix.md)） |
| 工作副本（唯一可写） | `/home/ctyun/桌面/zhongtai/ai-platform` |
| 基线 commit | `44100c9`（`chore(gates): ignore build outputs and force artifact rebuild in smoke gate`） |
| 后端工作目录 | `/home/ctyun/桌面/zhongtai/ai-platform/后端代码/basic-framework-boot` |
| 前端工作目录 | `/home/ctyun/桌面/zhongtai/ai-platform/前端代码/basic-framework-admin` |
| 未执行 | `.harness/verify.sh`（按要求不跑）、全量 `mvn clean verify`、真实供应商调用（无出网/无凭据） |

## 1. 交接说明：本切片接管了工作树中已存在的未提交实现

**必须复核的事实**：任务下发时声明"工作树干净（除 `.agent-worktrees/` 与 `examples/**/node_modules/.bin` 噪音）"，
但 2026-09-27 20:28 实际 `git status --short` 显示 14 个已修改跟踪文件 + 多个新增文件，
文件 mtime 为 17:47–18:10（早于本会话开始），且当时没有正在运行的 Maven/Vitest 进程。

处理方式：**逐文件复核 → 接管 → 补齐缺口 → 全部重跑验证**，没有重写既有实现、没有丢弃任何文件。
本会话新增/修改的仅是：

1. `AiMediaCapabilityGateTest` 增加"六个媒体能力逐个独立准入"用例（AT-068 全覆盖）并把拒绝断言加了场景描述；
2. `module-ai/README.md` 增加"多模态媒体准入（X01）"行；
3. 本文件（新建）+ `docs/integrations/ai-platform-multimodal-endpoints.md` 的补充：
   证据文件链接修正、探测顺序前置缺口登记（§7）、端口同步/产品异步区分（§1）、
   声明字段生效范围（§2）、`MEDIA_INPUT_*` 产生方说明（§3）、供应商适配准入结论（§7）。

其余文件（媒体契约、词汇、错误码、V84、快照、前端词汇同步、矩阵正文）为**接管内容**，
归属需要在提交时确认（不排除是同一卡片的更早一次会话留下的）。详见 §4 清单。

## 2. 冻结的词汇与契约最终形态

### 2.1 能力枚举 `ModelCapability`（顺序冻结，只追加不重排）

```text
TEXT, TEXT_STREAM, STRUCTURED_OUTPUT, TOOL_CALLING, EMBEDDING,
IMAGE_UNDERSTANDING, IMAGE_OCR, IMAGE_GENERATION, IMAGE_EDIT, SPEECH_TO_TEXT, TEXT_TO_SPEECH
```

- `isMedia()`：后六个为 `true`，其余为 `false`；实时语音（FR-37）**不在枚举内**，不预留占位值。
- `probeKind()`：每个能力 1:1 映射同名 `ModelProbeKind`（`CONNECTIVITY` 不属于任何可发布能力）。
- 位置：`后端代码/basic-framework-boot/basic-framework-core/basic-framework-spring-boot-starter-ai/src/main/java/com/basicframework/framework/ai/core/model/ModelCapability.java`

### 2.2 探测项 `ModelProbeKind`

```text
CONNECTIVITY, TEXT, TEXT_STREAM, STRUCTURED_OUTPUT, TOOL_CALLING, EMBEDDING,
IMAGE_UNDERSTANDING, IMAGE_OCR, IMAGE_GENERATION, IMAGE_EDIT, SPEECH_TO_TEXT, TEXT_TO_SPEECH
```

新增两个稳定明细码：`CODE_NO_IMAGE_RETURNED`（生成/编辑探测无产物）、`CODE_NO_AUDIO_RETURNED`（TTS 探测无产物）。

### 2.3 `ModelPort` 媒体方法（全部默认实现"能力未开通"拒绝）

| 方法 | 请求类型 | 响应类型 | 默认实现抛 |
|---|---|---|---|
| `understandImage` | `ImageUnderstandingRequest` | `MediaTextResponse` | `CAPABILITY_NOT_ENABLED`（含能力名） |
| `recognizeImageText` | `ImageOcrRequest` | `MediaTextResponse` | 同上 |
| `generateImage` | `ImageGenerationRequest` | `ImageResult` | 同上 |
| `editImage` | `ImageEditRequest` | `ImageResult` | 同上 |
| `transcribeSpeech` | `SpeechTranscriptionRequest` | `SpeechTranscriptionResponse` | 同上 |
| `synthesizeSpeech` | `SpeechSynthesisRequest` | `SpeechSynthesisResponse` | 同上 |

默认实现**不静默降级为文本调用**、不切换端点/供应商；`probe(kind)` 默认返回
`UNSUPPORTED + CODE_ADAPTER_NOT_IMPLEMENTED`，不发起任何调用。

### 2.4 平台自有媒体值对象（`core.model.media`，15 个文件，无厂商类型）

- `MediaFileRef`：只接受平台私有 `fileId`（正数）+ MIME + 字节数 + 可选 SHA-256；拒绝空值/非法 MIME/非正字节数。
- `MediaArtifact`：字节（构造与读取双向防御性复制）+ MIME + 自洽 SHA-256 + 宽高/时长（可选、正数）。
- 请求：图片理解/OCR/生成/编辑、STT/TTS；构造即校验平台硬上限：
  图片尺寸 `宽x高` 每边 1–8192、生成张数 1–8、图片输出格式 `png/jpeg/webp`、
  TTS 文本 1–4096 码元、音频输出格式 `mp3/wav/opus`。
- 响应：`MediaTextResponse`、`ImageResult`（≥1 张）、`SpeechTranscriptionResponse`（全文必填 + 可选分段）、
  `SpeechSynthesisResponse`、`SpeechSegment`（时间片单调）；均携带 `ModelUsage`（缺失记 `UNKNOWN`，不伪造 0）。
- 厂商差异（multipart/base64/轮询）只允许出现在 `provider.springai`；`core` 全目录
  `grep org.springframework.ai` 无命中。

### 2.5 错误码与子区间（越界授权项，两侧 + 映射文档同步）

| 常量 | 码 | 语义 | HTTP |
|---|---|---|---|
| `AI_MODEL_CAPABILITY_NOT_ENABLED` | 1_003_002_007 | 端点未开通媒体能力（未声明或未通过探测确认），拒绝先于客户端解析与网络调用 | 400 |
| `AI_MEDIA_REQUEST_INVALID` | 1_003_010_000 | 媒体请求不合规（尺寸/张数/格式/音色/文本长度） | 400 |
| `AI_MEDIA_INPUT_TYPE_UNSUPPORTED` | 1_003_010_001 | 输入媒体类型不在该能力声明白名单 | 400 |
| `AI_MEDIA_INPUT_TOO_LARGE` | 1_003_010_002 | 输入媒体超过端点声明单文件上限 | 400 |
| `AI_MEDIA_INPUT_DURATION_EXCEEDED` | 1_003_010_003 | 音频时长超过端点声明上限 | 400 |
| `AI_MEDIA_OUTPUT_EMPTY` | 1_003_010_004 | 上游成功但无媒体产物，拒绝交付/落私有文件 | 502 |

新领子区间 `1_003_010_xxx`（`AiErrorCodeRanges.DOMAIN_MEDIA`）不复用他域编号；
映射为 `ModelException.Reason → ErrorCode`，统一在 `AiMediaCapabilityGate` 内完成，
未列入的 Reason 沿用 `AiModelFailureCodes` 既有映射；失败响应不回传上游报文/输入/凭据。
映射文档：`docs/contracts/ai/error-code-map.md`（含新增的 `1_003_009_xxx` 行补全）。

### 2.6 准入判据（`AiMediaCapabilityGate`，AT-068）

1. 端点存在且启用（不存在 404 / 停用 409）；
2. 当前配置版本的能力集合包含该媒体能力，否则 400 `AI_MODEL_CAPABILITY_NOT_ENABLED`；
3. 该能力的探测最新结论为 `SUPPORTED` 且 `configRevision` 等于端点当前配置版本，否则同码拒绝；
4. 通过后才 `AiModelClientResolver.resolve` 并调用端口。

调用方必须显式给出端点编号（不做候选遍历），因此不存在隐式失败转移；FR-35"失败不自动外发到另一提供商"由此保证。

## 3. 拒绝路径与零外发证据（AT-068 可验证部分）

| 断言 | 测试位置 | 机制 |
|---|---|---|
| 未声明媒体能力 → 稳定错误码 + 零外发 | `AiMediaCapabilityGateTest.undeclaredCapabilityRejectsBeforeResolvingClient` | 计数端口 `mediaCalls == 0` + `verify(clientResolver, never()).resolve(any())` |
| 六个媒体能力逐个：已声明未探测 / 只有别的媒体能力结论 / 未声明 → 全部拒绝且零外发 | `AiMediaCapabilityGateTest.everyMediaCapabilityNeedsItsOwnDeclarationAndProbe` | 同上；证明媒体能力之间不能互相推断 |
| 声明后从未探测、探测 FAILED/UNSUPPORTED、结论配置版本过期、其它探测项串用 | 同测试类对应 4 个用例 | 同上 |
| 端点停用 → 端错误码且零外发 | `disabledEndpointRejectsWithEndpointCodeBeforeOutbound` | 同上 |
| 准入通过才调用端口，且恰好 1 次 | `admittedCapabilityInvokesPortExactlyOnce` | 计数端口 == 1 |
| 端口失败 → 平台错误码且不回传上游文案 | `portModelExceptionsMapToStablePlatformCodesWithoutUpstreamText` | 6 个媒体 Reason 映射 + `message == 平台文案` |
| 端口默认实现拒绝且不触发文本调用 | `MediaPortDefaultsTest.mediaDefaultsRejectWithCapabilityNotEnabled` | 文本调用计数器 == 0 |
| 默认探测不发起厂商调用 | `MediaPortDefaultsTest.probeDefaultsToAdapterNotImplementedWithoutAnyCall` | 同上 |
| 适配器未实现媒体探测时不外发 | `SpringAiModelClient.probe` 媒体分支返回 `UNSUPPORTED + ADAPTER_NOT_IMPLEMENTED`（新增 4 行，见 §4 红线例外） | 分支内不构造任何请求 |

测试目录（绝对路径）：

- `/home/ctyun/桌面/zhongtai/ai-platform/后端代码/basic-framework-boot/basic-framework-module-ai/src/test/java/com/basicframework/module/ai/adapter/model/AiMediaCapabilityGateTest.java`（12 例）
- `/home/ctyun/桌面/zhongtai/ai-platform/后端代码/basic-framework-boot/basic-framework-core/basic-framework-spring-boot-starter-ai/src/test/java/com/basicframework/framework/ai/core/model/`（`MediaPortDefaultsTest`、`MultimodalVocabularyTest`、`media/*` 共 6 个类 30 例）

## 4. 变更文件清单

### 4.1 卡片允许路径内

| 文件 | 说明 |
|---|---|
| `后端代码/.../basic-framework-spring-boot-starter-ai/src/main/java/.../core/model/ModelCapability.java` | 追加 6 个媒体能力 + `isMedia()` + `probeKind()` |
| 同目录 `ModelProbeKind.java` / `ModelProbeResult.java` / `ModelException.java` / `ModelPort.java` | 6 个媒体探测项、2 个明细码、6 个 Reason、6 个默认拒绝方法 |
| 同目录 `media/**.java`（15 个新文件，含 `package-info.java`） | 平台自有媒体请求/响应值对象 |
| 同模块 `src/test/.../media/**`（4 个新文件）、`MediaPortDefaultsTest.java`、`MultimodalVocabularyTest.java` | 新增真实测试（30 例） |
| 同模块 `README.md` | 契约表与"多模态媒体契约（X01）"章节、测试清单 |
| `后端代码/.../basic-framework-module-ai/src/main/java/.../adapter/model/AiMediaCapabilityGate.java` | 准入闸门（新增） |
| 同模块 `src/test/.../adapter/model/AiMediaCapabilityGateTest.java` | 12 例（其中 1 例为本会话新增） |
| 同模块 `README.md` | **本会话新增**一行：多模态媒体准入（X01）与 X02 前置 |
| `docs/integrations/ai-platform-multimodal-endpoints.md` | 端点准入矩阵（新增；本会话补 3 处） |
| `docs/ai-platform/verification/x01-multimodal-capability-contract-evidence.md` | 本文件（越界授权 ③ 指定文件名） |

### 4.2 越界扩展（主管已批准，显式登记）

| 文件 | 越界理由 |
|---|---|
| `module-ai/src/main/java/.../enums/AiErrorCodeConstants.java` | 授权 ①：新增 6 个错误码，**新领 `1_003_010_xxx` 子区间**，不复用他域编号 |
| `module-ai/src/main/java/.../enums/AiErrorCodeRanges.java` | 授权 ①：登记 `DOMAIN_MEDIA` 子区间 |
| `docs/contracts/ai/error-code-map.md` | 授权 ①：两侧同步（含补全既有 `1_003_009_xxx` 行） |
| `basic-framework-server/src/main/resources/db/migration/V84__ai_multimodal_capability_widen.sql` | 授权 ②：从 V84 起领新号，历史迁移未改 |
| `数据库文件/basic_framework.sql` | 授权 ②：快照声明 `through V84` + 三列加宽同步 |
| `前端代码/.../model-endpoint/data.ts`、`data.test.ts` | 授权 ④：只加能力/探测词汇选项与精确断言，未改页面逻辑 |
| `docs/ai-platform/verification/**`（本文件） | 授权 ③ |

**未改动**（明确说明）：`docs/contracts/{data-lifecycle.json,data-permission-exemptions.json,permission-catalog.json,field-catalog.yaml}`
无需变更（见 §6），`PersistenceLifecycleIT` 未改（无新增表/列/权限）。

### 4.3 红线例外（最小必要，需主管确认）

`starter-ai/src/main/java/.../provider/springai/SpringAiModelClient.java`（+4 行，不动其余逻辑）：
`probe(ModelProbeKind)` 是**无 `default` 分支的穷尽 switch 表达式**，`ModelProbeKind` 新增 6 个取值后
该模块无法编译；唯一的最小改动是补一个分支，返回
`ModelProbeResult.unsupported(kind, CODE_ADAPTER_NOT_IMPLEMENTED)`，不构造请求、不发起厂商调用。
X02–X04 实现媒体方法时应替换此分支。除此之外未触碰 `provider/**`。

### 4.4 并行代理负责（本切片未修改）

`前端代码/basic-framework-admin/packages/ai-contracts/**`（`src/index.ts` 修改，`multimodal.ts`、
`multimodal-request.ts`、`__tests__/multimodal*.test.ts`、`README.md` 新增）。
只读核对结论：其 6 个能力名与后端枚举**逐字一致**；但把生成/编辑标为 `ASYNC` 属**产品维度**标注，
已在本切片矩阵 §1 显式区分（端口是同步契约），请主管在合流时确认两处表述不冲突。

## 5. 命令、目录、退出码与关键输出

环境：`umask 022`；`export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`（Java 17）。

| # | 命令 | 目录 | 退出码 | 关键输出 |
|---|---|---|---|---|
| 1 | `./mvnw -q -o -pl basic-framework-core/basic-framework-spring-boot-starter-ai spotless:apply` | `后端代码/basic-framework-boot` | 0 | 无输出 = 无格式漂移 |
| 2 | `./mvnw -q -o -pl basic-framework-module-ai spotless:apply` | 同上 | 0 | 同上（含本会话新增测试） |
| 3 | `./mvnw -q -o -pl basic-framework-server spotless:apply` | 同上 | 0 | 同上 |
| 4 | `./mvnw -o -pl basic-framework-core/basic-framework-spring-boot-starter-ai test` | 同上 | 0 | `Tests run: 159, Failures: 0, Errors: 0`，`BUILD SUCCESS`；报告：`basic-framework-core/basic-framework-spring-boot-starter-ai/target/surefire-reports/` |
| 5 | `./mvnw -o -pl basic-framework-module-ai test -Dtest='AiMediaCapabilityGateTest'` | 同上 | 0 | `Tests run: 12, Failures: 0, Errors: 0`，`BUILD SUCCESS`；报告：`basic-framework-module-ai/target/surefire-reports/` |
| 6 | `pnpm exec vitest run --dom apps/web-ele/src/views/ai/model-endpoint` | `前端代码/basic-framework-admin` | 0 | `Test Files 4 passed (4) / Tests 20 passed (20)` |
| 7 | `node scripts/check-data-lifecycle.mjs` | 仓库根 | 0 | 最终表 82 张、逻辑删除列 51 张、物理外键 65 条；**快照同步** |
| 8 | `node scripts/check-data-permission.mjs` | 仓库根 | 0 | 应用表 71 / 运行时保护 2 / 显式豁免 69 / 平台托管 11；通过 |
| 9 | `node scripts/check-permission-catalog.mjs` | 仓库根 | 0 | 接口引用 138、目录 139、不可授予 4、保留 5；通过 |
| 10 | `node scripts/check-field-catalog.mjs` | 仓库根 | 0 | 字段 9 个（drift 0），比对通过 39 项 |
| 11 | `node scripts/check-starter-documentation.mjs` | 仓库根 | 0 | 每个能力接缝均含 README.md |
| 12 | `./mvnw -o -q -pl <module> jacoco:report` + 解析 `jacoco.xml` | `后端代码/basic-framework-boot` | 0 | 新文件行覆盖率：`media/**` 95.35%–100%，`ModelCapability/ModelPort/ModelProbeKind/ModelProbeResult` 100%，`AiMediaCapabilityGate` 34/34=100% |

**诚实说明（首次全模块运行的抖动）**：第一次 `-am test` 全量运行出现 1 个失败
`GuardedExternalHttpClientTest.cancellationStopsPendingRequest`（断言偶发超时，位于 `core.http` 包，
本卡未触碰该文件/类）；单独重跑该类 `22/22` 通过，随后全模块重跑 `159/159` 通过（退出码 0）。
判定：**与本卡无关的既有抖动**，未放宽容差、未跳过任何测试。

覆盖率口径说明：上表第 12 行是**逐模块** JaCoCo 报告（真实执行数据），
仓库权威口径是 `basic-framework-coverage` 的聚合报告（需后端完整门禁，本卡未跑）；
新文件按"新增文件 ≥80%"标准已满足，`docs/contracts/coverage-baseline.json` 未改（见 §8）。

## 6. 迁移与台账

- **需要迁移**：11 个能力名全量按前端排序拼接（`EMBEDDING,IMAGE_EDIT,...,TOOL_CALLING`）共 **145 字符**，
  超过 `ai_model_endpoint_revision.capabilities`、`ai_service.required_capabilities`、
  `ai_service_release.required_capabilities` 的 `varchar(128)`。
- `V84__ai_multimodal_capability_widen.sql`：三列 `MODIFY` 为 `varchar(255)`（只加宽，不改语义、不加列/表）；
  历史迁移（V48/V56 等）未改写；最大编号原为 V83，V84 无碰撞。
- 快照 `数据库文件/basic_framework.sql` 同步：声明 `through V84` + 三列长度/注释更新；
  `check-data-lifecycle.mjs` 的 Flyway↔快照↔契约三方一致性检查通过（上表第 7 行）。
- **四处台账无需变更**：无新增表、无新增列（只有列宽变化）、无新增权限码/菜单；
  四个检查器（第 7–10 行）全绿即为证据。`PersistenceLifecycleIT` 无需改动
  （其职责是最后快照与迁移链一致性，V84 由上面第 7 行覆盖）。
- 前端降级：新增能力值是**追加**，旧客户端按未知值降级展示，不改变既有值语义（`data.ts` 注释与矩阵 §7）。

## 7. 未验证项（本卡不得标记"已验证"的部分）

1. **六个媒体能力在真实端点的探测结论**：本机无出网、无供应商凭据，矩阵 §4 逐项标"未验证"，
   精确复现步骤（合成夹具 + curl 逐协议）见矩阵 §6；不得据文本 API 可用推断媒体可用。
2. **媒体探测不会被执行**：`AiModelCapabilityProbeServiceImpl` 的 `PROBE_ORDER`/`KIND_CAPABILITIES`
   只覆盖六类文本能力，媒体探测项未纳入 → 准入第 3 条判据在现行探测流程下对媒体不可达。
   已在矩阵 §7「X02 必须先补的准入前置」登记（该文件不在 X01/X02–X04 允许路径内，需主管授权）。
3. `provider.springai` 的媒体实现、厂商重试/取消语义（X02–X04）。
4. 媒体计价维度（张/秒）与配额护栏（X02+ 与 Q02 扩展）。
5. 凭据轮换对探测结论的影响（探测 DTO 未暴露凭据版本）。
6. 媒体能力的页面/浏览器验收（附件上传、录制授权降级）属 X02–X04，本卡只冻结后端拒绝语义。
7. 聚合覆盖率报告与 `coverage-baseline.json` 新条目（需后端完整门禁；本卡只给逐模块实测）。

## 8. 未纳入本切片的实现选择（有意为之，非缺口）

- **未新增 `docs/contracts/ai/*.schema.json`**：媒体协议目前没有 HTTP 端点，权威 Schema 是
  `core.model.media` 的平台类型；X02 建立开放 API 时按该目录体例补 JSON Schema + 双端夹具，
  本卡不预先发明一个无消费者的 Schema。
- **未改 `basic-framework-module-ai-api`**：本卡没有跨模块公开 API 的消费者，媒体契约的接缝在
  `starter-ai`（模块 → 适配器）而非模块间。

## 9. 需要主管串行处理的事项

1. **提交归属确认**：工作树内含接管内容（§1），请确认这些文件属于本卡提交；
   `packages/ai-contracts/**` 属并行代理，与本切片一起提交时需核对两处词汇表述（§4.4）。
2. **X02 范围扩展（准入前置）**：`module-ai/src/main/java/.../service/model/AiModelCapabilityProbeServiceImpl.java`
   第 42–56 行（`PROBE_ORDER`、`KIND_CAPABILITIES`）需加入六个媒体探测项并补探测服务测试；
   建议在 X02 授权时显式加入该文件。
3. **迁移版本口径漂移（本卡范围外）**：以下文档仍写"最大 V83 / 82 个文件"，V84 后需更新：
   `docs/deployment/configuration-manual.md:148`、`docs/deployment/deployment-and-rollback.md:72`、
   `docs/operations/upgrade-checklist.md:10,57`。（`docs/upgrades/q08-*.md` 是时点证据，不必改。）
4. **完整门禁**：按主管流程跑 `.harness/verify.sh contracts/backend/integration/frontend`（本卡未授权执行）；
   若覆盖率棘轮要求登记新文件下限，请在门禁后回填 `docs/contracts/coverage-baseline.json`。
5. **真实端点实测**：需要出网与受控凭据的实验环境，按矩阵 §6 逐项执行并回填 §4 状态。

## 10. 下一张可领取任务及其前置证据

- **X02（图片理解与 OCR 闭环）**：前置 = 本卡词汇/契约（§2.1–2.4）、`AiMediaCapabilityGate` 与错误码（§2.5–2.6）、
  V84 迁移与快照（§6）、拒绝路径证据（§3）；开工前必须先完成 §9.2 的探测前置与 §9.5 的真实探测。
- 本卡完成的独立部分（无阻断）：词汇/契约冻结、准入矩阵、迁移与快照、拒绝路径与零外发验证；
  未完成部分全部有明确归属（§7 未验证项与 §9 待办）。
