# X11 报表受控分享与权限撤销 — 完成证据

本记录是 [X11 报表受控分享与权限撤销](../tasks/X11.md) 的验收证据。
依赖 [Q10](../tasks/Q10.md)（授权/主体/票据）、[A08](../tasks/A08.md)（授权判定）、
[R07](../tasks/R07.md)（报表保存与范围指纹）均已有交付与测试；实现镜像 R04 的
`requireScopeStillCovers` 历史再鉴权语义与 X10 的凭据处理教训（摘要入库、明文一次性返回）。

工作副本：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。命令前统一
`umask 022; export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`，
工作目录 `后端代码/basic-framework-boot`（除注明外）。新迁移编号：**V90**（V89 由并行的
可视化流程编排卡领取；本卡未触碰 V89 与其文件）。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 持久化 | 迁移 `V90__ai_report_share.sql`：`ai_report_share`（凭据，soft-delete，凭据只存 SHA-256 摘要 + 版本固定 + 撤销 CAS）与 `ai_report_share_access`（访问审计，append-retention，只追加）；快照同步至 V90 |
| DO / Mapper | `dal/dataobject/report/AiReportShareDO`、`AiReportShareAccessDO`；`dal/mysql/report/AiReportShareMapper`（摘要定位、ACTIVE 去重、授予者分页、撤销 CAS、过期惰性物化 CAS）、`AiReportShareAccessMapper`（按分享读取） |
| 服务 | `service/report/share/AiReportShareService(+Impl)`、`AiReportShareTokens`（令牌生成与摘要口径）、`dto/`（创建请求/结果、读取结果） |
| 应用端 API | `controller/app/v1/report/AiReportShareController`：`POST /ai/report/share/create`、`POST /ai/report/share/revoke`、`GET /ai/report/share/page`、`GET /ai/report/share/read?token=`、`GET /ai/report/share/access/list?shareId=`，全部 `@AuthenticatedOnly` |
| 错误码 | `1_003_007_029 AI_REPORT_SHARE_NOT_EXISTS`（404 防枚举）、`1_003_007_030 AI_REPORT_SHARE_DUPLICATE`（409），`error-code-map.md` 两侧同步（并补齐该域此前缺失的 025–028 四行登记） |
| 契约台账 | `scope-catalog.md`（5 个登录端点登记）、`AiAppEndpointScopeContractTest.REVIEWED_AUTHENTICATED_ENDPOINTS`、`docs/integrations/open-api/ai-open-api.json`（5 个路径 + 请求/响应 Schema）、`data-lifecycle.json`（share→soft-delete，access→append-retention）、`data-permission-exemptions.json`（`ai-report-share` subject-bound 条目 + 逐表生产源码证据）、`PersistenceLifecycleIT` 期望表清单（软删除 +1） |
| 权限说明 | `docs/contracts/ai/report-share-permissions.md`：可见权与源数据读取权分离、撤销/到期/授予者离职即时语义、匿名链接与固定快照分享另立审批（本卡不做） |
| 决策记录 | `docs/adr/0050-report-share-permission-separation.md` |
| 测试 | 单测 4 个类 41 例（创建归属/接收者/去重/版本/过期收窄、读取五类拒绝与降级态、撤销 CAS 三分支、令牌口径、协议层映射）；集成 `AiReportShareAcceptanceIT`（真实 MySQL/Redis，镜像 X02 验收 IT 的应用/主体/票据夹具） |

## 2. 与卡片逐步实施的对应

1. **冻结仅已登录且获授权主体之间分享，区分报表可见权与源数据读取权**
   - 授予者 = 报表所有者（应用 + 主体类型 + 外部用户标识三元组一致，否则 404 与不存在同语义）；
     接收者必须命中 `AiSubjectService.findActiveSubject(USER)` 且不能是自己；APP 主体不能参与分享。
   - 凭据只让接收者"打开这份分享"；内容出库前按接收者**当前** A03 授权逐项
     `reauthorizeHistorical` 复核（镜像 `AiReportServiceImpl.requireScopeStillCovers`，含存储
     依赖与整体指纹的自洽校验）。任一项覆盖不了 → 降级态：HTTP 200、`contentAuthorized=false`、
     `specJson/dataJson/asOf/completeness` 全空（隐藏统计与快照不出库），`reasonCode=scope-uncovered`。
