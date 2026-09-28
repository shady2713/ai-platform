# X04 非实时语音（STT/TTS）— 完成证据

| 项目 | 内容 |
|---|---|
| 任务卡 | [X04](../tasks/X04.md) |
| 需求 | FR-36（非实时语音）；AT-068 |
| 依赖 | X01（多模态能力契约，已交付）、X03（媒体任务模型，已交付）、A07（文件业务 ACL，已交付） |
| 工作副本（唯一可写） | `/home/ctyun/桌面/zhongtai/ai-platform` |
| 基线 commit | `2ca7bab`（X03 图片生成与编辑） |
| 后端工作目录 | `/home/ctyun/桌面/zhongtai/ai-platform/后端代码/basic-framework-boot` |
| 未验证 | 真实供应商 STT/TTS 调用（无凭据/无出网）；客户端音频上传路径（见 §3.1）；前端音频切片属 X05 |

## 1. 交付范围

复用 X03 的 `ai_media_task`/`ai_media_asset` 任务模型（不新造第二套任务体系、不新增 Job），补齐非实时语音两条链路：

```text
POST /app-api/ai/speech/transcribe     受理语音转写（源音频 = 平台私有文件引用 + 声明级元数据）
POST /app-api/ai/speech/synthesize     受理语音合成（文本 + 音色/语言等受控参数）
GET  /app-api/ai/speech/task           任务事实（状态/失败码/产物/用量）
GET  /app-api/ai/speech/task/page      按主体范围分页
POST /app-api/ai/speech/task/cancel    取消（仅待领取状态）
```

执行由既有的常驻消费者 `aiMediaTaskJob` 按 `operation`（TRANSCRIBE/SYNTHESIZE）分发到 `AiSpeechStepExecutor`；
产物（音频或识别文本）落平台私有文件，业务类型仍是 `ai_media_task`（仅所有者可读）。

## 2. 变更清单（要点）

| 位置 | 内容 |
|---|---|
| `module-ai/service/speech/**` | `AiSpeechAudioFormat`（X01 冻结音频白名单与魔数）、`AiSpeechAudioGuard`（声明与内容一致 + 时长/字节上限）、`AiSpeechParams`（文本/音色/语言提示形状收窄）、`AiSpeechService(+Impl)`（受理前核验源音频：A07 + 内容核验）、`AiSpeechStepExecutor`（准入 → 端口 → 产物核验 → 私有文件 → 产物行 + 用量） |
| `controller/app/v1/speech/**` + `vo/*` | 5 个应用端点（`@AuthenticatedOnly`）；协议里没有上游地址 |
| `starter-ai/provider/springai/SpringAiModelClient` | `SPEECH_TO_TEXT`/`TEXT_TO_SPEECH` 两个**真实探测**（合成 8 kHz WAV 夹具）、`synthesizeSpeech` 运行时实现、音频 MIME 映射 |
| `starter-ai/provider/springai/SpringAiModelClientFactory` + `SyntheticMediaFixtures` | 仅当端点声明语音能力时装配音频模型；合成 WAV 夹具 |
| `V86__ai_media_task_speech_columns.sql` | `voice`/`language_hint` 两列 + `output_format` 放开为空（见 §4.2） |
| 台账/文档 | `AiErrorCodeConstants` + `error-code-map.md`（`AI_MEDIA_OUTPUT_DURATION_EXCEEDED`=1_003_010_006）、`scope-catalog.md` 5 条、`ai-open-api.json` 48 → 53 条路径、`数据库文件/basic_framework.sql` 快照同步 V86、`docs/integrations/ai-platform-multimodal-endpoints.md` 登记两处缺口 |
| 测试 | module-ai：`AiSpeech{Params,AudioFormat,AudioGuard,Service,StepExecutor,Controller}Test`（46 例）；starter-ai：`SpringAiSpeechTest`（375 行）；server：`AiSpeechAcceptanceIT`（814 行 / 10 例，真实 MySQL/Redis） |

## 3. 验收映射

| 验收项 | 判据 | 证据 |
|---|---|---|
| AT-068 能力未开通 | 未声明/未探测确认 → 准入拒绝，零外发 | `AiMediaCapabilityGate` + `AiSpeechStepExecutorTest`；IT 中未声明端点不发生调用 |
| 转写：源音频必须有权限 | 受理与执行两次按当前主体读取（A07），失权 → 拒绝 | `AiSpeechAcceptanceIT`（失权用例） |
| 转写/合成失败不留假产物 | 上游无产物/非法音频 → 任务 FAILED，无 `ai_media_asset` 行 | `AiSpeechStepExecutorTest` + IT |
| 用量如实 | 上游未给 → UNKNOWN 且数值为空 | 执行器与 IT 断言 |
| 幂等 | 同幂等键重复提交返回既有任务，不重复调用上游 | IT |

## 4. 交付过程中发现的问题

### 4.1 客户端音频上传路径被 infra 白名单阻断（跨卡缺陷，登记未修）

