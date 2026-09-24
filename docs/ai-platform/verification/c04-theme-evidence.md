# C04 实现主题发布与共享设计 token — 完成证据

| 项目 | 内容 |
|---|---|
| 任务卡 | [C04](../tasks/C04.md) |
| 状态 | DONE（本文件记录的命令均已实际执行；真实浏览器验收见"未验证项"） |
| 需求 | FR-15（主题与体验） |
| 依赖 | F07（协议冻结：`theme-tokens.schema.json` + TS 契约，证据 `f07-protocol-freeze-evidence.md`）、A01（应用与凭据，证据 `a01-application-credential-evidence.md`）、F08（错误码区间，证据 `f08-error-code-migration-evidence.md`） |
| 工作副本 | `/home/ctyun/桌面/zhongtai/ai-platform` |
| 变更范围 | 后端 `service/theme`、`controller/admin/theme`、`dal/{dataobject,mysql}/theme`、迁移 V79；前端 `packages/ai-chat-ui/src/theme`；四处台账 + 快照；错误码两侧；主题渲染边界文档 |

## 1. 变更文件清单

### 后端（`basic-framework-module-ai`）

- `dal/dataobject/theme/AiThemeDO.java`：`ai_theme` 一行 = 一个**不可变主题修订**；发布状态三态
  （DRAFT/PUBLISHED/SUPERSEDED）、`tokensJson`/`layoutJson`/`tokensFingerprint`、乐观锁。
- `dal/mysql/theme/AiThemeMapper.java`：按对外标识、按（应用, 修订号）、最大修订号、当前生效修订、
  分页（应用 + 状态过滤）与乐观锁 CAS。
- `domain/theme/AiThemeValidator.java`：**不依赖 Spring 的纯校验类**——token 与布局的受控校验、
  规范化 JSON 输出、内容摘要（SHA-256）、字体白名单、平台默认值。
- `service/theme/AiThemeService.java` / `AiThemeServiceImpl.java`：建修订（修订号 = 应用内最大 + 1）、
  发布/回退（同一动作）、分页、查询、解析有效主题；发布在**同一事务**内先取代原生效修订再激活目标，
  并发冲突由乐观锁 CAS 与数据库唯一键 `uk_ai_theme_published` 双重兜底（唯一键冲突转 409）。
- `service/theme/dto/AiThemeSaveDTO.java`、`dto/AiThemeEffectiveDTO.java`（含来源标记常量）。
- `controller/admin/theme/AiThemeController.java` + `vo/`（Save/Publish/Page/Resp/Effective）：
  5+1 个端点，权限码与 V79 菜单种子一一对应；`/fonts` 暴露字体白名单，避免前端各自维护一份允许列表。
- `enums/AiErrorCodeConstants.java`：新增 6 个错误码（`1_003_007_019`–`024`，报表与 Chat 子区间）。
- 迁移 `basic-framework-server/.../db/migration/V79__ai_theme.sql`：建表（3 个唯一键 + 1 个索引 + 物理外键）
  与主题菜单/权限点（4105–4107）。

### 前端（`packages/ai-chat-ui/src/theme`）

- `tokens.ts`：`ResolvedTheme`/`ThemeLayout`、token 与布局的受控校验（与后端同值）、
  继承顺序（平台默认 → 应用已发布修订 → 运行时覆盖）、`applyRuntimeOverride`（白名单字段，
  不落库、不污染基准）、`themeCssVariables`（值全部由本模块生成）。
- `adapters.ts`：同一份主题 → Chat（CSS 变量）/图表（色板，品牌主色第一序列色）/
  报表（深浅色名）共用；`isAllowedFontFamily` 自检。
- `samples.ts`：浅色/深色/平台默认三份样例载荷（管理端预览与宿主示例直接可用）。
- `README.md`：模块说明、用法、继承与覆盖边界、校验边界。
- `packages/ai-chat-ui/src/index.ts`：导出主题模块的公开 API。

### 台账、快照与文档

- `docs/contracts/data-lifecycle.json`：`ai_theme` 登记为 soft-delete（配置类表口径），
  新物理外键 `fk_ai_theme_application`。
- `docs/contracts/data-permission-exemptions.json`：新增 `ai-theme-config` 豁免组（function-permission，
  含控制器证据行）。
