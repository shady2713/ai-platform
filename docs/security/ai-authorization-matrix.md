# AI 中台授权矩阵（A08 冻结，机器校验）

本表是 AI 中台"应用 × 主体 × 资源 × 操作"的**权威预期矩阵**：
`AiAuthorizationMatrixIT` 用真实 MySQL 逐格执行并比对本表，**表格与代码任何一侧漂移都会让门禁失败**。
修改授权语义时必须同时改本表与测试。

## 身份与资源

| 代号 | 说明 |
|---|---|
| appA | 应用 crm-portal（启用） |
| appB | 应用 portal-b（启用） |
| alice | appA 下的 USER 主体（范围：组织 10、对象 report-1，来源 crm-auth） |
| bob | appA 下的 USER 主体（范围同 alice） |
| carol | appB 下的 USER 主体（范围同 alice） |
| report-1 | appA 下授予 alice **READ** 的报表资源（资源类型 REPORT） |
| kb-1 | appA 下授予 alice **READ** 的知识库资源（资源类型 KNOWLEDGE_BASE） |
| session-1 | alice 的会话（附件仅所有者） |

## 预期矩阵

| # | 应用 | 主体 | 操作 | 资源 | 预期 | 判定依据 |
|---|---|---|---|---|---|---|
| 1 | appA | alice | READ | REPORT report-1 | 允许 | 授权目录命中且动作在白名单 |
| 2 | appA | alice | EXPORT | REPORT report-1 | 拒绝 | 白名单只有 READ |
| 3 | appA | alice | READ | FILE report-1 | 拒绝 | 同 ID 不同类型不串权 |
| 4 | appA | bob | READ | REPORT report-1 | 拒绝 | bob 无该资源授权 |
| 5 | appA | alice | READ | KNOWLEDGE_BASE kb-1 | 允许 | 授权目录命中 |
| 6 | appB | carol | READ | REPORT report-1 | 拒绝 | 跨应用隔离（appB 无该授权） |
| 7 | appA | alice | 读写会话附件 session-1 | ai_chat_session | 允许 | 仅所有者 |
| 8 | appA | bob | 读会话附件 session-1 | ai_chat_session | 拒绝 | 非所有者（同"不存在"语义） |
| 9 | appB | carol | 读 appA 的文件 | ai_file_binding | 拒绝 | 跨应用文件不可见 |
| 10 | appA | alice | READ（撤销授权后） | REPORT report-1 | 拒绝 | 撤销立即生效（authz revision 递增） |
| 11 | appA | alice | 换票（应用撤销后） | — | 拒绝 | 应用停用后不得签发新票据 |
| 12 | appA | alice | 读旧票据（主体撤销后） | — | 拒绝 | 主体撤销后旧票据立即失效 |
| 13 | appA | alice | 历史产物再鉴权（范围变化后） | REPORT report-1 | 拒绝 | 范围指纹变化（SCOPE_FINGERPRINT_CHANGED） |
| 14 | 未知 | 任意 | 任意 | 任意 | 拒绝 | 未知业务类型/未知主体类型 fail-closed |

## 使用方式

- 复现：`./mvnw -Pintegration -pl basic-framework-server -am verify -Dit.test=AiAuthorizationMatrixIT`
- 报告：测试失败信息会逐格给出（`appA/alice/READ/report-1 expected=ALLOW actual=DENY`），
  修复后表格自动重新对齐；不接受"只 mock 授权返回 false"的替代验证（每格都走真实服务与真实 MySQL）。
