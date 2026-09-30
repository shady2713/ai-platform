# Y02 跨系统业务对象与主数据映射 — 完成证据

| 项目 | 内容 |
|---|---|
| 任务卡 | [Y02 建立跨系统业务对象与主数据映射](../tasks/Y02.md) |
| 需求 | FR-22、FR-38（V2 跨系统依赖链第二环） |
| 依赖 | Y01（`c0d0804`，证据 `y01-cross-system-discovery-evidence.md`，语义吻合：可访问系统集合、目录发现、防枚举、范围选择） |
| 验收 | AT-070 及其四条专项 |
| 迁移 | V93（`V93__ai_master_object_mapping.sql`，3 表 + 2 物理外键 + 菜单 4118/4119） |
| 错误码 | `1_003_015_000` – `1_003_015_014`（新领子区间） |
| 决策记录 | [ADR 0053](../../adr/0053-master-data-entity-mapping-versions.md) |
| 提交 | 见 §9 |

## 1. 交付内容

### 1.1 数据模型（V93）

| 表 | 生命周期 | 作用 |
|---|---|---|
| `ai_master_object` | soft-delete | 企业统一对象，`object_code` 全局唯一且**不可修改**（跨系统映射的锚点）；`current_revision` 指向当前已发布版本（0=尚无） |
| `ai_master_object_revision` | soft-delete | 映射版本头：`(对象, 版本号)` 唯一；草稿可编辑，发布后条目与 `mapping_fingerprint` 冻结；发布人 ≠ 草稿创建人 |
| `ai_master_object_mapping` | **hard-delete**（有意） | 版本内容行：`(对象, 版本, 系统, 实体类型, 源键)` 唯一；只属于草稿，发布后随版本冻结 |

`ai_master_object_mapping` 刻意不做软删除：若用逻辑删除，唯一键 `(master_object_id, revision, application_id, entity_type, source_key)` 会被历史行占住，**撤销登记后无法重新登记同一源键**。发布版本的行随版本长期保留，语义记录在 `docs/data-lifecycle.md`。

### 1.2 领域模型（`domain/semantic`）

| 类 | 行数 | 职责 |
|---|---|---|
| `AiMasterMappingFacts` | 170 | 判定算法的**单一真源**：半开有效期、窗口/时刻两套冲突检测、内容指纹、SHA-256 摘要 |
| `AiMasterMappingLine` | 120 | 不可变映射行（record），带 `inForceAt` / `canonicalText` / `sourceIdentity` / `objectSystemIdentity` |
| `AiMasterObjectType` | 44 | 对象类型枚举（CUSTOMER/SUPPLIER/PRODUCT/EMPLOYEE/ORGANIZATION/OTHER） |
| `AiMasterMappingMatchMethod` | 38 | 匹配方式：**只有** MANUAL（人工登记）与 TRUSTED_FEED（可信主数据导入），无自动推断 |
| `AiMasterMappingProblem` | 20 | 冲突/缺失的可展示问题描述 |

把三类规则集中在一个纯函数类里是刻意的：登记发布校验、判定、版本可核验三条路径共用，任何一条走偏都会造成"同一份映射在不同路径下结论不同"。

### 1.3 服务与协议层

| 文件 | 行数 | 职责 |
|---|---|---|
| `service/semantic/AiMasterObjectServiceImpl` | 506 | 对象 CRUD、版本草稿、条目增删、发布（冲突检查 + 独立审核 + 指纹冻结） |
| `service/semantic/AiMasterMappingResolverImpl` | 228 | 双向判定：对象→源键 / 源键→对象 |
| `service/semantic/AiMasterObjectCatalogServiceImpl` | 207 | 目录发现（复用 Y01 可访问系统集合） |
| `service/semantic/AiMasterRevisionVerifier` | 71 | 版本可核验：重算指纹 + 有效期 + 发布态 |
| `controller/admin/semantic/AiMasterObjectController` | 386 | 17 个管理端端点 |
| VO / DTO | 18 + 11 个文件 | 仅在协议层；Service/DAL 不导入 VO（已核验，见 §5.4） |

