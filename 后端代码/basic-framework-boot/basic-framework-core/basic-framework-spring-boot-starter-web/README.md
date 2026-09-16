# basic-framework-spring-boot-starter-web

Web 能力接缝，负责 API 前缀、CORS、统一响应与异常、访问日志、请求体缓存、XSS 清理和 OpenAPI 装配。

## 请求边界

- JSON 请求体仅为重复读取和访问日志缓存，默认上限 1MB，通过 `basic-framework.web.request-body-cache-max-bytes` 调整，允许范围为 1 字节至 10MB。超过上限直接返回 HTTP 413；文件上传使用 multipart 流程，不经过此缓存。
- JSON 字符串字段只接受 JSON string；数字、数组和对象不会被宽松转换为字符串。XSS 排除地址只跳过内容清理，不跳过类型校验。
- XSS 清理只处理请求参数值和 JSON 字符串字段，不改写 Header、Servlet Attribute 或原始 QueryString，避免破坏认证信息与框架内部状态。
- 默认富文本白名单不允许 `style` 和 `data:` 图片。确需更宽富文本能力时，应由业务模块提供独立、可审查的 `XssCleaner`，不得扩大全局白名单。
- 访问与错误日志中的请求、响应和路径变量均按敏感字段目录脱敏：已路由请求只记录 SpringMVC 路由模板，未路由请求记录固定标识；未处理异常仅记录固定安全文案，具体堆栈由错误日志负责，避免将内部异常正文写入访问日志表。
- 响应序列化或脱敏失败时，访问日志写入固定占位内容且不影响业务响应；访问日志基础设施自身失败时只记录异常类型，不记录可能携带连接串、SQL 参数或凭据的异常消息。
- `captchaVerification` 等验证码校验凭证按 L4 处理；通用清理器按标准化字段名删除，Controller 不得仅依赖局部注解来保护它。
- 接口额外敏感字段通过 `@ApiAccessLog.sanitizeKeys` 追加，不得缩减全局脱敏规则；数据分级由仓库 `docs/security/data-classification.md` 定义。
- 传输层错误遵循 HTTP 标准：方法不支持为 405（含 `Allow`）、请求体过大为 413、媒体类型不支持为 415；Filter 与 SpringMVC 共享 `CommonResult` 错误体。
- `BasicFrameworkWebAutoConfiguration` 注册传输层异常处理 Bean，并注入全局异常处理器；SpringMVC 与 Filter 使用同一个实例，无需应用扫描 starter 的内部包。
- 路由和静态资源未命中的 404 使用固定提示，不在响应或调试日志中拼接原始路径，避免路径中的个人信息或凭据经访问日志结果消息再次保存。
- 数据库唯一冲突（包括预检查通过后的并发写入）统一返回 HTTP 409 与固定提示。处理器按异常类型识别冲突，不解析 SQL、索引名或冲突字段原值；业务预检查仍可返回领域内的明确冲突提示。
- 客户端 IP 解析为 fail-closed：默认不采信 `X-Forwarded-For` / `X-Real-IP`，直接使用 TCP 对端地址，防止伪造代理头绕过按 IP 的限流与审计。部署在反向代理之后时，必须把代理出口 IP 或网段配置到 `basic-framework.web.trusted-proxies`（支持 IPv4/IPv6 精确地址与 IPv4 CIDR，如 `127.0.0.1`、`10.0.0.0/8`）；配置项格式非法时启动期直接失败。未配置可信代理时，限流与访问审计取到的都是代理 IP。

## 扩展约束

应用端 Controller 继续放在 `controller.app`，自动获得 `/app-api` 前缀；管理端 Controller 放在 `controller.admin`，自动获得 `/admin-api` 前缀。微信小程序、APP 和 H5 共用应用端契约时，不需要修改本 starter。

OpenAPI 的全量分组和模块分组统一读取上述前缀，不自行硬编码路径。认证参数只声明 `Authorization` 请求头，不提供示例令牌或默认凭据；界面增强由官方 Knife4j 自动配置负责。
