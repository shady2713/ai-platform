# X10 受控异步结果 Webhook — 完成证据

本记录是 [X10 受控异步结果 Webhook](../tasks/X10.md) 的验收证据。
依赖 [Q10](../tasks/Q10.md)、[F09](../tasks/F09.md)（受控出站边界）、[O06](../tasks/O06.md)（运行/任务语义）均已有证据文档；
投递语义复用 O03 的租约栅栏与 X03/X04 的常驻消费者形态。

工作副本：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。命令前统一
`umask 022; export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`，
工作目录 `后端代码/basic-framework-boot`（除注明外）。新迁移编号：**V88**（V87 为当前最高）。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 协议契约 | `docs/contracts/ai/webhook-protocol.md`：事件白名单、请求头、签名规范串与校验顺序（验签 + 时间戳窗口 + 投递编号去重）、正文结构、有界重试与结论分类、出站边界约束、接收端落地步骤 |
| 持久化 | 迁移 `V88__ai_webhook_delivery.sql`：`ai_webhook_target`（配置，软删除）、`ai_webhook_delivery`（事实，软删除 + 唯一键 `uk_ai_webhook_delivery_event`/`uk_ai_webhook_delivery_no`）、`ai_webhook_delivery_attempt`（尝试留痕，append-retention）；菜单 4124–4128、Job 39；快照同步至 V88 |
| 签名与防重放 | `service/webhook/AiWebhookSignature`：HMAC-SHA256 覆盖 `timestamp.deliveryNo.sha256hex(body)`，常量时间比较；`AiWebhookPayload` 固定字段序、无凭据无正文；投递编号 `whd_<32 hex>` 入队冻结、重试不变 |
| 目标登记 | `service/webhook/AiWebhookTargetService(+Impl)`：应用内标识唯一、事件白名单收窄、地址形状收窄（拒 URL 凭据信息）、密钥 CredentialCipher 密文（AAD 绑目标编号）与独立轮换、停用即停发 |
| 投递闭环 | `service/webhook/AiWebhookDeliveryService(+Impl)`：缺一条补一条的入队（正文与编号冻结）、CAS 领取 + 租约、结论落库（尝试行 + 状态迁移）、指数退避 + 上限、死信人工重投（追加预算、序号单调递增）、保留期清理 |
| 投递器 | `service/webhook/AiWebhookDeliverySender`：只经 F09 `ExternalHttpClient` 出站；发送前复检目标启用；3xx 不跟随、4xx 确定失败、429/5xx/超时/连接失败可重试；密钥只用于本地计算签名 |
| 常驻消费者 | `job/AiWebhookDeliveryJob`：补漏入队 → 恢复过期租约 → 领取 → 投递 → 落结论 → 清理留痕；**只读写投递与尝试两张表**，不触碰运行/任务 |
| 应用端 API（控制面） | `controller/admin/webhook/AiWebhookTargetController`（create/update/update-status/rotate-secret/delete/get/page）与 `AiWebhookDeliveryController`（get/page/attempts/redeliver），权限码 `ai:webhook:query|manage|rotate|delete|redeliver` |
| 接收端样例 | `basic-framework-server` 测试夹具 `AiWebhookReceiverSample`：**真实本机 HTTP 接收端**，按协议顺序校验（必需头 → 时间戳窗口 → 验签 → 投递编号去重）并支持 6 种故障注入 |
| 控制台页面 | `apps/web-ele/src/views/ai/open-platform/webhook`：目标维护（登记/停用/轮换密钥/删除）+ 投递与死信视图（筛选、尝试留痕、人工重投），纯逻辑与组件测试 18 例 |
| 契约台账 | `docs/contracts/ai/error-code-map.md`（`1_003_011_xxx` + 传输原因码词表）、`data-lifecycle.json`、`data-permission-exemptions.json`（`ai-webhook-delivery`）、`docs/data-lifecycle.md`、`docs/security/outbound-http-boundary.md`、`PersistenceLifecycleIT` 表清单、`RemovedCapabilityMigrationIT` job 计数、模块 README |
| 错误码 | `AiErrorCodeConstants`/`AiErrorCodeRanges` 新增 `1_003_011_xxx` 子区间（7 个码，两侧同步） |

