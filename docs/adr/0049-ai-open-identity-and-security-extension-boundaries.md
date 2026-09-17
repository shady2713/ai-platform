# 0049：AI 中台开放身份与安全扩展边界

日期：2026-09-16。状态：采用（冻结契约与拒绝规则；实现按任务卡另行授权）。

## 背景

AI 中台需要在管理后台的 ADMIN 身份之外，开放两类主体：接入的业务系统（APP）与业务系统内的最终用户（USER）。
同时要支持第三方网页嵌入 Chat，并对外部业务库做受控范围解析。现有框架只有 ADMIN Provider，
跨站嵌入、外部范围、SSE 与 API 状态语义尚未冻结；若不先把身份与信任边界写成可拒绝的规则，
后续 A/O/C 系列任务会在实现期各自发明身份语义。

本 ADR 冻结边界与拒绝规则；逐项实现仍需对应任务卡的明确授权，本决策不宣称相关能力已实现。

## 决策

### 1. 会话 Provider 唯一性（现有实现，冻结并要求 AI 遵守）

`UserSessionCommonApi` 每个用户类型只允许一个实现：启动期建立类型索引，重复声明、空类型或未知类型都抛
`IllegalStateException`（`TokenAuthenticationFilter#indexUserSessionApis`）。system 模块只提供 ADMIN Provider；
APP/USER 主体的 MEMBER Provider 由 AI 模块实现并注册，不得新增第二套身份存储来绕过该接缝。

### 2. 禁止 ADMIN 会话回退（现有实现，冻结并扩展到 `/app-api/ai/v1`）

请求路径携带的用户类型与会话返回的用户类型不一致时直接拒绝（`错误的用户类型`）；未装配对应类型的 Provider 时
拒绝（`当前用户类型未配置会话校验器`）。管理端令牌不得用于 `/app-api`，反之亦然。
AI 开放 API 一律位于 `/app-api/ai/v1`，其主体只能是 APP 或 USER。

### 3. 身份字段只来自服务端会话（冻结）

`LoginUser` 仅由"请求令牌 → 会话校验结果"构建，`userId`/`userType`/权限与附加信息均取自会话，
不接受请求参数、请求头或客户端 context 覆盖。AI 的 `AiExecutionContext` 只包含服务端建立的
appId、subjectId、subjectKind、scope 上限、资源授权版本与 traceId；模型输出、队列 payload 与客户端 context
都不得覆盖这些字段（见 [关键实现蓝图](../ai-platform/12-critical-implementation-blueprints.md) 第 1 节）。

### 4. 匿名面只能编译期声明（现有实现，冻结）

匿名路径只能来自 Controller 上的 `@PermitAll` 或编译期可审查的 `AuthorizeRequestsCustomizer`：
过滤器链创建时从注解解析出明确的"方法 + 路径"，未声明方法时仅允许 GET/POST/PUT/DELETE/HEAD/PATCH，
其他请求方法让应用启动失败（`@PermitAll 不支持请求方法`）。框架不提供可由部署参数任意扩大的 URL 白名单
（`SecurityProperties` 无 URL 白名单字段）。`EndpointAuthorizationContractTest` 扫描管理端与应用端全部映射，
每个映射必须显式选择 `@PermitAll`、`@AuthenticatedOnly` 或 `@PreAuthorize` 之一。

### 5. CORS 不是认证（冻结 + 变更点）

CORS 过滤器只负责浏览器跨源放行（`BasicFrameworkWebAutoConfiguration#corsFilterBean`，`allowCredentials=true`），
端点授权始终由令牌与权限码决定，任何依赖 `Origin` 放行的授权都会被拒绝。
生产 profile 必须配置精确 HTTPS 来源（`cors-allowed-origins`，当前以注释约束），禁止通配符来源与凭据组合。

**变更点**：`WebProperties.corsAllowedOrigins` 默认值为 `*`，缺少启动期校验；需要在 F09（受控 HTTP 边界）
一并增加"生产等价环境下通配来源 + 凭据"的启动失败校验，避免默认值成为部署态缺口。

