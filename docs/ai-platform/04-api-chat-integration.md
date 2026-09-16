# 04 对外 API 与第三方 Chat 集成协议

状态：v1 设计草案；以下路径和消息不是现有接口。实施需由对应任务生成 OpenAPI/JSON Schema 并与本文件一致。

## 1. 三种接入方式

| 方式 | 调用方负责 | 中台负责 |
|---|---|---|
| 后端 API | 认证自身用户、保管应用凭据、处理结果 | AI服务、权限、运行、知识、查询、产物 |
| 标准 Chat + SDK | 提供容器、换票回调、主题/业务上下文 | 全部 Chat UI 和结果渲染 |
| 自建前端 | 界面、流式事件消费、结果渲染 | 同一 API 与服务执行，不另建业务实现 |

API 发布和应用管理分别建页面。开放 API 用 `/app-api/ai/v1`，控制面用 `/admin-api/ai`。此处“开放”表示对业务系统开放，不要求暴露公网。

## 2. 认证与凭证

### 2.1 服务端换票

`POST /app-api/ai/v1/auth/token`

- 仅可信业务后端调用。使用 `Authorization: Basic base64(appKey:appSecret)`，必须 TLS；不把 secret 放 QueryString。
- 请求：subjectKind=APP 或 USER；USER 必须 externalUserId；可请求 scopes、serviceIds，服务端取与应用授权的交集。
- 对应用凭据、主体、来源及配额进行校验后返回 accessToken、tokenType=Bearer、expiresIn、subjectId、实际 scopes。
- USER 模式是对业务后端身份声明的信任委托；接入合同明确该后端必须先验证自己的登录用户。中台不接受浏览器直接提交用户 ID 换票。
- 该接口采用自有应用换票协议，不宣称完整 OAuth2/OIDC 服务。未来接企业 IdP 通过独立身份适配器。
- token 默认期限、熵和保留规则见 [05](05-data-security-contracts.md)。无浏览器 refresh token；到期由宿主后端重新换票。

`POST /app-api/ai/v1/auth/revoke`：撤销当前 token；应用级停用/主体禁用在管理命令中撤销相关 token。新票不会恢复已撤销权限。

### 2.2 请求身份

其他接口使用 `Authorization: Bearer <accessToken>`。appId/subjectId 从已认证票据获取，客户端 body 中同名字段不得改变身份。

APP 凭证只调用明确允许机器身份的服务。需要“我的客户”“我的部门”的服务必须使用 USER 和可验证范围。控制面 token 与开放 token 不互认。

### 2.3 Scope 建议目录

`services:read`、`runs:execute`、`runs:read`、`runs:cancel`、`conversations:write`、`files:write`、`files:read`、`knowledge:search`、`knowledge:write`、`reports:read`、`reports:write`、`reports:refresh`、`actions:confirm`。

scope 表示动作资格，资源 ACL 表示对象范围。持有 reports:read 不能读取其他用户的报表。管理端 `ai:*` 权限码与这些 scope 独立登记和测试。

## 3. 核心接口目录

| 方法与相对路径 | 请求重点 | 响应重点 | 权限 |
|---|---|---|---|
| GET /services | 分页、能力过滤 | 已授权发布服务及输入约束 | services:read |
| POST /conversations | serviceId、可选标题 | conversationId、serviceReleaseId | conversations:write |
| GET /conversations | cursor、limit | 当前主体会话列表 | 当前主体 |
| GET /conversations/{id}/messages | cursor、limit | 有权查看的消息/结果块 | 当前主体 |
| POST /conversations/{id}/rename | title、version | 新版本 | 当前主体 |
| DELETE /conversations/{id} | 无正文 | 删除任务或完成状态 | 当前主体 |
| POST /runs | serviceId、conversationId、message、attachments、context | runId、状态、links | runs:execute |
| GET /runs/{id} | 无正文 | 当前状态、步骤摘要、结果/错误 | runs:read + 归属 |
| GET /runs/{id}/events | afterSeq 或 Last-Event-ID | SSE事件 | runs:read + 归属 |
| POST /runs/{id}/cancel | reasonCode | 当前取消状态 | runs:cancel + 归属 |
| POST /files | multipart、purpose | fileId、大小、类型、状态 | files:write |
| GET /files/{id}/content | 无正文 | 授权文件流 | files:read + 业务ACL |
| POST /knowledge/search | serviceId、query、可选缩小范围 | 引用及有权片段 | knowledge:search |
| POST /knowledge/{kbId}/documents | fileId、sourceKey、metadata | documentId、taskId | knowledge:write + KB管理 |
| GET /tasks/{id} | 无正文 | 状态、进度、错误、结果引用 | 归属与目的权限 |
| POST /reports | runId、保存模式、title | reportId、version | reports:write |
| GET /reports/{id} | 可选version | ReportSpec、受控结果 | reports:read + ACL |
| POST /reports/{id}/revisions | baseVersion、修改消息 | taskId/runId | reports:write + ACL |
| POST /reports/{id}/refresh | expectedVersion | taskId | reports:refresh + 源资源ACL |
| POST /actions/{id}/confirm | challengeId、expectedVersion | 执行状态 | actions:confirm + 当前策略 |
| POST /actions/{id}/reject | expectedVersion | 已拒绝 | 当前主体 |

