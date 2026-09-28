# X02 图片理解与 OCR 闭环 — 完成证据

| 项目 | 内容 |
|---|---|
| 任务卡 | [X02](../tasks/X02.md) |
| 需求 | FR-35；AT-023、AT-025、AT-068（见 [08-testing-acceptance.md](../08-testing-acceptance.md)） |
| 依赖 | X01（已交付，[证据](x01-multimodal-capability-contract-evidence.md)）、A07（文件业务 ACL，已交付）、K04（文档解析与切片，已交付） |
| 工作副本（唯一可写） | `/home/ctyun/桌面/zhongtai/ai-platform` |
| 基线 commit | `9c492d1`（X01：多模态能力契约与端点验证矩阵） |
| 后端工作目录 | `/home/ctyun/桌面/zhongtai/ai-platform/后端代码/basic-framework-boot` |
| 前端工作目录 | `/home/ctyun/桌面/zhongtai/ai-platform/前端代码/basic-framework-admin` |
| 未执行/未验证 | 真实供应商多模态运行时调用（无凭据/无出网）；真实模型环境下的识别质量评测（Q10 阻断项 B 之一） |

## 1. 交付范围

平台侧闭环：受控图片输入 → 视觉/OCR 能力准入 → 端口调用 → 结果落库/落文件/入索引 → App API 与 Chat 附件卡片。

```text
POST /app-api/ai/vision/understand       图片理解（描述/问答）
POST /app-api/ai/vision/image/ocr        单图文字识别
POST /app-api/ai/vision/document/ocr     扫描件多页 OCR → 作为该文档的新版本重新索引
```

三条路径共用同一套输入守卫与准入判据，差别只在"输出去哪"：理解/OCR 返回文本，文档 OCR 把识别稿
转成平台私有文件后走既有知识入库流水线（版本、幂等、切片、active 切换语义全部复用 K02–K05）。

## 2. 冻结契约与关键语义（不新增词汇）

| 契约 | 位置 | 本卡用法 |
|---|---|---|
| `ModelCapability.IMAGE_UNDERSTANDING / IMAGE_OCR` | starter-ai `core/model/ModelCapability.java` | 端点必须**声明并由探测确认**才准入（X01 矩阵） |
| `ModelPort.understandImage / recognizeImageText` | starter-ai `core/model/ModelPort.java` | 平台唯一外发入口；未实现的能力保持 X01 的显式拒绝 |
| `MediaFileRef` | starter-ai `core/model/media/MediaFileRef.java` | 只外发 fileId + MIME + 字节数 + sha256，**不传字节、不传 URL** |
| `MediaTextResponse.text` 非空 | starter-ai `core/model/media/MediaTextResponse.java` | 空文本按 `AI_MEDIA_OUTPUT_EMPTY` 拒绝，不当成功交付 |
| `AiMediaCapabilityGate` | module-ai `adapter/model/AiMediaCapabilityGate.java` | 声明 ∩ 探测确认，未通过**不发起任何调用** |
| 文件业务 ACL（A07） | module-ai `service/file/AiFileServiceImpl` + infra `FileBusinessAccessProviderRegistry` | 读私有图片先过 A07；无权限与不存在同语义 |

**置信度与位置不伪造**：`ModelPort` 的 OCR 输出只有整页文本，没有逐区域置信度，因此
`AiVisionOcrResultDTO.confidenceSource = UNKNOWN`、`confidence = null`、`reviewRequired = true`
（机器识别一律标记需人工复核）；识别稿正文逐页写明"识别范围：整页 · 置信度来源：未提供（或上游提供）·
未人工核验"，并在切片载荷里保留"第 N 页 + 机器识别"字样，供引用时核验。

## 3. 变更文件清单

### 3.1 starter-ai（供应商适配：能力探测）

| 文件 | 变更 |
|---|---|
| `provider/springai/SpringAiModelClient.java` | 新增 `IMAGE_UNDERSTANDING` / `IMAGE_OCR` 真实探测（`probeImageUnderstanding`、`probeImageOcr`、`callVisionOnce`），把 X01 的"适配器未实现"占位替换为真实多模态调用；生成/编辑与语音四项仍返回 `CODE_ADAPTER_NOT_IMPLEMENTED` |
| `provider/springai/SyntheticMediaFixtures.java` | 新增：平台内置最小合成图（理解图 / OCR 文字行条带图），探测不依赖外部素材 |
| `test/.../SpringAiMediaProbeTest.java` | 新增：媒体探测的真调用/无产物/能力未声明三条路径 |
| `test/.../SpringAiModelProbeTest.java` | 移除已被媒体探测用例覆盖的过期断言 |

### 3.2 module-ai（平台侧闭环）

