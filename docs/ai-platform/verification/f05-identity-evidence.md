# F05 身份与安全扩展证据（2026-09-16）

本记录是 [F05 冻结开放身份与安全扩展 ADR](../ai-platform/tasks/F05.md) 的验收证据：交付物为
[ADR 0049](../../adr/0049-ai-open-identity-and-security-extension-boundaries.md) 与两处 starter README 扩展点，
本卡只冻结契约与拒绝规则，不含实现（实现按任务卡另行授权）。

## 1. 现状核对（代码级证据）

| 核对项 | 结论 | 证据位置 |
|---|---|---|
| MEMBER Provider 唯一性 | 每个用户类型只允许一个 `UserSessionCommonApi` 实现；空类型、未知类型、重复类型都在启动期抛 `IllegalStateException` | `basic-framework-spring-boot-starter-security/.../TokenAuthenticationFilter.java` 的 `indexUserSessionApis` |
| 未装配类型拒绝 | 路径类型未装配 Provider 时抛 `AccessDeniedException("当前用户类型未配置会话校验器")` | 同上 `resolveUserSessionApi` |
| 类型不匹配拒绝（ADMIN 回退） | 会话类型与请求路径类型不一致时抛 `AccessDeniedException("错误的用户类型")` | 同上 `buildLoginUserByToken` |
| 身份字段来源 | `LoginUser` 仅由"令牌 → 会话校验结果"构建，不接受参数/头覆盖 | 同上 |
| `/app-api` 路由 | 应用端 Controller 位于 `controller.app`，自动获得 `/app-api` 前缀 | `basic-framework-spring-boot-starter-web/README.md`（扩展约束）、`WebProperties` 的 `Api` 定义 |
| 匿名面声明 | 匿名路径只来自 `@PermitAll` 或编译期可审查的 `AuthorizeRequestsCustomizer`；未声明方法仅放行 GET/POST/PUT/DELETE/HEAD/PATCH，其他方法启动失败（`@PermitAll 不支持请求方法`） | `BasicFrameworkWebSecurityConfigurerAdapter#getPermitAllUrlsFromAnnotations` |
| 无部署期 URL 白名单 | `SecurityProperties` 无 URL 白名单字段 | `SecurityProperties` 字段清单 |
| 端点授权契约 | 每个映射必须显式选择一种策略，测试扫描全部映射 | `EndpointAuthorizationContractTest`（server 测试） |
| CORS 实现 | `CorsFilter`：`allowCredentials=true` + `cors-allowed-origins` 配置，注册在 `/**` | `BasicFrameworkWebAutoConfiguration#corsFilterBean` |
| CORS 生产约束 | prod profile 要求精确 HTTPS 来源（注释约束） | `application-prod.yaml` 的 `web.cors-allowed-origins` |
| CORS 缺口 | `WebProperties.corsAllowedOrigins` 默认 `*`，无"通配 + 凭据"启动期拒绝 | `WebProperties`（第 35 行默认值） |

## 2. 四项拒绝规则（F05 验收）

| 验收项 | 规则 | 现状 |
|---|---|---|
| ADMIN 回退 | 路径类型与会话类型必须一致，否则拒绝；类型未装配即拒绝；`/app-api` 不得回退管理端会话 | 已实现，ADR 冻结 |
| 伪造 userId | 身份字段只能由会话构建；`AiExecutionContext` 只含服务端建立字段 | 认证链已实现；执行上下文断言随 A 系列实现 |
| 全局 PermitAll | 匿名面只能注解/编译期声明；未知方法启动失败；无部署白名单 | 已实现 |
| CORS 当认证 | 授权不依赖 Origin；生产禁止通配来源 + 凭据 | 授权部分已实现；通配校验为变更点（F09） |

## 3. 变更点清单

已随 ADR 0049 给出（CORS 通配校验、MEMBER Provider、换票/撤销、外部范围解析、embed 安全头、SSE 语义），
逐项标注归属任务；本卡不实现。

## 4. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 新增 ADR 与 README 的链接、契约台账校验通过 |

本卡只改文档：`docs/adr/0049-*.md`、security/web 两个 starter README 的扩展点章节；
**未修改任何认证、授权、CORS 实现代码**，既有规则未被改弱（`git diff` 仅含文档路径）。

## 5. 未验证项

1. 变更点的实现与验证（CORS 通配拒绝、MEMBER Provider、换票/撤销、embed 安全头、SSE）待对应任务卡。
2. 跨站嵌入的浏览器级行为（CSP 报错、postMessage 来源拒绝）待 Q06 浏览器门禁。