### 1.4 前端

`apps/web-ele/src/api/ai/semantic/index.ts`（325 行，17 个接口，与控制器一一对应）+ `views/ai/semantic/`（对象列表 `index.vue`、版本模块 `modules/versions.vue`、判定模块 `modules/resolution.vue`）。无显式 `any`、无 `v-html`/`innerHTML`（见 §5.4）。

## 2. 与卡片逐步实施的对应

| 卡片要求 | 实现 | 证据 |
|---|---|---|
| 1. 增加企业统一对象、源键映射、匹配方式、有效期和映射版本 | `ai_master_object` + `ai_master_object_mapping` + `match_method` + `valid_from/valid_to` + `ai_master_object_revision` | V93；§4 IT 1/4/5 |
| 2. 先支持人工登记或可信主数据接口，**不以同名自动关联** | `AiMasterMappingMatchMethod` 只有 MANUAL / TRUSTED_FEED 两值；`source_name` 只在展示与响应中透传，**判定路径不读它** | IT 1 `sameDisplayNameAcrossSystemsIsNeverMergedWithoutExplicitRegistration` |
| 3. 展示冲突/一对多/缺失映射并要求明确处理，发布到语义目录 | 冲突在**发布前按时间窗**阻断（`windowLineConflicts` / `windowObjectConflicts`）；`AiMasterObjectCatalogService` 只把生效条目发布进目录 | IT 2 `conflictingMappingsBlockPublishAndJudgement`、IT 5 `catalogDiscoveryHidesUnauthorizedSystemsAndIsBudgetBounded` |

## 3. 关键安全语义与不变量

1. **同名不同客户在结构上不可能合并**。`source_name`（展示名）不参与 `AiMasterMappingFacts` 的任何判定与指纹计算；关联只认显式登记的 `(来源系统, 实体类型, 源键)`。指纹排序键是 `AiMasterMappingLine#canonicalText()`，改展示名不会让历史版本"指纹不符"。
2. **判定必须显式钉住版本与时刻**。`requireResolveRequest` / `requireReverseRequest` 强制 `revisionNo`、`asOf` 非空，没有"取最新版本""取服务器当前时间"的省略写法（`AiMasterMappingResolverImpl.java:200-227`）。旧报表按受理时的版本与指纹解释。
3. **冲突与过期一律阻断，绝不静默取一个**。唯一"不报错"的否定结论是未登记（`mapped=false` / `REASON_NOT_REGISTERED`）。"有登记但全部不在有效期"抛 `AI_MASTER_MAPPING_EXPIRED_CONFLICT`，与"从未登记"是不同语义。
4. **发布是独立审核动作**。`published_by <> created_by`，否则 `AI_MASTER_OBJECT_PUBLISHER_CONFLICT`（1_003_015_014）。
5. **版本外改动会被发现**。发布时冻结 `mapping_fingerprint`，每次判定重算比对，不符即 409（`AI_MASTER_OBJECT_REVISION_FINGERPRINT_CONFLICT`）。
6. **判定侧保留三道"多余"的检查**（`AiMasterMappingResolverImpl.java:30-39`）：指纹重算、多对一复核（发布时已拦，判定侧仍拦——历史数据/人工修库后宁可拒绝也不挑一个）、反查的两次读取之间 `current_revision` 被推进则拒绝（事实在读取过程中变化）。
7. **目录发现与 Y01 同一条安全线**：无权系统不出现、拒绝不可区分、单版本 200 条预算（超预算 `AI_MASTER_OBJECT_CATALOG_BUDGET_EXCEEDED`）；送进模型的目录**不含源键值**（键值是业务数据，留在服务端参与关联执行）。
8. **无操作员身份不能登记**。`AiMasterObjectServiceImpl.java:484` —— 映射决定了"哪些标识算同一实体"，不能由匿名调用写入。
9. **不做缓存、不加常驻任务**。判定路径每次重算（只读事实），与 ADR 0053 第 4 条一致；未新增 `infra_job` 行，因此 `PersistenceLifecycleIT` 的种子任务清单与 `RemovedCapabilityMigrationIT` 的计数（15）都无需改动。

