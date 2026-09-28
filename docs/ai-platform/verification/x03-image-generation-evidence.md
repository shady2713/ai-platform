# X03 图片生成与编辑闭环 — 完成证据

| 项目 | 内容 |
|---|---|
| 任务卡 | [X03](../tasks/X03.md) |
| 需求 | FR-35（图片生成/编辑）；AT-068 |
| 依赖 | X01（多模态能力契约，已交付）、A07（文件业务 ACL，已交付）、O06（运行与任务语义，已交付） |
| 工作副本（唯一可写） | `/home/ctyun/桌面/zhongtai/ai-platform` |
| 基线 commit | `74c4b67`（X02 图片理解与 OCR 闭环） |
| 后端工作目录 | `/home/ctyun/桌面/zhongtai/ai-platform/后端代码/basic-framework-boot` |
| 前端工作目录 | `/home/ctyun/桌面/zhongtai/ai-platform/前端代码/basic-framework-admin` |
| 未验证 | 真实供应商生成/编辑调用（无凭据/无出网）；真实模型环境下的生成质量评测（Q10 阻断项） |

## 1. 交付范围

受理与执行分离的图片生成/编辑闭环：

```text
POST /app-api/ai/image/generate       受理文生图任务（幂等键去重）
POST /app-api/ai/image/edit           受理底图编辑任务（受理前核验底图归属与内容）
GET  /app-api/ai/image/task           任务事实（状态/失败码/产物/用量）
GET  /app-api/ai/image/task/page      按主体范围分页
POST /app-api/ai/image/task/cancel    取消（仅待领取状态）
```

任务由常驻 Job（`aiMediaTaskJob`，V85 内置）按租约领取并交给执行器；产物只以**平台私有文件编号**出现，
产物读取走既有受控文件端点（A07 业务类型 `ai_media_task`，仅所有者可读）。

## 2. 关键设计取舍

1. **为什么不复用 `ai_run` / `ai_run_task`**：`ai_run` 的 `service_id`/`release_id`/`content_hash` 均为 NOT NULL，
   运行语义绑定"已发布 AI 服务的会话运行"（文本链路，含提示词模板与上下文构建）。图片/语音生成是**端点级**调用，
   没有服务发布版本可绑定；硬塞进 `ai_run` 只能造假 `release_id`。因此按 K03 入库任务已验证的形态，
   新建**媒体任务**表（`ai_media_task`）承载同一组语义：幂等受理、租约栅栏、终态唯一、失败稳定码、用量如实。
   通用 `RUN_STEP` 常驻消费者是否上线仍属 Q10 登记的用户决策项，本卡不改变 `ai_run_task` 的语义。
2. **图片与（X04）语音共用一套任务模型**：`ai_media_task` 用 `media_kind` + `operation` + `capability` 表达种类，
   执行细节由 `AiMediaStepExecutor` 端口承接（X03 提供图片实现）；X04 只增加语音执行器与端点，不再造第二张任务表。
3. **不读上游地址**：产物只可能是端口返回的字节（`MediaArtifact`）。协议、任务表与产物表都没有 URL 字段；
   出网只发生在受管模型客户端内（端点地址 + 主机/端口白名单），因此"上游给个 URL 让我们去取"这条路径不存在。
4. **产物先验后存**：`AiVisionImageGuard.verifyOutput` 判定白名单格式 + 魔数 + 字节/像素上限，不合规整笔失败
   （`AI_MEDIA_OUTPUT_INVALID`，502），不落半张图、不转码。
5. **编辑底图两端都验**：受理阶段按当前主体读一次（A07 + 图片核验，无权/伪装在受理前就拒绝），
   执行阶段再读一次（受理时能读、执行时失权同样失败）。

## 3. 变更文件清单

### 3.1 持久化与台账