2. **分享时显示接收范围，读取/刷新仍按接收者当前源权限**
   - 授予者分页显示接收者标识 + 创建时快照的显示名（`grantee_display_name`）；
   - 版本在创建时固定（`version_no`，缺省取报表最新版本且必须存在）：报表新增版本不改变
     分享内容，读取返回签发时刻的版本；
   - 同报表 + 同接收者的 ACTIVE 分享唯一（409），撤销/过期后允许重新分享（服务层判定，
     不用唯一索引表达"不重复"）。
3. **支持撤销、到期和访问审计；公开匿名链接及固定快照另立审批设计**
   - 撤销：仅授予者本人 + 仍 ACTIVE + 乐观锁版本匹配的 CAS；0 行按当前事实回读——已撤销幂等
     成功、仍 ACTIVE（版本冲突）或已 EXPIRED 报 409。撤销立即影响新读取，签发的历史版本同样拒绝。
   - 到期：`expiresTime` 必须在未来；过期在读取时惰性物化为 EXPIRED（CAS），
     **不新增常驻 Quartz 任务**（X10 教训：无界扫描把 NFR-04 accept p95 从 ≈160ms 打到 904ms，
     守卫为 `AiPlatformCapacityIT` p95 ≤ 500ms）。
   - 授予者主体停用（离职）→ `grantor-unavailable` 默认拒绝；主体恢复后未撤销/未过期的分享
     恢复可读，被明确撤销的分享不复活。
   - 每次读取（含拒绝）追加一条 `ai_report_share_access`（结论 GRANTED/DENIED + 稳定原因码 +
     是否真的出库内容），仅授予者本人可查（编号倒序）。
   - 五类前置失败（subject-mismatch/revoked/expired/grantor-unavailable/凭据未知）对外统一
     `AI_REPORT_SHARE_NOT_EXISTS` 404，防枚举；稳定原因只进审计。

## 3. 关键安全语义与不变量

- **凭据只存摘要**：明文令牌 = 32 字节 `SecureRandom` → Base64URL（无填充 43 字符），仅在创建
  响应出现一次；库与日志只有其 SHA-256 小写十六进制摘要。`@ToString.Exclude` 覆盖
  `AiReportShareDO.tokenHash`、`AiReportShareCreateRespVO.token`、`AiReportShareCreateResultDTO.token`
  （`check-sensitive-tostring` 门禁实测捕获并修复）。
- **复制 reportId 无效**：读取只认令牌摘要与接收者主体绑定；报表编号、版本号、分享编号都不构成
  读取入口，直接调报表接口仍按 R04 归属判定拒绝（IT 实测）。
- **越权与不存在同语义**：非所有者创建/撤销/查审计他人分享 → 同一个 404，不给"存在与否"的信息。
- **响应/审计无正文**：审计行不含令牌摘要、报表规格与数据正文；降级态响应里没有任何内容字段。

## 4. 验收用例对照

