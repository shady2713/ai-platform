# ADR 0003：HTTP 状态语义化

- 状态：已接受

## 上下文

HTTP 状态必须表达通用结果，响应体业务码用于表达可稳定处理的子原因。
前后端和测试必须使用同一协议。

## 决策

成功响应使用 HTTP 2xx 且 `CommonResult.code = 0`。失败响应仍返回
`CommonResult{code,msg}`，HTTP 状态按下表选择：

| 场景 | HTTP |
|---|---:|
| 参数校验、解析或类型错误 | 400 |
| 未认证、Token 缺失或过期 | 401 |
| 已认证但无权限 | 403 |
| 资源不存在 | 404 |
| 请求方法不支持 | 405 |
| 唯一冲突、重复操作或非法状态迁移 | 409 |
| 请求体超过服务端上限 | 413 |
| 请求媒体类型不支持 | 415 |
| 其他领域语义错误 | 422 |
| 限流 | 429 |
| 未预期异常 | 500 |

- 映射只由 `GlobalExceptionHandler` 及安全过滤器的统一响应桥接实现，Controller 不自行包装状态。
- 前端以 HTTP status 为第一信号，body code 只用于业务分支和用户文案。
- 401 携带 `WWW-Authenticate`，405 携带 `Allow`，429 携带 `Retry-After`；500 对外只返回固定文案，
  原始异常消息不得进入响应。
- 前后端协议变化必须在同一变更中完成。

## 已批准的例外

- `POST /system/captcha/get` 与 `POST /system/captcha/check`（CaptchaController）直接返回
  anji-captcha 的 `ResponseModel`（repCode 协议），不包装为 `CommonResult`。
  原因：前端 `@vben/common-ui` 的 Verification 组件直接判定 `res.repCode === '0000'`，
  包装会打断滑动/点选验证码交互，而前后端协议切换必须同变更完成。
  收敛条件：前端验证码组件适配 `CommonResult` 后，两端在同一变更中移除该例外。
  现状由 `CaptchaControllerTest` 钉住。
