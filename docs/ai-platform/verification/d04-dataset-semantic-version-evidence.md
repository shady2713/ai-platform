# D04 数据集语义版本管理证据（2026-09-20）

本记录是 [D04 实现数据集语义版本管理](../tasks/D04.md) 的验收证据。
依赖 [D02](../tasks/D02.md)、[D03](../tasks/D03.md)、[A03](../tasks/A03.md)、[F07](../tasks/F07.md)
均已有证据文档（`d02-http-openapi-evidence.md`、`d03-mysql-readonly-evidence.md`、`a03-resource-authorization-evidence.md`、
`f07-protocol-freeze-evidence.md`），本卡只消费其公开契约（连接器授权白名单 + 只读元数据发现）。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 持久化 | 迁移 `V68__ai_dataset_semantic_version.sql`：`ai_dataset`（来源对象 + 版本序列）与 `ai_dataset_version`（不可变语义快照 + schemaHash + 漂移结论）；菜单与权限 4060–4066；快照同步至 V68（逻辑删除表 36 → 38） |
| 语义定义 | `domain/semantic/AiDatasetDefinition`：字段/指标/维度/枚举/时间/单位/粒度 + 逐字段权限策略的唯一校验入口；规范化 JSON 与 `schemaHash`（sha256） |
| 类型映射 | `domain/semantic/AiSemanticTypes`：语义类型 ↔ 上游 `data_type` 的兼容映射（未知上游类型一律不兼容） |
| 漂移结论 | `domain/semantic/AiDatasetDriftReport`：缺失列/类型变化（阻塞发布）与新增列（仅记录）分开表达 |
| 服务 | `service/dataset/AiDatasetService(+Impl)`：数据集 CRUD/启停、版本创建、验证、发布、版本查询；来源授权、版本不可变、发布前重校验 |
| 漂移写入器 | `service/dataset/AiDatasetVersionDriftWriter`：独立事务落"已漂移"结论（发布失败回滚不影响该事实） |
| 引用保护 | `service/dataset/AiDatasetVersionReferenceChecker`（R04 报表注册实现）+ `AiDatasetConnectorReferenceChecker`（注册进 D01 端口：数据集在用连接器时拒绝删除） |
| 控制面 API | `controller/admin/dataset/AiDatasetController`：11 个端点 + 7 个 VO，权限码与 V68 种子一一对应 |
| 错误码 | `1_003_006_018`–`029`（数据集/版本/漂移/引用/停用），与 `docs/contracts/ai/error-code-map.md` 两侧同步 |
| 台账同步 | `数据库文件/basic_framework.sql`（V68 表与菜单）、`data-lifecycle.json`（2 张表进软删除 + 2 条外键）、`data-permission-exemptions.json`（新豁免 `ai-dataset-config` + 逐表证据）、`PersistenceLifecycleIT` 预期表清单 |
| 测试 | `AiDatasetDefinitionTest`(9)、`AiDatasetServiceImplTest`(8)、`AiDatasetControllerTest`(4)、`AiDatasetVersionIT`(6，真实 MySQL + 真实只读账号 + 真实上游结构变化) |

## 2. 与卡片逐步实施的对应

1. **定义字段/指标/维度/枚举/时间/单位/粒度/审核关联与权限策略**：
   定义是**规范化 + 严格校验**的单一入口：白名单键（未知键拒绝）、逻辑名与 QueryPlan 契约同一模式
   （`^[a-z][a-z0-9_]{0,63}$`）、字段/指标/维度共享命名空间（重名拒绝）、
   每个字段与指标必须声明 `visibility`，`RESTRICTED` 必须给出权限码（**无权限策略不可发布**的静态部分）；
   别名大小写不敏感，重复或与任何逻辑名冲突即拒绝（**有歧义别名可识别**）。
   枚举只允许出现在 `ENUM` 类型字段上且取值唯一；时间语义必须是已声明的日期/时间字段并带粒度与时区；
   `SUM/AVG` 只能作用于数值/小数类型。
2. **建立 dataset/version 和 schemaHash**：数据集持有来源对象（`schema.table`，必须在连接器白名单内）与版本序列；
   版本是不可变快照（规范化定义 + `schemaHash`），版本号在数据集内递增，发布后定义与哈希不可修改；
   上游结构哈希（`source_schema_hash`）是漂移判定基线，与定义哈希分开。
