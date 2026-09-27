# Q07 性能与韧性验收 —— 前端 + 运维文档切片（完成证据）

| 项目 | 内容 |
|---|---|
| 任务卡 | [Q07](../ai-platform/tasks/Q07.md) |
| 本切片范围 | 前端韧性浏览器用例（`前端代码/basic-framework-admin/tests/**`）+ 运维文档（`docs/operations/**`）+ 本报告（`docs/acceptance/**`）；**不含**后端测试切片（并行代理负责 `后端代码/**`） |
| 状态 | **部分交付**：前端侧 AT-014/017/039/059 有真实浏览器实测；AT-016/018/059 的服务端并发/重启/配额实现属未验证项（需后端切片）；发现 1 个跨层缺陷（窗口过期错误码）与 3 项观察，均不在本切片允许路径，已给精确补丁 |
| 需求 | FR-09/10/11/31/34，NFR-04/05/06/08（见[产品需求](../ai-platform/02-product-requirements.md)） |
| 依赖 | Q06（浏览器夹具，已交付）、C02（会话状态机）、C01/O05（SSE 客户端与服务端）、Q02（配额占位） |
| 工作副本 | `/home/ctyun/桌面/zhongtai/ai-platform` |
| 环境 | Linux + Chromium 153.0.8010.12（playwright chromium-1243）；本机 `docker ps` 无容器、3306/48080 无监听（无 MySQL/后端/模型） |

## 1. 变更文件清单

| 文件 | 说明 |
|---|---|
| `前端代码/basic-framework-admin/tests/playwright/support/q07-resilience-api.mjs` | Q07 韧性夹具：应用端 API 子集的**拦截桩**（SSE 逐帧慢速下发、重复/乱序回放、非终态断流、连接重置、429、窗口过期）+ 探针页/探针 bundle 的服务端 + 请求账本 |
| `前端代码/basic-framework-admin/tests/playwright/support/q07-harness.ts` | 用例共用夹具操作：打开 Chat 生产产物页并把接口重定向到桩、发送、读界面文本/阶段徽标 |
| `前端代码/basic-framework-admin/tests/playwright/fixtures/probe/entry.mjs` | 探针入口（vite 打包）：挂载真实 `ResultTable.vue` + 真实 `blocks.ts` 解析；直接驱动真实 SDK 客户端做断线重连/窗口过期 |
| `前端代码/basic-framework-admin/tests/playwright/fixtures/pages/q07-probe.html` | 探针页（同源，无 CORS 干扰） |
| `前端代码/basic-framework-admin/tests/playwright/support/global-setup.mjs` | 增：构建 Q07 探针 bundle（`process.env.NODE_ENV` 定死、`@vitejs/plugin-vue`），与两个应用产物同一新鲜度规则（`Q06_FORCE_REBUILD=1` 强制重建） |
| `前端代码/basic-framework-admin/tests/playwright/specs/at-017-slow-consumer.pw.ts` | 新增 3 例：慢速重复/乱序回放去重与有界渲染、非终态断流（可重连、不假成功）、连接重置（稳定失败可见） |
| `前端代码/basic-framework-admin/tests/playwright/specs/at-059-quota-rejection.pw.ts` | 新增 2 例：受理 429 与订阅 429 的原因可解释、不假成功、可重试恢复 |
| `前端代码/basic-framework-admin/tests/playwright/specs/at-039-partial-unknown.pw.ts` | 新增 1 例：`PARTIAL`/`UNKNOWN` 显式标注、`COMPLETE` 不误报、空值 `—` 不补 0、分页信息如实展示 |
| `前端代码/basic-framework-admin/tests/playwright/specs/at-014-sse-reconnect.pw.ts` | 新增 2 例：断线重连带 `afterSeq` 且重放事件按 seq 去重、不重执行；窗口过期按契约转快照（tripwire，见 §5.1） |
| `前端代码/basic-framework-admin/tests/playwright/README.md` | 更新：Q07 用例与结论、Q07 夹具说明、新增缺陷 8/9/10、未验证项 |
| `docs/operations/queue-backlog-and-resilience-observability.md` | 运维文档：队列积压与韧性可观测量（表/Job 日志/接口/日志的查询与判读、"数据不足"表述、已知缺口） |
| `docs/acceptance/q07-frontend-resilience-and-ops.md` | 本报告 |