## 2. 与卡片逐步实施的对应

1. **登记应用回调目标与事件白名单，网络目标复用受控 HTTP 策略**
   - 目标属于应用（`application_id`），应用内标识唯一且创建后不可修改；事件白名单只接受
     `RUN.SUCCEEDED`/`RUN.FAILED`/`RUN.CANCELLED`，空集合与非法取值直接 400（不会"默认订阅一切"）。
   - 登记期只做**形状收窄**（绝对 http/https、有主机、**拒绝 URL 内的 `user:pass@` 凭据信息**、长度有界）；
     "能不能出站"永远由 F09 允许清单与私网策略判定，不在登记期复制第二份真值。
   - 发送链路只走 `ExternalHttpClient`（`GuardedExternalHttpClient`）：未列入允许清单的主机/端口在发送前被拒（零请求）。
2. **持久化事件投递与尝试记录，签名绑定时间戳/事件 ID/正文摘要，密钥由 CredentialCipher 保护并支持轮换**
   - `ai_webhook_delivery` 冻结正文（`payload_json`）与摘要（`payload_digest`）以及对外唯一的投递编号；
     重试复用同一份字节与同一个编号 → 接收端的去重与验签在重试之间仍然成立（只有时间戳与尝试序号逐次变化）。
   - 签名规范串 `timestamp.deliveryNo.sha256hex(body)`；密钥只存 `secret_ciphertext`（AAD 绑目标编号），
     轮换走独立接口并递增 `secret_revision`，任何响应只返回 `secretConfigured`/`secretRevision`。
   - 每次完成的尝试单独留痕（结论、HTTP 状态、稳定原因码、签名时间戳、耗时），按保留期分批物理清理。
3. **明确至少一次投递、接收端去重、有限退避、死信查看和人工重投，只发送有权状态与资源引用**
   - 至少一次：领取是 CAS + 租约，进程崩溃留下的 `RUNNING` 由租约过期恢复（未达上限回队列、达上限置死信）。
   - 接收端去重：投递编号是对外唯一且重试不变；接收端样例对重复编号返回 409 且不二次处理。
   - 有限退避：`base × 2^(attempt-1)`（默认 30s 起）并有 `retry-max-seconds`（默认 600s）封顶，
     次数上限取目标 `max_attempts`（入队时快照）；超限落死信并记独立结论 `1_003_011_006`。
   - 死信查看：投递分页支持按状态/事件/目标过滤（`FAILED` 即死信视图）+ 尝试留痕接口；
     人工重投只对死信生效、目标必须存在且启用（停用/删除一律拒绝）。
   - 只发有权状态与引用：正文只有 `eventType`/`resourceType`/`resourceKey`/`status`/`occurredAt`；
     不含提示词、模型响应正文、会话内容、上游地址与凭据；管理端响应只有状态、计数、稳定原因码与正文摘要。

## 3. 关键安全语义与不变量

- **投递失败绝不影响运行结果**：投递链路只读 `ai_run`（入队扫描与正文构造），从不写运行/任务表、
  从不重新发起运行。集成测试在重试耗尽、超时、重定向、未授权目标、目标停用等所有失败分支上
  断言运行状态、事件数与任务数**逐项不变**（`run_it_wh_retry`/`run_it_wh_lost`）。
- **未授权与私网目标零请求**：`TARGET_NOT_ALLOWED`（主机/端口不在清单）与 `PRIVATE_TARGET_DENIED`
  （环回/私网未显式批准）都在发送前拒绝；用例断言本机接收端请求数保持 0。
