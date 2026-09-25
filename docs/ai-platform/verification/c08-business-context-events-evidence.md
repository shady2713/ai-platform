# C08 实现业务上下文和宿主事件 — 完成证据

| 项目 | 内容 |
|---|---|
| 任务卡 | [C08](../tasks/C08.md) |
| 状态 | DONE（协议与两侧实现；浏览器端到端见"未验证项"） |
| 需求 | FR-13（业务上下文）、FR-14（宿主事件） |
| 依赖 | C07（展示形态与生命周期，证据 `c07-*`）、S04（上下文预算与分区，证据 `s04-*`） |
| 工作副本 | `/home/ctyun/桌面/zhongtai/ai-platform` |
| 变更范围 | 后端 `module-ai/service/context`；`packages/ai-contracts`（桥协议补四类业务消息 + 上下文契约）；`packages/ai-embed-sdk`（上下文仓库、宿主事件）；`apps/ai-chat/src/bridge`（iframe 侧处理） |

## 1. 变更文件清单

- 后端 `service/context/AiBusinessContextSchema.java`：业务上下文的**逐键形状协议**（键白名单 + page/objectType/objectId/filters/locale/timezone 的取值形状、
  filters 只允许标量与标量数组、timezone 用 JDK tzdb 实判、规范化键序稳定）；`AiContextBuilderImpl` 改为委托它（校验规则只有一份）。
- `packages/ai-contracts/src/business-context.ts`：同一份上下文契约的 zod 版本（消费者侧 fail-closed）。
- `packages/ai-contracts/src/bridge.ts`：把白名单里留位的四类业务消息**建模**——`CONTEXT_UPDATE`、`THEME_UPDATE`（宿主→iframe）、
  `NAVIGATE_REQUEST`（只带登记路由名与标量参数，协议层不接 URL/脚本）、`REPORT_CREATED`（`rpt_` 前缀 + 版本）。
- `packages/ai-embed-sdk/src/context/business-context-store.ts`：上下文仓库——`update()` 只改"下一次运行"，`snapshot()` 在受理时取副本，
  运行期间的更新不影响已受理运行；校验失败保留原值（不出现半更新）。
- `packages/ai-embed-sdk/src/events/host-events.ts`：`validateHostNavigation` + `createHostEventHandlers`——
  只认宿主**登记**的路由名与声明过的参数类型，未登记路由/任意 URL/脚本/未知参数/类型不符一律拒绝（返回稳定原因，不抛异常）。
- `apps/ai-chat/src/bridge/iframe-bridge.ts`：处理 `CONTEXT_UPDATE`（非法上下文回 `CONTEXT_SCHEMA_INVALID` 且保留原值）、`THEME_UPDATE`（只换观感）；
  新增 `requestNavigate`/`notifyReportCreated` 上报；宿主方向的导航/报表消息按乱序拒绝。
- 测试：`AiBusinessContextSchemaTest`(7，后端)、`packages/ai-contracts/src/__tests__/bridge.test.ts` 扩充、`ai-embed-sdk/src/events/__tests__/host-events.test.ts`(6)、
  `apps/ai-chat/src/bridge/__tests__/iframe-bridge.test.ts` 扩充(4)。

## 2. 与卡片逐步实施的对应

| 卡步骤 | 实现 | 验证 |
|---|---|---|
| 1. context/theme 更新只作用下一 run | 两侧":`update()` 改待生效值 + `snapshot()` 受理时取副本；主题只换观感 | `host-events.test.ts`（快照不受后续更新影响、快照是副本）、`iframe-bridge.test.ts`（CONTEXT_UPDATE 后旧快照不变） |
| 2. REPORT_CREATED 与 NAVIGATE_REQUEST 类型化事件 | 桥协议建模 + iframe 上报方法 + 宿主校验器 | `bridge.test.ts`（形状与拒绝）、`iframe-bridge.test.ts`（上报方向正确、反向为乱序）、`host-events.test.ts`（转发形状） |
| 3. 宿主导航按登记路由参数校验，拒绝任意 URL/脚本 | `validateHostNavigation`（登记表驱动） | `host-events.test.ts`（`https://evil…`、`javascript:…`、未登记路由、未知参数、类型不符全部拒绝） |

### 卡片验收项

| 验收项 | 结论 | 证据 |
|---|---|---|
| AT-051 错误来源的消息不被接受 | 通过：C06 的来源校验继续生效；本卡新增的业务消息同样要过 instanceId/版本/schema 与状态机 | `iframe-bridge.test.ts`（未初始化前的业务消息、反向消息都拒绝） |
| AT-053 宿主切用户 | 通过（上下文侧）：`clear()` 与代次切换后的旧消息丢弃由 C06/C07 承接；上下文仓库在切用户时可清空，运行中的运行用旧快照 | `host-events.test.ts`（clear 后为空）、C06/C07 用例 |
| 上下文不能改 app/user/scope | 通过：键白名单两侧都拒绝身份/范围字段 | `AiBusinessContextSchemaTest`（appCode/subjectId/externalUserId/scope/applicationId 全拒）、`host-events.test.ts`、`iframe-bridge.test.ts` |
| 运行中更新不改旧 run | 通过：受理时快照 + 副本 | `host-events.test.ts`、`iframe-bridge.test.ts` |
| 无权对象追问/拒绝 | **部分覆盖**：协议与运行链路只保证"非法上下文被拒"；"objectId 不存在/含糊 → 追问"需要业务对象目录（本卡不引入），
  追问能力由既有的 clarification 结果块（C03）承接，接线属 C09/C10 宿主侧 | 见第 8 节未验证项 |

## 3. 关键约束落地

