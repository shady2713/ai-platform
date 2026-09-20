# D01 连接器配置与秘密管理证据（2026-09-20）

本记录是 [D01 实现连接器配置与秘密管理](../tasks/D01.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 持久化 | 迁移 `V66__ai_connector.sql`：`ai_connector`（声明式配置 + 加密秘密 + 引用标记）与 `ai_connector_probe`（探测结论）；菜单与权限 4050–4054；快照同步至 V66（逻辑删除表 33 → 35） |
| 声明式配置校验 | `service/connector/AiConnectorConfig`：HTTP/MYSQL 白名单键 + 严格取值（https 基址、主机名、端口、库名/用户名、枚举），未知键与连接串语法一律拒绝 |
| 连接器服务 | `service/connector/AiConnectorService(+Impl)`：创建/修改/轮换秘密/启停/删除/连接测试/探测记录/查询与分页 |
| 引用保护端口 | `service/connector/AiConnectorReferenceChecker`：数据集（D04）、工具（D08）在各自卡片注册实现，任一报告引用即拒绝删除 |
| 控制面 API | `controller/admin/connector/AiConnectorController`：`/ai/connector/{create,update,rotate-credential,update-status,delete,get,page}` 与 `/{id}/probe`，权限码与 V66 种子一一对应 |
| 错误码 | 新增数据与工具子区间 `1_003_006_xxx`：`AI_CONNECTOR_NOT_FOUND`/`CODE_DUPLICATE`/`CONFIG_INVALID`/`REFERENCED`/`DISABLED`，与 `docs/contracts/ai/error-code-map.md` 两侧同步 |
| 台账同步 | `data-lifecycle.json`（2 张表进软删除 + 1 条外键）、`data-permission-exemptions.json`（新豁免 `ai-connector-config`，`function-permission` + 逐表证据）、`PersistenceLifecycleIT` 预期表清单 |
| 边界测试 | `AiConnectorConfigTest`(4)、`AiConnectorServiceImplTest`(5)、`AiConnectorControllerTest`(4)、`AiConnectorIT`(4，真实 MySQL) |

## 2. 与卡片逐步实施的对应

1. **建立 HTTP/MYSQL 连接器配置 schema 及密钥加密字段**：配置是**声明式结构化字段**
   （HTTP：`baseUrl`/`method`/`healthPath`/`authType`/`timeoutMillis`；MYSQL：`host`/`port`/`database`/`username`/`sslMode`），
   不接受整段连接串；秘密经 `CredentialCipher`（AES-GCM，AAD 含连接器编号）加密后只存密文，
   响应只回 `credentialConfigured`，轮换只递增 `credential_revision`。
2. **实现创建/修改/探测/启停/引用保护**：标识与类型创建后不可修改（引用方的稳定键）；
   启停用乐观锁；删除前先问所有 `AiConnectorReferenceChecker`，任一报告引用（或 `referenced` 标记为真）即 409；
   连接测试按类型执行（HTTP 经受控出站客户端，MySQL 直连已校验地址）并落探测记录。
3. **连接测试经过统一网络策略且日志脱敏**：HTTP 探测走 `ExternalHttpClient`（F09 冻结的出站边界，
   默认拒绝一切目标），被拒绝时只记录稳定原因码（如 `TARGET_NOT_ALLOWED`）；
   MySQL 探测只用声明式字段拼出的 JDBC 地址（并强制 `allowLoadLocalInfile=false`、`autoDeserialize=false`）；
   探测结论只保存 `status`/`detail_code`/`latency_ms`，不含主机、凭据与异常正文。

## 3. 关键约束与安全语义

- **恶意连接串参数拒绝**：未知配置键（`allowLoadLocalInfile`、`autoDeserialize`、`jdbcUrl`、`headers` 等）一律拒绝；
  取值层面拒绝非 https 基址、带查询串/片段/用户信息的基址、目录穿越路径（`..`）、带冒号/斜杠/引号的主机名、
  含 `;`/`?`/反引号/空格的库名与用户名、越界端口与非法枚举（单测逐条断言）。
- **秘密不回显**：插入时不带明文（先插入行、再用行编号作 AAD 写入密文）；查询接口与 VO 字段名单里没有秘密与密文；
  IT 断言库中密文不含明文、`config_json` 不含秘密、轮换后密文变化且版本递增。
- **有数据集引用不能删除**：删除前调用引用检查端口；IT 注册测试检查器后删除被拒（409）且行未被删除，
  解除引用后可删除——D04 的数据集引用将复用同一端口。
- **停用后不对外连接**：停用的连接器探测直接返回 `AI_CONNECTOR_DISABLED`（避免"停用后仍在发起连接"）。
- **唯一键只约束未删除行**：`uk_ai_connector_code` 使用 MySQL 8 函数索引 `(if(deleted = 1, NULL, code))`，
  已删除行映射为 NULL（唯一索引允许多个 NULL），因此同一 code 可反复创建与删除，
  而不会在第二次软删除时与历史已删除行冲突（首次实现用 `(code, deleted)` 时 IT 直接暴露了该冲突）。

## 4. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -o -pl basic-framework-module-ai test` | 0 | 325 例通过（D01 新增 13 例：配置校验 4、服务 5、控制器 4） |
| `./mvnw -Pintegration -pl basic-framework-server test -Dtest=AiConnectorIT` | 0 | 4 例通过（真实 MySQL：密文落库与不回显、轮换递增版本、引用保护、MySQL 连接测试与停用拒绝、分页过滤） |
| `sh .harness/verify.sh contracts` / `backend` | 0 / 0 | 契约（含镜像/权限/生命周期/字段目录/敏感 toString）与后端门禁通过 |

## 5. 顺带修复的依赖缺口

1. **软删除表的唯一键语义**：`(code, deleted)` 形式在"删除 → 重建 → 再删除"时冲突（第二次软删除会与历史已删除行
   撞唯一键）。D01 改用 MySQL 8 函数索引 `(if(deleted = 1, NULL, code))` 实现"唯一键只约束未删除行"。
   该模式对既有 `ai_conversation`/`ai_service_release` 等表同样适用，但属各自卡片的范围，未在本卡改动；
   作为已知差异记录在此。
2. **集成测试基类补充容器访问器**：连接器探测需要以真实地址连接集成 MySQL，基类新增
   `mysqlMappedPort()`/`mysqlRootPassword()` 两个受保护访问器（不改变既有测试行为）。
3. **门禁运行纪律**：本卡首次门禁出现"应用上下文加载失败/打包 jar 缺失"的连锁错误，定位为
   **两个门禁链重叠执行**（后启动的 `clean verify` 删除了前一个仍在使用的 target 与已安装 jar）。
   已改为单链串行执行并确认全绿；这也是本机跑门禁时必须遵守的纪律。

## 6. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、权限目录、生命周期、字段目录、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单元测试、格式、架构与覆盖率检查通过 |
| `sh .harness/verify.sh integration` | 0 | 46 个 IT 类 / 135 例全绿（含 `AiConnectorIT` 4 例） |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 新增登记 5 个文件（最低 88.27%），无基线下调、无登记删除 |

## 7. 覆盖率

| 文件 | 覆盖率 |
|---|---|
| `service/connector/AiConnectorConfig.java` | 94.85% |
| `service/connector/AiConnectorServiceImpl.java` | 88.27% |
| `controller/admin/connector/AiConnectorController.java` | 100% |
| `dal/mysql/connector/AiConnectorMapper.java` | 100% |
| `dal/mysql/connector/AiConnectorProbeMapper.java` | 100% |

## 8. 未验证项

1. **真实 HTTP 连接测试**：本机与 CI 未配置出站允许清单与可访问的 HTTPS 目标，端到端只验证到
   "策略拒绝 → 稳定原因码"；真实上游连通性由部署环境验证（出站白名单随部署配置）。
2. **MySQL TLS 模式**：`sslMode` 字段已校验与落库，但本机容器为明文连接，`REQUIRED`/`VERIFY_IDENTITY`
   的证书校验未在真实 TLS 上验证。
3. **数据集/工具引用**：引用保护端口已就绪并有测试实现；真实数据集（D04）与工具（D08）的注册在其卡片落地。
4. **连接器页面**：菜单 4050 已随迁移落地，页面在 D10（连接器语义与工具管理页面）交付。
