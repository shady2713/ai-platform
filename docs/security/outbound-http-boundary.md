# 出站 HTTP 传输边界（F09 冻结）

所有平台发起的外部调用（模型端点、业务连接器、Webhook、结果回调）必须经由
`basic-framework-spring-boot-starter-ai` 发布的 `ExternalHttpClient`，不得在业务代码里直接使用
`RestTemplate`、`HttpClient`、`OkHttp` 等自建客户端。实现为 `GuardedExternalHttpClient`。

## 默认策略与三道闸门

| 闸门 | 规则 | 默认 |
|---|---|---|
| 目标允许清单 | 主机必须精确命中 `basic-framework.ai.http.allowed-hosts`；端口必须命中 `allowed-ports` | **清单为空 = 拒绝一切目标**；端口默认仅 443 |
| 地址校验 | 解析目标全部地址；命中环回/链路本地/站点本地/唯一本地(IPv6 ULA)/未指定/组播时，必须显式 `allow-private-targets=true` 才放行 | 默认 `false`（企业内网模型与连接器需要显式批准） |
| 请求卫生 | 请求头只能由服务端构造；`Host`/`Connection`/`Content-Length`/`Transfer-Encoding`/`Cookie`/`Set-Cookie` 一律拒绝；头部数量上限 `max-header-count`（默认 32） | 默认拒绝上述头 |

## 传输行为

- **协议**：只允许 `https`；`http` 仅在显式批准内网目标（`allow-private-targets=true`）时允许。
- **重定向**：不跟随。3xx 原样返回，是否改址由业务决策（避免被目标引向未批准的主机）。
- **TLS**：使用 JVM 默认信任库与主机名校验，**不提供关闭校验的开关**。
- **超时**：连接超时 `connect-timeout`（默认 5s）；读取/整体超时 `read-timeout`（默认 30s），可按请求覆盖。
- **响应大小**：`max-response-bytes`（默认 1 MiB）；声明长度超限直接拒绝并丢弃，分块响应在读取到上限+1 字节时中断。
- **取消与关闭**：`executeAsync` 返回的 future 取消即中止本次调用；`close()` 后拒绝新请求。取消不保证上游已停止，
  需要幂等的上游写入必须自带去重键。
- **错误**：统一为 `ExternalHttpException` 的稳定原因（`TARGET_NOT_ALLOWED`、`PRIVATE_TARGET_DENIED`、
  `INVALID_REQUEST`、`CONNECT_FAILED`、`TIMEOUT`、`RESPONSE_TOO_LARGE`），消息与日志不含目标凭据与响应正文。

## 已知残余风险

1. **DNS TOCTOU**：地址校验与实际连接之间存在解析结果变化的窗口。当前通过"允许清单 + 私网显式批准"收窄影响面；
   需要更强保证时应叠加网络层出口网关（仅允许批准的目标）。
2. 允许清单为精确主机名匹配（不支持通配），目标变更需要显式配置发布。
3. 平台不代理上游内容：响应体只作为数据返回，模型输出不得当作可执行内容（见 ADR 0049 的信任链约定）。

## 验收与证据

`GuardedExternalHttpClientTest`（22 例）在本地 `HttpServer` 上覆盖：空清单拒绝一切、主机/端口不在清单、
私网未批准拒绝、批准后放行、协议限制、被禁头与头部数量、非法地址、域名不可解析、连接被拒、
不跟随 302、声明超限与分块超限、读取超时、取消、关闭后拒绝、失败映射与地址分类（含 IPv6 ULA 与公网放行）。