3. **发现结构漂移后置待验证，发布前验证可执行范围**：
   验证（`verifyVersion`）用 D03 的授权元数据发现比对"定义引用的列"与上游列：
   缺列或类型不兼容 → `DRIFTED`（待验证）+ 漂移结论落库；仅上游新增列 → 仍 `VERIFIED` 但记录漂移信息。
   发布（`publishVersion`）要求已验证，并**重新确认**上游结构自验证以来未变化：
   变化即置 `DRIFTED`（独立事务，抛错不回滚该标记）并拒绝，避免"验证通过但发布的是已失效的定义"。

## 3. 关键约束与安全语义

- **来源必须已授权**：数据集只能声明连接器白名单内的对象（否则等于绕过 D03 的白名单），
  IT 断言未授权来源返回 403 `AI_DATASET_SOURCE_NOT_AUTHORIZED`；来源只允许 MySQL 只读连接器
  （HTTP 接口来源在 D07 落地时扩展来源类型，本卡不半实现）。
- **未知列不可发布**：定义引用了上游不存在的列 → 验证判 `DRIFTED`、发布被拒（IT 用真实 `DROP COLUMN` 验证）。
- **无权限策略不可发布**：`RESTRICTED` 缺权限码、`visibility` 非法在定义层即拒绝；
  单测覆盖 `hasPermissionPolicy` 的边界（含 `permission=null` 不能因 `String.valueOf(null)` 而"通过"）。
- **版本不可变 + 可追溯**：已发布版本再次验证/发布返回 409 `AI_DATASET_VERSION_IMMUTABLE`；
  数据集删除被引用（报表版本）时拒绝（409 `AI_DATASET_REFERENCED`），删除后版本快照仍可读（IT 断言）。
- **响应不含上游数据**：验证/发布结论只回列名与结论（缺失/类型变化/新增），不回列值、不回连接信息；
  控制器契约测试断言 VO 字段名单里没有凭据/密文/行数据字段。
- **停用即停写**：数据集停用后不能创建/验证/发布版本（409 `AI_DATASET_DISABLED`），但历史版本快照仍可读。
- **引用保护双向生效**：数据集引用连接器 → 连接器删除被拒（D04 注册的检查器，IT 验证）；
  报表引用版本 → 数据集删除被拒（端口已就绪，R04 注册实现）。

## 4. 验收用例对照

| 验收项 | 覆盖点 | 证据 |
|---|---|---|
| 未知列不可发布 | 定义引用上游不存在的列 → 待验证 + 发布被拒 | `AiDatasetVersionIT.rejectsUnauthorizedSourceAndUnknownColumns`、`detectsDriftAndRequiresFreshVerificationBeforePublishing` |
| 无权限策略不可发布 | `RESTRICTED` 缺权限码 / visibility 非法 | `AiDatasetDefinitionTest.rejectsMissingPermissionPolicyAndUnknownKeys`、`enforcesLimitsAndPermissionPolicyHelper`、`AiDatasetVersionIT.rejectsUnauthorizedSourceAndUnknownColumns` |
| 有歧义别名可识别 | 别名重复、与字段/指标/维度名冲突 | `AiDatasetDefinitionTest.rejectsAmbiguousAliases`、`AiDatasetVersionIT` 别名用例 |
| 旧报表引用版本可追溯 | 版本快照不可变 + 引用保护 + 删除后仍可读 | `AiDatasetVersionIT.publishesVerifiedVersionAndKeepsOlderSnapshotTraceable`、`protectsReferencedVersionsAndConnectorDeletion` |
| 版本与漂移测试 | 版本号递增、schemaHash 稳定、漂移三态 | `AiDatasetDefinitionTest.schemaHashIsStableAndContentSensitive`、`canonicalJsonRoundTripsThroughParse`、`AiDatasetServiceImplTest`、`AiDatasetVersionIT`（6 例） |

## 5. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。
命令前统一 `umask 022; export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -o -pl basic-framework-module-ai test` | 0 | 383 例通过（D04 新增 23 例：定义 9、服务 8、控制器 4、其余为定义回归补充） |
| `./mvnw -Pintegration -pl basic-framework-server test -Dtest=AiDatasetVersionIT` | 0 | 6 例通过（真实 MySQL：来源授权、验证/发布、上游增删列与类型变化的漂移、发布前重校验、引用保护、停用） |
| `node scripts/check-data-lifecycle.mjs` | 0 | 最终表 63 张、策略登记 63 张、逻辑删除列 38 张、物理外键 41 条，快照同步 |
| `node scripts/check-data-permission.mjs` | 0 | 应用表 52 张：运行时保护 2、显式豁免 50、平台托管 11 |
| `sh .harness/verify.sh contracts` / `backend` / `integration` | 0 / 0 / 1（仅尾部棘轮） | 见第 7 节 |
| `./mvnw -Pintegration -pl basic-framework-server test -Dtest=PersistenceLifecycleIT` | 0 | 逻辑删除列清单与新表一致（`ai_dataset`/`ai_dataset_version` 按字母序落位） |