未改动：`packages/**`、`apps/**`、`后端代码/**`、`.harness/**`、根 `scripts/**`、`.github/**`、`docs/contracts/**`。

## 2. 实跑结果（本机、真实浏览器）

### 2.1 浏览器套件（全量，强制重建产物）

```
cd /home/ctyun/桌面/zhongtai/ai-platform/前端代码/basic-framework-admin
Q06_FORCE_REBUILD=1 pnpm exec playwright test --config tests/playwright/playwright.config.ts
```

| 指标 | 本次（Q07 后） | 基线（Q06 证据） |
|---|---|---|
| 退出码 | **0** | 0 |
| passed | **32** | 24 |
| failed / unexpected | **0** | 0 |
| skipped | 3（AT-056 ×2、AT-067 报表页 ×1，均为环境缺口） | 3 |
| 用时 | 119.1 s（另一次全量复跑 120.0 s，均含全量重建） | ≈1.8 min |
| 报告 | `.local-state/q06-browser/report.json`（`stats = {expected:32, skipped:3, unexpected:0, flaky:0}`） | 同左 |

新增 8 例（24 + 8 = 32）：`at-014`（2）、`at-017`（3）、`at-039`（1）、`at-059`（2）。**没有新增 failed，也没有放宽既有断言**；
其中 1 例是 `test.fail` tripwire（Playwright 计为 expected，不计入 failed；见 §5.1）。

### 2.2 组件级（会话状态机 / 客户端协议）

```
pnpm exec vitest run --dom packages/ai-chat-ui/src/conversation packages/ai-chat-ui/src/client packages/ai-embed-sdk/src apps/ai-chat/src
```
退出码 0；**14 个文件 / 108 例通过**（含 `state.test.ts` 6 例、`use-conversation.test.ts` 15 例、`client.test.ts` 9 + 7 例）。

### 2.3 工具链

| 命令 | 退出码 | 结果 |
|---|---|---|
| `pnpm exec tsc -p tests/playwright/tsconfig.json --noEmit` | 0 | 类型检查通过（无 `any`） |
| `pnpm exec eslint tests/playwright` | 0 | 含 `perfectionist` 导入顺序与 prettier 规则 |
| `pnpm exec prettier --check "tests/**"` | 0 | `All matched files use Prettier code style!` |
| `pnpm exec cspell lint "tests/**"` | 0 | 28 个文件，0 问题 |
| `node scripts/check-coverage-ratchet.mjs frontend` | 0 | `frontend 单文件基线通过`（本切片只新增测试文件，不改源码与基线） |
| `gzip -c packages/ai-embed-sdk/dist/ai-embed-sdk-5.6.0.js \| wc -c` | 0 | 23,824 字节（23.3 KB gzip；NFR-06 目标 ≤100 KB，实测值，非本切片交付物） |

## 3. 逐条 AT 结论

