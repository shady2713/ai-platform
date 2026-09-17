# A01 应用及客户端凭据管理证据（2026-09-17）

本记录是 [A01 实现应用及客户端凭据管理](../tasks/A01.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 持久化 | 迁移 `V50__ai_application.sql`：`ai_application`（appCode 唯一、origins 精确列表、enabled、乐观锁 version）+ `ai_application_credential`（`secret_digest char(64)`、状态机 ACTIVE/REVOKED、revoked_time）；菜单/权限种子 4010–4015；快照 `数据库文件/basic_framework.sql` 同步 |
| 领域规则 | `domain/application/ApplicationOrigins`（精确 Origin 校验与归一化）、`ApplicationSecrets`（256 位随机秘密、SHA-256 摘要、常量时间比较） |
| 服务 | `service/application/AiApplicationService(+Impl)`：创建（签发首个凭据）、修改（appCode 不可改）、启停、轮换、吊销、删除、分页/详情、`authenticate`（换票校验） |
| 控制面 API | `controller/admin/application/AiApplicationController`：`/ai/application/{create,update,update-status,rotate-credential,revoke-credential,delete,get,page}`，权限码 `ai:application:{create,update,delete,query,rotate,revoke}` |
| 错误码 | `1_003_003_000`–`1_003_003_003`（应用不存在 404 / appCode 重复 409 / Origin 非法 400 / 凭据无效 401）+ `error-code-map.md` 同步 |
| 台账 | `data-lifecycle.json`（两表逻辑删除策略 + 物理外键）、`data-permission-exemptions.json`（控制面豁免 + 控制器权限证据） |
| 测试 | `AiApplicationServiceImplTest`(7)、`AiApplicationControllerTest`(4)、`AiApplicationPersistenceIT`(4，真实 MySQL) |

## 2. 与卡片逐步实施的对应

1. **application/credential 表**：字段与约束见迁移；凭据表对 `application_id` 建物理外键（RESTRICT），
   删除应用前必须先吊销凭据（服务层与数据库双重约束）。
2. **appCode 唯一、精确 Origin、启停、一次显示密钥**：`appCode` 走唯一索引（逻辑删除参与唯一键），
   服务层预检查返回 409；Origin 只接受 `scheme://host[:port]`，路径/查询/通配/用户信息/非法 scheme 全部 400；
   秘密明文只在创建与轮换响应出现一次，之后任何接口都只有 `credentialConfigured` 布尔值。
3. **摘要保存、显式轮换与吊销、默认无重叠**：库里只有 `secret_digest`（IT 直接查表断言无明文）；
   轮换在一个事务内"吊销全部 ACTIVE 凭据 + 签发新凭据"，任意时刻至多一条 ACTIVE；
   吊销后旧秘密**立即**无法换票（IT 断言）。

## 3. 关键约束与安全语义

- **防枚举**：`authenticate` 对"应用不存在 / 未启用 / 凭据错误 / 已吊销 / 参数缺失"统一返回
  `AI_APPLICATION_CREDENTIAL_INVALID`（401），不区分原因；摘要比较使用 `MessageDigest.isEqual` 常量时间。
- **AT-011（后台普通管理员读取秘密）**：详情与列表响应模型只有 `credentialConfigured`，
  控制器用例用反射断言响应字段集合，确认不存在摘要/明文/秘密字段。
- **秘密不落日志**：`secret`、`secretDigest`、`credentialId` 均按敏感字段目录排除出 `toString`；
  凭据签发响应不被控制器记录。
- **appCode 不可变**：改标识等于换应用（对接方按 appCode 换票），服务层拒绝并返回 409。
- **CAS 并发**：轮换/吊销/启停/删除都带乐观锁版本，冲突返回 409。

## 4. 验证结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约、台账（生命周期/数据权限/错误码映射）、敏感字段与接口授权策略检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 全量后端构建、单元测试与架构门禁通过 |
| `sh .harness/verify.sh integration` | 0（集成用例部分） | Testcontainers（MySQL 8.4）**全部集成用例通过**（含本卡新增用例）；命令末尾的覆盖率棘轮检查在登记基线前报"未登记"，登记后复验通过 |
| `node scripts/check-coverage-ratchet.mjs --update` | 0 | 新增文件基线登记（新文件下限 80%，只升不降） |
| `node scripts/check-coverage-ratchet.mjs all` | 0 | 前后端单文件基线全部通过 |
| `sh .harness/verify.sh frontend` | 0 | 前端门禁（check/lint/test:coverage/构建/前端棘轮）通过（本卡未改动前端，沿用 M06 收口结果） |

## 5. 未验证项

1. **换票链路**：`authenticate` 已提供并测试，但票据签发/校验属 A04；A04 完成后需补端到端用例。
2. **Origin 的实际校验点**：服务端保存精确来源，请求侧比对（`/app-api` 的 Origin 校验）在 A04 票据链路中接线。
3. **管理页面**：应用管理页面属后续前端卡（与 M06 同族），本卡只交付控制面 API。
4. **审计**：本卡依赖平台既有管理端请求日志与操作日志基础设施；`@LogRecord` 细粒度审计随 API 层统一接入。
