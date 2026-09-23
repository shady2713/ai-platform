# AI 应用端 scope 目录（A05 冻结）

应用端（`/app-api/ai/**`）的每个接口必须二选一：**匿名审查端点**（`@PermitAll`，登记在下表）
或**scope 守卫**（`@PreAuthorize("@aiScope.hasScope('资源类型','资源标识','动作')")`）。
守卫的判定委托 A03 的统一授权入口（应用启用 → 主体范围 → 授权目录 → 动作白名单），表达式自身不做缓存。

## 资源类型 × 动作

| 资源类型（AiResourceType） | 允许动作（AiAction） | 说明 |
|---|---|---|
| `REPORT` | `READ` / `EXECUTE` / `EXPORT` | 报表读取、运行、导出 |
| `KNOWLEDGE_BASE` | `READ` / `EXECUTE` | 知识库检索与问答 |
| `FILE` | `READ` / `EXPORT` | 文件读取与导出 |
| `TOOL` | `EXECUTE` | 工具调用 |
| `DATASET` | `READ` / `EXPORT` | 数据集查询与导出 |

动作词表是白名单：未列入的动作在授权目录写入时即被拒绝（400），表达式解析未知类型/动作时返回拒绝。

## 匿名审查端点

| 端点 | 归属 | 鉴权方式 | 理由 |
|---|---|---|---|
| `POST /app-api/ai/auth/ticket` | `AiAuthController#issueTicket` | 应用客户端凭据（appCode + appSecret）+ 失败节流 | 换票入口本身以客户端凭据鉴权；不接受仅 Origin 的调用 |

新增匿名端点必须同步更新本表，并由 `AiAppEndpointScopeContractTest` 与
`EndpointAuthorizationContractTest` 双重扫描拦截。

## 登录即可访问端点（服务层按业务归属判定）

这类端点不声明 scope 表达式，只要求已认证的 MEMBER 主体；**归属判定在服务层**按业务绑定执行，
同样必须在本表登记（契约测试强制）：

| 端点 | 归属判定 |
|---|---|
| `AiFileController#upload` | `ai_file_binding`：报表/知识库按 A03 授权目录，会话附件仅本人 |
| `AiFileController#read` | 同上，且每次都按当前状态重新判定（无缓存） |
| `AiFileController#release` | 仅所有者可解除引用；无其他有效引用时才删除文件 |
| `AiConversationController#create` | `ai_conversation`：归属由服务端会话身份（应用 + 主体 + 外部用户标识）决定，请求体不能自报 |
| `AiConversationController#page` | 同上：只返回当前主体的会话（按编号倒序，翻页稳定） |
| `AiConversationController#get` | 同上：越权与不存在同语义（404） |
| `AiConversationController#rename` | 同上：仅本人会话可改名（乐观锁） |
| `AiConversationController#delete` | 同上：先关闭访问（会话状态 + 全部消息），正文由保留策略清理 |
| `AiConversationController#bindService` | 同上：服务必须属于同一应用；已固定发布版本后需显式新建或迁移会话 |
| `AiConversationController#bindRelease` | 同上：版本只固定一次，必须是该服务已发布过的版本 |
| `AiConversationController#appendMessage` | 同上：消息按会话归属写入，序号在会话内递增 |
| `AiConversationController#messages` | 同上：按序号升序读取，越权与不存在同语义 |
| `AiRunController#accept` | `ai_run`：归属由服务端会话身份决定；幂等键与请求摘要决定复用或 409 |
| `AiRunController#get` | 同上：越权与不存在同语义（404） |
| `AiRunController#page` | 同上：只返回当前主体的运行（按编号倒序） |
| `AiRunController#events` | `ai_run_event`：订阅前按当前主体判定归属；开流后的错误以终态事件表达，心跳是注释 |
| `AiToolActionController#confirm` | `ai_tool_action`：归属由服务端会话身份决定；确认必须携带一次性挑战与原参数（改参数/换用户/过期拒绝），执行 CAS 只发生一次 |
| `AiToolActionController#reject` | 同上：只有 PENDING 可拒绝，终态后拒绝无效 |
| `AiToolActionController#execute` | 同上：只有 CONFIRMED 可执行一次（重放不产生第二次副作用） |
| `AiToolActionController#get` | 同上：越权与不存在同语义 |
| `AiToolActionController#page` | 同上：只返回当前主体的动作（按编号倒序） |
| `AiRunController#cancel` | `ai_run`：取消是显式动作，写入终态事件并终止任务；越权与不存在同语义 |
| `AiTaskController#progress` | `ai_run`：按当前主体过滤运行，只返回状态与结果引用（标识 + 摘要） |
| `AiTaskController#page` | 同上：只返回当前主体的运行进度（按编号倒序） |
| `AiTaskController#retry` | 同上：人工重试先按当前权限重建身份，再校验任务可重试性（UNKNOWN 拒绝普通重试） |
| `AiKnowledgeRetrievalController#search` | 知识库：授权范围来自当前主体的 A03 授权目录（READ ∩ 启用中知识库），**过滤条件只由它推导**（问题文本不参与）；候选复核要求知识库启用 + 版本 READY + 是文档当前 active 版本，否则丢弃并计数（索引残留不可见） |
| `AiKnowledgeRetrievalController#citation` | 同上：引用标识只能来自本次检索候选；读取片段重新鉴权后从**原文**取回该位置正文，授权回收即失败（越权与不存在同语义） |
| `AiKnowledgeRetrievalController#content` | 同上：读取原文经 A07 的业务文件权限 SPI（业务类型 `ai_knowledge_document`，业务键 = 知识库标识），文件引用解除或授权回收后立即拒绝 |
| `AiReportController#save` | `ai_report`：归属由服务端会话身份（应用 + 主体类型 + 外部用户标识）决定，请求体不能自报归属；新建带 code，保存新版本必须回传乐观锁版本（不一致 409） |
| `AiReportController#get` | 同上：越权与不存在同语义（404），不借错误码枚举他人报表编号 |
| `AiReportController#page` | 同上：只返回当前主体的报表（按编号倒序），模式过滤只收窄自己的结果集 |
| `AiReportController#versions` | 同上：只返回本人报表的版本摘要（不含规格与数据正文） |
| `AiReportController#current` | 同上：读取当前生效版本前按 A03 `reauthorizeHistorical` 复核保存时的范围指纹，指纹不一致或判定拒绝即 409，要求按当前权限重新生成 |
| `AiReportController#version` | 同上：**旧版本编号也是读取入口**，同样复核范围指纹，复制 reportId/旧版本号不能绕过 |

## 会话身份（MEMBER 用户类型）

- `AiUserSessionCommonApi` 只声明 `UserTypeEnum.MEMBER`：ADMIN 会话与 MEMBER 会话**互斥**，
  跨端调用（ADMIN token 调 `/app-api`、MEMBER 票据调 `/admin-api`）被框架的类型校验拒绝，不降级。
- 会话只携带**服务端建立的字段**：应用编号、主体类型、可信外部用户标识、范围指纹；
  外部主体身份不映射为系统用户编号（`userId` 使用票据编号）。
- 票据撤销、到期、应用停用或主体撤销后，`checkAccessToken` 抛稳定错误 → 框架按"未认证"处理。
