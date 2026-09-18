# A05 接入 MEMBER 认证 Provider 与 scope 表达式证据（2026-09-18）

本记录是 [A05 接入MEMBER认证Provider与scope表达式](../tasks/A05.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| MEMBER 会话 Provider | `module-ai/framework/security/AiUserSessionCommonApi`：只声明 `UserTypeEnum.MEMBER`，票据 → 会话（服务端字段：应用编号、主体类型、可信外部用户标识、范围指纹、到期时间） |
| scope 表达式 | `module-ai/framework/security/AiScopeSecurityService`（Bean `aiScope`）：`hasScope(...)` / `hasAnyScope(...)`，委托 A03 统一判定，fail-closed |
| scope 目录契约 | `docs/contracts/ai/scope-catalog.md`：资源类型 × 动作词表、匿名端点登记表、MEMBER 会话身份说明 |
| 框架缺陷修复 | `TokenAuthenticationFilter#resolveUserSessionApi`：多 Provider 且请求未声明用户类型时**显式拒绝**（原先会 NPE，被第二个 Provider 引入后暴露） |
| 安全 starter 文档 | `basic-framework-spring-boot-starter-security/README.md`：用户类型 Provider 扩展点与 scope 表达式约定 |
| 测试 | `AiUserSessionCommonApiTest`(3)、`AiScopeSecurityServiceTest`(5)、`TokenAuthenticationFilterTest` 新增回归用例(1)、`AiAppEndpointScopeContractTest`(3，server)、`AiIdentityIsolationIT`(3，真实 MySQL + MockMvc) |

## 2. 与卡片逐步实施的对应

1. **实现 AiUserSessionCommonApi 及明确 scope 表达式**：Provider 实现框架的 `UserSessionCommonApi`
   并只声明 MEMBER；scope 表达式以独立 Bean（`aiScope`）提供，语义落在 `docs/contracts/ai/scope-catalog.md`，
   表达式内的资源类型/动作必须来自目录词表（契约测试强制）。
2. **从 token 构建当前 app/subject 并检查启用/有效期/撤销**：`checkAccessToken` 直接调用 A04 的
   `AiTicketService.verify`——该调用每次都重新读取应用与主体状态，因此票据撤销、到期、应用停用、
   主体撤销都会立即认证失败（沿用 A04 的稳定 401 语义，无跨请求缓存）。
3. **端点授权扫描覆盖 AI app controllers 及 scope 目录**：`AiAppEndpointScopeContractTest` 扫描
   `com.basicframework.module.ai.controller.app` 下全部映射，要求"恰好一种策略"、
   匿名端点必须在目录中登记、`@PreAuthorize` 只能使用目录词表；与既有的
   `EndpointAuthorizationContractTest`（策略存在性）互补。

## 3. 关键约束与安全语义

- **ADMIN / MEMBER 互斥（AT-007）**：`AiIdentityIsolationIT` 用真实 MySQL + MockMvc 验证
  "AI 票据可用于 MEMBER 会话、但用于 `/admin-api` 被拒 401/403"与
  "ADMIN 会话用于 `/app-api` 受保护端点被拒 401/403"，两个方向都不存在身份降级。
- **未知类型不回退（AT-008）**：请求声明未装配会话校验器的用户类型时直接拒绝（401/403），
  不回退到 ADMIN；Provider 缺失/重复在启动期失败（既有框架行为 + 本次 NPE 修复后的显式拒绝）。
- **fail-closed**：scope 表达式在无登录用户、无 AI 会话字段、目录外类型/动作、判定异常时全部返回 false，
  并且不记录异常正文。
- **边界修复记录**：新增第二个 Provider 后，框架原实现在"请求未声明用户类型"时对不可变 Map
  取 null 键会 NPE（500）。修复为显式 `AccessDeniedException`（403），并补回归单测；
  该修复虽然超出卡片列出的路径清单，但为满足 AT-008"禁止回退/必须拒绝"所必需，已在此登记。

## 4. 验证结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -pl basic-framework-core/basic-framework-spring-boot-starter-security test -Dtest=TokenAuthenticationFilterTest` | 0 | 10 例通过（含多 Provider 未声明类型的拒绝回归） |
| `./mvnw -pl basic-framework-module-ai verify` | 0 | 117 例通过（A05 新增 8 例） |
| `./mvnw -pl basic-framework-server test -Dtest=AiAppEndpointScopeContractTest` | 0 | 3 例通过（端点策略 + 目录词表 + 文档一致性） |
| `./mvnw -Pintegration -pl basic-framework-server verify -Dit.test=AiIdentityIsolationIT` | 0 | 3 例通过（AT-007/AT-008 双向隔离 + 未知类型拒绝） |
| `sh .harness/verify.sh contracts` / `backend` / `integration` + 棘轮 | 见交接记录 | 与本批后续卡片同批执行 |

## 5. 未验证项

1. **packaged jar 形态的跨端拒绝**：本卡用 MockMvc + 真实 MySQL 覆盖双向隔离；
   `PackagedJarBootSmokeIT` 目前只验证启动/健康/Flyway/Redis，未加入 HTTP 跨端用例（后续卡片补）。
2. **scope 守卫的真实端点**：目录与扫描已就绪，但暂没有需要细粒度资源授权的应用端业务端点
   （随 S/O/K/D 系列接入）。
3. **会话与设备的绑定**：沿 A04 的未验证项，票据与会话尚未绑定设备指纹。
