# S03 版本回退与运行快照解析证据（2026-09-19）

本记录是 [S03 实现版本回退与运行快照解析](../tasks/S03.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 版本解析器 | `domain/runtime/AiRunSnapshot`（运行固定值：releaseId + 内容摘要 + 端点配置版本 + 资源版本，含 `pinsContentOf`/`sameModelRevision`/`sameResources` 三项逐条校验）；`AiServiceReleaseService#resolveForNewRun`（按别名）与 `#resolvePinnedRun`（按会话固定值） |
| 回退 API | `AiServiceReleaseService#rollback` + `AiServiceReleaseController` 的 `POST /ai/service/release/rollback`（权限码 `ai:service:activate`，与 V57 菜单 4037「切换发布版本」一致） |
| 运行解析只读接口 | `GET /ai/service/release/resolve`（权限码 `ai:service:query`），返回 releaseId/版本号/状态/内容摘要/端点配置版本/资源版本清单 |
| N/N-1 服务快照测试 | `AiServiceVersionRollbackIT`（4 例，真实 MySQL）：N 与 N-1 快照共存、别名切换与回退只影响后续运行、固定版本始终按当前授权与绑定状态判定 |
| 边界测试 | `AiServiceReleaseServiceImplTest`（20 例，新增 9 例）、`AiRunSnapshotTest`（3 例）、`AiServiceReleaseControllerTest`（4 例，新增解析映射与权限断言） |
| 错误码 | `AiErrorCodeConstants`/`AiErrorCodeRanges` 与 `docs/contracts/ai/error-code-map.md` 两侧同步新增 `1_003_008_006 AI_SERVICE_RELEASE_NOT_PUBLISHED`、`1_003_008_007 AI_SERVICE_RELEASE_PIN_STALE`（同属已有服务配置子区间，未新占域） |

本卡**不需要新的 Flyway 迁移**：回退只是既有状态列的流转（退役旧 ACTIVE → 激活目标），
运行固定值由运行/会话侧持久化（O01/O02 落库），因此未改动 `数据库文件/basic_framework.sql`、
`data-lifecycle.json`、`data-permission-exemptions.json` 与 `PersistenceLifecycleIT` 的预期表清单。

## 2. 与卡片逐步实施的对应

1. **按别名解析 run 使用 releaseId/modelRevision/资源版本**：`resolveForNewRun` 解析唯一 ACTIVE 版本后，
   返回 `AiRunSnapshot`（releaseId、releaseVersion、contentHash、modelEndpointId、`modelRevision`＝端点配置版本、
   逐条资源绑定的 `{id, type, key, version}`）。固定值里**没有**授权结论、提示词正文或凭据。
2. **回退只改变后续 run，新旧会话行为明确**：
   - `rollback` 与 `publish` 共用 `switchAlias`：同一事务内推进服务行乐观锁 → 退役旧 ACTIVE → CAS 激活目标；
   - 新运行按别名解析（回退后解析到被激活的历史版本），已固定会话按 `resolvePinnedRun` 解析回它开始的版本；
   - IT `aliasSwitchKeepsPinnedSessionsOnTheirVersion` 断言：别名从 N 切到 N+1、再回退到 N 的过程中，
     固定 N 的会话始终解析到 N、固定 N+1 的会话始终解析到 N+1，且同一服务任何时刻只有一条 ACTIVE。
3. **当前授权变化始终优先于旧快照**：`resolution` 每次都读当前值——端点存在且启用、端点配置版本未漂移、
   冻结绑定仍为 ACTIVE 且与固定值逐条一致、逐条动作的当前授权仍允许；任一不满足即拒绝，
   固定值只决定"用哪个版本"，不决定"还有哪些权限"。

## 3. 关键约束与安全语义

- **回退不是放宽**：回退目标必须是曾经发布过的版本（ACTIVE/RETIRED），候选被拒绝
  （`AI_SERVICE_RELEASE_NOT_PUBLISHED`）；目标已是当前生效版本时拒绝（`AI_STATE_CONFLICT`），
  避免把"回退"实现成原地重放。回退与发布跑**同一套**预检查，失败一律回滚事务，当前 active 不变。
- **固定值不可被改写**：`pinsContentOf` 同时比对服务、版本编号与内容摘要；只比编号无法发现内容被改写的库行。
  跨服务固定与版本不存在同语义（404，防枚举）。摘要不命中返回 `AI_SERVICE_RELEASE_PIN_STALE`，
  要求显式迁移会话，绝不静默换版本执行。
- **固定运行不静默换绑定**：`sameResources` 按绑定编号、资源标识与版本逐条比对；
  绑定被解绑（版本推进）或整行消失即 `AI_SERVICE_RESOURCE_UNAVAILABLE`。
- **失效模型给稳定错误**：端点停用返回 `AI_MODEL_ENDPOINT_DISABLED`（409），端点不存在返回
  `AI_MODEL_ENDPOINT_NOT_FOUND`（404），端点配置漂移返回 `AI_SERVICE_ENDPOINT_CONFIG_CHANGED`（409）；
  三条路径在新运行、固定会话与预检查中语义一致。
- **停用优先于固定**：服务被显式停用（无 ACTIVE 版本）后，固定会话同样停止
  （`AI_SERVICE_NOT_PUBLISHED`）；固定不是绕过服务级停用的通道。
- **能力按版本自己冻结的集合判定**：见第 5 节。
- 运行解析结果不含提示词正文、授权结论与凭据，接口只返回版本与资源标识（`AiServiceRunSnapshotRespVO`）。

## 4. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -o -pl basic-framework-module-ai test` | 0 | 194 例通过（S03 新增 13 例：服务 9、领域 3、控制器 1） |
| `./mvnw -Pintegration -pl basic-framework-server test -Dtest=AiServiceVersionRollbackIT` | 0 | 4 例通过（真实 MySQL：N/N-1 快照共存、当前授权优先、回退预检查、停用后固定会话停止） |
| `./mvnw -Pintegration -pl basic-framework-server test -Dtest='AiServiceReleaseIT,AiServiceDraftIT'` | 0 | 5 例通过（S01/S02 回归，含能力判定改为按发布版本冻结集合后的发布路径） |
| `sh .harness/verify.sh contracts` | 0 | 台账/权限/生命周期/字段目录全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单元测试、格式、架构、覆盖率检查通过 |
| `sh .harness/verify.sh integration` | 1 → 0 | 36 个 IT 类 / 97 例全绿；唯一失败是尾部棘轮（新文件未登记，属预期），登记后复验通过 |

## 5. 顺带修复的依赖缺口

1. **发布预检查的能力判定读的是草稿而不是发布版本**（S02 遗留）：`publishBlockers` 原先调用
   `AiServiceService#checkCapabilities`，用**当前草稿**的 `requiredCapabilities` 判定；
   候选冻结后草稿仍可继续编辑，因此一个已冻结的版本可能按"另一个草稿"的能力通过检查（反之亦然）。
   S03 的回退必须对历史版本重跑预检查，这个缺口会直接把错误结论搬到回退路径上，
   现改为按 `release.requiredCapabilities` 与端点探测结论求交集判定（`releaseCapabilitiesUnsupported`）。
   单元测试 `publishBlockersAreOrderedAndFailClosed` 的"能力不再满足"分支已同步改为桩住探测总览。
2. **运行解析缺少端点启用与当前授权检查**（S02 遗留）：`resolveForNewRun` 原先只检查端点配置版本与绑定状态，
   端点被停用、授权被撤销后新运行仍会解析成功。现两条路径统一走 `resolution`，
   先判端点可用性再判绑定与当前授权，把"当前值优先于快照"落到运行入口。
3. **预检查顺序补上端点可用性**：端点不存在/停用时能力与评测证据都不成立，
   新增的端点可用性判定排在能力之前（fail-closed）；其余顺序与 S02 证据一致（能力→资源→完整性→授权→评测）。

## 6. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、权限目录、生命周期、字段目录、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单元测试、spotless、架构与覆盖率检查通过 |
| `sh .harness/verify.sh integration` | 1 → 0 | 36 个 IT 类 / 97 例全绿（含 `AiServiceVersionRollbackIT` 4 例）；唯一失败是其尾部棘轮检查（新增文件未登记，属预期），`--update` 登记后复验通过 |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 新增登记 `AiRunSnapshot.java`（100%）；无基线下调、无登记删除，复验通过 |

## 7. 覆盖率

新增/变化的受测文件（数字取自 server 的 jacoco aggregate 报告，即 `mvn clean verify` 生成）：

| 文件 | 覆盖率 |
|---|---|
| `domain/runtime/AiRunSnapshot.java` | 100%（51 行全部覆盖；新增登记） |
| `service/serviceconfig/AiServiceReleaseServiceImpl.java` | 96.93%（基线 96.67%，未下调） |
| `controller/admin/serviceconfig/AiServiceReleaseController.java` | 100% |
| `dal/mysql/serviceconfig/AiServiceReleaseMapper.java`、`AiServiceReleaseEvaluationMapper.java` | 100% |
| `service/serviceconfig/dto/AiServiceRunSnapshotDTO.java`、`controller/.../vo/AiServiceRunSnapshotRespVO.java`、`AiServiceRunResourceRespVO.java` | Lombok 生成代码被 JaCoCo 过滤，不进棘轮台账（与既有 VO/DTO 一致） |

## 8. 未验证项

1. **运行/会话落库**：本卡交付固定值的生成与校验，以及"固定值如何解析回同一版本"；
   把固定值写进会话与运行行、并在后续消息中读回来属于 O01/O02（`AiRunSnapshot` 已按可持久化的字段设计）。
2. **按最终用户主体的授权**：运行解析按**服务所属应用**主体判定当前授权（与发布预检查同一基准）；
   单次运行的最终用户/外部主体级判定属于运行入口 O02 与 A 系列的主体范围解析。
3. **回退的审计流水**：回退只推进版本行状态与乐观锁，未新增审计表；
   控制面操作审计（谁在何时回退了什么）在 Q 系列与框架操作日志统一处理。
4. **前端交互**：版本历史与回退入口的页面展示属 S05。