- `数据库文件/basic_framework.sql`：新增 `ai_theme` 建表块与 V79 菜单种子；
  快照声明更新为 `through V79`，软删除表数量 48 → 49。
- `PersistenceLifecycleIT`：逻辑删除列清单加入 `ai_theme`（按 `ORDER BY table_name` 的位置）。
- `docs/contracts/ai/error-code-map.md`：同步 6 个新错误码。
- `docs/security/ai-theme-rendering-boundary.md`：主题渲染边界（允许取值、字体白名单、注入面、生效范围）。

### 测试

- 单元：`AiThemeValidatorTest`（11 例）、`AiThemeServiceImplTest`（13 例）、`AiThemeControllerTest`（4 例）。
- 集成：`AiThemePersistenceIT`（7 例，真实 MySQL + Flyway 全量迁移）。
- 前端：`theme/__tests__/tokens.test.ts`（18 例）、`theme/__tests__/adapters.test.ts`（5 例）。

## 2. 与卡片逐步实施的对应

| 卡步骤 | 实现 | 验证 |
|---|---|---|
| 1. 建立主题版本存储与发布，校验色值/字体白名单/布局 | `ai_theme` + `AiThemeValidator` + 服务层发布/回退 | `AiThemeValidatorTest`（色值/半径/未知字段/字体白名单/布局矩阵）、`AiThemePersistenceIT`（发布→取代→回退、唯一键兜底、非法输入零落库） |
| 2. 定义默认/应用/允许运行时覆盖优先级 | 后端 `resolveEffective`（平台默认 → 已发布修订 + 来源标记）；前端 `resolveEffectiveTheme` + `applyRuntimeOverride` | `AiThemeServiceImplTest`（默认回落与已发布解析）、`AiThemePersistenceIT.effectiveThemeFollowsInheritanceOrder`、`tokens.test.ts`（覆盖只允许五字段、字体拒绝、不污染基准） |
| 3. Chat/图表/报表共用 tokens | `adapters.ts` + `themeCssVariables` | `adapters.test.ts`（色板以品牌主色开头且长度不变/无重复、报表深浅色一致）、`tokens.test.ts`（CSS 变量值与单位） |

### 卡片验收项

| 验收项 | 结论 | 证据 |
|---|---|---|
| AT-054 主题深浅色与窄屏（Chat/图表/报表一致） | 部分通过：三处渲染输入来自同一份 `ResolvedTheme`（CSS 变量/图表色板/报表主题名），深浅色与窄屏令牌有单测；**"键盘可用"与真实浏览器观感由 Q06/G5 承接** | `adapters.test.ts`、`tokens.test.ts`；未验证项见第 8 节 |
| 任意 CSS/url/脚本拒绝 | 通过：色值只接受十六进制、半径限区间、字体命中自托管白名单（`url(...)` 被拒）、布局只接受枚举与受限整数、未知字段一律拒绝；两侧各自校验（后端权威、前端 fail-closed） | `AiThemeValidatorTest`（含 `url(https://…)`、`Arial`、`red`、`javascript:` 全部拒绝）、`tokens.test.ts`（同口径的前端用例） |
| 深浅色可读 | 通过（令牌层面）：深浅色两套图表色板与文字色由主题驱动，深浅背景各有一份可辨识色板 | `adapters.test.ts`（dark 与 light 的背景/标签色不同且主色入板） |
| 运行时覆盖不污染其他 app | 通过：覆盖只作用于返回的新对象（基准对象不被修改），不写库、不经过服务端 | `tokens.test.ts`（覆盖后 `base` 保持原主色、`overridden=false`） |

## 3. 关键约束落地

- **修订不可变**：`ai_theme` 只在创建时写入 token/布局；发布后再次发布会以 `AI_THEME_REVISION_IMMUTABLE`
  拒绝（409），调整必须新建修订——回退因此总能指向内容确定的历史修订。
- **生效唯一由数据库兜底**：`uk_ai_theme_published`（`if(publication_state='PUBLISHED', application_id, NULL)`
  的函数唯一索引）保证同一应用最多一条生效修订；服务层先做同事务比对给出可读 409，
  数据库冲突（`DuplicateKeyException`）再收敛为同一错误码。集成测试**绕过服务层**直接 `UPDATE`
  也要被数据库拦住（`databaseRejectsSecondActiveRevisionAndDuplicateRevisionNumber`）。
