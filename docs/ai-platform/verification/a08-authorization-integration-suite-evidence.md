# A08 授权和身份反向集成套件证据（2026-09-18）

本记录是 [A08 实现授权和身份反向集成套件](../tasks/A08.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 权限矩阵报告 | `docs/security/ai-authorization-matrix.md`：应用 × 主体 × 资源 × 操作的**权威预期矩阵**（14 行），由测试逐格校验 |
| 集成拒绝证据 | `AiAuthorizationMatrixIT`（真实 MySQL + 真实服务链路）：逐格执行并输出 `expected=… actual=…` 报告；跨应用/跨主体/撤销/未知类型全部真实验证 |
| 敏感端点登记 | `docs/security/ai-sensitive-endpoints.md` + `AiSensitiveEndpointRegistryTest`：接触秘密/票据/凭据的端点必须登记，登记项必须真实存在，且敏感值不得出现在路径或查询参数 |
| 日志全链路断言 | `AiLogLeakageTest`（AT-058）：DEBUG 级捕获日志，断言凭据明文、票据、提示词正文、个人信息与 SQL 片段一次都不出现 |
| 顺带修复 | `SpringAiModelStreamTest.closeReleasesUpstreamSubscriptionAndIsIdempotent` 的注册竞态（先注册取消回调再放行测试线程），修复后连续 3 次运行通过 |

## 2. 与卡片逐步实施的对应

1. **构造 appA/appB 及 Alice/Bob 身份矩阵**：两个启用应用（crm-portal / portal-b）、三个主体
   （alice/bob 属 appA，carol 属 appB），alice 在 appA 拥有 `report-1`（REPORT）与 `kb-1`（KNOWLEDGE_BASE）的 READ 授权。
2. **覆盖所有 scope/主体/资源组合和撤销**：矩阵 14 行覆盖
   命中、动作白名单外、同 ID 不同类型不串权、同应用他主体、跨应用、会话附件所有者、跨应用文件、
   授权撤销后、应用撤销后换票、主体撤销后旧票据、范围指纹变化后的历史再鉴权、未知主体/未知业务类型。
   每格都经真实实现：A01 应用与凭据、A02 主体范围、A03 授权判定与指纹、A04 票据、A06 撤销、A07 文件。
3. **敏感入口列表、日志与 URL 泄漏断言**：敏感端点登记表逐项与控制器实现对照（缺登记即失败、登记不存在即失败）；
   URL 维度断言"票据与秘密只能经请求头/请求体传递"；日志维度用金丝雀字符串做全量断言。

## 3. 关键约束与安全语义

- **不是 mock 授权**：矩阵每格调用真实服务与真实 MySQL；授权失败由服务层真实抛出稳定错误码，
  测试捕获 ServiceException 归为 DENY（与"无权限=不存在"的语义一致）。
- **矩阵即契约**：`docs/security/ai-authorization-matrix.md` 与测试同步；任一语义变更都会让门禁失败，
  强制"先改契约再改实现"。
- **敏感面登记制**：控制器扫描覆盖 AI 的全部应用端与管理端控制器，按"方法名或入参类型含
  credential/secret/token"判定敏感面，未登记即失败。
- **日志金丝雀**：金丝雀是完整业务语义串（含手机号与上游报文），断言其不出现在任何 DEBUG 日志、
  也不出现在异常消息里。

## 4. 验证结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -pl basic-framework-module-ai verify` | 0 | 151 例通过（A08 新增 3 例：日志泄漏） |
| `./mvnw -pl basic-framework-core/basic-framework-spring-boot-starter-ai test -Dtest=SpringAiModelStreamTest` | 0 | 连续 3 次通过（修复竞态后） |
| `./mvnw -pl basic-framework-server test -Dtest=AiSensitiveEndpointRegistryTest` | 0 | 3 例通过（登记完整性 + 存在性 + URL 约束） |
| `./mvnw -Pintegration -pl basic-framework-server -am verify -Dit.test=AiAuthorizationMatrixIT` | 0 | 2 例通过（14 行矩阵 + 撤销类断言，真实 MySQL） |
| `sh .harness/verify.sh contracts` / `backend` / `integration` + 棘轮 | 见交接记录 | 与本批后续卡片同批执行 |

## 5. 未验证项

1. **packaged jar 形态的 AT-007**：双向隔离已在 A05 用 MockMvc + 真实 MySQL 覆盖；
   `PackagedJarBootSmokeIT` 目前只验证启动/健康/Flyway/Redis，未加入 HTTP 跨端用例（仍为开放项）。
2. **浏览器侧撤销体验**：AT-010 的浏览器部分随 Q 系列补齐。
3. **矩阵未覆盖资源**：当前 AI 侧只有 REPORT / KNOWLEDGE_BASE / CHAT_SESSION 三类可授权对象；
   工具（TOOL）与数据集（DATASET）随 D/K 系列接入后扩展矩阵。
4. **敏感端点的响应侧**：响应字段集合已由 A01（无凭据字段）与 A05（scope 目录）测试覆盖；
   本卡聚焦"请求侧与日志侧"的登记与断言。
