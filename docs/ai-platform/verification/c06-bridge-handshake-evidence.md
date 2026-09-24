# C06 实现 SDK 握手、换票和实例隔离 — 完成证据

| 项目 | 内容 |
|---|---|
| 任务卡 | [C06](../tasks/C06.md) |
| 状态 | DONE（协议与状态机层；**真实 iframe/浏览器验收见"未验证项"**） |
| 需求 | FR-14（嵌入与 SDK） |
| 依赖 | C05（embed 壳与安全头，证据 `c05-*`）、C01（共享客户端协议层，证据 `c01-*`） |
| 工作副本 | `/home/ctyun/桌面/zhongtai/ai-platform` |
| 变更范围 | `packages/ai-contracts/src`（桥协议契约）、`packages/ai-embed-sdk/src`（宿主侧桥）、`apps/ai-chat/src/bridge`（iframe 侧桥） |

## 1. 变更文件清单

### 契约（`packages/ai-contracts/src/bridge.ts`）

- `BRIDGE_PROTOCOL_VERSION` + `BRIDGE_MESSAGE_TYPES`（13 类白名单：HELLO/READY/AUTH/INIT/CONTEXT_UPDATE/
  THEME_UPDATE/OPEN/CLOSE/TOKEN_REQUIRED/REPORT_CREATED/NAVIGATE_REQUEST/ERROR/DESTROY）。
- 判别联合 + `.strict()`：未知类型、多余字段、非法实例标识/协议版本、超长字段一律拒绝。
- `isCompatibleProtocolVersion`（同主版本且相差 ≤1 个小版本）、`whitelistedBridgeType`
  （区分"白名单内但本版未建模"与"彻底非法的输入"）。
- `index.ts` 导出契约；iframe 侧通过 SDK 再导出复用同一份契约（`apps/ai-chat` 不直连 `ai-contracts`）。

### 宿主侧（`packages/ai-embed-sdk/src`）

- `src/bridge/host-bridge.ts`：`HostBridge` 状态机（CREATED → WAITING_READY → AUTHENTICATING → INITIALIZED → DESTROYED）
  \+ 来源校验（origin 精确命中允许域、`event.source` 必须是本实例 iframe、instanceId、协议版本、schema）
  \+ **换票 single-flight（并发共享一次回调、结果不缓存）** + 代次隔离（`resetSession()`）+ `destroy()` 清理。
- `src/types.ts`：`BridgeState` 与 `Theme` 的类型出口。
- `src/index.ts`：导出宿主桥与桥协议契约（供两侧复用）。

### iframe 侧（`apps/ai-chat/src/bridge/iframe-bridge.ts`）

- `IframeBridge`：`handshake()` 通知就绪；仅接受父窗口 + 允许域 + 本实例 + 兼容版本的消息；
  HELLO 校验应用标识（跨应用串用入口被拒）；AUTH 只把票据留在内存；INIT 应用主题并进入 INITIALIZED；
  `requestToken(reason)` 在票据过期/撤销时请求续票；DESTROY 清空凭据与主题并单向终止。

### 测试

- `packages/ai-contracts/src/__tests__/bridge.test.ts`（6 例）
- `packages/ai-embed-sdk/src/bridge/__tests__/host-bridge.test.ts`（9 例）
- `apps/ai-chat/src/bridge/__tests__/iframe-bridge.test.ts`（7 例）

## 2. 与卡片逐步实施的对应

| 卡步骤 | 实现 | 验证 |
|---|---|---|
| 1. HELLO/READY/AUTH/INIT 与协议版本协商 | 两侧状态机 + `isCompatibleProtocolVersion` | `bridge.test.ts`（版本协商矩阵）、`host-bridge.test.ts`（不兼容版本回 `PROTOCOL_VERSION_UNSUPPORTED` 且不换票）、`iframe-bridge.test.ts` |
| 2. 双向校验 origin/source/instance/schema，精确 targetOrigin | 两侧 `receive()` 的固定校验顺序；`post` 由宿主注入的传输端口实现（生产用精确 `targetOrigin`，宿主/iframe 两侧都不允许 `*`） | `host-bridge.test.ts`（恶意 frame/来源不符/实例不符/schema 非法四类都送不进 AUTH）、`iframe-bridge.test.ts`（同 origin 但非父窗口被丢弃且不回 ERROR） |
| 3. `getAccessToken` 去重、过期续票、切用户 `resetSession` | single-flight（并发共享、结算后清空以便真正重新换取）+ TOKEN_REQUIRED 续票 + 代次隔离 | `host-bridge.test.ts`（连续 READY 只换一次票、并发 TOKEN_REQUIRED 只触发一次、切用户后旧代次结果作废） |
| 4. 按状态机接收消息，提前业务消息拒绝 | 两侧状态机 + `MESSAGE_OUT_OF_ORDER`/`MESSAGE_NOT_SUPPORTED` 明确错误码 | `iframe-bridge.test.ts`（未 AUTH 前 `CONTEXT_UPDATE` 被拒且不落票据）、`host-bridge.test.ts`（启动阶段业务消息被拒） |
| 5. 每条消息校验 origin/source/instance/version/schema；切用户 generation | 校验在 `receive()` 内完成，任何一步不过直接丢弃（不进入业务分支） | 同上两类用例 |