- **重定向不跟随**：3xx 原样返回并按确定失败收尾，绝不改址重发（避免被引向未批准主机）。
- **停用即停发**：停用后入队不建行、发送前复检失败（在途投递按事实收尾）、人工重投被拒；
  重新启用后补投停用期间的事件（无水位线扫描的既定语义，已写入协议文档）。
- **至少一次 + 接收端去重 = 副作用一次**：投递编号唯一且重试不变；接收端样例对重复编号返回 409
  并保持"已处理事件唯一"。用例覆盖"接收端已生效但响应丢失"：重试撞上 409 被按确定失败收尾，
  接收端事件仍只有一条。
- **密钥边界**：密钥不进日志、不进响应、不进 `toString`（DO/VO/DTO 都 `@ToString.Exclude`）；
  出站请求头全部由服务端构造，不转发入站头与宿主凭据；URL 里的凭据信息在登记期就被拒绝。
- **响应/日志无正文**：管理端不返回 `payload_json`（只给 `payloadDigest`），
  投递行与尝试行只落稳定原因码，不含上游响应正文、目标地址与密钥。

## 4. 验收用例对照

| 卡片 §4 验收 | 覆盖点 | 证据 |
|---|---|---|
| 伪造签名/过期时间戳/重放被示例接收端拒绝 | 换密钥重算签名 → 401；签名正确但时间戳越窗 → 401；同一投递编号再来一次 → 409 且不二次处理 | `AiWebhookDeliveryIT.receiverRejectsForgedSignatureStaleTimestampAndDuplicateDelivery`（真实接收端）、`AiWebhookSignatureTest` |
| 投递超时不重新执行原 run | 接收端已生效但响应丢失 → 平台记 `timeout` 可重试；重试撞去重 409 → 确定失败；全程运行状态/事件/任务数不变；接收端事件恰好一条 | `AiWebhookDeliveryIT.aLostResponseAfterAcceptanceCannotProduceASecondReceipt`、`retriesAreBoundedAndExhaustionBecomesADeadLetterWithoutRerunningTheRun` |
| 停用应用停止发送 | 停用后入队不建行、接收端请求数不增；在途投递发送前复检失败（`1_003_011_001`）；停用状态下人工重投被拒；重新启用后重投成功 | `AiWebhookDeliveryIT.disablingATargetStopsNewDeliveriesAndFinishesInflightOnesAccordingToFacts`、`AiWebhookDeliveryServiceImplTest.redeliverRejectsDisabledTargets` |
| 未授权目标与重定向被拒 | 未授权主机确定失败且零请求；私网地址未批准被 `PRIVATE_TARGET_DENIED`（零请求）；302 只发一次、按确定失败收尾 | `AiWebhookDeliveryIT.unauthorizedTargetsAndPrivateAddressesAreRefusedBeforeAnyRequest`、`redirectIsNotFollowedAndIsRecordedAsADefiniteFailure` |
| 卡片 §5 投递与管理闭环 | 终态运行 → 入队（幂等）→ 领取 → 真实 socket 投递 → 接收端验签 → 投递行 SUCCEEDED + 尝试行 DELIVERED；控制面分页（目标/投递）只返回登记与投递事实 | `AiWebhookDeliveryIT.terminalRunIsDeliveredOverTheRealBoundaryAndTheReceiverVerifiesTheSignature`、`enqueueIsIdempotentAndNeverDuplicatesTheDeliveryRow`、`managementQueriesReturnTheRecordedFacts` |
| 卡片 §5 接收示例 | 真实本机接收端按协议顺序校验并提供稳定拒绝码；6 种故障注入（5xx/4xx/3xx/未生效超时/已生效响应丢失/正常） | `AiWebhookReceiverSample` + 上表各用例 |
| 卡片 §5 故障与安全验证 | 出站边界拒绝、重定向、超时、5xx 重试上限、死信、人工重投、尝试留痕保留期清理 | 见第 5 节命令 5 |