## 6. 顺带修复的缺口（自查）

1. **规范化 JSON 不可回读（自查修复）**：`canonicalJson()` 最初给非枚举字段也写出 `"enumValues": []`，
   重新解析时被判为"非枚举类型却带取值"而拒绝——即落库的定义再也读不回来。
   修复：非枚举字段不写 `enumValues`，且解析时空数组等价于未声明；新增
   `AiDatasetDefinitionTest.canonicalJsonRoundTripsThroughParse` 锁定该不变式。
2. **`permission=null` 误判为合规（自查修复）**：权限码校验最初写成
   `PERMISSION_PATTERN.matcher(String.valueOf(permission))`，`null` 会变成字符串 `"null"` 而通过校验，
   使"RESTRICTED 必须给权限码"形同虚设。修复为显式判空。
3. **漂移标记被回滚（集成测试暴露）**：发布时发现漂移要抛错回滚，最初把"置 DRIFTED"写在同一个事务里，
   结果结论随回滚丢失（调用方看到的仍是 VERIFIED）。改为独立事务写入器（`AiDatasetVersionDriftWriter`），
   与 O04 终态写入同一做法。
4. **`List.of` 不接受 null（自查修复）**：规范化 JSON 的字段顺序表里 `permission` 可能为 null，
   用 `List.of` 会 NPE；改用允许 null 的 `Arrays.asList`。
5. **`scripts/secret-scan.mjs`**：登记 D04 集成测试里的一次性只读账号口令。

## 7. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、权限目录、生命周期、字段目录、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单测、格式、架构与覆盖率检查通过 |
| `sh .harness/verify.sh integration` | 1 | **仅尾部覆盖率棘轮**因新文件未登记而失败；49 个 IT 类 / 151 例 **0 失败 0 错误**（含 `AiDatasetVersionIT` 6 例） |
| `node scripts/check-coverage-ratchet.mjs --update` | 0 | 登记 D04 新增文件，无基线下调、无登记删除 |
| `node scripts/check-coverage-ratchet.mjs all` | 0 | 单文件基线全部通过 |

## 8. 覆盖率

| 文件 | 覆盖率 |
|---|---|
| `domain/semantic/AiDatasetDefinition.java` | 96.73% |
| `domain/semantic/AiSemanticTypes.java` | 100% |
| `domain/semantic/AiDatasetDriftReport.java` | 100% |
| `service/dataset/AiDatasetServiceImpl.java` | 94.64% |
| `service/dataset/AiDatasetVersionDriftWriter.java` | 81.82% |
| `service/dataset/AiDatasetConnectorReferenceChecker.java` | 85.71% |
| `dal/mysql/dataset/AiDatasetMapper.java` | 100% |
| `dal/mysql/dataset/AiDatasetVersionMapper.java` | 100% |
| `controller/admin/dataset/AiDatasetController.java` | 100% |

（首次门禁 `AiDatasetMapper` 55.56%、`AiDatasetVersionMapper` 73.68%：前者缺少分页调用、后者含未被使用的
`selectLatestPublished`。处理方式是**补真实调用**（集成测试断言数据集分页）并**删除死代码**，
而不是下调基线或写只求覆盖率的空测试。）

## 9. 未验证项

1. **HTTP 接口来源的数据集**：本卡只支持 MySQL 只读对象来源（D07「API 查询参数与结果归一化」扩展来源类型），
   对 HTTP 连接器建数据集会被拒绝（`AI_CONNECTOR_CONFIG_INVALID`），HTTP 语义字段与结果归一化未验证。
2. **真实报表引用**：引用保护端口已就绪并有测试实现（IT 注册替身检查器）；
   真实报表（R04）注册实现后才会保护生产数据。
3. **数据权限行级策略**：数据集与版本按控制面配置豁免（功能权限 `ai:dataset:*`），
   没有部门/用户行级归属；若后续要求"按部门可见数据集"，需要新的数据权限登记。
4. **跨库/多来源对象的数据集**：来源固定为单个 `schema.table`；多对象 join 语义由 D06 编译期处理，未在本卡验证。
5. **数据集页面**：菜单 4060 已随迁移落地，页面在 D10（连接器语义与工具管理页面）交付；本卡不含前端改动。