### 卡片验收项

| 验收项 | 结论 | 证据 |
|---|---|---|
| AT-050 跨 Origin Chat 嵌入 | **协议层通过、浏览器层未验证**：换票不依赖 Cookie（票据经 AUTH 消息传入并只留内存） | 结构证据见第 3 节；Cookie 禁用下的浏览器验收由 Q06/G5 承接 |
| AT-051 恶意 frame | 通过：origin 不在允许域、来源不是本实例 iframe、实例号不符、schema 非法四类都**不会触发换票**；HELLO 声明应用与本入口不一致直接拒绝 | `host-bridge.test.ts` 第 3/4 例、`iframe-bridge.test.ts` 第 3 例与 APP_MISMATCH |
| AT-052 两个 app 不串 token | 通过：每个实例持有自己的允许域/实例标识，AUTH 只发本实例 iframe；iframe 侧只接受父窗口与本应用 | `host-bridge.test.ts`（两个实例互不干扰）、`iframe-bridge.test.ts`（APP_MISMATCH） |
| AT-053 旧实例消息丢弃 | 通过：`resetSession()` 代次 +1，在途换票结果作废，晚到事件按代次过滤；DESTROY 后一切消息不处理 | `host-bridge.test.ts`（切用户用例）、`iframe-bridge.test.ts`（销毁用例） |

## 3. 关键约束落地

- **凭据只在内存**：token 只出现在 AUTH 消息里；两侧都不写 URL/localStorage/日志；`DESTROY` 立即清空。
- **允许域是构造前提**：两侧都要求显式给出允许域（来自服务端发布配置/bootstrap），为空即拒绝构造，
  不存在"未配置就谁都信"的退化路径。
- **单飞不等于缓存**：`TOKEN_REQUIRED`（过期/撤销）必须真正重新换取，否则会把过期票据续成"看起来还行"。
- **实现未覆盖的白名单消息明确失败**：`MESSAGE_NOT_SUPPORTED`（不是静默丢弃），为 C07/C08 扩展留出确定语义。
- **不新增依赖**：桥协议放在既有 `ai-contracts`，宿主侧复用既有 SDK 包；iframe 侧通过 SDK 复用同一契约，
  未改任何依赖声明、锁文件或请求器。

## 4. 实际执行的命令与结果

| 命令 | 退出码 | 结果 |
|---|---|---|
| `pnpm exec vitest run --dom packages/ai-contracts packages/ai-embed-sdk apps/ai-chat` | 0 | `Test Files 13 passed`，**82 例通过**（含桥协议 6 + 宿主侧 9 + iframe 侧 7） |
| `pnpm run check:type` | 0 | 37/37 任务通过 |
| `pnpm run check` / `pnpm run lint` | 见第 6 节 | 工作区 check 与 lint |
| 门禁链（contracts / frontend / 棘轮） | 见第 6 节 | 本卡为纯前端 + 契约，未改后端与迁移 |

## 5. 新增/变化的对外契约与上游差异

- **前端 API**：`@vben/ai-contracts` 新增桥协议（类型、解析、版本协商、白名单查询）；
  `@vben/ai-embed-sdk` 新增 `createHostBridge`/`HostBridge`/`BridgeState` 与桥协议再导出；
  `apps/ai-chat/src/bridge` 新增 `createIframeBridge`/`IframeBridge`。