- **发布版本参与缓存键**：`tokens_fingerprint` 是 tokens+layout 的 SHA-256，随有效主题一起返回（C05 用它做缓存键）。
- **主题不是第二套样式表**：两侧都只接受声明 token；字体是自托管白名单；布局是枚举与受限整数；
  运行时覆盖只允许五个字段且**不允许替换字体**（远程字体入口）。
- **主题不携带行为**：权限、功能开关不在主题里；`ai_theme` 的读写由 `ai:theme:*` 功能权限管理。
- **配置类表的生命周期**：按 `docs/data-lifecycle.md` 的 AI 表分类采用逻辑删除（与 `ai_report`/`ai_service_release`
  同一口径），并登记到生命周期台账。
- **不新增依赖**：后端复用 `JsonUtils`/MyBatis-Plus 既有能力，前端只用 `vue` + `@vben/ai-contracts`；
  未改 lockfile、父 POM、请求器或 Harness 拓扑。

## 4. 实际执行的命令与结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（后端命令在 `后端代码/basic-framework-boot`，
前端命令在 `前端代码/basic-framework-admin`）；命令前统一
`umask 022; export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64; PATH="/tmp/harness-shim:$HOME/.local/bin:$PATH"`。

| 命令 | 退出码 | 结果 |
|---|---|---|
| `./mvnw -q -o -pl basic-framework-module-ai spotless:apply` | 0 | 格式已应用（三个测试文件首轮格式不合规已修正） |
| `./mvnw -o -pl basic-framework-module-ai test -Dtest='AiThemeValidatorTest,AiThemeServiceImplTest,AiThemeControllerTest'` | 0 | **28 例通过**（11 + 13 + 4） |
| `./mvnw -q -o -pl basic-framework-module-ai -DskipTests -Djacoco.skip=true install` | 0 | 模块安装（集成测试使用新 jar） |
| `./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiThemePersistenceIT'` | 0 | **7 例通过**（真实 MySQL + 全量 Flyway：78 个迁移全部校验通过） |
| `pnpm exec vitest run --dom packages/ai-chat-ui/src/theme` | 0 | `Test Files 2 passed`，**23 例通过** |
| `pnpm run check:type` | 0 | 37/37 任务通过 |
| `node scripts/check-data-lifecycle.mjs` | 0 | `最终表 76 张，策略登记 76 张，逻辑删除列 49 张，物理外键 59 条；快照同步` |
| `node scripts/check-data-permission.mjs` | 0 | `应用表 65 张…显式豁免 63 张…检查通过` |
| `node scripts/check-permission-catalog.mjs` | 0 | `接口引用 131 个权限码，目录 132 个`（+3 个主题权限码） |
| `sh .harness/verify.sh contracts` | 见第 6 节 | 契约门禁（首轮 exit 1：`check-sensitive-tostring`，已修复） |
| `sh .harness/verify.sh backend` | 见第 6 节 | 后端门禁 |
| `sh .harness/verify.sh frontend` | 见第 6 节 | 前端门禁 |
| `./mvnw -q -Pintegration clean verify` | 0 | **228 例 IT 全绿**（failsafe 汇总 0 failures / 0 errors / 0 skipped） |
| `./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='PackagedJarBootSmokeIT'` | 0 | 负载敏感用例的定向复跑：该类通过（163.6 秒） |

## 5. 新增/变化的对外契约

- **API**：`POST /ai/theme/create`（`ai:theme:create`）、`GET /ai/theme/get`、`GET /ai/theme/page`、
  `GET /ai/theme/effective`、`GET /ai/theme/fonts`（`ai:theme:query`）、
  `POST /ai/theme/publish`（`ai:theme:publish`，发布与回退同一动作）。
- **数据表**：`ai_theme`（`public_id` 为 `thm_` 前缀的不透明标识；`uk_ai_theme_public_id`、
  `uk_ai_theme_revision`、`uk_ai_theme_published`；`fk_ai_theme_application` → `ai_application` RESTRICT）。