| 文件 | 变更 |
|---|---|
| `basic-framework-server/src/main/resources/db/migration/V85__ai_media_task.sql` | 新表 `ai_media_task`（受理即固定端点/配置版本、租约栅栏、幂等键唯一、用量来源）与 `ai_media_asset`（产物只存私有文件编号 + 服务端核验事实）；内置 `infra_job` id=33 `aiMediaTaskJob`（每 30 秒） |
| `数据库文件/basic_framework.sql` | 快照同步两张新表；声明版本 V85；逻辑删除表计数 51 → 53 |
| `docs/contracts/data-lifecycle.json` | 两张新表登记为 soft-delete |
| `docs/contracts/data-permission-exemptions.json` | 新增豁免条目 `ai-media-task`（`subject-bound`：应用 + 主体类型 + 外部用户标识；产物文件走 A07 仅所有者） |
| `PersistenceLifecycleIT` / `RemovedCapabilityMigrationIT` | 期望表清单加入两张新表；内置 Job 计数 13 → 14 |

### 3.2 模块实现（module-ai）

| 文件 | 变更 |
|---|---|
| `dal/dataobject/media/AiMediaTaskDO.java`、`AiMediaAssetDO.java` | 任务与产物 DO（状态/操作/计量来源常量集中在此） |
| `dal/mysql/media/AiMediaTaskMapper.java`、`AiMediaAssetMapper.java` | 幂等定位、主体范围分页、CAS 领取/续租/终态/取消、过期租约恢复 |
| `service/media/AiMediaTaskService(+Impl)` | 幂等受理、主体范围查询与取消、租约领取与终态栅栏、过期租约恢复 |
| `service/media/AiMediaStepExecutor.java` + `dto/*`（Submit/Result/Asset/Lease/StepOutcome） | 执行端口与 DTO（X04 复用同一任务模型） |
| `service/image/AiImageParams.java` | 尺寸白名单、张数 1–8、格式白名单、文本与幂等键长度收窄 |
| `service/image/AiImageService(+Impl)` | 受理前核验底图（A07 + 图片核验），生成/编辑受理 |
| `service/image/AiImageStepExecutor.java` | 准入 → 端口调用 → 产物核验 → 落私有文件 → 产物行 + 用量 |
| `job/AiMediaTaskJob.java` | 常驻消费者：恢复过期租约 → 领取 → 分发 → 写终态（稳定原因码） |
| `controller/app/v1/image/AiImageController.java` + `vo/*`（6 个） | 5 个应用端点与协议层 VO |
| `service/file/AiFileBusinessType.java`、`adapter/file/AiFileAccessProviderConfiguration.java` | 新业务类型 `ai_media_task`（仅所有者可读）+ 授权 Provider 装配 |
| `service/vision/AiVisionImageGuard.java`、`AiVisionImageOutput.java` | 复用图片核验：抽出 `verifyInput`/`verifyOutput` 公开入口（输入与产物同一条白名单/像素拒绝线） |
| `enums/AiErrorCodeConstants.java`、`docs/contracts/ai/error-code-map.md` | 新错误码 `AI_MEDIA_OUTPUT_INVALID`（1_003_010_005，502） |

### 3.3 协议与前端

| 文件 | 变更 |
|---|---|
| `docs/contracts/ai/scope-catalog.md`、`AiAppEndpointScopeContractTest` | 5 个应用端点登记（`@AuthenticatedOnly` + 主体范围判定说明） |
| `docs/integrations/open-api/ai-open-api.json` | 新增 5 条 `/app-api/ai/image/**` 路径（43 → 48） |
| `packages/ai-chat-ui/src/message/image-generation.ts`、`ImageGenerationCard.vue` + 2 个测试、README.md | 生成/编辑卡片：状态、失败码、产物（只发 fileId 事件，不硬编码任何 URL）、用量未知态、源图不可读态、取消幂等 |
| `packages/ai-chat-ui/src/index.ts` | 导出卡片与视图类型/标签（宿主可直接 `import { ImageGenerationCard } from '@vben/ai-chat-ui'`），与 X02 附件卡片同一条导出约定 |

### 3.4 测试