## 5. 验证结果（真实命令、退出码、测试数）

| # | 命令 | 退出码 | 结论 |
|---|---|---|---|
| 1 | `./mvnw -q -o -pl basic-framework-module-ai spotless:apply` | 0 | 格式（spotless 构建期同样检查） |
| 2 | `./mvnw -o -pl basic-framework-module-ai test -Dtest='AiWebhook*Test' -DfailIfNoTests=false` | 0 | **68 例通过**（X10 相关 10 个测试类） |
| 3 | `./mvnw -q -o -pl basic-framework-module-ai -am install -DskipTests` | 0 | 供 server 集成测试解析（先装后用，避免幽灵失败） |
| 4 | `./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiWebhookDeliveryIT' -DfailIfNoTests=false` | 0 | **10 例通过**（真实 MySQL/Redis + 真实接收端 + 真实受控出站边界）；同次运行还跑了 server 单元测试 49 例 |
| 5 | `pnpm exec vitest run --dom apps/web-ele/src/views/ai/open-platform/webhook`（工作目录 `前端代码/basic-framework-admin`） | 0 | **18 例通过**（纯逻辑 11 + 组件 7） |
| 6 | `pnpm -F @vben/web-ele run typecheck` | 0 | `vue-tsc --noEmit` 通过 |
| 7 | `pnpm exec eslint apps/web-ele/src/views/ai/open-platform/webhook` | 0 | 前端 lint 通过（含 prettier 规则） |
| 8 | `node scripts/check-data-lifecycle.mjs` | 0 | 最终表 87 / 策略 87 / 逻辑删除列 55 / 物理外键 65；快照同步至 V88 |
| 9 | `node scripts/check-data-permission.mjs` | 0 | 应用表 76，运行时保护 2，显式豁免 74，平台托管 11 |
| 10 | `node scripts/check-permission-catalog.mjs` | 0 | 接口引用 143 个权限码，目录 144（含新增 5 个 `ai:webhook:*`） |
| 11 | `node scripts/check-source-quality.mjs` | 0 | 无超 800 行源码文件（本卡新增最大文件 552 行：集成测试） |
| 12 | `./mvnw -o -pl basic-framework-module-ai test`（模块全量单测，回归） | 0 | **1175 例通过**（X10 前为 1107，本卡新增 68 例） |
| 13 | `node scripts/check-coverage-ratchet.mjs backend` | 1（预期） | 只报 14 个新文件尚未登记基线，无既有文件低于基线 |
| 14 | `node scripts/check-sensitive-tostring.mjs` / `check-field-injection.mjs` / `check-controller-validation.mjs` / `check-field-catalog.mjs` / `check-safe-exception-handling.mjs` / `check-sensitive-diff-log.mjs` / `check-security-signals.mjs` / `check-exceptions.mjs` / `check-gate-wiring.mjs` / `check-gate-rejection-tests.mjs` / `check-starter-documentation.mjs` | 0（各） | contracts 门禁的脚本级检查全部通过 |

未在本卡执行（由协调者的完整门禁承担）：`clean verify` 全量后端、全量集成（含 `PersistenceLifecycleIT`/
`RemovedCapabilityMigrationIT`）、契约棘轮登记、前端构建。本卡已按同一形状同步这几处台账：
`PersistenceLifecycleIT` 期望表清单（新增 `ai_webhook_delivery`/`ai_webhook_target`）、
`RemovedCapabilityMigrationIT` 的 `infra_job` 计数（14 → 15）、SQL 快照（表 + 菜单 + Job + 版本声明）。

## 6. 覆盖率（模块报告实测，新文件下限 80%）

聚合报告（`basic-framework-coverage/target/site/jacoco-aggregate/jacoco.xml`，单测 + 集成实测行覆盖）：