## 4. 验收用例对照（`AiMasterMappingAcceptanceIT`，真实 MySQL Testcontainers）

| # | 测试方法 | 卡片验收条款 | 断言要点 |
|---|---|---|---|
| 1 | `sameDisplayNameAcrossSystemsIsNeverMergedWithoutExplicitRegistration` | AT-070 / 同名不同客户不合并 | 两系统同名实体未显式登记时反查返回 `mapped=false`；显式登记后才 `mapped=true` 且指向同一 `objectCode` |
| 2 | `conflictingMappingsBlockPublishAndJudgement` | AT-070 / 冲突映射阻断或标不完整 | 一对多与多对一在**发布**与**判定**两侧分别抛 `AI_MASTER_MAPPING_CONFLICT` |
| 3 | `expiredMappingsBlockInsteadOfSilentlyFallingBack` | AT-070 / 过期映射阻断或标不完整 | 有效期不覆盖判定时刻 → `AI_MASTER_MAPPING_EXPIRED_CONFLICT`，不回退到其它版本 |
| 4 | `publishingANewRevisionNeverChangesHowOldReportsAreInterpreted` | AT-070 / 更换映射版本不改旧报表定义 | 发布 v2 后按 v1 判定仍得 v1 结果；v1 指纹不变；v2 结论独立 |
| 5 | `catalogDiscoveryHidesUnauthorizedSystemsAndIsBudgetBounded` | 逐步实施 3（发布到语义目录） | 无权系统不出现、条目预算有界、送模型的目录不含源键值 |
| 6 | `judgementRequiresExplicitPinnedVersionAndIndependentReview` | 逐步实施 1/2（版本 + 显式登记） | 缺 `revisionNo`/`asOf` → `AI_REQUEST_INVALID`；草稿不可判定；发布人=创建人被拒 |

另有一张 `AiMasterMappingAdminMaintenanceIT`（4 例）覆盖**管理页面的读写路径**——对象分页、
版本分页、草稿条目删除的乐观锁、以及三个 Mapper 的入参防御分支。这些是本卡必须产出
"管理页面"的实际依赖，详见 §6.1 与 §6.2。

## 5. 验证结果（真实命令、退出码、测试数）

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform/后端代码/basic-framework-boot`

### 5.1 模块单测（49 例全绿，exit 0）

```sh
umask 022
export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64
export PATH="/tmp/harness-shim:$HOME/.local/bin:$PATH"
./mvnw -o -pl basic-framework-module-ai test -Dtest='AiMaster*' -DfailIfNoTests=false
```

| 测试类 | 例数 | 失败 |
|---|---|---|
| `AiMasterObjectServiceImplTest` | 12 | 0 |
| `AiMasterMappingFactsTest` | 9 | 0 |
| `AiMasterMappingResolverImplTest` | 8 | 0 |
| `AiMasterObjectCatalogServiceImplTest` | 6 | 0 |
| `AiMasterMappingLineValidationTest` | 6 | 0 |
| `AiMasterObjectControllerTest` | 5 | 0 |
| `AiMasterRevisionVerifierTest` | 3 | 0 |
| **合计** | **49** | **0** |

### 5.2 验收 IT（6 例全绿，exit 0，真实 MySQL + Redis Testcontainers，未用 H2）

```sh
./mvnw -o -q -pl basic-framework-module-ai -am install -DskipTests -Djacoco.skip=true
./mvnw -o -pl basic-framework-server verify -Pintegration \
  -Dit.test='AiMasterMappingAcceptanceIT' -DfailIfNoTests=false
```

`Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 76.50 s`；`BUILD SUCCESS`，总耗时 01:49 min（含 Testcontainers 启动 47.75 s）。V93 迁移在全量 Flyway 链上成功执行。

管理维护路径 IT：

```sh
./mvnw -o -pl basic-framework-server verify -Pintegration \
  -Dit.test='AiMasterMappingAdminMaintenanceIT' -DfailIfNoTests=false