| 文件 | 变更 |
|---|---|
| `service/vision/AiVisionService(+Impl)` | `understandImage` / `recognizeText`：守卫 → 准入 → A07 读文件并核验 → 端口调用 → VO 结果 |
| `service/vision/AiVisionImageGuard.java` | 输入守卫：声明 MIME 白名单（PNG/JPEG/WEBP）、字节上限、像素上限、sha256 归一化；伪装图片（声明 PNG 实为非图片）与超大像素在此拒绝 |
| `service/vision/AiVisionImageHeader.java` / `AiVisionImageFormat.java` / `AiVisionImageInfo.java` | 从真实字节解析图片头（宽/高/格式），用于像素与格式判定 |
| `service/vision/AiVisionLimits.java` | 平台侧上限常量（字节、像素、OCR 字符数） |
| `service/vision/AiVisionRegionSource.java` / `AiVisionConfidenceSource.java` | 位置/置信度**来源**词汇（WHOLE_PAGE / PROVIDER / UNKNOWN），不接受凭空的数值 |
| `service/vision/dto/*`（7 个） | 请求/结果 DTO（协议无关，Service 层不使用 VO） |
| `service/document/AiDocumentOcrService(+Impl)` | 多页 OCR：逐页走 OCR 能力，无文本即整笔拒绝 |
| `service/document/AiOcrDocumentSource.java` | 识别稿生成：页码升序、只写有文字的页、写明来源与未核验、服务端算 sha256 |
| `service/document/AiOcrDocumentReindexService(+Impl)` | 上传派生私有文件（A07 归属：该知识库的知识文档）→ 既有入库（版本 + 任务同事务） |
| `service/document/AiOcrPage.java`、`dto/*`（3 个） | 页结果与请求/结果 DTO |
| `controller/app/v1/vision/AiVisionController.java` + `vo/*`（7 个） | 三个 App 端点（`@AuthenticatedOnly`），VO 只在协议层 |
| `service/model/AiModelCapabilityProbeServiceImpl.java` | 探测顺序接入两个媒体探测项（否则媒体能力永远无法"探测确认"，见 §6 边界判断） |

### 3.3 测试与台账

| 文件 | 变更 |
|---|---|
| `module-ai/src/test/.../service/vision/AiVisionImageGuardTest.java` | 守卫用例（五类拒绝） |
| `module-ai/src/test/.../service/vision/AiVisionServiceImplTest.java` | 服务用例（准入未过不外发、端口失败映射、空文本拒绝） |
| `module-ai/src/test/.../service/document/AiDocumentOcrServiceImplTest.java` | 多页 OCR 与整笔拒绝 |
| `module-ai/src/test/.../service/document/AiOcrDocumentSourceTest.java` | 识别稿正文/页码/置信度来源/摘要 |
| `module-ai/src/test/.../service/document/AiOcrDocumentReindexServiceImplTest.java` | 派生文件上传参数与入库入参 |
| `module-ai/src/test/.../controller/app/v1/vision/AiVisionControllerTest.java` | 三个端点的请求映射与鉴权 |
| `server/src/test/.../integration/AiVisionOcrAcceptanceIT.java` | 真实 MySQL/Redis：理解、OCR、无权限拒绝、失败不替换旧版本、识别稿真的进了切片 |
| `server/src/test/.../AiAppEndpointScopeContractTest.java` | 三个新端点登记进 `REVIEWED_AUTHENTICATED_ENDPOINTS` |
| `docs/contracts/ai/scope-catalog.md` | 同上（契约台账同步） |
| `docs/integrations/open-api/ai-open-api.json` | 三个 App 端点登记（43 条路径） |
| `packages/ai-chat-ui/src/attachment/vision-attachment.ts`、`ImageAttachmentCard.vue` + 2 个测试 | 附件卡片：识别结果引用、置信度来源展示、"需人工复核"提示；不含 `v-html`/`innerHTML` |

## 4. 验收映射

| 验收项 | 判据 | 证据 |
|---|---|---|
| AT-023 超大/损坏/伪装文件 | 声明 MIME 与实际字节不符、超过字节/像素上限、无法解析图片头 → 拒绝且不读文件、不外发 | `AiVisionImageGuardTest`、`AiVisionServiceImplTest`（准入未过时 `OCR_CALLS=0`） |
| AT-025 跨主体隔离 | 无权限文件与不存在的文件同语义（`AI_RESOURCE_NOT_FOUND`），且**不外发**；派生识别稿必须绑定该知识库才可入库 | `AiVisionOcrAcceptanceIT#unownedPrivateFileCannotBeRecognizedAndProducesNoOutbound`（断言 `OCR_CALLS` 为 0）、`#documentOcrReindexesAsNewVersionAndFailedOcrKeepsOldActiveVersion`（A07 归属断言） |
| AT-068 能力未开通 | 未声明/未探测确认 → 准入拒绝，且不读文件、不外发 | `AiMediaCapabilityGate`（X01 交付）+ `AiVisionServiceImplTest`；`AiVisionOcrAcceptanceIT#undeclaredCapabilityIsRejectedBeforeReadingFileOrCallingModel`；媒体运行时适配缺口见 §6 |
| 扫描件 OCR 失败不替换旧版本 | OCR 无文本 → 整笔拒绝，旧 active 版本不变、版本数不增 | `AiVisionOcrAcceptanceIT` 同用例第 2 段（`AI_MEDIA_OUTPUT_EMPTY` + `listVersions` 仍为 1） |
| 识别稿走既有切片语义 | 新版本 `sourceRef=ocr:sourceFileId=…`、切片载荷含"第 1 页 / 机器识别 / 正文"、位置来自既有解析器（`段落 1`） | 同用例第 3 段 |