确认相关协议先定义；V1.0 只发布已具备完整读工具确认闭环的端点，业务写工具在 V1.1 验收后开放。未交付接口不出现在公开目录。

## 4. 通用协议

- 普通 JSON 沿用 `CommonResult { code, msg, data }`，成功 code=0。HTTP 使用真实语义状态，不把 401/403/429 变成200。
- 创建运行/异步任务返回 HTTP202 与任务引用，其余成功按现有协议返回200。202与SSE属于需要 ADR/测试登记的响应形态。
- 400输入格式；401票据无效；403动作无权；404不存在或应隐藏的无权资源；409状态/版本/幂等冲突；413过大；422能力/业务语义不满足；429限流；502上游协议错误；503资源暂不可用；504超时。新增映射统一进异常桥接，禁止逐Controller手拼。
- 开放资源ID为有类型前缀的不透明字符串（如 app_/svc_/run_/rpt_），不得由前端解析数据库ID。内部管理Long保持现有序列化。
- `Idempotency-Key` 用于创建运行、保存/刷新、文档同步及确认命令。服务端按应用+主体+动作+键记录规范化请求摘要；并发同请求仅执行一次，不同正文409。
- 时间点使用带时区偏移的 RFC3339；业务日期区间显式 timezone，startInclusive/endExclusive。金额使用十进制字符串，字段标注单位/币种。
- requestId/traceId 为排查标识，不能承担认证或幂等。用户输入长度、上下文、文件、分页上限见05。
- 参数与响应 schema 明确 additionalProperties 策略；公共字段新增遵循兼容规则，未知结果类型必须可被旧 SDK 明确降级。

## 5. 统一结果模型

RunResult 包含：runId、serviceReleaseId、modelRevision、status、blocks、sources、usage、timings、warnings、completeness、createdAt/completedAt。

ResultBlock 按 type 区分：

| type | 内容 | 渲染要求 |
|---|---|---|
| text | 纯文本或受控Markdown AST | 禁原始HTML，链接协议与Origin校验 |
| table | columns、rows、单位、空值、分页信息 | 类型化单元格；金额精确显示 |
| chart | ChartSpec + datasetRef | 白名单图表、参数和字段绑定 |
| report | reportId/version/specRef | 独立预览、保存、刷新授权 |
| citation | documentId/version/chunkId/location | 由真实检索记录构建，点击再鉴权 |
| file | fileId、name、mime、size | 受控下载，不透传上游临时URL |
| action | actionId、工具摘要、参数摘要、到期时间 | 待确认操作，按钮不直接携带任意请求 |
| clarification | 问题、候选项或所需字段 | 用户回答后形成新输入，不伪装成功结果 |

`completeness`：COMPLETE / PARTIAL / UNKNOWN。出现 PARTIAL/UNKNOWN 时图表、文字和报表均显示原因。sources 包含资源引用、查询定义版本、数据截至时间及统计口径。

## 6. 流式协议

先 POST /runs 得到 runId，再使用带 Authorization 的 fetch GET 事件流。普通 EventSource 不便携带自定义 Authorization，因此 SDK 统一使用 fetch 流解析；禁止把 token 放URL。

每个持久事件含 protocolVersion、runId、seq、type、occurredAt、data；SSE id 与seq一致。事件类型：run.accepted、run.step、message.delta、block.completed、action.required、run.completed、run.failed、run.cancelled。心跳使用SSE注释行`: heartbeat`，不占seq、不持久化、不进入业务事件去重。

- 只有 terminal 事件或状态查询确认，才能显示完成。文字增量不是最终可信结构；图表/报表在 block.completed 后验证并渲染。
- 事件持久化保留期内按 afterSeq 重放，SDK按seq去重；超出重放窗口返回稳定错误，客户端读取run快照，不重新POST运行。
- 连接断开不代表取消；用户点取消才执行取消命令。后端可按配置取消无人订阅的短会话，但必须形成持久状态并在协议中声明。
- 开流前的错误按HTTP返回，开流后的错误发送run.failed。禁止把业务异常正文当message.delta。
- 服务器只记录必要事件，不把完整模型输出再写到通用访问日志。异步dispatch与安全上下文由集成测试验证。
- 同一 run terminal 状态单向且幂等；heartbeat不计费、不持久累积。过慢消费者有有界队列，转状态查询而不是无限缓存。

## 7. SDK 与嵌入协议

### 7.1 宿主接口（TypeScript 设计）

```ts
interface AiChatOptions {
  container: HTMLElement;
  appCode: string;
  embedOrigin: string;
  serviceId: string;
  mode: 'inline' | 'drawer' | 'dialog';
  getAccessToken: () => Promise<{ token: string; expiresAt: string }>;
  context?: BusinessContext;
  theme?: ThemeTokens;
  onNavigate?: (event: BusinessNavigateEvent) => void;
  onReportCreated?: (event: ReportCreatedEvent) => void;
}
// mountAiChat 返回 open/close/updateContext/updateTheme/resetSession/destroy。
```

