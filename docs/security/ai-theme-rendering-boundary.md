# AI 主题渲染边界（C04）

本文定义主题"能从外部拿到什么"的边界，是 C04 交付的契约性说明。
机器可读的实现分两侧：后端 `AiThemeValidator`（`basic-framework-module-ai`）与前端
`packages/ai-chat-ui/src/theme/tokens.ts`。

## 为什么需要边界

主题是**唯一**一处把外部输入直接变成界面观感的地方：一份"任意 CSS"的主题等价于一次持久化注入，
而"远程字体地址"等价于把宿主页面的渲染链路交给第三方域名。因此主题只接受**声明的 token**，
两侧各自校验（后端权威、前端 fail-closed），任何一侧拒绝即不生效。

## 允许的取值

| 字段 | 取值 | 说明 |
| --- | --- | --- |
| `primaryColor` | `#RGB` / `#RRGGBB` | 只接受十六进制；`red`、`rgb()`、`var()` 一律拒绝 |
| `radius` | 数值 0..24 | 与冻结契约 `theme-tokens.schema.json` 同区间 |
| `fontFamily` | **白名单**（4 个自托管/系统字体栈） | 见下 |
| `colorScheme` | `light` / `dark`（可缺省） | 未声明时不猜：渲染层按浅色 |
| `fontScale` | `small` / `normal` / `large` | 只接受档位，不接受字号数值 |
| `density` | `compact` / `normal` | 同上 |
| `narrowBreakpoint` | 整数 240..1440（px） | 布局数值只在发布配置里设定，不允许运行时覆盖 |
| `minSidebarWidth` | 整数 240..720（px） | 同上 |

**未知字段即拒绝**（不是忽略）：与冻结契约的 `additionalProperties: false` 同口径，
避免"客户端填了但服务端没校验"的字段悄悄生效。

## 字体白名单（两侧同值）

1. `system-ui, -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif`（平台默认）
2. `"PingFang SC", "Microsoft YaHei", system-ui, sans-serif`
3. `Georgia, "Songti SC", "SimSun", serif`
4. `ui-monospace, SFMono-Regular, Menlo, "Courier New", monospace`

白名单只包含**自托管与系统字体栈**：不接受远程字体地址（`url(...)`、`@font-face`、
`https://font.example.com/...`），也不接受任何样式片段（`;`、`{`、`}`、`expression(`）。
字体是应用发布配置的一部分，宿主运行时覆盖**不允许**替换字体。

## 注入面

- 主题不进 HTML：渲染层只把它们写成 **CSS 自定义属性**或已有组件的 props，
  值由平台代码生成（十六进制色、白名单字体、带单位数值），不存在"把主题文本当样式执行"的路径。
- 主题不携带行为：权限、功能开关、菜单可见性都**不在**主题里（行为配置不可由主题改写）。
- 主题版本参与缓存键：`ai_theme.tokens_fingerprint` 是 tokens+layout 的 SHA-256，
  嵌入页与静态资源的缓存键同时包含应用与主题修订/摘要（C05 落地），撤销配置立即失效。

## 生效范围

| 层级 | 是否落库 | 影响范围 |
| --- | --- | --- |
| 平台默认 | 否 | 所有未发布主题的应用 |
| 应用已发布修订 | 是（`ai_theme` 的 `PUBLISHED` 行，发布后不可修改） | 仅该应用 |
| 宿主运行时覆盖 | **否** | 仅当前实例，刷新即回到发布配置 |

同一应用同时最多一个生效修订，由数据库唯一键 `uk_ai_theme_published` 兜底（不是只在服务层比对）；
回退 = 把历史修订重新置为生效，首次发布时间不被篡改。