| 文件 | 行数（可覆盖） | 覆盖 | 覆盖率 |
|---|---:|---:|---:|
| `service/webhook/AiWebhookDeliverySender.java`（新） | 68 | 68 | 100.0% |
| `service/webhook/AiWebhookDeliveryServiceImpl.java`（新） | 133 | 131 | 98.5% |
| `service/webhook/AiWebhookTargetServiceImpl.java`（新） | 132 | 124 | 93.9% |
| `service/webhook/AiWebhookSignature.java`（新） | 21 | 17 | 81.0%（未覆盖的 4 行是"JVM 缺 SHA-256/HmacSHA256"的两处防御性 catch，不可达） |
| `service/webhook/AiWebhookEventTypes.java`（新） | 24 | 24 | 100.0% |
| `service/webhook/AiWebhookPayload.java`（新） | 9 | 9 | 100.0% |
| `service/webhook/AiWebhookFailureCodes.java`（新） | 8 | 8 | 100.0% |
| `service/webhook/AiWebhookDeliveryOutcome.java`（新） | 8 | 8 | 100.0% |
| `job/AiWebhookDeliveryJob.java`（新） | 47 | 47 | 100.0% |
| `controller/admin/webhook/AiWebhookTargetController.java`（新） | 38 | 38 | 100.0% |
| `controller/admin/webhook/AiWebhookDeliveryController.java`（新） | 42 | 42 | 100.0% |
| `dal/mysql/webhook/AiWebhookTargetMapper.java`（新） | 11 | 11 | 100.0% |
| `dal/mysql/webhook/AiWebhookDeliveryMapper.java`（新） | 11 | 11 | 100.0% |
| `dal/mysql/webhook/AiWebhookDeliveryAttemptMapper.java`（新） | 3 | 3 | 100.0% |
| DO / VO / DTO（新，14 个文件） | 0 | 0 | 无逻辑行（Lombok 访问器无行号） |

三个 Mapper 的自定义语句（入队补漏扫描、领取/退避/落终态/租约恢复/人工重投、分页、尝试读取与保留期清理）
全部在真实 MySQL 上执行（单测 + `AiWebhookDeliveryIT`），因此没有 0 覆盖行。

`node scripts/check-coverage-ratchet.mjs backend` 实测输出（退出码 1）：只报 **14 个新文件尚未登记单文件覆盖率基线**，
**没有**任何已有文件低于基线。棘轮登记由协调者在完整后端/集成门禁后执行 `--update`；本卡未下调任何基线、未删除任何登记。

三个 Mapper 的全部自定义语句（入队补漏扫描、领取/退避/落终态/租约恢复/人工重投、尝试读取与保留期清理）
都在 `AiWebhookDeliveryIT` 的真实 MySQL 上执行；单文件基线与棘轮登记由协调者在完整门禁后执行 `--update`。

## 7. 自查发现的缺陷（实现过程中被测试暴露并已修复）

1. **人工重投重置尝试计数会撞尝试序号唯一键**：初版 `redeliver` 把 `attempt_count` 重置为 0，
   而尝试行的 `uk_ai_webhook_attempt_no(delivery_id, attempt_no)` 依赖领取时的计数——重投后的新尝试
   与历史尝试序号相同，尝试行插入失败（DuplicateKey），结论没有落库、投递行停在 `RUNNING`，
   Job 摘要出现 `claimed=1` 却没有任何结论计数。
   **修复**：人工重投的语义改为"**追加一份与目标配置一致的重试预算**"
   （`max_attempts = attempt_count + target.max_attempts`），尝试计数与序号保持单调递增；
   投递编号与正文不变，接收端去重语义不受影响。证据：`AiWebhookDeliveryIT.retriesAreBounded...`
   修复前失败（`claimed=1,delivered=0`）、修复后通过。