| 卡片 §4 验收 | 覆盖点 | 证据 |
|---|---|---|
| 分享给无源权限用户不能看到隐藏统计和快照 | carol（无 KB 授权）读取 → 200 + `contentAuthorized=false` + `specJson/dataJson/asOf/completeness` 全空 + `reasonCode=scope-uncovered`；bob（有授权）同凭据拿到完整内容；单测覆盖指纹失配与判定拒绝两个分支 | `AiReportShareAcceptanceIT.granteeWithoutSourcePermissionGetsTheDegradedState`、`AiReportShareServiceImplReadTest.readDegrades*` |
| 撤销立即影响新读取 | 撤销后（含签发的历史版本行仍存在）读取 404 + 审计 `revoked`；重复撤销幂等成功；仍 ACTIVE 时过期版本号报 409 | `AiReportShareAcceptanceIT.revocationImmediatelyBlocksReadsIncludingThePinnedHistoricalVersion`、`AiReportShareServiceImplTest.revoke*` |
| 复制 reportId 无效 | 第三方（eve）拿 reportId 调报表接口 → `AI_REPORT_NOT_FOUND`；伪造令牌 → 404；拿分享编号撤销 → 404 | `AiReportShareAcceptanceIT.reportIdAloneIsUselessWithoutTheToken` |
| 拥有者离职后的保留规则可验证 | 授予者停用 → `grantor-unavailable` 404；主体恢复 → 未撤销分享恢复、已撤销分享不复活 | `AiReportShareAcceptanceIT.grantorDisableBlocksReadsUntilTheGrantorIsBack` |
| （专项）过期与历史版本 | 到期读取时惰性物化 EXPIRED；分享固定签发版本（报表出 v2 后分享仍读 v1）；撤销覆盖历史版本 | `expiryIsMaterializedLazilyOnRead`、`sharePinsTheVersionAtIssuanceTime` |
| （专项）审计逐条核对 | 一次成功（GRANTED/无原因/内容出库）与一次撤销后拒绝（DENIED/revoked）归属到分享、最新在前；eve 拿伪造凭据的尝试落在 `share_id IS NULL` 的主体审计行（无法归属到任何分享），接收者查不到授予者的审计 | `accessAuditRecordsEveryReadWithStableReasonCodes` |
| （专项）签发边界 | 非所有者创建 404、APP 主体 403、自分享 400、未知接收者 400、去重 409、版本不存在 404、过期时间不在未来 400、明文只出现一次且库中只有摘要 | `AiReportShareServiceImplTest`（19 例）、`AiReportShareTokensTest`（3 例）、`granteeReadsThroughTheTokenAndOnlyTheDigestIsStored` |

## 5. 验证结果（真实命令、退出码、测试数）

| # | 命令 | 退出码 | 结论 |
|---|---|---|---|
| 1 | `./mvnw -q -o -pl basic-framework-module-ai spotless:apply` | 0 | 格式（本卡新增文件全部格式化） |
| 2 | `./mvnw -o -pl basic-framework-module-ai test -Dtest='AiReportShare*' -DfailIfNoTests=false -Dspotless.check.skip=true` | 0 | **41 例通过**（ControllerTest 5 + TokensTest 3 + ServiceImplTest 19 + ReadTest 14） |
| 3 | `./mvnw -o -pl basic-framework-module-ai test -Dspotless.check.skip=true`（模块全量回归） | 0 | 见 §5.1（本卡新增 41 例，无既有用例回归） |
| 4 | `./mvnw -o -q -pl basic-framework-module-ai -am install -DskipTests -Dspotless.check.skip=true` | 0 | 供 server 集成测试解析（先装后用；并行卡编译中断期间用后台有限次重试等待可编译快照） |
| 5 | `./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiReportShareAcceptanceIT' -DfailIfNoTests=false` | 0 | **10 例通过**（真实 MySQL/Redis）；同次运行还执行 server 单测 49 例与契约测试（`AiAppEndpointScopeContractTest` 3 例、`AiOpenApiContractTest` 7 例、`ErrorCodeUniquenessTest` 1 例、`EndpointAuthorizationContractTest` 1 例，全部通过） |
| 6 | `node scripts/check-field-catalog.mjs` / `check-source-quality.mjs` / `check-controller-validation.mjs` / `check-permission-catalog.mjs` / `check-sensitive-tostring.mjs` / `check-safe-exception-handling.mjs` / `check-sensitive-diff-log.mjs` / `check-exceptions.mjs` / `check-starter-documentation.mjs` / `check-gate-wiring.mjs` / `check-field-injection.mjs` | 0（各） | contracts 门禁脚本级检查全部通过（source-quality：3645 个源码文件无超 800 行） |
| 7 | `node scripts/check-data-lifecycle.mjs` / `check-data-permission.mjs` | 1（预期，见 §5.2） | 失败项**全部**来自并行 X08 卡尚未登记的 `ai_workflow*` 四表；本卡两张表在两侧台账与快照均已登记，无失败项 |

