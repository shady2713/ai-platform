# Security starter

该 starter 负责访问令牌认证、权限表达式，以及可恢复
凭据的统一加密接缝。

## 多用户类型会话

Token 过滤器按请求所属的 `UserTypeEnum` 选择 `UserSessionCommonApi` Provider。
每个 Provider 必须通过 `getSupportedUserType()` 声明唯一用户类型；重复声明会在
应用启动时失败，请求对应类型未装配 Provider 时拒绝认证。system 模块只提供
ADMIN Provider；接入微信小程序、APP 或 H5 会员体系时，由应用模块实现并注册
MEMBER Provider，不得让 `/app-api` 令牌回退到管理后台会话。

Controller 上的 `@PermitAll` 会在安全过滤链创建时转换为明确的 HTTP 方法与路径；
未声明方法时仅开放 GET、POST、PUT、DELETE、HEAD 和 PATCH。TRACE、OPTIONS 等未支持
方法会让应用启动失败，不允许静默扩大匿名访问面。
匿名路径只能通过 Controller 的 `@PermitAll` 或编译期可审查的
`AuthorizeRequestsCustomizer` 声明，不提供可由部署参数任意扩大的 URL 白名单。

## 接口授权声明

每个 Controller 映射必须且只能显式选择一种访问策略：匿名接口使用 `@PermitAll`，
只要求有效登录身份的个人能力或通用读取接口使用 `@AuthenticatedOnly`，需要业务权限码的
接口使用 `@PreAuthorize`。不得依赖过滤链的默认登录要求表达接口契约；
`EndpointAuthorizationContractTest` 会扫描管理端与应用端的全部映射并阻断遗漏。

BCrypt 强度必须位于 4–31，非法配置在启动期失败。

用户自选密码不由该 starter 直接编码；system 模块在所有设密入口执行 ADR 0011 的
统一策略。部署品牌保留词通过
`basic-framework.security.password-policy.reserved-terms` 配置，默认包含框架名。

## 可恢复凭据

短信渠道密钥、文件存储密钥统一通过 `CredentialCipher` 保护。密文采用
带版本前缀、随机 nonce 和业务上下文 AAD 的 AES-256-GCM；业务模块不得自行保存
明文或复制密码学实现。生产环境必须通过 `CREDENTIAL_ENCRYPTION_KEY` 注入 Base64
编码的 32 字节主密钥，密钥不得进入仓库、日志或 API 响应。非生产环境未配置时，
调用加解密能力会关闭失败。

## 高风险操作授权与审计

操作审计写入失败时只记录脱敏后的有界异常堆栈，不记录审计对象、请求参数或异常正文。

高风险入口继续使用服务端权限表达式，并由业务服务验证操作者与目标资源归属。

## AI 中台开放身份扩展（[ADR 0049](../../../../../docs/adr/0049-ai-open-identity-and-security-extension-boundaries.md)）

开放身份（业务系统 APP 主体、最终用户 USER 主体）不新增第二套认证链，仍通过
`UserSessionCommonApi` 接缝注册 Provider：每个用户类型只允许一个实现，重复或未知类型在启动期失败；
`system` 只提供 ADMIN Provider，AI 的 MEMBER Provider 由 AI 模块实现并注册。

拒绝规则（实现期不得放宽）：路径用户类型与会话类型不一致即拒绝；类型未装配即拒绝；
`/app-api` 令牌不得回退到管理后台会话；身份字段（userId/userType/权限/附加信息）只能由
"令牌 → 会话校验"构建，不接受请求参数或客户端 context 覆盖。

换票、撤销与执行上下文重建（A04/A06）落地时必须保持"票据只存摘要、撤销后不得在途回填"的既有取向。