- **错误码**：`1_003_007_019`–`1_003_007_024`（见 `docs/contracts/ai/error-code-map.md`）。
- **权限码**：`ai:theme:query` / `ai:theme:create` / `ai:theme:publish`（V79 菜单种子 4105–4107）。
- **前端 API**：`@vben/ai-chat-ui` 新增 `resolveEffectiveTheme`、`applyRuntimeOverride`、`parseThemeTokens`、
  `parseThemeLayout`、`platformDefaultTheme`、`themeCssVariables`、`chartTokensOf`、`reportThemeOf`、
  `colorSchemeOf`、`isAllowedFontFamily`、`ALLOWED_FONT_FAMILIES`、`defaultLayout` 与样例载荷。
- **上游差异（如实记录）**：
  1. 冻结契约 `theme-tokens.schema.json` 只包含 `primaryColor`/`radius`/`fontFamily`/`colorScheme`；
     设计文档 §8.1 还列出 `backgroundColor`/`surfaceColor` 等更宽的集合。本卡**以冻结契约为准**，
     其余观感差异留给后续版本按变更流程扩展 Schema（不擅自扩散字段）。
  2. 设计文档把 `ai_theme` 标为"A"，但仓库对配置类表的既定口径是**逻辑删除**
     （`ai_report`/`ai_service_release` 同样如此），故按 soft-delete 登记；"发布版本不可修改"
     用服务层不可变性表达（见第 3 节）。
  3. 平台字体白名单是**平台规则**而非法定协议内容（冻结 Schema 只限制长度）：因此它在两侧各实现一次，
     取值与说明同步维护在 `docs/security/ai-theme-rendering-boundary.md`，并由两侧测试固定。
  4. `GET /ai/theme/fonts` 是本卡新增的只读端点（管理端下拉选项），不在原始设计契约中。
- **页面**：`ai/theme/index` 组件由 C09 交付；V79 已按 V69（查询计划）的既有口径落地菜单与权限码。

## 6. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 字段目录、生命周期、数据权限、权限目录、安全信号、异常期、覆盖率棘轮测试全部通过（首轮退出 1 的原因见第 7 节第 1 条，已修复） |
| `sh .harness/verify.sh backend` | 0 | 编译、单测（含本卡 28 例）、格式、架构（ArchUnit）与覆盖率检查通过 |
| `sh .harness/verify.sh frontend` | 0 | `check`（vue-tsc 37/37 + cspell 1003 文件 0 问题）+ `lint` + `test:coverage`（348 文件 / 1883 例）+ 生产构建 `Production JavaScript: 228 files passed no-undef validation` + 前端棘轮 |
| `./mvnw -q -Pintegration clean verify`（integration 门禁的 maven 段） | 0 | **228 例 IT 全部通过**（failsafe 汇总 failures 0 / errors 0 / skipped 0，22:49 完成；含 `AiThemePersistenceIT` 7 例、`PersistenceLifecycleIT`、`PackagedJarBootSmokeIT`） |
| `sh .harness/verify.sh integration`（整体） | 1 | **唯一失败项是尾部棘轮**：4 个新后端文件"尚未登记单文件覆盖率基线"（新文件的预期状态，见第 7 节说明）；maven 段本身 exit 0 |
| `node scripts/check-coverage-ratchet.mjs --update` | 0 | 登记 7 个新文件基线（后端控制器 100%、服务 100%、校验器 95%、Mapper 100%；前端 100%/100%/97.92%），1 个既有条目上浮（`AiApplicationServiceImpl` 82.57% → 83.49%，本卡新增的主题用例经由真实应用服务校验存在性而带来的覆盖面），**无任何条目下降、无条目被删除** |
| `node scripts/check-coverage-ratchet.mjs all` | 0 | `all 单文件基线通过` |

> 棘轮登记只改 `docs/contracts/coverage-baseline.json`（台账文件），不影响任何测试或运行行为；
> 登记后 `all` 为 0，即 `sh .harness/verify.sh integration` 的两段（maven 段 + 棘轮段）都已满足。
> 为节省一轮 30 分钟的全量 IT，未在登记后重跑整个 integration 门禁，此边界如实记录于此。

## 7. 顺带修复的依赖缺口（真实暴露）