- **上游差异（如实记录）**：
  1. `apps/ai-chat` 未声明 `@vben/ai-contracts` 依赖（其依赖为 `ai-chat-ui`/`ai-embed-sdk`/`vue`），
     而 C06 的允许路径不含依赖声明改动 → iframe 侧改从 `@vben/ai-embed-sdk` 复用同一份契约（SDK 再导出），
     避免新增依赖声明；若后续需要直连，应在允许改依赖声明的卡片里调整。
  2. 设计契约 7.2 的握手顺序在实现中固定为：宿主 `HELLO`（声明 appCode）→ iframe `READY`（运行时就绪）
     → 宿主 `AUTH` → 宿主 `INIT` → `INITIALIZED`。这是对"交换 HELLO/READY"的**具体化**：
     两个方向的消息各承担一件事，避免同一类型在两个方向语义不同。
- **字段/权限/迁移**：无新增（未改 DB、未改权限码、未改菜单）。

## 6. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 全部契约脚本通过 |
| `pnpm run check` | 0 | 环形依赖、依赖声明、显式 any、typecheck（37/37）、cspell 全部通过 |
| `pnpm run lint` | 0 | prettier + eslint + stylelint 全部通过 |
| `sh .harness/verify.sh frontend` | 1 | 失败项**只有尾部棘轮**：3 个新文件"尚未登记单文件覆盖率基线"（新文件的预期状态）；其前置步骤全部通过——`Test Files 351 passed / Tests 1906 passed`、生产构建 `Production JavaScript: 228 files passed no-undef validation` |
| `node scripts/check-coverage-ratchet.mjs --update` | 0 | 登记 3 个新文件基线（`ai-contracts/bridge.ts`、`ai-embed-sdk/host-bridge.ts`、`ai-chat/iframe-bridge.ts`），**无既有条目下降、无删除** |
| `node scripts/check-coverage-ratchet.mjs all` | 0 | `all 单文件基线通过`（即 frontend 门禁的棘轮段在登记后满足） |

> 本卡为纯前端 + 契约改动（未动后端、迁移、SQL 快照与四处台账），故按既有口径只跑 contracts + frontend 链；
> 登记只改 `docs/contracts/coverage-baseline.json`（台账），不影响运行行为，因此未重跑整条 frontend 门禁。

## 7. 顺带修复的依赖缺口（真实暴露）

1. **字段与方法同名会互相遮蔽**：`HostBridge` 里同时有私有字段 `state` 与方法 `state()`，
   编译通过但运行时字段遮蔽方法（`bridge.state is not a function`）。两侧都改名为 `currentState`。
2. **方向未定时消息形状会打架**：初版把 iframe → 宿主的 HELLO 建模为带 `appCode`，导致宿主 → iframe 的 HELLO
   无法通过同一 schema；改为"HELLO 只由宿主发、携带 appCode；READY 只由 iframe 发"后双方都与契约一致。
3. **握手阶段的放行集合要显式写全**：INIT 紧跟 AUTH 到达，初始实现只放行了 AUTH/HELLO/DESTROY，
   把 INIT 误判为乱序；已把 INIT 纳入并加注释说明"这两步是宿主紧接着发来的"。
4. **`onAuth` 不该透传整条协议消息**：改为只传 `{expiresAt, token}`，减少误把协议对象记进日志的机会。

## 8. 未验证项与已知边界

1. **真实 iframe/浏览器验收未完成**：本卡证据是协议与状态机层（真实 `postMessage`、真实 iframe、
   第三方 Cookie 禁用场景、多标签切用户）由 Q06/G5 用真实浏览器承接；本卡的"跨源安全测试"是**端口级**的
   （注入传输/事件端口，逐条证明校验顺序与拒绝行为）。
2. **精确 targetOrigin 由宿主实现**：`HostBridgeTransport`/`IframeBridgeTransport` 的 `post` 由宿主提供，
   本卡在类型与文档上要求"精确 targetOrigin、禁止 `*`"，但其真实取值需 C07/C10 的宿主实现与浏览器验收确认。
3. **业务消息尚未实现**：`CONTEXT_UPDATE`/`THEME_UPDATE`/`OPEN`/`CLOSE`/`NAVIGATE_REQUEST`/`REPORT_CREATED`
   已在白名单与契约里，但本版一律以 `MESSAGE_NOT_SUPPORTED` 明确拒绝，实现由 C07/C08 承接。
4. **未接入独立 Chat 应用外壳**：iframe 侧的 bootstrap 拉取与桥实例装配属 C07/C08；本卡只交付桥本体与用例。
5. **环境缺口（非本卡）**：`sh .harness/verify.sh dependencies` 因 Trivy 漏洞库镜像不可达仍失败。
