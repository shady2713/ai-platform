# O01 会话与消息存储证据（2026-09-19）

本记录是 [O01 实现会话和消息存储](../tasks/O01.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 持久化 | 迁移 `V59__ai_conversation.sql`：`ai_conversation`（归属 + 绑定服务/发布版本 + 业务上下文 + 状态）与 `ai_conversation_message`（会话内序号 + 角色 + 正文 + 正文摘要）；4 条物理外键、2 个唯一键；快照同步至 V59（逻辑删除表 27 → 29） |
| 会话 API | `controller/app/v1/conversation/AiConversationController`：`POST /ai/conversation/{create,message}`、`GET /ai/conversation/{page,get,messages}`、`PUT /ai/conversation/{rename,bind-service,bind-release}`、`DELETE /ai/conversation/delete`，全部 `@AuthenticatedOnly` 并登记到 scope 目录 |
| 服务层 | `service/conversation/AiConversationService(+Impl)`：创建/分页/读取/重命名/删除、绑定服务与固定发布版本、追加消息与按序号分页、`loadRunContext`（进入新运行前重新鉴权） |
| 身份解析 | `AiConversationSubjectResolver`（从 MEMBER 会话的服务端附加信息解析应用 + 主体 + 外部用户标识，解析失败按拒绝）、`AiConversationSubject` |
| 台账同步 | `data-lifecycle.json`（两张表进软删除策略 + 4 条物理外键）、`data-permission-exemptions.json`（新豁免 `ai-conversation-data`，`subject-bound` + 逐表证据）、`PersistenceLifecycleIT` 预期表清单、`docs/contracts/ai/scope-catalog.md` 登记 9 个登录即可访问端点 |
| 错误码 | `1_003_004_002 AI_CONVERSATION_KEY_DUPLICATE`，与 `docs/contracts/ai/error-code-map.md` 两侧同步 |
| 边界测试 | `AiConversationServiceImplTest`(13)、`AiConversationControllerTest`(5)、`AiConversationSubjectTest`(2)、`AiConversationSubjectResolverTest`(3)、`AiConversationIT`(4，真实 MySQL) |

## 2. 与卡片逐步实施的对应

1. **创建/列出/重命名/删除当前主体会话**：归属完全来自服务端解析出的主体（应用 + 主体类型 + 外部用户标识），
   请求体只能给出业务键、标题、服务与业务上下文；所有查询都带主体条件（`selectOwned` / `selectPageBySubject`），
   越权访问与不存在同语义（404）。分页按编号倒序，新增会话不影响已翻页结果。
2. **绑定服务 release 与业务上下文 schema**：会话可绑定服务（服务必须属于同一应用，否则 404）；
   首个运行解析到 releaseId 后由 `bindRelease` 固定，且只固定一次——已固定后改绑服务或换版本都返回 409，
   升级会话必须显式新建或迁移（与 FR-07 的会话版本语义一致）。业务上下文按 JSON 对象保存，
   已注册字段的判定由运行时上下文构造器（S04）执行，本卡不重复实现一份白名单。
3. **消息内容作为受控业务数据保存，历史引用再鉴权**：正文只按主体过滤读取，写入时同时落 SHA-256 摘要
   （摘要不等于正文）；`sequence_no` 在会话内递增并带唯一约束，按序号翻页不重复、不跳过；
   `loadRunContext` 在返回历史与固定版本之前调用 S03 的 `resolvePinnedRun`，按**当前**授权重新判定固定版本，
   失权即拒绝——这就是"旧上下文无权限不得进入新 run"。

## 3. 关键约束与安全语义

- **归属不可自报**：请求体没有应用/主体/归属字段（`AiConversationPageReqVO` 无自有字段，单测直接断言），
  身份只来自 A05 写入 MEMBER 会话的服务端信息；非 MEMBER 或缺少会话信息时按"不存在"处理。
- **越权与不存在同语义**：跨主体读取、改名、读消息、追加消息全部 404（IT 用第二个主体逐项断言）。
- **删除先关闭访问**：删除先把会话状态置 DELETED 并关闭其全部消息（`releaseAll`），再走标准逻辑删除；
  正文的物理清理与保留期由 O06 的清理任务按批次处理，避免"先删数据、后关权限"的窗口。
- **正文不进日志**：DO/DTO/VO 的正文与业务上下文字段都从 `toString()` 排除；只记录摘要与计数。
- **固定版本不是权限**：会话固定的发布版本只决定"用哪个版本"，资源授权与停用状态始终按当前值判定。
- **并发安全**：会话行乐观锁串行化并发写（重命名/删除/绑定/追加消息），CAS 失败即回滚，消息不会留下半成品。

## 4. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -o -pl basic-framework-module-ai test` | 0 | 242 例通过（O01 新增 23 例：服务 13、控制器 5、主体 2、主体解析 3） |
| `./mvnw -o -pl basic-framework-server test -Dtest='AiAppEndpointScopeContractTest,EndpointAuthorizationContractTest,ErrorCodeUniquenessTest'` | 0 | 端点策略与错误码契约通过（会话端点已登记为登录即可访问） |
| `./mvnw -Pintegration -pl basic-framework-server test -Dtest=AiConversationIT` | 0 | 4 例通过（真实 MySQL：主体归属与越权、分页稳定、删除先关闭访问、运行上下文重新鉴权） |
| `node scripts/check-data-lifecycle.mjs` / `check-data-permission.mjs` / `check-permission-catalog.mjs` | 0 / 0 / 0 | 生命周期台账（54 张表 / 29 条外键 / 29 张软删除）、数据权限分类、权限目录全部通过 |

## 5. 顺带修复的依赖缺口

本卡未发现需要顺带修复的既有缺口。会话的版本固定语义直接复用 S03 的 `AiRunSnapshot` 与
`resolvePinnedRun`（而不是自己实现一套版本比较），因此"回退只影响后续运行、固定会话沿用原版本"
在会话侧自动成立，没有出现第二套版本判定逻辑。

## 6. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、权限目录、生命周期、字段目录、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单元测试、格式、架构与覆盖率检查通过 |
| `sh .harness/verify.sh integration` | 1 → 0 | 38 个 IT 类 / 105 例全绿（含 `AiConversationIT` 4 例）；唯一失败是尾部棘轮（新文件未登记，属预期），`--update` 后复验通过 |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 新增登记 6 个文件（最低 84.21%），无基线下调、无登记删除，复验通过 |

## 7. 覆盖率

新增受测文件（数字取自 server 的 jacoco aggregate 报告，即 `mvn clean verify` 生成）：

| 文件 | 覆盖率 |
|---|---|
| `service/conversation/AiConversationServiceImpl.java` | 91.33% |
| `service/conversation/AiConversationSubject.java` | 100% |
| `service/conversation/AiConversationSubjectResolver.java` | 100% |
| `dal/mysql/conversation/AiConversationMapper.java` | 100% |
| `dal/mysql/conversation/AiConversationMessageMapper.java` | 84.21% |
| `controller/app/v1/conversation/AiConversationController.java` | 100% |

## 8. 未验证项

1. **运行与消息的关联**：`source_run_id` 列已就绪，但运行编号在 O02 产生；本卡不写入该列。
2. **保留期与清理**：删除只关闭访问，正文的物理清理、批次与审计属 O06（任务查询、重试与清理）。
3. **消息与知识/数据资源的引用**：附件、检索片段与工具结果进入消息属 O05/O06 与 K/D 系列。
4. **会话级配额与限流**：会话与消息数量上限、按主体限流属 Q 系列（配额与限流）。