1. **函数唯一索引的取值形态**：一开始只打算在服务层保证"同一应用最多一个生效修订"，
   但并发首次发布会绕过服务层比对。改为在迁移里加函数唯一索引 `uk_ai_theme_published`，
   并把数据库冲突收敛为同一错误码——集成测试用**绕过服务层**的 `UPDATE` 证明了兜底有效。
2. **测试里的 JSON 拼接**：字体栈自身含双引号，手工拼接 token JSON 会生成非法 JSON
   （首轮 3 个测试因此报"主题 token 不合规"）。测试统一改为经 `JsonUtils`/`JSON.stringify` 序列化，
   顺带说明"前端传来的 token 必须是合法 JSON"这条校验确实在生效。
3. **`selectPage` 重载歧义**：Mapper 的自定义 `selectPage(PageParam, Long, String)` 与框架的
   `selectPage(PageParam, Collection, Wrapper)` 在 `null` 实参下无法区分（单元测试与集成测试各触发一次），
   改为显式类型转换/`eq` 匹配。
4. **spotless 与测试同步**：`basic-framework-server` 的测试文件格式未在编译前应用，导致集成测试首轮
   在 `spotless:check` 阶段失败（不是代码问题）；已按既有做法在编译前对改动模块执行 `spotless:apply`。
5. **平台默认必须自己可发布**：`defaultTokensJson()` 与后端白名单共同决定"平台默认主题"，
   用例显式断言"默认值能通过自己的校验"，避免出现"没有应用主题时反而渲染不出来"。
6. **`check-sensitive-tostring` 门禁拦下了主题 token 字段**：字段名里的 `token` 命中敏感词规则，
   而主题 token 确实也不该进日志正文，故在 `AiThemeDO`/两个 DTO/两个 VO 上加了 `@ToString.Exclude`
   （共 8 处），而不是把字段改名绕过门禁。
7. **cspell 词表补充**：字体名 `Menlo`/`Songti` 与色值写法 `RRGGBB` 不在工作区词典里，
   已按既有做法加入 `cspell.json` 的 `words`（保持升序，否则 `jsonc/sort-array-values` 会红）。
8. **`PackagedJarBootSmokeIT` 的 120 秒启动预算是负载敏感的**：首次全量集成运行（与其余 227 例 IT 同批）
   出现一次 `Packaged jar did not become healthy within PT2M`（子进程日志显示当时仍在初始化 Quartz，
   应用侧无异常）；随后定向复跑该类通过（163.6 秒），紧接着的第二次全量运行 **228/228 全绿**。
   处理方式与既有 `UserProfilePersistenceIT` 同一口径：不跳过、不排除，如实记录并给出定向复跑与整批复跑的证据。

## 8. 未验证项与已知边界

1. **真实浏览器验收未完成**：AT-054 的"键盘可用""窄屏切全屏""深浅色观感"这类结论需要真实浏览器
   （Q06 建立浏览器门禁后由 G5 承接）。本卡证据是校验/解析/适配层的单元与集成测试，
   **不声称**已完成浏览器验收。
2. **管理端页面未交付**：`ai/theme/index` 组件属 C09；因此 V79 菜单当前指向尚未创建的组件
   （与 V69 的 `ai/query/index` 同一现状），管理端在此前看不到该页面的实际内容。
3. **嵌入页未接入**：主题在嵌入页的落地（握手 INIT 的 theme 字段、缓存键包含应用与主题修订）
   属 C05；本卡只保证服务端能给出有效主题与摘要。
4. **设计文档 §8.1 的扩展 token 未实现**：`brandName`/`logoFileId`/`backgroundColor` 等不在冻结 v1 契约内，
   本卡不实现（见第 5 节差异 1）。
5. **`GET /ai/theme/fonts` 为新增端点**：属本卡的实现便利，未在原始契约中声明，已在第 5 节列出。
6. **`PackagedJarBootSmokeIT` 的启动预算**：该用例硬编码 120 秒健康等待，在本机全量套件负载下曾差一点超时
   （见第 7 节第 8 条）。本卡未改该用例（不在允许路径内），仅如实记录：后续如需调整需按测试卡流程处理。
7. **环境缺口（非本卡）**：`sh .harness/verify.sh dependencies` 因 Trivy 漏洞库镜像不可达仍失败；
   本卡未改依赖与镜像，未重跑该门禁。