| AT | 本切片结论 | 证据（命令 + 数字） | 边界 |
|---|---|---|---|
| **AT-016** 取消与晚到完成并发 | **前端侧通过（组件级）**；服务端并发侧**未验证** | §2.2 复跑：`state.test.ts`（取消进入终态、重复取消不生效、取消后晚到完成按 seq 规则处理）+ `use-conversation.test.ts`（取消调用服务端、重试复用幂等键语义），共 108 例通过；C02 证据已登记 | 浏览器级未做（取消需要服务端 cancel 与真实运行）；并发终态一致性与"无重复结果"属后端切片 |
| **AT-017** 慢消费者 | **前端侧通过（新增 3 例真实浏览器）**；服务端"有界内存/服务可用"**未验证** | `at-017-slow-consumer.pw.ts` 3 例 pass：① 108 帧重复/乱序慢速回放（8ms/帧）→ 每个事件只渲染一次、整轮只产生 2 条消息、受理与订阅各 1 次；② 非终态断流 → 阶段停在"执行中"、无错误块、不重执行；③ 连接重置 → 阶段"失败（可重试）"、错误可见、无"已完成" | 服务端队列/内存/背压与 20 并发压测属后端切片；本切片只判界面在慢/断时的行为 |
| **AT-018** 进程重启与任务租约 | **未验证** | — | 需要 Testcontainers + 可执行 jar（重启语义），属后端切片；本机无 MySQL/Docker |
| **AT-039** API 分页截断/失败 | **前端显示口径通过（新增 1 例真实浏览器）**；分页截断的 API 行为**未验证** | `at-039-partial-unknown.pw.ts` pass：`PARTIAL` → `数据完整性：PARTIAL（结果可能不完整）`；`UNKNOWN` → 同形标注；`COMPLETE`/未声明 → 无标注（不误报）；空值 `—`（不补 0）；分页 `第 1 页 · 每页 2 条 · 共 45 条` 如实展示 | 连接器分页截断本身（D07）由后端/连接器证据覆盖 |
| **AT-059** 并发配额与异常释放 | **前端"429 可解释"通过（新增 2 例真实浏览器）**；"不超发/不永久占位"**未验证**，且发现运行链路未接配额占位（§5.4） | `at-059-quota-rejection.pw.ts` 2 例 pass：受理 429（`AI_QUOTA_EXCEEDED`=1_003_001_005）→ 界面原样显示服务端原因 `并发配额已用尽（占位 8/8），请稍后重试`、无"已受理运行"、无"已完成"；订阅 429 → 已受理但不显示完成、原因可见；配额恢复后点"重试"能重新受理（2 次受理请求，恢复成功） | 配额计数/释放（Redis/MySQL、`ai_quota_lease`）属 Q02 + 后端切片；前端只判"界面如实解释" |
| **AT-060** 上游 usage 缺失（顺带） | **部分**：结果块的"未知不写 0"浏览器通过；用量页 `UNKNOWN/ESTIMATED` 标签为组件级通过（Q03），浏览器级未验证 | `at-039` 用例（空值 `—`、`UNKNOWN` 标注）+ Q03 证据（`views/ai/usage/data.ts` 的 `describeUsageSource`/`isUnknownUsage` 组件测试） | 用量页需要登录态与后端，本机不可达 |
| **AT-014** SSE 断线重连（顺带，Q07 要求的"按 seq 去重"） | **客户端语义通过（1 例）+ 1 例 tripwire（已确认缺陷）** | `at-014-sse-reconnect.pw.ts`：第一次订阅 `reason=closed, lastSeq=2`；重连请求 `?runId=4001&afterSeq=2`；服务端重放 seq1/2 被丢弃，只采纳 3/4，`reason=terminal`；全程无第二个受理、无快照读取 | tripwire 见 §5.1（窗口过期常量不一致） |

## 4. 平台确定性与模型效果分开

- 本切片全部结论都是**平台确定性**：断言对象是"收到确定的协议响应后界面做什么"（去重、有界渲染、稳定失败、
  不假成功、标注完整性），用固定剧本的桩构造，**与模型质量无关**。
- **模型效果不在本切片范围**：没有任何用例依赖真实模型输出；涉及模型/凭据的步骤在本环境无法验证
  （`model-dependent: not configured in this environment`）。
- 数据来源标注：`REPORTED/ESTIMATED/UNKNOWN` 与 `COMPLETE/PARTIAL/UNKNOWN` 都是**平台口径**，
  界面只做如实展示（AT-039/060），不把估算当实测、不把未知当 0。

## 5. 已知缺陷、观察项与精确补丁（均不在本切片允许路径）

### 5.1 缺陷：重放窗口过期错误码与冻结契约不一致（影响 AT-014 的"转查询"）