`module-infra` 的受控上传白名单不含**任何音频后缀**（`FileTypeUtils.ALLOWED_EXTENSIONS`），
因此：客户端无法通过受控上传端点上传播音；TTS 产物（.mp3/.wav/.ogg）也无法落成平台私有文件——
两条路径都以 `FILE_TYPE_NOT_ALLOWED`（`1_001_003_003`）失败。本卡用两条 IT 用例把**当前**行为钉住
（`audioUploadThroughControlledFileEndpointIsRejectedByCurrentInfraWhitelist`、
`synthesizeCannotStoreArtifactWhileInfraWhitelistLacksAudio`），并在
`docs/integrations/ai-platform-multimodal-endpoints.md` §6 登记为需要 `module-infra` 小卡修复的项。
X04 未越界修改 infra 的文件类型策略（那是平台级安全边界，需要独立卡与复核）。

**影响**：平台侧语音语义（受理、幂等、租约、失权拒绝、产物核验）已按 IT 验证；端到端"客户端上传录音 → 转写"
仍被该策略阻断，属**未验证**（不是本卡可改的范围）。

### 4.2 `ai_media_task.output_format` 默认 'png' 造成假数据与幂等冲突（本卡修复）

转写任务没有任何输出格式，但列是 `NOT NULL DEFAULT 'png'`：任务行会记下一个并不存在的输出格式，
而且同幂等键重复提交在幂等比较里失败（`AI_IDEMPOTENCY_CONFLICT`）。修复：V86 把该列改为可空、去掉默认值；
图片生成/编辑与 TTS 仍在受理时显式写入；IT 断言转写任务行的 `output_format` 为 NULL。

### 4.3 执行失败原因码的既有收敛（记录，不属本卡修复）

`AiMediaTaskJob` 对**非** `1_003_*` 形状的异常一律收敛为稳定原因码 `execution-failed`（X03 语义）；
因此"音频上传被 infra 白名单拒绝"这类失败在任务行里表现为 `execution-failed`，而不是 infra 的错误码。
这保证了任务行只出现平台稳定码，但也意味着跨模块错误原因不外泄——已在 IT 与本文中写明。

### 4.4 STT 运行时适配缺口（未验证项）

`ModelPort.transcribeSpeech` 需要"按 fileId 取字节"的接缝（X01 契约里 `MediaFileRef` 明确不携带字节），
该接缝尚未建立：保持平台显式的 `CAPABILITY_NOT_ENABLED` 拒绝，不伪造成功路径。已在
`docs/integrations/ai-platform-multimodal-endpoints.md` §6 与 starter README 登记。

## 5. 验证执行

| 步骤 | 命令 | 结果 |
|---|---|---|
| 本卡 IT | `./mvnw -o -pl basic-framework-module-ai -am install -DskipTests` 后 `./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiSpeechAcceptanceIT' -DfailIfNoTests=false` | exit 0，`Tests run: 10, Failures: 0, Errors: 0`（首轮 3F+2E 暴露 §4.2 与断言问题，修复后连跑两次全绿） |
| module-ai 单测/覆盖率 | `./mvnw -o -pl basic-framework-module-ai verify` | exit 0，`Tests run: 1087`；新文件覆盖：`AiSpeechController` 98.15、`AiSpeechAudioFormat` 95.92、`AiSpeechAudioGuard` 89.74、`AiSpeechParams` 100、`AiSpeechServiceImpl` 100、`AiSpeechStepExecutor` 94.68、`AiSpeechAudioVerified` 100 |
| starter-ai | `./mvnw -o -pl basic-framework-core/basic-framework-spring-boot-starter-ai verify` | exit 0，`Tests run: 187`；`SpringAiModelClient` 98.18 → 98.91（不低于既有基线 98.62），`SpringAiModelClientFactory` 100，`SyntheticMediaFixtures` 96.49 |
| 端点登记契约 | `./mvnw -o -pl basic-framework-server test -Dtest='AiOpenApiContractTest,AiAppEndpointScopeContractTest,ErrorCodeUniquenessTest,...'` | exit 0，15 例 |
| 四道门禁 | 副本根目录 `sh .harness/verify.sh {contracts,backend,integration,frontend}` | 见 §6 |

## 6. 门禁结果

| 门禁 | 退出码 | 关键结果 |
|---|---|---|
| `contracts` | 0 | 首轮被"单文件 800 行"源码质量门禁拦下（`AiSpeechAcceptanceIT` 814 行）→ 拆成 `AiSpeechAcceptanceSupport`（夹具基类）+ 两个用例类（265 / 484 / 162 行，共 10 例不变），复跑通过 |
| `backend` | 0 | `./mvnw -q clean verify` 全绿 |
| `integration` | 0 | IT 全绿（含本卡 10 例）、连接上限连锁 0 处（`1040` 计数 0）；`check-coverage-ratchet.mjs backend` 通过（本卡新文件已登记，`SpringAiModelClient` 98.91% 高于既有基线） |
| `frontend` | 0（同批复跑） | 本卡未改前端文件；与 X09 同批的前端门禁：401 文件 / 2272 例全绿、行覆盖 91.82%、棘轮通过 |

## 7. 未验证项清单

- 真实供应商 STT/TTS 探测与 `synthesizeSpeech` 运行时调用（无凭据/无出网）；探测已接线、TTS 路径有录制式端口单测。
- STT 探测使用平台合成的 0.5 秒音调 WAV；真实端点对非语音音频返回空文本时记 `UNSUPPORTED + NO_TEXT_RETURNED`
  （沿用 X01 冻结判据），真实端点确认属未验证。
- 客户端上传录音 → 转写 的端到端路径（被 §4.1 的 infra 白名单阻断）。
- 前端音频切片（属 X05：实时语音会话与打断恢复的前置）。