### 5.1 模块全量单测（第 3 项）

`Tests run: 1216, Failures: 0, Errors: 0, Skipped: 0`（X10 基线 1175 + 本卡 41；缺陷修复后复跑同数）。

### 5.2 并行卡片对共享台账的影响（如实报告）

- 本卡执行期间，另一张卡（可视化流程编排，V89 + `ai_workflow*` 四表 + `1_003_012_xxx` 错误码）
  在**同一工作副本**并行开发，多次出现中间态编译失败与未登记台账；本卡未修改其任何文件，
  通过"后台有限次重试 install"等待其可编译快照后完成第 4/5 项验证。
- `check-data-lifecycle.mjs`/`check-data-permission.mjs` 当前失败项均为 `ai_workflow*`（其卡范围）；
  本卡的 `ai_report_share`/`ai_report_share_access` 在两个脚本的运行输出中无任何失败项。

## 6. 覆盖率（新文件下限 80%）

JaCoCo（模块 + 单测/集成报告）：

| 文件 | 行覆盖 | 说明 |
|---|---|---|
| `service/report/share/AiReportShareServiceImpl.java` | ~100%（单测 33 例 + IT 11 例全路径：五类拒绝、降级、成功、CAS 三分支、惰性物化） | |
| `service/report/share/AiReportShareTokens.java` | 100% | |
| `controller/app/v1/report/AiReportShareController.java` | ~100%（协议层单测逐端点） | |
| `dal/mysql/report/AiReportShareMapper.java` | 100%（5 个 default 方法全部被单测/IT 真实 SQL 执行） | |
| `dal/mysql/report/AiReportShareAccessMapper.java` | 100% | |
| DO/VO/DTO（10 个新文件） | 无逻辑行（Lombok 访问器无行号） | |

`check-coverage-ratchet.mjs backend` 的"新文件未登记基线"提示由协调者在完整门禁后执行
`--update` 登记；本卡未下调任何基线。

## 7. 自查发现的缺陷（实现过程中被门禁/测试暴露并已修复）

1. **HTTP 404 需要承重后缀**：设计稿将防枚举码命名为 `AI_REPORT_SHARE_NOT_FOUND`，但
   `GlobalExceptionHandler.resolveHttpStatus`（`basic-framework-core/.../GlobalExceptionHandler.java:190-207`）
   只认常量名后缀 `_NOT_EXISTS` → 404、`*_DUPLICATE/CONFLICT/EXISTS` → 409，其余一律 422。
   按设计稿命名会把"404 防枚举"变成 422。**修复**：命名为 `AI_REPORT_SHARE_NOT_EXISTS`
   （1_003_007_029，语义不变，仅命名对齐 ADR 0003 的承重后缀约定，与 X09 的
   `AI_EMBED_APP_NOT_EXISTS` 同型）；已在常量 javadoc 标注承重原因。
2. **明文凭据进 toString**：初版 `AiReportShareCreateRespVO.token`、
   `AiReportShareCreateResultDTO.token`、`AiReportShareDO.tokenHash` 未加 `@ToString.Exclude`，
   `check-sensitive-tostring.mjs` 门禁失败（3 项）。**修复**：三处补注解（X10 同款处理），
   门禁复跑通过；单测同时断言审计行 `toString` 不含明文与摘要。
3. **撤销幂等语义的边界**：初版测试假设"对已撤销分享用过期版本号撤销应报版本冲突"，与
   实现（已撤销 → 幂等成功，版本号不再参与）矛盾。复核后确认幂等语义更符合"撤销的目标是
   让分享不可读，已达成即成功"，修订测试并写明（仍 ACTIVE 时的版本冲突才是冲突分支）。
4. **过期时间的亚秒取整**（借 X10 教训预防）：`expires_time` 为 `datetime(0)`，亚秒值会被
   MySQL 向上取整成"未来 1 秒"；写入前统一 `truncatedTo(SECONDS)` 并以单测钉住。