- **现象（浏览器实测）**：服务端按契约返回 409 + `{code: 1_003_004_006, msg: '事件重放窗口已过期，请读取运行快照'}` 时，
  客户端抛 `AiOpenApiError{status:409, code:"1003004006"}`，**不读取运行快照**（用例 annotation 记录了完整返回值）。
- **原因**：前端常量写成 `1003004009`，而契约与后端都是 `1_003_004_006`（= `1003004006`）。
- **证据**：`docs/contracts/ai/error-code-map.md:52`（`1_003_004_006 | AI_RUN_EVENT_WINDOW_EXPIRED | 409`）；
  `后端代码/.../enums/AiErrorCodeConstants.java:426`；前端 `packages/ai-embed-sdk/src/client.ts:68`、
  `packages/ai-chat-ui/src/client/index.ts:39`。
- **精确补丁（4 处源码 + 1 处文档；本切片无权限，未改）**：

```diff
--- a/前端代码/basic-framework-admin/packages/ai-embed-sdk/src/client.ts
+++ b/前端代码/basic-framework-admin/packages/ai-embed-sdk/src/client.ts
@@ -68 +68 @@
-const RUN_EVENT_WINDOW_EXPIRED = '1003004009';
+const RUN_EVENT_WINDOW_EXPIRED = '1003004006';

--- a/前端代码/basic-framework-admin/packages/ai-chat-ui/src/client/index.ts
+++ b/前端代码/basic-framework-admin/packages/ai-chat-ui/src/client/index.ts
@@ -39 +39 @@
-export const RUN_EVENT_WINDOW_EXPIRED = '1003004009';
+export const RUN_EVENT_WINDOW_EXPIRED = '1003004006';

--- a/前端代码/basic-framework-admin/packages/ai-embed-sdk/src/__tests__/client.test.ts
+++ b/前端代码/basic-framework-admin/packages/ai-embed-sdk/src/__tests__/client.test.ts
@@ -151 +151 @@ （"重放窗口过期改为读取快照"用例）
-        return jsonResponse({ code: 1_003_004_009, msg: '重放窗口过期' }, 409);
+        return jsonResponse({ code: 1_003_004_006, msg: '重放窗口过期' }, 409);
@@ -228 +228 @@ （"窗口过期但快照读取也失败"用例）
-        return jsonResponse({ code: 1_003_004_009, msg: '重放窗口过期' }, 409);
+        return jsonResponse({ code: 1_003_004_006, msg: '重放窗口过期' }, 409);

--- a/前端代码/basic-framework-admin/packages/ai-chat-ui/src/client/README.md
+++ b/前端代码/basic-framework-admin/packages/ai-chat-ui/src/client/README.md
@@ -39 +39 @@
-重放窗口过期（`1003004009`）时改为读取快照
+重放窗口过期（`1003004006`，契约 1_003_004_006）时改为读取快照
```

  另：`docs/ai-platform/verification/c01-open-client-evidence.md:28` 也记录了旧值，需由 C01 归属方同步。
  修好后 `at-014-sse-reconnect.pw.ts` 的 tripwire 会"意外通过"，应把 `test.fail` 转为正常断言。
- **注意（避免误判）**：真实后端在**开流之后**才检测窗口过期（`AiRunController.events` 里 `replay` 在
  `SseEmitter` 创建之后调用，失败走 `completeWithError`），因此生产上客户端看到的更可能是"流中断"而不是 409 JSON。
  本缺陷的价值是：**只要服务端以契约错误码回应，前端就不会走快照分支**；是否改后端行为属后端切片判断。

### 5.2 观察：运行链路的传输层失败显示浏览器原始报文

- 实测 annotation：连接被重置时界面错误块文本为 `"network error"`（不是稳定原因码）。
- 对比：会话列表路径已归一为 `NETWORK_UNREACHABLE`（Q06 修复）；运行路径（`@vben/ai-embed-sdk` 的
  `streamRunEvents`）没有同样的归一化。属 `packages/**`，需授权后修。

### 5.3 观察：应用层没有自动重连循环