声明类型在 ai-contracts/ai-embed-sdk 中定义；本示例不含 any。getAccessToken 必须调用宿主自己的后端，不能在回调中嵌入长期 secret。

### 7.2 握手

1. SDK验证 embedOrigin、创建iframe，src只带公开appCode、协议版本和非秘密实例标识。
2. embed页面的CSP frame-ancestors来自当前应用的精确允许Origin；管理页面安全头保持原策略。frame-ancestors不能仅靠meta标签或前端JavaScript设置。
3. SDK与iframe交换 HELLO/READY，双方校验 event.origin、event.source、instanceId、protocolVersion和schema；targetOrigin禁止星号。
4. 宿主后端换票成功后，SDK通过已校验通道发送 AUTH。iframe只在内存中保存；不写URL、localStorage、日志或埋点。
5. INIT携带service/context/theme；iframe按server发布配置校验主题和功能范围。宿主隐藏按钮不改变后端权限。
6. TOKEN_REQUIRED触发宿主去重续票；失败呈现重新登录状态。多次重试不能重复提交创建run请求。
7. DESTROY关闭流、销毁图表、清空token、移除监听器和DOM。宿主用户切换必须resetSession，旧响应通过instance/session generation丢弃。

消息白名单：HELLO、READY、AUTH、INIT、CONTEXT_UPDATE、THEME_UPDATE、OPEN、CLOSE、TOKEN_REQUIRED、REPORT_CREATED、NAVIGATE_REQUEST、ERROR、DESTROY。实例隔离必须支持同一页面挂载两个不同应用Chat。

NAVIGATE_REQUEST只携带登记的业务路由名与类型化参数，由宿主决定导航；禁止执行模型返回的任意URL/脚本。图片/附件下载在中台受控路径完成。

### 7.3 页面安全头

- embed动态页面由受控路由/反向代理输出CSP；允许Origin来自应用已发布配置，输入必须是精确https Origin，开发环境localhost走独立profile规则。
- 仅embed路由采用允许指定跨源祖先的策略，不能全局删除X-Frame-Options、关闭CSRF或扩大CORS通配。
- CSP包含自托管脚本和资源白名单，禁止unsafe-eval；图表库是否需要额外样式权限先由P0实测。
- 静态脚本使用固定版本路径和内容hash。一个部署内SDK与Chat协议兼容N/N-1，不能引用公共CDN/latest。

## 8. UI 主题与交互规范

### 8.1 ThemeTokens

brandName、logoFileId、assistantAvatarFileId、primaryColor、backgroundColor、surfaceColor、textColor、mutedTextColor、borderColor、fontFamily（白名单）、fontScale（small/normal/large）、radius（0/4/8/12）、density（compact/normal）、mode（light/dark/system）。

颜色仅接受已校验色值，图片走私有/公开品牌资产策略；禁止url()/CSS表达式/任意HTML。默认主题可用，应用主题为基线，宿主运行时覆盖仅限允许字段。覆盖不落库、不影响其他应用。

### 8.2 一致性范围

- 消息气泡、输入框、按钮、引用、表格、图表、报表与错误状态使用同一主题。
- 图表颜色序列需在深浅背景保持可辨识；状态不能只靠颜色表达。
- 宿主容器宽度决定布局；侧栏建议最小360px，窄屏切全屏；长表格自身滚动，不撑破宿主。
- 实时生成时可停止；失败时说明可重试范围；审批待确认时列具体动作和影响对象。
- 弹窗/侧栏焦点捕获、Esc关闭、关闭后焦点恢复；读屏播报按节流的完整片段，避免逐token刷屏。
- 主题切换保留会话与滚动位置；模式切换不重建业务身份。

### 8.3 交付示例

必须附两个不含真实凭证的本地示例：plain HTML +最小业务后端换票、Vue +主题/上下文切换。示例使用不同Origin，证明跨站集成有效；不只在同源开发代理下演示。

## 9. API 与 SDK 兼容

- API主版本在路径，SDK遵循semver；协议版本与包版本分开。
- 服务器至少接受当前与上一协议版本；新增可选字段不破坏旧消费者。新必需字段/语义变化需要新版本。
- 发布包保留N-1 SDK静态资源；旧报表经过schema迁移器读取或明确阻断，不能静默丢字段。
- 主题新增token带默认值；删除token经过弃用期。弃用提示进入开发文档和测试，不在业务用户对话中输出技术细节。

## 10. 第三方接入验收

换票 → 创建会话 → 发起run → 流式结果 → 图表 → 保存报表 → 刷新 → 引用下载 → token过期 → 用户切换 → 卸载。每一步验证正确身份、接口状态和视觉状态。

同时验证：错误Origin、伪造source、重放旧消息、两个iframe串线、伪造userId、跨app文件/报表读取、第三方Cookie禁用、旧SDK连接新版后端、网络断开后恢复。

标准依据：[postMessage](https://developer.mozilla.org/en-US/docs/Web/API/Window/postMessage)、[frame-ancestors](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy/frame-ancestors)。这些资料用于协议事实；本文件中的产品接口是拟定设计。
