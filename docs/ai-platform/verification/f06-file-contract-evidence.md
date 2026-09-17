# F06 文件薄契约与业务授权证据（2026-09-16）

本记录是 [F06 发布文件薄契约与业务授权 SPI](../ai-platform/tasks/F06.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 文件薄契约 | `basic-framework-module-infra-api`：`FileCommonApi`（创建/元数据/内容/删除） |
| 契约 DTO | `api/file/dto`：`FileCreateReqDTO`、`FileReadReqDTO`、`FileDeleteReqDTO`、`FileRespDTO`、`FileSubjectDTO`、`FileBusinessAccessContext`；不含 infra 内部类型（无 DO/路径/配置编号） |
| 业务授权 SPI | `api/file/FileBusinessAccessProvider`：`getBusinessType`、`canRead`、`canDelete`（默认拒绝） |
| 授权注册表 | `service/file/FileBusinessAccessProviderRegistry`：启动期建索引，空白/重复业务类型启动失败，未注册类型读取一律拒绝 |
| 契约实现 | `module-infra`：`api/file/FileApiImpl`（主体按 `canManageFiles=false` 构造） |
| 服务层改造 | `service/file/FileService(+Impl)`：`createBusinessFile`、`getAuthorizedFile`、`deleteBusinessFile`；`canRead` 增加业务绑定分支；管理端删除加业务绑定护栏 |
| 数据库 | 迁移 `V47__file_business_binding.sql`（`business_type`/`business_id` + 一致性 CHECK + 反查索引），快照同步至 V47 |
| 边界门禁 | 8 个新契约类登记进 `ModuleBoundaryArchitectureTest` 显式允许清单 |
| 文档 | infra README 新增"文件薄契约与业务授权 SPI"章节 |

## 2. 语义要点

1. **业务绑定必填**：`FileCommonApi.createFile` 的 DTO 强制 `businessType` + `businessId`；业务类型未注册授权实现时拒绝创建。
2. **授权不由管理权限替代**：业务绑定文件的读取只由 Provider 决定，`canManageFiles` 与所有者身份都不豁免（测试断言）。
3. **fail-closed**：未注册业务类型、Provider 返回 false、Provider 未实现 `canDelete` 都拒绝；拒绝与"文件不存在"同语义。
4. **引用删除**：管理端删除接口拒绝业务绑定文件；业务删除必须经 `FileCommonApi.deleteFile` 且 Provider 显式允许，随后走既有受控删除与重试队列。
5. **既有语义不变**：无业务绑定的文件保持"公开可读 / 所有者可读 / 管理权限可读"的原有规则（回归测试覆盖）。

## 3. 验证结果

| 命令（工作目录） | 退出码 | 结论 |
|---|---|---|
| `./mvnw -pl basic-framework-module-infra-api install`（后端根） | 0 | 契约模块编译 + 契约测试通过；行覆盖率门槛 100% 达成 |
| `./mvnw -pl basic-framework-module-infra test` | 0 | 模块测试通过：`FileBusinessAuthorizationTest` 11 例、`FileApiImplTest` 4 例、`FileServiceImplTest` 33 例（含回归）等 |
| `sh .harness/verify.sh contracts` | 0 | 迁移/快照同步、生命周期与门禁接线校验通过 |
| `sh .harness/verify.sh backend` | 0 | 全量编译、单测、覆盖率门槛、Spotless、ArchUnit（含 8 个新契约允许清单）全绿 |
| `sh .harness/verify.sh integration` | 见第 4 节 | Testcontainers MySQL/Redis、Flyway 迁移链（含 V47）、打包 jar 启动探测 |

`FileBusinessAuthorizationTest` 覆盖：空白/重复业务类型启动失败、未注册类型拒绝、授权主体可读、
未授权主体拒绝、**管理权限不能冒充业务授权**、零 Provider 时业务文件不可读、无绑定文件旧规则回归、
未注册类型拒绝创建、管理端删除业务文件被拒、业务删除需 `canDelete` 授权。
`FileApiImplTest` 覆盖：DTO 到服务层参数映射、元数据只暴露契约字段、跨模块主体
（`canManageFiles=false`）与授权→读取顺序。

## 4. 门禁与棘轮

- `sh .harness/verify.sh integration`：Testcontainers MySQL/Redis、Flyway 迁移链（**V47 在真实 MySQL 上执行成功**，
  日志 `Migrating schema ... to version "47 - file business binding"` 多次出现，含 `customized_seeds` 场景）、
  打包 jar 启动探测全部通过；该次运行唯一失败项是覆盖率棘轮（3 个新文件尚未登记基线）。
- 随后用同一聚合报告执行 `node scripts/check-coverage-ratchet.mjs --update` 登记，并以门禁同款命令
  `node scripts/check-coverage-ratchet.mjs backend` 复验通过（`coverage-ratchet: backend 单文件基线通过`）。
  棘轮脚本只升不降（`Math.max(当前, 既有底线)`），新增文件满足 `newFileMinimum`。

## 5. 未验证项

1. **AI 侧 Provider 实现**：本卡只发布 SPI 与 infra 侧语义；AI 模块实现 `FileBusinessAccessProvider`
   （知识文档/会话附件/报表的访问权判定）属 K03/A07 范围，落地后需补跨模块集成用例。
2. **AT-009（换 fileId 跨应用/主体拒绝）与 AT-023（损坏/压缩炸弹）**：前者需要 AI 侧授权实现后才能在集成层验证；
   后者由既有上传校验链覆盖（`FileArchiveValidator`、类型白名单），V47 未改变上传路径。
3. 预签名上传路径（`createPresignedFile`）暂不支持业务绑定，属后续任务；当前只有服务端直传入口支持绑定。
