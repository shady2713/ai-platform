# 主题令牌（C04）

主题的**声明式令牌**与继承顺序：平台默认 → 应用已发布修订 → 允许的宿主运行时覆盖。

## 模块

| 文件 | 职责 |
| --- | --- |
| `tokens.ts` | `ResolvedTheme`/`ThemeLayout`、token 与布局的受控校验、继承与运行时覆盖、主题 → CSS 自定义属性 |
| `adapters.ts` | 同一份主题令牌 → Chat（CSS 变量）/ 图表（色板）/ 报表（深浅色）共用 |
| `samples.ts` | 三份样例载荷（浅色 / 深色 / 平台默认），供管理端预览与宿主示例直接使用 |

## 用法

```ts
import {
  applyRuntimeOverride,
  chartTokensOf,
  resolveEffectiveTheme,
  themeCssVariables,
} from '@vben/ai-chat-ui';

// 1) 服务端发布配置（GET /ai/theme/effective 或嵌入握手 INIT 的 theme 字段）
const theme = resolveEffectiveTheme(await loadEffectiveTheme(applicationId));

// 2) 宿主运行时覆盖：只允许品牌主色/半径/深浅色/字号档位/密度，且不落库
const effective = applyRuntimeOverride(theme, { primaryColor: '#16a34a' });

// 3) 容器上只写 CSS 变量；组件（消息块、引用、附件）用 var(--ai-*) 取色
Object.assign(container.style, themeCssVariables(effective));

// 4) 图表与报表共用同一份主题
renderChart(chartTokensOf(effective));
```

## 继承顺序与覆盖边界

| 层级 | 来源 | 是否落库 |
| --- | --- | --- |
| 1 | 平台默认（与 `@vben/ai-contracts` 的 `defaultTheme` 同值） | 否 |
| 2 | 应用已发布主题修订（`ai_theme` 的 `PUBLISHED` 行，服务端解析） | 是（修订不可变） |
| 3 | 宿主运行时覆盖（本模块 `applyRuntimeOverride`） | **否**（只作用于本次实例，不影响其他应用） |

**可覆盖字段只有五个**：`primaryColor`、`radius`、`colorScheme`、`fontScale`、`density`。 `fontFamily` 与布局数值（`narrowBreakpoint`/`minSidebarWidth`）**不可覆盖**——字体是应用自托管的品牌资产，允许宿主替换等于给远程字体开一个口子；布局数值由发布配置决定，避免同一应用在不同宿主里"长得不一样"。未知字段是**拒绝**而不是忽略：静默忽略会让宿主以为覆盖生效了。

## 校验边界（与后端同值）

- token 必须显式给出 `primaryColor`/`radius`/`fontFamily`（与冻结契约 `required` 一致），未知字段拒绝；
- 色值只接受 `#RGB`/`#RRGGBB`；半径 0..24；
- `fontFamily` 必须命中 `ALLOWED_FONT_FAMILIES`（自托管/系统字体栈，与后端 `AiThemeValidator` 同值），远程字体地址、`url(...)`、任意 CSS 片段一律拒绝；
- 布局只接受 `fontScale`/`density` 枚举与受限整数，缺省项补平台默认；
- 输出到 DOM 的值全部由本模块生成（十六进制色、白名单字体、带单位数值），不存在"透传的样式文本"。

## 与后端的关系

- 服务端校验（`AiThemeValidator`）是权威：前端这套校验是为了**在拿到数据后仍然 fail-closed**——宿主或第三方宿主可能传入未经服务端校验的主题，前端不能假设它已合法。
- 主题修订的不可变性与"同一应用最多一个生效修订"由后端与数据库唯一键保证（见 `C04` 证据文档）；本模块只消费解析结果，不做发布判断。
