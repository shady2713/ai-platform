# 第三方宿主接入指南与验收记录（C10）

本文是第三方（非平台管理端）宿主接入 AI 中台的**唯一指南**，并记录本轮可复现的验收证据。
宿主只需做三件事：**换票、校验事件、决定导航**；其余（iframe、握手、上下文、主题、生命周期）都由
`@vben/ai-embed-sdk` 提供。

## 1. 两个示例（均可运行、均不含真实凭据）

| 示例 | 位置 | 运行方式 | 说明 |
|---|---|---|---|
| 纯 HTML 宿主 | `前端代码/basic-framework-admin/examples/ai-host-html` | `pnpm -F @ai-platform-examples/host-html serve` | 无构建步骤：页面直接加载**版本化 SDK 产物**；同目录的 `server.mjs` 是**最小换票后端** |
| Vue 宿主 | `前端代码/basic-framework-admin/examples/ai-host-vue` | `pnpm -F @ai-platform-examples/host-vue dev` / `run build` | 主题/上下文/切用户切换的完整外壳；构建产物可托管在任意静态服务器 |

准备步骤：

```sh
cd 前端代码/basic-framework-admin
pnpm -F @vben/ai-embed-sdk run build          # 生成 dist/ai-embed-sdk-<version>.js（版本化自托管产物）
AI_APP_CODE=crm-portal \
AI_APP_SECRET=<宿主的应用客户端凭据> \
AI_HOST_ORIGIN=http://localhost:5180 \
AI_PLATFORM_BASE=http://localhost:48080 \
pnpm -F @ai-platform-examples/host-html serve
```

打开 `http://localhost:5180/` 即得到跨源宿主页（宿主端口与平台端口不同源）。

## 2. 最小换票后端的三条红线

`examples/ai-host-html/server.mjs` 是**示例**，不是生产换票代理：

1. **没有配置凭据就直接 503**：不存在"谁都能换票"的匿名端点（单测 `server.test.ts` 覆盖）；
2. **只允许配置的宿主 Origin**：其他 Origin 一律 403，且**不返回通配 CORS**；
3. **平台基址来自服务端配置**：客户端不能指定转发目标（否则会被当成任意转发器）；
   响应只回传 `token`/`expiresAt`，永不回显 `appSecret`。

生产环境的换票端点应放在宿主自己的后端（带会话鉴权、按主体申请票据、可审计）。

## 3. 宿主需要实现的三件事

| 步骤 | 接口 | 说明 |
|---|---|---|
| 换票 | `getAccessToken: () => Promise<{ token, expiresAt }>` | 调宿主自己的后端；浏览器里**不放长期凭据**；票据只进内存 |
| 事件 | `createHostEventHandlers({ routes, onNavigate, onReportCreated })` | 只认宿主**登记**的路由名与声明过的参数类型；URL/脚本一律拒绝 |
| 导航 | `onNavigate` 回调 | 由宿主决定是否跳转（平台不做浏览器跳转） |

上下文与主题由宿主决定：`createBusinessContextStore`（只作用下一次运行）与 `mount.updateTheme(...)`
（只改外壳令牌，不重建实例、不丢会话与滚动）。

## 4. 版本与兼容（N-1 基线）

- SDK 产物命名固定为 `ai-embed-sdk-<version>.js`，由平台自托管；**不使用公共 CDN**，也不使用 `latest` 之类浮动路径。
- 首发没有真实的历史产物，按 FR-34 的口径**冻结首发候选的协议行为**：
  `packages/ai-embed-sdk/fixtures/n-1/baseline.json`（协议版本、消息类型白名单、一段完整握手序列、产物 sha256）。
  升级 SDK 或协议时必须重放该夹具，证明新实现仍接受旧消息（`n-1-baseline.test.ts` 每次门禁都会跑）。
- 兼容口径：同一部署内 SDK 与 Chat 协议的**主版本必须一致**，次版本允许 N-1（`isCompatibleProtocolVersion`）。

## 5. 本轮验收记录（可复现）

| 验收项 | 结论 | 证据 |
|---|---|---|
| AT-050 跨 Origin 嵌入 | 示例结构完备（宿主/平台不同源、票据经宿主后端、iframe 用平台自托管入口）；**真实浏览器验收未完成** | 见第 6 节 |
| AT-051 错误来源消息 | 通过：宿主侧事件校验器拒绝未登记路由/未知参数/URL，SDK 桥只认允许域与父窗口 | `examples/ai-host-vue/src/host.test.ts`、`packages/ai-embed-sdk` 桥用例 |
| AT-052 一个页面两个 Chat | 通过（端口级）：宿主示例的实例标识与允许域逐实例持有 | C06 用例 + 示例选项构造 |
| AT-053 宿主切用户 | 通过：`switchUser` 销毁旧实例并清空上下文；旧响应由 SDK 按代次丢弃 | `host.test.ts`、C06/C07 用例 |
| AT-054 主题深浅色与窄屏 | 通过（配置层）：主题预设只改外壳令牌；窄屏断点由 SDK 布局令牌驱动 | `host.test.ts`、C07 用例 |
| AT-055 destroy 后重复 mount | 通过（结构层）：`destroy()` 幂等且清理监听器/iframe/令牌；示例的销毁按钮走同一路径 | C07 用例；内存观察属浏览器验收 |
| AT-056 embed 与 admin 安全头 | 通过：平台侧响应头策略由 C05 覆盖（嵌入只允配置域、admin 保持保护） | C05 证据 |
| AT-057 真实 N-1 SDK 连接新后端 | **首发基线验证**：冻结首发候选协议行为并每次门禁重放；真实双产物联调属 Q06 | `n-1-baseline.test.ts` |
| AT-067 禁公共 CDN | 通过：两个示例只引用自托管产物（SDK 由示例后端提供、嵌入页由平台提供） | `server.test.ts`、示例代码 |

## 6. 未验证项（必须由后续卡补齐）

1. **真实浏览器验收与“接入验收录像”**：需要 Q06 建立的真实浏览器门禁（跨源、第三方 Cookie 禁用、窄屏、键盘走查），
   本卡交付的是**可运行示例 + 端口级测试 + 复现命令**，并在此明确声明"未录制真实浏览器验收"。
2. **真实平台联调**：示例需要本地平台（48080）与真实应用凭据才能跑通换票；本卡未在 CI 中启动平台，
   因此"示例页面在真实平台下完成一次对话"未验证。
3. **N 与 N-1 双产物联调**：首发无历史产物，仅完成"基线夹具重放"；真正的双产物联调在 Q06 执行。
