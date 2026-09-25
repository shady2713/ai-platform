# C07 实现 SDK 展示形态与生命周期 — 完成证据

| 项目 | 内容 |
|---|---|
| 任务卡 | [C07](../tasks/C07.md) |
| 状态 | DONE（外壳与生命周期层；**真实浏览器/内存观察见"未验证项"**） |
| 需求 | FR-14（嵌入与 SDK） |
| 依赖 | C06（桥握手与实例隔离，证据 `c06-*`） |
| 工作副本 | `/home/ctyun/桌面/zhongtai/ai-platform` |
| 变更范围 | `packages/ai-embed-sdk/src/display`、`packages/ai-chat-ui/src/layout`（+ 两个包的出口） |

## 1. 变更文件清单

- `packages/ai-embed-sdk/src/display/mount.ts`：`createChatMount` — inline/drawer/dialog 三形态共用**一个 iframe**；
  打开/关闭、`setMode`、`updateTheme`、`destroy`；模态形态的焦点移入、Tab 循环、Esc 关闭与焦点归还；
  窄屏（< 主题 `narrowBreakpoint`）铺满视口；高度**受限协商**（`min(宿主上限, 视口可用高度)`，内部滚动）；
  `destroy` 清理 DOM/监听器/iframe/桥实例且幂等。桥实例从这里接管（首次打开即 `start()` 握手）。
- `packages/ai-chat-ui/src/layout/ChatLayout.vue`：iframe 内的三形态骨架——语义随形态变化
  （`dialog`/`drawer` 为 `role="dialog"`+`aria-modal` 且 Esc 触发 `close`；`inline` 为 `role="region"` 且不抢 Esc）、
  标题栏 + 原生关闭按钮、窄屏标记、`ResizeObserver` 卸载即断开。
- `packages/ai-chat-ui/src/layout/README.md`：用法与行为约定。
- 两个包的 `src/index.ts`：导出 `createChatMount`/`ChatDisplayMode`/`ChatFramePort`/`ChatMount`… 与 `ChatLayout`。
- 测试：`ai-embed-sdk/src/display/__tests__/mount.test.ts`（10）、`ai-chat-ui/src/layout/__tests__/chat-layout.test.ts`（4）。

## 2. 与卡片逐步实施的对应

| 卡步骤 | 实现 | 验证 |
|---|---|---|
| 1. inline/drawer/dialog 的 open/close/mount/destroy | `createChatMount`（三形态 + `setMode` 运行期切换） | `mount.test.ts`（打开/关闭、形态语义、切换） |
| 2. 焦点管理/Esc/resize/有界高度协商 | 模态形态焦点移入 + Tab 循环 + Esc 关闭 + 焦点归还；`resize` 重算；高度 = min(上限, 视口可用) | `mount.test.ts`（焦点归还、焦点陷阱两个方向、窄屏重算、高度断言） |
| 3. 卸载清理监听器/iframe/流/图表/内存 token | `destroy()`：解绑 keydown 与 resize、`bridge.destroy()`、移除 overlay、幂等；iframe 由 `ChatFramePort.destroy` 释放 | `mount.test.ts`（destroy 后无 overlay、Esc 无效、重复 destroy 幂等、销毁后可重新 mount） |

### 卡片验收项

| 验收项 | 结论 | 证据 |
|---|---|---|
| AT-052 一个页面两个 Chat | 宿主侧形态与桥实例**每实例独立**（容器、iframe、桥、允许域各一份）；iframe 侧隔离由 C06 覆盖 | `mount.test.ts`（同一容器重复 mount 各自独立面板）、C06 的双实例用例 |
| AT-054 主题深浅色与窄屏/键盘可用 | 部分通过（组件与环境层）：窄屏铺满视口、键盘焦点移入/归还/Tab 循环、主题更新只改外壳令牌不重建 iframe（滚动保留）；**真实浏览器的深浅色观感与键盘走查由 Q06/G5 承接** | `mount.test.ts`、`chat-layout.test.ts` |
| AT-055 destroy 后重复 mount 无泄漏 | 通过（结构层）：destroy 解绑全部监听器、移除 DOM、销毁 iframe 与桥；重复 destroy 幂等；destroy 后重新 mount 只留一份面板 | `mount.test.ts` 第 8 例；**内存观察（堆快照对比）属 Q06/G5** |
| 重复挂载无泄漏 | 通过 | 同上 |
| 键盘焦点恢复 | 通过 | `mount.test.ts`（Esc 后 `document.activeElement` 回到打开前的按钮） |
| 窄屏可用 | 通过（布局层） | `mount.test.ts`（`data-narrow=true` 且宽高 100%）、`chat-layout.test.ts` |

## 3. 关键约束落地