```

`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 68.90 s`；`BUILD SUCCESS`，
总耗时 01:53 min（Testcontainers 启动 47.16 s）。首跑即 4/4 全绿。

### 5.3 前端单测（27 例）

`api/ai/semantic/index.test.ts` 3 例、`views/ai/semantic/index.test.ts` 8 例、`data.test.ts` 5 例、`modules/resolution.test.ts` 5 例、`modules/versions.test.ts` 6 例。

### 5.4 人工核验（协议层边界与前端禁令）

- `grep -rn "controller.*\.vo\." service/semantic dal/semantic` → 无命中：Service/DAL 不反向导入 VO。
- `grep -rnE "\bany\b|v-html|innerHTML" api/ai/semantic views/ai/semantic` → 唯一命中是 `index.test.ts:264` 的 `expect.any(Error)`（vitest 断言，非 TypeScript 显式 `any`）。
- `grep -rnE "TODO|FIXME|UnsupportedOperation|System\.out|printStackTrace"` → 无命中。
- 控制器 17 个端点每个恰好一个鉴权标注，全部为 `@PreAuthorize`，权限码与 V93 菜单种子一致（`ai:semantic:query` 只读 / `ai:semantic:manage` 维护）。无 app 侧 `@AuthenticatedOnly` 端点，故不涉及 `AiAppEndpointScopeContractTest.REVIEWED_AUTHENTICATED_ENDPOINTS`、`scope-catalog.md` 与 `ai-open-api.json` 登记。
- 全部新文件 ≤800 行（`check-source-quality` 契约门禁全绿已复核；最大文件 `AiMasterObjectServiceImpl` 506 行）。

## 6. 覆盖率（新文件下限 80%，取自全量 integration 的聚合报告）

### 6.1 门禁暴露的覆盖率缺口（已修复）

首轮 `check-coverage-ratchet.mjs --update` 因三个 Mapper 低于 80% 硬底线而**自己失败**：

```
- AiMasterObjectMapper.java:         新文件覆盖率 41.18% 低于 80%
- AiMasterObjectMappingMapper.java:  新文件覆盖率 76.19% 低于 80%
- AiMasterObjectRevisionMapper.java: 新文件覆盖率 68.42% 低于 80%
```

从聚合报告 `jacoco-aggregate/jacoco.xml` 逐行定位到未覆盖行：

| Mapper | 未覆盖行 | 原因 |
|---|---|---|
| `AiMasterObjectMapper` | 18, 28–37 | `selectPage` 整段从未被调用；`selectByCode(null)` 守卫未覆盖 |
| `AiMasterObjectMappingMapper` | 47–48, 50–52 | `deleteEntry` 从未真正下发 DELETE；null 守卫未覆盖 |
| `AiMasterObjectRevisionMapper` | 18, 29, 39, 42–44 | `selectPage` 从未被调用；两处 null 守卫未覆盖 |

**先判定是不是死代码**：`grep` 确认五个方法都是管理页面的真实服务路径
（`AiMasterObjectServiceImpl:178` 对象分页、`:273` 条目删除、`:350` 版本分页），
因此按"要么由 IT 真调用，要么别加"的规则**补 IT 覆盖**而不是删方法。

新增 `AiMasterMappingAdminMaintenanceIT`（288 行，4 例，真实 MySQL）覆盖上述全部行，
包括 CAS 命中/未命中两侧。**注意**：null 守卫在服务层不可达（`getObject`/`getRevision`/
`requireVersion` 已先判 null），故由 IT 直接调 Mapper 钉住契约。

### 6.2 修复后的登记覆盖率

后端（13 个新文件，门槛 80%）：

| 文件 | 行覆盖 |
|---|---|
| `controller/admin/semantic/AiMasterObjectController.java` | 100.00% |
| `domain/semantic/AiMasterMappingFacts.java` | 96.23% |
| `domain/semantic/AiMasterMappingLine.java` | 100.00% |
| `domain/semantic/AiMasterMappingMatchMethod.java` | 100.00% |
| `domain/semantic/AiMasterMappingProblem.java` | 100.00% |
| `domain/semantic/AiMasterObjectType.java` | 100.00% |
| `service/semantic/AiMasterObjectServiceImpl.java` | 95.88% |
| `service/semantic/AiMasterMappingResolverImpl.java` | 100.00% |
| `service/semantic/AiMasterObjectCatalogServiceImpl.java` | 99.07% |
| `service/semantic/AiMasterRevisionVerifier.java` | 100.00% |
| `dal/mysql/semantic/AiMasterObjectMapper.java` | **100.00%**（修复前 41.18%） |
| `dal/mysql/semantic/AiMasterObjectMappingMapper.java` | **100.00%**（修复前 76.19%） |
| `dal/mysql/semantic/AiMasterObjectRevisionMapper.java` | **100.00%**（修复前 68.42%） |

前端（5 个新文件，门槛 80%）：

| 文件 | 行覆盖 |
|---|---|
| `api/ai/semantic/index.ts` | 85.71% |
| `views/ai/semantic/data.ts` | 98.50% |
| `views/ai/semantic/index.vue` | 96.73% |
| `views/ai/semantic/modules/resolution.vue` | 95.91% |
| `views/ai/semantic/modules/versions.vue` | 90.32% |

`node scripts/check-coverage-ratchet.mjs all` → **exit 0**。

## 7. 门禁暴露并已修复的缺陷（真实失败证据）

以下三项是本卡门禁链**实际跑红**后修复的，不是预防性改动：

1. **前端拼写门禁：`asof` 未收录**（exit 1）。`apps/web-ele/src/views/ai/semantic/modules/resolution.vue`
   的三处 `data-test`（`resolve-asof` / `reverse-asof` / `catalog-asof`）触发 cspell：
   ```
   resolution.vue:246:73 - Unknown word (asof)
   CSpell: Files checked: 1110, Issues found: 3 in 1 file.
   ```
   `asof`（as-of，判定时刻）是 Y02 的领域术语，后端全链路以 `asOf` 命名。修法：加入
   `前端代码/basic-framework-admin/cspell.json` 单词表（按字母序插在 `archiver` 与 `astro` 之间）。
   先例：Q08（`7ecc7ff`）、C04（`a1df954`）、O08（`7dbd0bb`）均以同样方式各加词条；
   该共享拼写字典不属于卡片显式管控的"依赖版本/父 POM/全局请求器/Harness 拓扑"。
   复验：`CSpell: Files checked: 1110, Issues found: 0 in 0 files.`

2. **前端格式门禁：2 个 Y02 测试文件未格式化**（exit 1）。
   ```
   [warn] apps/web-ele/src/views/ai/semantic/index.test.ts
   [warn] apps/web-ele/src/views/ai/semantic/modules/versions.test.ts
   Code style issues found in 2 files.
   ```
   修法：`npx prettier --write` 这两个文件。复验：`All matched files use Prettier code style!`

3. **前端 lint 门禁：2 处联合类型未排序**（exit 1）。
   ```
   index.test.ts  37:45  error  Expected "(isOpen: boolean) => void" to come before "undefined"  perfectionist/sort-union-types
   index.test.ts  39:42  error  Expected "() => Promise<void>" to come before "undefined"        perfectionist/sort-union-types
   ```
   `onOpenChange: undefined as ((isOpen: boolean) => void) | undefined` 与
   `onConfirm: undefined as (() => Promise<void>) | undefined` 的联合类型顺序不符合
   `perfectionist` 规则。修法：`npx eslint --fix`（规则本身声明可自动修复），改为
   `((isOpen: boolean) => void) | undefined` / `(() => Promise<void>) | undefined`。

### 7.1 复核确认无需修改的设计要点

以下是复核中确认并已正确处理的设计要点，记录以免后续卡片误改：

1. `ai_master_object_mapping` 的 hard-delete 是**有意**与 Y01 联邦映射（重提交复用同一行）相反的取向。二者不是同一类陷阱的重复：Y01 是"同一行承载多次审批"，本卡是"内容行可被撤销后重填"，后者若用逻辑删除会被唯一键永久占住。已在 `docs/data-lifecycle.md` 与 ADR 0053 第 6 条写明理由。
2. 多对一冲突在**发布时按时间窗**（任意重叠）判定，比"今天是否生效"更严格：未来的重叠也必须在登记时解决，避免上线后才暴露。`AiMasterMappingFacts.windowObjectConflicts` 与判定侧的 `instantObjectConflicts` 是两套独立检查，刻意不合并。
3. `AiMasterMappingResolverImpl` 中"反查先取当前已发布版本的行，再读对象确认仍是该版本"是必要的双读一致性检查（`matched.getRevision().equals(object.getCurrentRevision())`），不是冗余代码——两次读取之间版本被推进时必须拒绝。

## 8. 未验证项与未交付项

1. **Q06 真实浏览器验收未执行**。按 Y02 §7，"浏览器测试命令在 Q06 建立前不得声称已经存在"。本卡的页面验收止于 vitest 组件测试 + typecheck + lint，跨源/渲染/权限用例须在 Q06 与 G5 用真实浏览器补齐。
2. **未接真实主数据源**。`TRUSTED_FEED` 匹配方式只提供**登记入口**与判定语义，本卡不含任何真实 MDM/HUB 系统的对接（卡片逐步实施第 2 条只要求"先支持人工登记或可信主数据接口"的模型侧，真实对接属后续集成）。该取值当前只由管理员显式登记产生，判定路径对它与 MANUAL 一视同仁（都要求显式源键）。
3. **未新增模型调用**。Y02 不产生文本/媒体生成，判定路径是纯数据库只读，无 `ModelClientFactory` 依赖，因此"本机未装配模型客户端"这一既有环境限制对 Y02 无影响。
4. **`all` 门禁的 dependencies 段未验证**。Trivy 漏洞库 `mirror.gcr.io` 在本网络不可达，`verify.sh all` 的 dependencies 段必然失败。这是既有环境缺口（`harness-gate-runbook.md` 已记录），不是本卡引入；本卡未改动任何依赖版本或 lockfile，因此该段对本卡无实质影响，但如实登记为未验证。
5. **未覆盖跨卡场景**：同一源键在两个对象下的历史脏数据由判定侧多对一复核阻断（单测覆盖），但没有专门的 IT 构造"绕过发布直接写库"的脏数据。补这条 IT 需要绕过服务层直连 Mapper，属于测试手段而非产品能力，本卡记为未验证。

## 9. 门禁结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（`umask 022`，
`JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`，
`PATH="/tmp/harness-shim:$HOME/.local/bin:$PATH"`）。**严格串行，一次只跑一条。**

| 门禁 | 命令 | exit | 判定 |
|---|---|---|---|
| contracts | `sh .harness/verify.sh contracts` | **0** | 绿（25 组断言 `# fail 0`；修复后复跑仍为 0） |
| backend | `sh .harness/verify.sh backend` | **0** | 绿（`mvn -q clean verify`） |
| integration | `sh .harness/verify.sh integration` | 1 → 见下 | **测试全绿**，exit=1 仅来自门禁尾部棘轮 |
| frontend | `sh .harness/verify.sh frontend` | 1 → 见下 | **全流程绿**，exit=1 仅来自门禁尾部棘轮 |
| 棘轮登记 | `node scripts/check-coverage-ratchet.mjs --update` | **0** | 18 个新文件全部登记且 ≥80% |
| 棘轮复验 | `node scripts/check-coverage-ratchet.mjs all` | **0** | 绿 |