- **上下文是辅助信息**：两侧都只接受 FR-13 的六个键，身份/范围字段无法进入上下文。
- **"下一次运行"语义在代码里可见**：`update()`/`snapshot()` 分开，运行受理时取副本（不是引用）。
- **导航不执行任意目标**：登记表驱动，路由名与参数都必须是宿主声明过的；URL/脚本在协议层与校验层各拒一次。
- **校验规则单一来源**：后端是权威（`AiBusinessContextSchema`），前端是消费者侧的同一套规则（`business-context.ts`）。
- **不新增依赖**：只用既有 `zod`/`vue` 与包内相对引用。

## 4. 实际执行的命令与结果

| 命令 | 退出码 | 结果 |
|---|---|---|
| `./mvnw -o -pl basic-framework-module-ai test -Dtest='AiBusinessContextSchemaTest,AiContextBuilderImplTest'` | 0 | **15 例通过**（上下文协议 7 + 既有构造器 8） |
| `pnpm exec vitest run --dom apps/ai-chat packages/ai-embed-sdk packages/ai-contracts` | 0 | `Test Files 15 passed`，**101 例通过** |
| `pnpm run check:type` | 0 | 37/37 任务通过 |
| 门禁链（contracts / backend / frontend / integration / 棘轮） | 见第 6 节 | 本卡同时改了后端、契约与前端 |

## 5. 新增/变化的对外契约与上游差异

- **桥协议**：新增 `CONTEXT_UPDATE`/`THEME_UPDATE`/`NAVIGATE_REQUEST`/`REPORT_CREATED` 四个判别分支（白名单不变，只是从"已留位"变成"已建模"）。
- **上下文契约**：`business-context.ts`（TS）+ `AiBusinessContextSchema`（Java）同值。
- **上游差异（如实记录）**：
  1. 本卡按"公开契约变更先改权威 Schema 再改消费者"的流程，在 `packages/ai-contracts` 里补齐了四类业务消息——
     该包不在本卡列出的三个目录内，但它是**桥协议的唯一归宿**（C06 建立），把消息建模放在别处会造成两份协议；
     改动是**纯增量**（不动既有七类消息的形状），并在 `bridge.test.ts` 里固定。
  2. 上下文形状规则两侧各实现一次（Java 与 TS 无法共享代码）；差异点已明确：**timezone 的"是否真实存在"只由后端用 tzdb 判定**，
     前端只校验形状（更大范围的值交给服务端拒绝）。
  3. `OPEN`/`CLOSE` 仍为"白名单未建模"（会在两侧以 `MESSAGE_NOT_SUPPORTED` 明确拒绝）：它们的语义属于宿主外壳（C07 的 `setMode`），
     不需要跨帧消息，故未建模。

## 6. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 全部契约脚本通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单测（含本卡 7 例）、格式、架构与覆盖率检查通过 |
| `sh .harness/verify.sh frontend` | 0（复跑） | 首轮 exit 1 的原因是 `pnpm lint` 的 `perfectionist/sort-imports`（新测试文件里 `import type`/`import` 分组顺序），已修复；复跑通过（`check`：cspell 1013 文件 0 问题；覆盖率用例全绿；生产构建与前端棘轮通过） |
| `./mvnw -q -Pintegration clean verify`（integration 门禁的 maven 段） | 0 | 全量 IT 无失败（0 条 `Tests run: … Failures: [1-9]` 记录） |
| `sh .harness/verify.sh integration`（整体） | 1 | **唯一失败项是尾部棘轮**：本卡新增文件"尚未登记单文件覆盖率基线"（新文件的预期状态）；maven 段本身 0 失败 |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 登记 5 个新文件（后端上下文协议 + 前端契约/SDK/事件层），4 个既有条目上浮，**无下降、无删除** |

> 说明：首次跑门禁链时 frontend 门禁在 lint 阶段失败（未产出覆盖率报告），因此那一轮的 `--update` 只登记了后端新文件；
> 修复 lint 并复跑 frontend 后，第二轮 `--update` 补齐前端基线，`all` 通过。

## 7. 顺带修复的依赖缺口（真实暴露）

1. **事件层误加导入**：`host-events.ts` 起草时多了一行不存在的模块导入（编译期即发现），已删除——
   提醒：新增文件后先 `check:type` 再写测试，能省一轮。
2. **测试的导入路径**：新目录下的测试相对路径写错两次（`../` 与 `../../`），vitest 的报错信息直接指出解析失败文件，按提示修正即可。
3. **TS 字面量类型推断**：登记路由表的字面量被推断成 `string` 而非参数类型联合，加显式注解 `HostRouteRegistry` 解决。
4. **协议类型的存在性检查**：`IframeBridgeOptions` 新增回调（`onContext`/`onTheme`）时漏了一处可选字段，`check:type` 精确指出调用点。

## 8. 未验证项与已知边界

1. **"objectId 不存在或含糊 → 追问"未在本卡实现**：这需要业务对象目录（跨系统对象解析）与追问接线；
   平台已有的追问能力是结果块 `clarification`（C03）与运行确认链路（D09）。本卡保证的是"非法上下文被拒"，
   追问路由由 C09/C10 的宿主集成与 Q 系列端到端验收补齐。
2. **宿主真实导航未执行**：宿主侧只校验并回调（`onNavigate`），真实跳转由宿主的导航实现完成；
   跨源浏览器下的行为由 Q06/G5 验收。
3. **上下文对模型的实际影响未评测**：上下文是否被模型正确使用属评测范围（Q04）。
4. **`REPORT_CREATED` 的上报点未接线**：`notifyReportCreated` 已就绪，谁在报表保存成功后调用它属 C09/C10。
5. **环境缺口（非本卡）**：`sh .harness/verify.sh dependencies` 因 Trivy 漏洞库镜像不可达仍失败。