2. **`next_attempt_time` 亚秒值被 MySQL 向上取整**：人工重投与退避写的是带纳秒的 `LocalDateTime`，
   列是 `datetime(0)`，MySQL 会把 `.5` 及以上的小数秒向上取整成"未来 1 秒"，导致刚被重投的投递
   在下一秒之前不可领取（集成测试出现 `claimed=0`）。
   **修复**：写库前统一 `truncatedTo(SECONDS)`（与 X03 媒体任务受理时的约定一致）。
3. **接收端"已生效但响应丢失"的语义需要显式钉住**：合法重试会撞上接收端去重（409），
   平台按确定失败收尾（`last_error_code=http-client-error`，尝试行记录 HTTP 409）。
   这不是缺陷而是至少一次投递的代价，但必须可解释，因此写进协议文档并用
   `aLostResponseAfterAcceptanceCannotProduceASecondReceipt` 钉住（接收端事件恰好一条、运行不受影响）。
4. **重新启用目标会补投停用期间的事件**：入队是无水位线的"缺一条补一条"扫描，
   因此停用→重新启用会补投这段窗口内的终态事件（每轮有界）。这是刻意语义（事件不永久丢失），
   已在协议文档与 `disablingATargetStopsNewDeliveriesAndFinishesInflightOnesAccordingToFacts` 中显式声明；
   不想要补投时应保持停用或删除目标。

## 8. 未验证项与未交付项

1. **真实外部接收端**：未授权对接真实第三方系统（**未验证**）。本卡用**本机真实 HTTP 接收端样例**
   （真实 socket、真实验签、真实故障注入）验证平台语义；与真实外部系统的差异是网络时延、
   证书链与对端实现细节，接入真实系统需要用户明确授权的地址与共享密钥。
2. **多实例并发**：并发入队唯一键收敛、并发领取单赢家、栅栏失效不覆盖由同进程用例与数据库
   唯一键/CAS 语义验证；跨实例压测未做（同一 SQL 语义，未做多实例压测）。
3. **TLS/证书**：接收端样例是回环 `http`（需 `allow-private-targets=true`），
   未覆盖真实 `https` 证书校验路径；该路径由 F09 的 `GuardedExternalHttpClientTest` 覆盖。
4. **投递历史的长期归档**：本卡只做尝试留痕的保留期清理（默认 30 天，按批）；
   投递行与目标行的最终物理清理沿用统一保留策略，未在本卡定义具体归档流程。
5. **敏感端点登记（既有台账缺口，未修改）**：`docs/security/ai-sensitive-endpoints.md` 的机器校验
   （`AiSensitiveEndpointRegistryTest`）只扫描 `AiAuthController`/`AiApplicationController`/`AiFileController`
   三个控制器的硬编码清单，因此新增的 `AiWebhookTargetController#createTarget`/`#rotateSecret`
   （请求体携带签名密钥）不在该登记表内；既有的 `AiConnectorController`/`AiModelEndpointController`
   （同样有凭据入参）也不在其中。把它们纳入需要修改 `basic-framework-server` 的契约测试清单
   （超出本卡允许路径），本卡选择**不改动并如实报告**：新端点的密钥边界已由
   `AiWebhookTargetControllerTest`（响应不含明文/密文、DTO 不回显密钥）与
   `check-sensitive-tostring` 门禁覆盖。
6. **前端未做浏览器验收**：控制台页面有组件测试与 typecheck/lint，浏览器级验收（真实点击 + 视觉）
   属阶段验收范围，本卡未执行（`docs/ai-platform/verification/q06-browser-acceptance-evidence.md` 的流程）。
7. **文档包校验脚本未在本地运行**：`docs/ai-platform/scripts/verify-documents.py` 依赖的
   `jsonschema`/`openapi-spec-validator` 未安装在本机环境，本卡未执行；新增文档的**相对链接已逐一校验存在**
   （`x10-webhook-delivery-evidence.md`、`webhook-protocol.md`、`error-code-map.md`、`data-lifecycle.md`、
   `outbound-http-boundary.md` 五份文件 0 条断链），JSON 台账改动由 contracts 门禁脚本验证通过。