### 9.1 integration 的 exit=1 不代表测试失败

判据 `grep -cE 'Tests run:.*(Failures: [1-9]|Errors: [1-9])'` = **0**。
从 failsafe 报告聚合本轮真实结果：**86 个 IT 类 / `Tests run: 351, Failures: 0, Errors: 0, Skipped: 1`**。

- 1 个 skip 是 `PackagedJarBootSmokeIT.packagedJar_servesStagedEmbedAssetsAndShipsCompleteMigrationChain`，
  由 `PackagedJarBootSmokeIT.java:102` 的 `Assumptions.assumeTrue(...)` 条件跳过；该文件最后一次改动是
  Q09（`cc7aef6`），**与 Y02 无关，未跳过、未注释、未排除**。
- exit=1 的唯一来源是门禁尾部的 `node scripts/check-coverage-ratchet.mjs backend`，报
  "尚未登记单文件覆盖率基线"——这是新文件尚未写入 `docs/contracts/coverage-baseline.json` 时的**预期行为**。
  按既定流程在登记后复跑即绿。

### 9.2 frontend 的 exit=1 同理

修复 §7 的三项后，frontend 门禁的 `pnpm check`（typecheck 39/39、cspell 0 issues）、
`pnpm lint`（prettier clean、eslint 0 error）、`pnpm test:coverage`、
`node scripts/run-frontend-build.mjs`（257 个产物通过 no-undef 校验）**全部通过**；
exit=1 同样只来自门禁尾部的前端棘轮（5 个新文件未登记），登记后 `all` 为 0。