- 实测：非终态断流后界面停在"执行中"（可重连语义、不假成功），但没有自动发起第二次订阅；
  客户端 `afterSeq` 语义本身可用（`at-014` 用例证明）。要不要自动重连属产品决策（宿主也可能自行控制）。

### 5.4 观察：并发配额占位未被运行链路使用（影响 AT-059 归因）

- 代码走查：`AiQuotaService.acquire` 在 `src/main` 内**没有调用方**（管理端只用 `activeCount`），
  因此 `ai_quota_lease` 在接入前恒为空，`AI_QUOTA_EXCEEDED` 也不会由这套占位产生。
- 影响：AT-059 的"不超发、不永久占位"目前**没有运行链路证据**；运维判读时必须区分 429 的来源
  （配额占位 / 运行预算 `AI_RUN_BUDGET_EXCEEDED` / 上游限流 / 网关）。详见运维文档 §8.1。

### 5.5 观察：`RUN_STEP` 任务在仓库内没有常驻消费者（影响"积压"归因）

- 代码走查：`AiTaskService.claim` 的仓库内调用方只有评估运行（`eval-worker-*`）。
- 影响：用户运行的 `ai_run_task` 会一直 `QUEUED`；"队列积压"首先要排除"没有消费者"这一解释。
  另发现（**未经运行验证**）：评估 worker 领取"最老可领取任务"后若不属于自己，会以 1 秒租约交回，
  但 `claim` 已使 `attempt_count + 1`，反复发生会消耗用户任务的重试预算（判读特征：
  `attempt_count` 增长而 `last_error_code` 为空）。详见运维文档 §8.2。

### 5.6 观察：C02 证据称"重试复用同一幂等键"，实现每次生成新键

- 实测 annotation（幂等键每次随机，下面取自最终证据跑）：
  受理被 429 后点"重试"，两次受理请求的 `Idempotency-Key` **不同**
  （`chat-3b576a7b-6755-45c2-afd7-241b2a4e5b24` vs `chat-8d4b6339-938a-4201-b306-726e570a0828`）。
- 说明：状态机 `retry()` 会返回保存的键，但 `useConversation.retry()` 重新调用 `send()`，而 `send()` 每次生成新键。
  对"取消后重试"来说新键是必要的（复用会取回已取消的运行），因此这更像"证据描述与实现不一致"而非明确缺陷；
  但证据/注释需要修正，或由产品确认口径。属 `packages/ai-chat-ui`。

## 6. NFR 对照（阈值取自[产品需求 §6](../ai-platform/02-product-requirements.md)的已评审值）

| NFR | 已评审阈值（原文） | 本切片结论 |
|---|---|---|
| NFR-04 基础性能 | 建议 8 vCPU/16GB 测试环境，20 个并发 run；Mock 模型固定响应时平台受理 P95 ≤500ms、状态查询 P95 ≤300ms | **未测量**（本机无后端/MySQL；需要压测与机器配置记录，属后端切片）。本切片不产生任何延迟数字 |
| NFR-05 可靠性 | 重启恢复、重复请求、取消、故障补偿全部有实测；不承诺外部写操作 exactly-once | **部分**：前端侧"取消/断流/重连/去重/不假成功"有实测（§3 AT-016/017/014）；**重启恢复未验证**（AT-018） |
| NFR-06 前端 | 360/768/1440 宽度、深浅主题、跨源嵌入、键盘操作无关键阻断；SDK 基础包目标 gzip ≤100KB | 宽度/主题/键盘/嵌入由 Q06 套件覆盖（本切片复跑全绿）；SDK 产物 gzip 实测 **23,824 字节**（命令见 §2.3） |
| NFR-07 运维 | 延续框架 RTO 4h/RPO 1h；实际恢复演练取证 | **未验证**（恢复演练属部署/运维动作，未授权执行）；本切片交付的是"如何观察与判读" |
| NFR-08 工程 | 既有 Harness 全通过；新文件覆盖率 ≥80%；新增包和门禁均接入真实检查 | 本切片只新增测试与文档：棘轮 `frontend` 通过（§2.3）、tsc/eslint/prettier/cspell 全 0；未新增源码文件 |