| 文件 | 变更 |
|---|---|
| `module-ai/src/test/.../service/media/AiMediaTaskServiceImplTest.java` | 幂等受理、状态冲突、租约栅栏、用量不伪造 |
| `module-ai/src/test/.../service/image/AiImageParamsTest.java` | 参数收窄的边界 |
| `module-ai/src/test/.../service/image/AiImageServiceImplTest.java` | 生成/编辑受理与底图核验 |
| `module-ai/src/test/.../service/image/AiImageStepExecutorTest.java` | 准入未过不外发、产物不合规拒绝、产物落库与用量 |
| `module-ai/src/test/.../job/AiMediaTaskJobTest.java` | 无执行器/执行异常/栅栏未命中的稳定行为 |
| `module-ai/src/test/.../controller/app/v1/image/AiImageControllerTest.java` | 端点映射、鉴权注解、无地址字段 |
| `server/src/test/.../integration/AiImageGenerationAcceptanceIT.java` | 真实 MySQL/Redis：生成/编辑全链路、幂等、产物不合规、取消与跨主体 |

（测试文件为子代理补全，具体行号与计数见 §5；如与最终实现有差异以代码为准）

## 4. 验收映射

| 验收项 | 判据 | 证据 |
|---|---|---|
| AT-068 能力未开通 | 未声明/未探测确认 → 准入拒绝，且不发生调用 | `AiImageStepExecutor` 走 `AiMediaCapabilityGate`；IT 用例 |
| 重复提交同幂等键不重复生成 | 二次提交返回既有任务，上游调用次数不增加 | 单元 + IT |
| 上游任意 URL 不能绕过网络策略 | 产物只接受字节；非图片字节（如 HTML）整笔失败且不落库 | `AiVisionImageGuard.verifyOutput` 单测 + IT |
| 源图失权后编辑拒绝 | 受理前与执行期两次 A07 读取，失权 → `AI_RESOURCE_NOT_FOUND` | 单元 + IT |
| 取消/失败/进度可查，用量如实 | 终态与取消语义由租约栅栏保证；上游未给用量 → UNKNOWN 且数值为空 | 单元 + IT |

## 5. 验证执行

| 步骤 | 命令（工作目录：副本根目录，后端目录为 `后端代码/basic-framework-boot`） | 结果 |
|---|---|---|
| 契约门禁 | `sh .harness/verify.sh contracts` | exit 0（生命周期台账：84 张表 / 53 张逻辑删除列 / 65 条物理外键；数据权限分类 73 应用表；权限目录 138/139；门禁接线 17/3/25） |
| module-ai 单测 | `./mvnw -o -pl basic-framework-module-ai test` | exit 0：`Tests run: 1035, Failures: 0, Errors: 0`（其中本卡新增 84 例） |
| module-ai 覆盖率 | `./mvnw -o -pl basic-framework-module-ai verify` | exit 0；jacoco 单文件（xcsv）：`AiMediaTaskServiceImpl` 98.7%、`AiMediaTaskJob` 100%、`AiImageParams` 100%、`AiImageServiceImpl` 100%、`AiImageStepExecutor` 98.6%、`AiImageController` 98.1%、两个 Mapper 100%、DTO/产物视图 100% |
| 本卡验收 IT | `./mvnw -o -pl basic-framework-module-ai -am install -DskipTests` 后 `./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiImageGenerationAcceptanceIT' -DfailIfNoTests=false` | exit 0：`Tests run: 9, Failures: 0, Errors: 0`（真实 MySQL/Redis；连跑 3 次结果一致） |
| 相邻回归 | 同上 `-Dit.test='AiVisionOcrAcceptanceIT'`、`-Dit.test='RemovedCapabilityMigrationIT,PersistenceLifecycleIT'` | exit 0（4 例 / 5+1 例） |
| 后端门禁 | `sh .harness/verify.sh backend` | 见 §7 |
| 集成门禁 | `sh .harness/verify.sh integration` | 见 §7 |
| 前端门禁 | `sh .harness/verify.sh frontend` | 见 §7 |

## 5.1 本卡交付过程中发现并修复的 3 个真实缺陷（都有失败证据，非"顺手改"）

1. **平台错误码透传分支是死代码**（`job/AiMediaTaskJob.java`）：`ERROR_CODE_PREFIX = "1_"` 与
   `String.valueOf(code)` 比较永远为假（平台错误码是十进制整数），执行器的 `ServiceException` 一律被收敛成
   `execution-failed`，`AI_MEDIA_OUTPUT_INVALID` 永远写不进 `failure_code`。修复：前缀取
   `AiErrorCodeRanges.AI_PREFIX` 的十进制形式（与评测执行器同一写法）。证据：单测期望
   `1003010005` 实际得到 `execution-failed`；IT 断言产物不合规时 `failure_code = 1003010005`。