### 9.3 前端全量单测（快检，用于避免盲跑整轮）

```sh
cd 前端代码/basic-framework-admin
pnpm check:type && pnpm lint && pnpm test:unit
```

`check:type`：39/39 tasks successful。`test:unit`：**415 个测试文件 / 2370 个测试全部通过**。

### 9.4 实际执行的门禁相关命令清单

```sh
# 单测
./mvnw -o -pl basic-framework-module-ai test -Dtest='AiMaster*' -DfailIfNoTests=false
# 验收 IT（两轮：修复前聚焦验证 + 全量）
./mvnw -o -q -pl basic-framework-module-ai -am install -DskipTests -Djacoco.skip=true
./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiMasterMappingAcceptanceIT' -DfailIfNoTests=false
./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiMasterMappingAdminMaintenanceIT' -DfailIfNoTests=false
# 格式化（Java 与前端各一次，门禁都绑格式检查）
./mvnw -q -o -pl basic-framework-module-ai spotless:apply
./mvnw -q -o -pl basic-framework-server spotless:apply
npx prettier --write apps/web-ele/src/views/ai/semantic/index.test.ts \
  apps/web-ele/src/views/ai/semantic/modules/versions.test.ts
npx eslint apps/web-ele/src/views/ai/semantic --fix
```