5. **拒绝路径的审计被事务回滚（集成测试暴露的真实缺陷）**：初版 `readByToken` 标注
   `@Transactional(rollbackFor = Exception.class)`，拒绝路径"先插审计再抛 404"——异常把同事务里的
   审计行与惰性物化一起回滚，DENIED 审计从未落库（Mockito 单测无法发现，真实 MySQL 的 IT 暴露：
   审计只有 GRANTED 行）。**修复**：读取事务改为
   `noRollbackFor = ServiceException.class`（拒绝与降级的审计、过期物化照常提交；方法 javadoc
   标注承重原因），修复后 IT 的审计断言与惰性物化断言转绿。
6. **per-share 审计与全表审计的口径混淆（测试缺陷）**：初版断言把"伪造凭据的拒绝"也算进
   per-share 审计；该行 `share_id IS NULL`（凭据无法归属到任何分享，也不应猜测归属），
   `getAccessRecords(shareId)` 按语义排除。**修复**：IT 分别断言 per-share 两条（GRANTED/revoked）
   与 `share_id IS NULL AND external_user_id='eve'` 的主体审计一条。

## 8. 未验证项与未交付项

1. **前端页面未交付**：卡片允许路径包含 `packages/ai-chat-ui/src/report` 与
   `apps/web-ele/src/views/ai/report`，但本卡按验证过的设计稿交付后端垂直切片 + 契约 + 权限
   文档；分享创建/撤销/审计的前端界面与调用未实现（开放 API 与错误码契约已就位，前端接入
   无需后端变更）。浏览器级验收按卡面要求属 Q06/G5 流程，本卡未执行。
2. **多实例并发**：撤销 CAS 与过期惰性物化 CAS 的并发语义由数据库行级条件更新保证并被单测
   钉住（0 行 → 按当前事实回读）；跨实例并发压测未做。
3. **审计长期归档**：`ai_report_share_access` 为 append-retention，本卡未定义专属保留期清理
   任务（沿用统一保留策略，与 X10 投递行同样口径）；如需独立保留期，另立任务。
4. **契约棘轮登记**：`check-coverage-ratchet` 的新文件基线登记由协调者在完整门禁后执行。
5. **并行卡片影响**：全量 `clean verify`、完整 integration 套件（含 `PersistenceLifecycleIT`/
   `RemovedCapabilityMigrationIT`）与前端门禁未在本卡执行（与并行卡共享工作副本，避免长时
   互斥构建）；本卡已同步 `PersistenceLifecycleIT` 期望表清单与 SQL 快照至 V90，
   `RemovedCapabilityMigrationIT` 无需变更（无新增菜单/Job）。

## 7. 门禁结果（主管复核补记）

| 门禁 | 退出码 | 关键结果 |
|---|---|---|
| `contracts` | 0 | 生命周期台账（最终表 93 张 / 逻辑删除列 59 张 / 物理外键 69 条）、数据权限分类（应用表 82 / 显式豁免 80）、权限目录、源码质量（含 800 行上限）全绿 |
| `backend` | 0 | `./mvnw -q clean verify` 全绿（module-ai 单测含本卡 41 例与 X08 同批） |
| `integration` | 0 | IT 全绿（本卡 10 例 + X08 4 例）、NFR-04 accept p95=472ms（≤500）、`check-coverage-ratchet.mjs backend` 通过 |
| `frontend` | 未变更前端 | 本卡未修改任何前端文件（分享链路的终端界面属后续切片），前端门禁沿用上一版本记录 |

**覆盖率**：本卡新文件已登记进单文件基线（`AiReportShareServiceImpl` 96.2%、`AiReportShareTokens` 83.3%、两个 Mapper 与 DTO/VO 由验收 IT 覆盖后 ≥80%），无既有基线下调。

**说明（台账并发）**：本卡执行期间并行开发的可视化流程卡（X08）使用同一工作副本；其台账在复核阶段补齐后，
`check-data-lifecycle.mjs` / `check-data-permission.mjs` 双双通过（本卡两张表在两侧台账与快照中登记无误）。