### 6. 跨站 Chat 信任链（冻结）

第三方页面只通过官方 embed SDK/iframe 接入：宿主与 iframe 之间用 `postMessage` 握手并校验来源，
票据由宿主后端向平台换票获得，不使用第三方 Cookie 作为身份载体；embed 响应必须显式声明
`frame-ancestors`、`Content-Security-Policy` 与 `Referrer-Policy`，不允许内联脚本执行模型输出。
实现落在 C05/C06/C10 与 F09，本 ADR 只冻结信任链形状。

### 7. 对外状态与流式语义（引用并扩展到 AI 面）

沿用 [ADR 0003](0003-http-status-semantics.md)：401 表示票据缺失或失效，403 表示已认证但无范围/无权限，
409 表示幂等键冲突（同键不同摘要）或状态冲突，429 表示限流。SSE 的结束、取消与重放以 run 终态与
seq 为准（O05 实现），不得用连接断开表达取消。

## 拒绝规则与威胁用例（对应 F05 验收）

| 威胁用例 | 拒绝规则 | 现状与证据 |
|---|---|---|
| 用 ADMIN 令牌调用 `/app-api/ai/v1` | 路径用户类型与会话类型必须一致，否则 `AccessDeniedException` | 已实现（`TokenAuthenticationFilter#buildLoginUserByToken`） |
| 请求参数/客户端 context 伪造 userId 或 subjectId | 身份字段只能来自会话；执行上下文由服务端构建 | 已实现于认证链；AI 执行上下文构建处需按蓝图第 1 节加断言（A 系列实现） |
| 给业务接口加全局 `PermitAll` 或部署期白名单 | 匿名面只能注解声明；未知方法启动失败；无 URL 白名单配置 | 已实现（`BasicFrameworkWebSecurityConfigurerAdapter`） |
| 把 CORS 或 `Origin` 当作认证/授权依据 | 授权不依赖 Origin；生产禁止通配来源 + 凭据 | 授权部分已实现；通配校验为**变更点**（第 5 节） |
| 未装配 MEMBER Provider 时用 MEMBER 类型令牌访问 | 类型未装配即拒绝 | 已实现（`当前用户类型未配置会话校验器`） |
| 重复注册同类型 Provider（例如 AI 与 system 都声明 ADMIN） | 启动期 `IllegalStateException` | 已实现（`用户类型 N 存在重复会话校验器`） |

## 现有规则不被改弱

- 本 ADR 不修改任何既有认证/授权规则；新增 AI 面必须复用同一 `UserSessionCommonApi` 接缝与同一过滤器链。
- 匿名接口仍必须逐个注解声明；新增 embed 端点也必须在 `EndpointAuthorizationContractTest` 覆盖范围内显式声明策略。
- 凭据加密、密码策略、审计与安全信号沿用既有 starter 契约（见 security starter README 与
  [security-signals](0047-security-signals-and-authentication-boundaries.md)）。

## 变更点清单（实现仍需授权）

| 变更点 | 归属任务 | 说明 |
|---|---|---|
| 生产等价的 CORS 通配拒绝校验 | F09 | 通配来源 + 凭据组合在启动期失败 |
| MEMBER Provider（APP/USER）与 `subjectKind` 区分 | A02/A05 | 主体映射与认证 Provider 实现 |
| 换票、撤销与执行上下文重建 | A04/A06 | 票据只存摘要；撤销后不得在途回填 |
| 外部范围解析（业务授权接口 + Schema 登记） | A03/D 系列 | 空授权集合不等于无限制；范围必须由执行器强制附加 |
| embed 安全头与 iframe 信任链 | C05/C06/F09 | `frame-ancestors`、CSP、postMessage 来源校验 |
| SSE 结束/取消/重放语义 | O05 | 以 run 终态与 seq 为准 |

## 未验证项

- 本 ADR 只做契约冻结与现状核对，不包含实现验证；上述变更点在对应任务卡落地的门禁证据尚不存在。
- 跨站嵌入的真实浏览器行为（CSP 报错、postMessage 来源拒绝）待 Q06 建立浏览器门禁后验证。
