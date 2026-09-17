# F09 受控外部 HTTP 传输边界证据（2026-09-17）

本记录是 [F09 建立受控外部HTTP传输边界](../ai-platform/tasks/F09.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 出站客户端契约 | `starter-ai` `core/http/ExternalHttpClient`（execute / executeAsync + 取消语义 / close） |
| 受控实现 | `core/http/GuardedExternalHttpClient`：允许清单、地址校验、协议限制、不跟随重定向、TLS 默认校验、超时、响应上限、请求头卫生、取消与关闭 |
| 请求/响应/错误类型 | `ExternalHttpRequest`（服务端构造）、`ExternalHttpResponse`、`ExternalHttpException`（6 个稳定原因） |
| 策略配置 | `core/http/AiHttpProperties`（`basic-framework.ai.http.*`）：默认**拒绝一切目标** |
| 装配 | `BasicFrameworkAiAutoConfiguration`：启用 AI 时注册 `ExternalHttpClient`（`@ConditionalOnMissingBean` + `destroyMethod=close`） |
| 策略文档 | [docs/security/outbound-http-boundary.md](../../security/outbound-http-boundary.md)（闸门、传输行为、残余风险） |
| 启用说明 | starter-ai README 新增"受控出站 HTTP 边界"章节 |

## 2. 冻结规则（与卡片逐步实施对应）

1. **Origin/IP/端口允许策略 + 内网显式批准**：主机精确匹配允许清单、端口命中允许端口；清单为空拒绝一切；
   解析出的地址命中环回/链路本地/站点本地/唯一本地(IPv6 fc00::/7)/未指定/组播时，必须 `allow-private-targets=true` 才放行。
2. **DNS 与传输控制**：先解析并校验地址再做请求；不跟随重定向；TLS 用 JVM 默认信任库与主机名校验（无关闭开关）；
   连接/读取超时；响应体上限（声明长度与分块读取两条路径都拦截）。
3. **请求头由服务端构造**：`Host`/`Connection`/`Content-Length`/`Transfer-Encoding`/`Cookie`/`Set-Cookie` 一律拒绝；
   头部数量上限；`executeAsync` 取消即中止，`close()` 后拒绝新请求。

## 3. 验证结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -pl basic-framework-core/basic-framework-spring-boot-starter-ai verify` | 0 | 31 例通过；starter 行覆盖率门槛 **100%** 达成（含新 `core/http` 全部行） |
| `sh .harness/verify.sh contracts` | 见第 4 节 | 文档链接与契约门禁 |
| `sh .harness/verify.sh backend` | 见第 4 节 | 后端全量门禁（编译/单测/覆盖率/Spotless/ArchUnit） |

`GuardedExternalHttpClientTest`（22 例，全部使用本地 `HttpServer`，不访问外网）覆盖：
空清单拒绝一切、主机不在清单、端口不在清单、私网未批准拒绝、批准后放行（含请求头被服务端重写）、
协议限制（http 仅内网批准时允许）、被禁头与头部数量上限、非法地址（缺方法/不可解析 URI/缺主机）、
域名不可解析、连接被拒（关闭端口）、不跟随 302、声明超限、分块超限、读取超时、取消、关闭后拒绝、
失败映射（超时/连接/未知原因/已稳定原因透传）、地址分类（环回/站点本地/链路本地/未指定/组播/IPv6 ULA 拒绝，公网放行）。

## 4. 门禁与棘轮

新增文件的单文件覆盖率基线按 `node scripts/check-coverage-ratchet.mjs --update` 登记（只升不降）；
integration 门禁包含打包 jar 启动探测（出站客户端默认拒绝策略不影响未启用 AI 的应用启动）。

## 5. 未验证项

1. **真实出站调用**：平台尚无消费方（M02 模型工厂、D01 连接器实现时接线），本卡只验证边界本身；
   接入后需补"经边界访问真实模型端点"的集成用例。
2. **DNS TOCTOU 残余风险**：地址校验与连接之间存在解析变化窗口，已在策略文档记录；
   需要更强保证时叠加网络层出口网关（不在本卡范围）。
3. `module-ai` 的 `adapter/http` 与 `service/connector` 目录尚未有代码（等 M02/D01 落地后再建），
   本卡未创建空包。
