# A07 AI 文件授权 Provider 证据（2026-09-18）

本记录是 [A07 实现AI文件授权Provider](../tasks/A07.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 绑定持久化 | 迁移 `V54__ai_file_binding.sql`：`ai_file_binding`（文件 ↔ 业务对象引用 + 上传主体所有者 + 状态机 ACTIVE/RELEASED）；快照同步 |
| 业务类型词汇 | `service/file/AiFileBusinessType`：`ai_report`（REPORT）、`ai_knowledge_document`（KNOWLEDGE_BASE）、`ai_chat_session`（仅所有者）；未知类型解析为空并拒绝 |
| 业务文件服务 | `service/file/AiFileService(+Impl)`：上传（校验业务类型/键/内容 + 当前归属）、读取（按当前归属，无缓存）、解除引用（仅所有者 + 引用计数） |
| 主体解析 | `adapter/file/AiFileSubjectResolver`：从 MEMBER 会话或"票据编号回查"得到可信身份；票据无效即不可信 |
| infra SPI 实现 | `adapter/file/AiFileBusinessAccessProvider` + `AiFileAccessProviderConfiguration`：三种业务类型各一个 Provider，`canRead`/`canDelete` 全部 fail-closed |
| 应用端接口 | `controller/app/v1/file/AiFileController`：`POST /app-api/ai/file/upload`、`GET /app-api/ai/file/{fileId}`、`DELETE /app-api/ai/file/{fileId}`（全部 `@AuthenticatedOnly`，归属判定在服务层） |
| 契约 | `docs/contracts/ai/scope-catalog.md` 新增"登录即可访问端点（服务层按业务归属判定）"登记表 |
| 台账 | `data-lifecycle.json`（软删除表 + 无物理外键）、`data-permission-exemptions.json`（主体绑定豁免 + Mapper/服务证据） |
| 测试 | `AiFileServiceImplTest`(7)、`AiFileBusinessAccessProviderTest`(4)、`AiFileControllerTest`(3)、`AiFileBindingIT`(3，真实 MySQL + infra 文件存储) |

## 2. 与卡片逐步实施的对应

1. **建立 ai_file_binding 并实现 infra 业务授权 SPI**：绑定表以
   `(file_id, business_type, business_key)` 为唯一键；三种业务类型各注册一个 `FileBusinessAccessProvider`
   （infra 注册表要求每类型恰好一个实现，重复/缺失都会拒绝）。
2. **提供受控上传/读取/删除引用**：上传经 `FileCommonApi`（infra 负责魔数、白名单后缀、压缩包安全与存储），
   读取经同一渠道的可控读取接口；删除是"解除引用"，只有**没有其他有效引用**时才调用 infra 删除，
   且删除动作在引用仍为 ACTIVE 时发起（否则 Provider 会因"无有效绑定"拒绝授权，文件永远删不掉——
   该顺序由 IT 暴露并修正）。
3. **知识/会话/报表分别校验归属，未知 businessType 失败**：
   报表与知识文档走 A03 授权目录（资源类型 REPORT / KNOWLEDGE_BASE），会话附件仅上传主体本人，
   未知类型在服务层 400、在 infra 注册表侧同样拒绝。

## 3. 关键约束与安全语义

- **禁止 canManageFiles 伪装**：infra 传入的授权上下文只有 `(fileId, businessType, businessId, subject{userType,userId})`，
  管理权限不参与且无法进入判定（Provider 单测断言"能读≠能删"）。
- **跨主体/跨应用拒绝（AT-009）**：绑定记录所有者身份（应用 + 主体类型 + 外部用户），
  他主体读取按**不存在**处理（同语义防枚举），IT 断言 bob 读 alice 的附件失败。
- **共享引用未释放不得误删**：一个文件可被多个业务对象引用；只有最后一个引用解除时才删除文件，
  IT 用两条绑定验证"释放一条后文件仍可用"。
- **失权立即生效（AT-048）**：读取与授权判定都不缓存；撤销应用后（复用撤销前会话）立即读不到文件，
  且无法再换新票（IT 断言）。
- **未知业务类型 fail-closed**：服务层 400、Adapter/注册表侧拒绝，绝不放行。

## 4. 验证结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -pl basic-framework-module-ai verify` | 0 | 147 例通过（A07 新增 14 例） |
| `./mvnw -Pintegration -pl basic-framework-server -am verify -Dit.test=AiFileBindingIT` | 0 | 3 例通过（真实 MySQL + infra 数据库存储：上传/读取/跨主体拒绝/共享引用/撤销即时生效） |
| `sh .harness/verify.sh contracts` / `backend` / `integration` + 棘轮 | 见交接记录 | 与本批后续卡片同批执行 |

## 5. 未验证项

1. **知识文档与会话键的领域归属**：K 系列（知识库）与 C 系列（会话）尚未实现，
   本卡按其业务键做归属判定（知识库键、会话键）；相应模块落地后需补端到端用例。
2. **上传体积与压缩包攻击的边界值**：由 infra 的 `FileArchiveValidator` 与文件白名单负责
   （F06 已验收）；本卡只验证"非法类型与内容被拒绝时 AI 层不漏放"。
3. **产物的批量清理**：解除引用只处理单文件；业务对象删除时的批量解绑与补偿随对应模块实现。
4. **管理端查看绑定**：本卡只交付应用端接口；管理端只读视图随前端卡补齐。
