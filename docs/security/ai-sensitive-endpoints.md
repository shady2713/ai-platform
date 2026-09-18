# AI 敏感端点登记（A08 冻结，机器校验）

本表登记 AI 中台**接触秘密、票据或凭据**的端点，以及它们在日志与 URL 维度的约束。
`AiSensitiveEndpointRegistryTest` 扫描控制器强制"该登记的必须登记、已登记的必须存在"。

## 登记表

| 端点 | 实现标记 | 敏感面 | 日志/URL 约束 |
|---|---|---|---|
| `POST /app-api/ai/auth/ticket` | `AiAuthController#issueTicket` | 请求体携带 `appSecret`，响应携带一次性票据明文 | 秘密与票据**不得出现在日志、URL、路径变量或查询参数**；服务端只存摘要 |
| `POST /admin-api/ai/application/create` | `AiApplicationController#createApplication` | 请求体携带首个凭据 | 明文只在响应出现一次；日志与访问日志不得记录请求体 |
| `PUT /admin-api/ai/application/rotate-credential` | `AiApplicationController#rotateCredential` | 凭据由服务端生成（请求不带秘密） | 明文只在响应出现一次；日志与访问日志不得记录响应体 |
| `PUT /admin-api/ai/application/revoke-credential` | `AiApplicationController#revokeCredential` | 凭据状态变更（请求不带秘密） | 响应不返回任何凭据材料；撤销立即生效且不可回显 |
| `POST /app-api/ai/file/upload` | `AiFileController#upload` | 请求体包含文件内容 | 内容不落日志 |
| `GET /app-api/ai/file/{fileId}` | `AiFileController#read` | 路径变量为文件编号（非秘密） | 路径不得包含票据或秘密；无权限与不存在同语义 |
| `DELETE /app-api/ai/file/{fileId}` | `AiFileController#release` | 路径变量为文件编号 | 同上 |

## 通用约束（由安全 starter 与本表共同保证）

- **票据**只经 `Authorization` 头传递：AI 端点不接受把票据放在路径或查询参数。
- **秘密明文**只出现在创建/轮换的响应体里一次；访问日志与错误日志按敏感字段目录清理。
- **SQL 参数、问题正文、检索片段**不得进入日志：AI 侧错误只暴露稳定原因码（`ModelException.Reason`）
  与 AI 错误码，不拼接上游报文。
- 断言方式：`AiLogLeakageTest` 在 DEBUG 全量捕获日志，验证上述值一次都不出现。