## 7. 未验证项（如实列出，均不得当 pass）

1. **AT-018（进程重启与任务租约）**：需 Testcontainers + 可执行 jar + MySQL/Redis；本机无容器与数据库。
2. **AT-017 的服务端部分**（有界内存、背压、服务在慢消费者下仍可用）：需真实压测与 8vCPU/16GB 环境。
3. **AT-059 的服务端部分**（不超发、不永久占位）：Q02 有组件/IT 级证据，但运行链路未接 `acquire`（§5.4），
   因此**端到端未验证**。
4. **AT-039 的 API 侧**（连接器分页截断/失败的真实行为）：本切片只判界面显示口径。
5. **AT-060 的用量页浏览器级**：需要登录态与后端；本切片只覆盖结果块口径。
6. **AT-056（安全头）与 AT-067 报表页**：沿用 Q06 的环境缺口（无后端/无登录态）。
7. **Windows / CI 实跑**：只在 Linux + Chromium 153.0.8010.12 验证。
8. **运维文档中的 SQL/接口未在本机执行**（无 MySQL/后端）：文档给的是"可执行命令 + 判读方法"，
   不是运行结果；§8 的阈值是判读建议，未接入告警。

## 8. 复现方式（最短路径）

```bash
cd /home/ctyun/桌面/zhongtai/ai-platform/前端代码/basic-framework-admin
export PATH="$HOME/.local/bin:$PATH"

# 1) 全量浏览器套件（含 Q07 新增 8 例；强制重建全部产物）
Q06_FORCE_REBUILD=1 pnpm exec playwright test --config tests/playwright/playwright.config.ts
#    → 32 passed / 3 skipped / 0 failed（report.json: stats.expected=32, unexpected=0）

# 2) 只跑 Q07 韧性用例
pnpm exec playwright test --config tests/playwright/playwright.config.ts \
  at-014-sse-reconnect at-017-slow-consumer at-039-partial-unknown at-059-quota-rejection

# 3) 组件级（AT-016 前端侧）
pnpm exec vitest run --dom packages/ai-chat-ui/src/conversation apps/ai-chat/src

# 4) 工具链
pnpm exec tsc -p tests/playwright/tsconfig.json --noEmit
pnpm exec eslint tests/playwright && pnpm exec prettier --check "tests/**" && pnpm exec cspell lint "tests/**"
node scripts/check-coverage-ratchet.mjs frontend
```

## 9. 交接与后续动作（需要串行处理）

1. **应用 §5.1 的精确补丁**（`packages/**`，本切片无权限）：改常量后把 `at-014` 的 tripwire 转正。
2. **后端切片对齐**：AT-016/018/059 的服务端结论（本工作区已出现 `AiPlatformCapacityIT`、
   `AiRunResilienceAcceptanceIT`、`AiQuotaConcurrencyAcceptanceIT`、`AiApiCompletenessAcceptanceIT` 四个新 IT 文件，
   **属并行代理的切片，本报告不引用其结果**）。
3. **运维文档 §8 的四项缺口**（配额接线、RUN_STEP 消费者、AI 业务指标/DB exporter、`ops/prometheus` 规则）：
   均需新允许路径，建议并入 Q09/Q10 或另开卡。
4. **产品确认**：运行链路是否要自动重连（§5.3）、重试幂等键口径（§5.6）。

---

**状态更新（主管追加，2026-09-27）**：§5.1 的跨卡缺陷**已修复并验证**——`packages/ai-embed-sdk/src/client.ts` 与
`packages/ai-chat-ui/src/client/index.ts` 的窗口过期码从幽灵码 `1003004009` 改为契约码 `1003004006`，
包内测试与 README 同步，`at-014-sse-reconnect.pw.ts` 的 tripwire 已转正为真实断言；
全量浏览器套件仍为 **32 passed / 0 failed / 3 skipped**（该 tripwire 原按 expected 计入 passed，故总数不变）。
§9 第 1 条标记为**已完成**。