8. **`ai-open-api.json` 与 `scope-catalog.md` 未变更**：本卡没有新增应用端（`/app-api`）端点——
   投递目标与投递管理都在管理端（`/admin-api`），出站投递由平台发起，因此这两份契约台账无需同步
   （`AiOpenApiContractTest` 与 `AiAppEndpointScopeContractTest` 在本次集成运行中通过）。

## 主管复核补记：NFR-04 性能回归与修复（本卡合并前必须读）

首次全量 integration 运行发现 **Q07 的 NFR-04（20 并发受理 P95 ≤ 500ms）被打穿**：实测 accept
p50=509 / p95=584，复测 p50=714 / p95=904（此前各卡基线 p50≈137–150 / p95≈158–162）。受理代码路径未被 webhook 触碰，
因此做了一次对照实验（临时在容量用例里关 Quartz，实验代码已撤销，绝不用来"过门禁"）：**关掉后 p50=184 / p95=262 通过**，
打开后失败 → 责任方是每 10 秒运行的投递 Job。

根因（EXPLAIN ANALYZE，20k 终态运行 + 1 个启用目标）：`AiWebhookDeliveryMapper.selectPendingCandidates` 的补漏入队语句
每轮读 19,800 行、对每行做一次 `JSON_CONTAINS` 判定、`LIMIT 50` 又排在排序之后（=O(全部终态运行)），单轮 375–483ms，
且整轮在@Transactional 里；它占用一条 Druid 连接（测试池 max-active=20，正好等于受理并发数），于是每 10 秒把并发受理拖慢。

修复（X10 卡内）：入队改为**有界 + 可续**的两段扫描（水位/背压段 `update_time ASC` + 每轮重扫的最近窗口 `update_time DESC`，
状态集合由 Java 从白名单推导，去掉了逐行 `JSON_CONTAINS`），锚点取 `MAX(update_time)` 而不是应用时钟（本环境 DB 是 UTC、应用是
Asia/Shanghai，差 8 小时），并新增索引 `idx_ai_run_webhook_scan (application_id, update_time)`；读操作移出事务，每批（≤limit 行 + 一次水位 UPDATE）
一个短事务提交。修复后单轮扫描 14–70ms（计划 0.66ms），同一查询计划从 20,500 行降到 99 行 + 50 次覆盖索引查找。

修复后实测：`AiPlatformCapacityIT,AiWebhookDeliveryIT` 一起跑 accept **p50=155 / p95=162**（通过，且不是靠关 Quartz 或放宽目标换来的）；
20k 行种子最坏场景 p50=170 / p95=186。全量 integration 复跑：0 失败，`NFR-04 accept p50=151 / p95=171`。
新增回归测试 `AiWebhookDeliveryIT#boundedEnqueueIsResumableAcrossRoundsAndCoversEveryEligibleEventExactlyOnce`
（limit=1 每轮只入一条、多轮收敛到恰好一次、非白名单/非终态不入队、水位推进后同秒写入的运行仍会入队）。

**教训（已记入记忆）**：新增常驻定时任务必须实测"对请求路径的延迟影响"，不能只看功能测试；

### 后续复核点（未验证）

- 修复代理**在隔离运行下复现不出受理 P95 失败**（需要全量套件把 `ai_run` 撑到规模）；本卡的证据是"任务侧单轮成本实测 + 全量套件修复后转绿"。
- 未加自动化性能断言（时序/计划文本断言易脆）：守卫是新索引 + 两条有界语句，证据是 EXPLAIN 与实测数字。
- 历史积压（例如给多年老应用新建目标）按"应用最旧优先"补投，新事件由最近窗口优先，**积压事件顺序与原最近优先扫描不同**（语义仍是至少一次，已写进 mapper javadoc）。