## 5. 验证执行

命令均在授权副本内执行；后端工作目录 `后端代码/basic-framework-boot`，门禁在副本根目录执行。

| 步骤 | 命令（工作目录见下） | 结果 |
|---|---|---|
| 集成（本卡用例，聚焦） | `./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiVisionOcrAcceptanceIT' -DfailIfNoTests=false` | `Tests run: 4, Failures: 0, Errors: 0`（exit 0，报告 `basic-framework-server/target/failsafe-reports/com.basicframework.server.integration.AiVisionOcrAcceptanceIT.txt`） |
| 契约门禁 | 副本根目录 `sh .harness/verify.sh contracts` | exit 0（见 §7） |
| 后端门禁 | 副本根目录 `sh .harness/verify.sh backend` | exit 0（见 §7；单测 2533 例） |
| 集成门禁（最终记录） | 副本根目录 `sh .harness/verify.sh integration` | exit 0（见 §7；IT 272 例 + 覆盖率棘轮 backend 通过） |
| 前端门禁 | 副本根目录 `sh .harness/verify.sh frontend` | exit 0（2138 例全绿；见 §7） |

命令均在授权副本内执行；后端工作目录 `后端代码/basic-framework-boot`（`umask 022`、`JAVA_HOME` 指向 JDK 17）。

## 6. 边界判断与已知缺口

1. **派生识别稿后缀用 `.txt` 而不是 `.md`**：受控上传（F06/A07）的白名单不含 `.md`，`.md` 会在
   `FileTypeUtils.isAllowedUploadType` 被拒；K04 对 `.txt`/`.md` 走同一个文本分段解析器（位置同为 `段落 N`），
   因此改用 `.txt` 不损失任何位置或切片语义。这是"平台受控上传策略优先于文件名美观"的选择。
2. **`service/model/AiModelCapabilityProbeServiceImpl` 超出本卡列出的目录**：媒体能力必须进入探测顺序才能被
   标记为"探测确认"，否则 X01 的准入矩阵永远无法对这两个能力成立。已登记为需复核的范围扩展。
3. **运行时供应商适配缺口（未验证项）**：`SpringAiModelClient` 本轮只实现两个媒体能力的**探测**；
   `understandImage`/`recognizeImageText` 的运行时调用仍落到 `ModelPort` 默认实现（显式
   `CAPABILITY_NOT_ENABLED` 拒绝，不静默、不回退文本探测）。真正的多模态运行时适配需要在
   starter-ai 侧增加"按 fileId 取字节"的接缝（`MediaFileRef` 明确不携带字节），该接缝与生成/编辑、
   语音的运行时适配一并归入 X03/X04；本轮平台侧全链路以端口替身（`VisionOcrTestConfiguration`）验证。
4. **真实模型评测缺失（未验证项）**：识别质量、字号/版式差异、多语言表现都需要真实模型环境，
   属 Q10 登记的发布阻断项（真实评测环境），本轮不伪造通过率。

## 7. 门禁结果

（见下表；每项含命令、退出码与关键计数）

| 门禁 | 退出码 | 关键结果 |
|---|---|---|
| `contracts` | 0 | 权限目录：接口引用 138 权限码 / 目录 139、不可授予 4、保留 5；门禁接线：check 脚本 17、辅助 3、测试 25；安全信号 4 项三方一致；例外台账 1 条、过期 0 |
| `backend` | 0 | `./mvnw -q clean verify`：单测 2533 例（482 个测试类）全绿；spotless/arch 门禁通过 |
| `integration` | 0 | `./mvnw -q -Pintegration clean verify`：IT 272 例（75 个测试类）0 失败（1 跳过，为既有的按环境跳过用例）；随后 `check-coverage-ratchet.mjs backend` 通过 |
| `frontend` | 0 | `pnpm check` + `pnpm lint` + `pnpm test:coverage` + 生产构建全部通过：前端测试 2138 例（386 个测试文件）全绿，工作区行覆盖 91.63%；随后 `check-coverage-ratchet.mjs frontend` 通过 |

覆盖率棘轮：本卡新增后端文件 15 个、前端文件 2 个已登记基线（新文件最低 80%）；既有的 `SpringAiModelClient`
（+X02 探测实现）从 98.45% 基线提升记录到 98.62%；`AiVisionImageInfo` 的两个未使用辅助方法按"不留死代码"删除，
而不是为死代码补测试。

## 8. 未验证项清单

- 真实供应商多模态运行时调用（图片理解 / OCR 的厂商调用链，见 §6.3）。
- 真实模型环境下的识别质量与首轮评测（见 §6.4）。
- `.harness/verify.sh all` 中的 `dependencies` 段（Trivy 漏洞库在本网络不可下载，Q10 已登记为环境缺口）。