- **不重建 iframe**：形态切换与主题更新都只重排外壳/令牌；这是"会话与滚动位置保留"的前提（AT-054）。
- **模态语义完整**：焦点移入 → 陷阱 → Esc 关闭 → 焦点归还；`inline` 形态不抢 Esc（不吞宿主快捷键）。
- **精确 targetOrigin**：外壳的 `post` 用允许域推导 origin，未使用 `*`。
- **清理是显式的**：destroy 逐项解绑（keydown、resize、桥、DOM、iframe），且可重复调用。
- **不新增依赖**：只复用既有包（`vue`、`@vben/ai-contracts`、`@vben/ai-embed-sdk`）。

## 4. 实际执行的命令与结果

| 命令 | 退出码 | 结果 |
|---|---|---|
| `pnpm exec vitest run --dom packages/ai-chat-ui/src/layout packages/ai-embed-sdk` | 0 | `Test Files 4 passed`，**32 例通过**（展示形态 10 + 布局 4 + 既有 18） |
| `pnpm run check:type` | 0 | 37/37 任务通过 |
| 门禁链（contracts / frontend / 棘轮） | 见第 6 节 | 本卡为纯前端改动 |

## 5. 新增/变化的对外契约与上游差异

- **前端 API**：`@vben/ai-embed-sdk` 新增 `createChatMount`、`ChatMount`、`ChatDisplayMode`、`ChatFramePort`、
  `ChatMountLayout`、`ChatMountOptions`；`@vben/ai-chat-ui` 新增 `ChatLayout` 组件。
- **上游差异（如实记录）**：
  1. **主题更新只到外壳**：`updateTheme` 立刻作用于宿主侧外壳令牌，并随下一次握手进入 iframe；
      iframe 侧的 `THEME_UPDATE` 处理属 C08（当前 iframe 会以 `MESSAGE_NOT_SUPPORTED` 明确拒绝），
      "主题切换保留会话/滚动"在宿主侧成立（不重建 iframe），但**端到端外观一致性未验证**。
  2. **形态协商不经过协议**：形态是宿主侧展示关注点，故 `OPEN`/`CLOSE` 消息仍未实现（白名单已留位），
      iframe 通过自身布局响应宿主尺寸（窄屏铺满由两侧各自的断点令牌决定，取值同源）。
  3. **iframe 由宿主创建**：本卡把 `ChatFramePort` 作为端口（真实实现需处理 sandbox/allow 属性与
     `targetOrigin`），真实属性组合与沙箱策略由 C10 的宿主示例与 Q06 浏览器验收确认。

## 6. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 全部契约脚本通过 |
| `pnpm run check` / `pnpm run lint` | 0 / 0 | 工作区检查（含 cspell 与 typecheck 37/37）与 lint 全绿 |
| `sh .harness/verify.sh frontend` | 1 | 失败项**只有尾部棘轮**：`ChatLayout.vue` 与 `display/mount.ts` 两个新文件"尚未登记单文件覆盖率基线"；前置步骤全部通过（生产构建 `Production JavaScript: 228 files passed no-undef validation`、覆盖率用例全绿） |
| `node scripts/check-coverage-ratchet.mjs --update` | 0 | 登记 2 个新文件基线（均为 92.98%），1 个既有条目上浮，**无下降、无删除** |
| `node scripts/check-coverage-ratchet.mjs all` | 0 | `all 单文件基线通过` |

## 7. 顺带修复的依赖缺口（真实暴露）

1. **字段/方法同名遮蔽**（延续 C06 的教训）：本卡在 `mount` 里避免同名，但把 `overlay!` 这类非空断言
   换成显式判空——lint 规则禁止非空断言，显式判空也让"面板已销毁"的边界更清楚。
2. **嵌套三元被 lint 拒绝**：尺寸计算从嵌套三元改成 if/else 链，可读性更好。
3. **`get(exists())` 的 VTU 语义**：`get()` 返回的包装器没有 `exists()`（它会直接抛错），
   断言改为 `.element` 非空。

## 8. 未验证项与已知边界

1. **真实浏览器验收未完成**：AT-054 的深浅色观感、键盘走查、窄屏切换的真实渲染，
   以及 AT-055 的**内存观察**（堆快照对比无泄漏）都需要 Q06/G5 的真实浏览器门禁；
   本卡证据是 DOM 级（happy-dom）与组件级。
2. **iframe 真实属性未定**：`sandbox`/`allow`/`loading` 等属性组合、`targetOrigin` 的真实取值
   由宿主实现（C10 示例）与浏览器验收确认。
3. **未接入独立 Chat 应用**：`apps/ai-chat` 尚未装配 `ChatLayout`（其外壳接线属 C08/C09）。
4. **图表/流清理**：图表实例与事件流在 iframe 内，其销毁由 iframe 侧负责；宿主侧 destroy 只保证
   iframe 与桥被释放（跨帧断言需浏览器验收）。
5. **环境缺口（非本卡）**：`sh .harness/verify.sh dependencies` 因 Trivy 漏洞库镜像不可达仍失败。