2. **迁移内 `infra_job` 主键冲突**（`V85__ai_media_task.sql`）：id=33 已被 `V64` 占用，迁移直接
   `Duplicate entry '33'`，任何迁移过的库都无法启动（server 测试全挂）。修复：改用 id=38（当前最大 37 + 1），
   并由 `RemovedCapabilityMigrationIT`（内置 Job 计数 14、handler 名一致）与 `PersistenceLifecycleIT`
   （Quartz 注册含 `aiMediaTaskJob`）双向锚定。
3. **新受理任务会被"四舍五入到下一秒"**（`service/media/AiMediaTaskServiceImpl.java`）：
   `next_attempt_time` 写入带纳秒的 `datetime(0)`，MySQL 进位上取整，导致刚受理的任务在下一秒前不可领取
   （IT 出现 `claimed=0` 而任务仍是 QUEUED）。修复：写入前截断到秒（与 K03 入库任务写入方一致）。

## 5.2 套件规模触顶：测试容器连接上限（测试基础设施修复，非产品语义变更）

首次全量 integration 运行在 13:05 出现 `errorCode 1040 / SQLState 08004`（MySQL "Too many connections"），
随后 11 个 IT 类连锁失败（`AiMysqlQueryExecutionIT`、`AiQueryPlanIT`、`CacheAndProtectionIT`、
`FilePresignedUploadPersistenceIT` 等，均报"只读连接不可用/连接被拒"）。

- **证据**：日志中 991 处 `errorCode 1040`，首个出现在 `AiKnowledgeLifecycleIT` 之后；
  失败类与被测能力无关（缓存、文件、报表用例同样失败），不是某个产品的语义缺陷。
- **原因**：整个 IT 套件共用同一个 JVM 与同一个 MySQL 容器；除应用连接池外，连接器用例还会按连接器
  建受控**只读池**（按设计长期缓存，不在用例里销毁），MySQL 默认 `max_connections=151` 在本套件规模下被打满。
  套件这次多了一个 IT 类（本卡），跨过了上限。
- **处理**：`AbstractPersistenceIntegrationTest` 的 MySQL 容器命令加 `--max-connections=500`（只改测试容器，
  不改门禁、不改产品代码），并在注释里写明原因。替代方案（给连接器只读池加上限/回收）属连接器卡片范围，
  在此登记为后续项。

## 5.3 门禁结果

| 门禁 | 退出码 | 关键结果 |
|---|---|---|
| `contracts` | 0 | 生命周期台账 84 张表 / 53 张逻辑删除列 / 65 条物理外键；数据权限分类 73 应用表；权限目录 138/139；门禁接线 17/3/25；例外台账 1 条过期 0 |
| `backend` | 0 | `./mvnw -q clean verify` 全绿（module-ai 单测 1035 例含本卡新增 84 例） |
| `integration` | 见下 | maven 段：IT 全绿、0 失败、无连接上限连锁（1040 计数 0）；棘轮段在登记本卡新文件后通过（`check-coverage-ratchet.mjs all`） |
| `frontend` | 见下 | `pnpm check` + `lint` + `test:coverage` + 生产构建通过：388 个测试文件 / 2174 例全绿（本卡新增 36 例），工作区行覆盖 91.67%；棘轮登记后通过 |

覆盖率棘轮：本卡新增前端 2 文件与后端 12 文件已登记基线（新文件最低 80%）；
`AiVisionImageGuard` 因 X03 新增"输入/产物核验"公开入口导致行覆盖下滑，已补 5 条 `verifyOutput` 用例
（空内容/超字节/截断头/超像素/伪装字节）恢复并通过 95.12% 的既有基线——**没有下调任何既有基线**。

## 6. 未验证项

- 真实供应商生成/编辑的运行时适配（同 X02 §6.3：媒体运行时适配需要 starter-ai 侧"按 fileId 取字节"的接缝，
  与 X04 一起在具备真实模型环境时接入；本轮以端口替身验证平台侧全链路）。
- 生成质量与安全过滤效果评估需要真实模型环境（Q10 阻断项）。