## 10. 契约与台账同步清单

| 台账 | 变更 |
|---|---|
| `数据库文件/basic_framework.sql` | 3 张表 DDL + 2 条物理外键 + "through V93" 声明 + 软删除表计数 |
| `docs/contracts/data-lifecycle.json` | `ai_master_object`/`_revision` 入 soft-delete 清单；`ai_master_object_mapping` 入 hard-delete 清单；2 条外键入物理外键清单 |
| `docs/contracts/data-permission-exemptions.json` | 3 张表加入 `ai-control-plane-config` 的 `tables` 与每个 `control.tables`；`evidence.contains` 为控制器中的真实方法签名 |
| `docs/contracts/ai/error-code-map.md` | 新增"主数据映射子区间（Y02，`1_003_015_xxx`）"表（15 个码） |
| `AiErrorCodeConstants` / `AiErrorCodeRanges` | 15 个错误码 + `DOMAIN_MASTER_DATA = 1_003_015` |
| `PersistenceLifecycleIT` | 期望表清单新增 `ai_master_object`、`ai_master_object_revision`（按表名字母序插在 `ai_knowledge_ingestion_task` 之后） |
| `basic-framework-module-ai/README.md` | 新增"跨系统主数据映射（Y02）"能力行 |
| `docs/data-lifecycle.md` | 新增"跨系统主数据映射表（Y02）"分治说明 |
| `docs/adr/0053-master-data-entity-mapping-versions.md` | 新增决策记录（显式登记、版本固定、冲突/过期阻断、生命周期分治） |
| 菜单权限 | V93 种子 4118（`ai:semantic:query`，页面）/ 4119（`ai:semantic:manage`，维护按钮） |
