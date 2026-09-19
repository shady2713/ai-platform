# S02 发布预检查与不可变版本证据（2026-09-19）

本记录是 [S02 实现发布预检查和不可变版本](../tasks/S02.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 持久化 | 迁移 `V57__ai_service_release_gate.sql`：`ai_service.eval_threshold`（发布门槛，草稿可改）、`ai_service_release.eval_threshold`（冻结门槛）、新表 `ai_service_release_evaluation`（评测结论，绑定内容摘要 + 端点配置版本）；菜单与权限 4036–4038；快照同步至 V57 |
| 服务层 | `service/serviceconfig/AiServiceReleaseService(+Impl)`：`createCandidate`（冻结内容/端点配置版本/资源快照）、`recordEvaluation`（平台判定通过与否）、`publish`（预检查 + 事务内别名切换）、`disable`（停用）、`checkPublishReadiness`、`resolveForNewRun`（新运行解析） |
| 领域 | `domain/serviceconfig/AiServiceContentHash`：发布内容摘要（端点、端点配置版本、门槛、能力、提示词、输入/输出 Schema、冻结绑定集合；动作顺序归一） |
| 控制面 API | `controller/admin/serviceconfig/AiServiceReleaseController`：`/ai/service/release/{create-candidate,evaluate,publish,disable,check-publish,list,bindings,evaluations}`，权限码 `ai:service:{release,activate,evaluate,query}` |
| 契约 | 错误码新增服务配置子区间 `1_003_008_xxx`（`AiErrorCodeRanges`/`AiErrorCodeConstants` 与 `docs/contracts/ai/error-code-map.md` 两侧同步） |
| 边界测试 | `AiServiceReleaseServiceImplTest`(11)、`AiServiceReleaseControllerTest`(3)、`AiServiceContentHashTest`(3)、`AiServiceReleaseIT`(3，真实 MySQL) |
| 台账 | `data-lifecycle.json`（评测表软删除 + 两条外键）、`data-permission-exemptions.json`（逐表证据）、快照菜单 4036–4038 |

## 2. 与卡片逐步实施的对应

1. **生成不可变 release 并固定配置 revision**：`createCandidate` 用**草稿当前内容**写入发布版本，
   同时冻结模型端点编号、`endpoint_config_revision`、评测门槛与资源绑定快照；同时调用
   `markReferenced` 把端点标记为被引用（provider/baseUrl 之后不可原地修改）。
   候选创建会推进服务行乐观锁版本，同一 `draftVersion` 无法用来创建第二个候选。
2. **校验模型能力、资源状态、输入/输出 Schema 和评测门槛**：发布预检查一次性给出全部未满足项
   （顺序即错误优先级）：能力（声明 ∩ 探测确认）→ 端点配置版本 → 资源绑定状态 →
   内容摘要完整性 → 当前授权 → 评测证据。Schema 的 JSON 对象校验在草稿保存与候选创建两处都执行。
3. **发布别名切换采用事务/乐观锁**：`publish` 在单个事务里推进服务行乐观锁（并发发布只有一个能通过）、
   退役旧 ACTIVE、以版本 CAS 激活候选；任一步失败都回滚，当前 active 不受影响。
   同一服务最多一条 ACTIVE（IT 以 SQL 断言）。
4. **先冻结候选供测试和评测，未切 active 前不出现在业务应用可用服务中**：
   候选状态为 CANDIDATE，只有 `resolveForNewRun` 解析到 ACTIVE 才可用于新运行；
   服务没有 ACTIVE 版本时解析返回 `AI_SERVICE_NOT_PUBLISHED`，绝不回退到草稿。
5. **评测结果固定到候选内容 hash 和模型配置版本**：评测记录写入被评测内容的摘要与端点配置版本；
   发布时读取该版本**最新一条**结论，要求摘要与配置版本都命中且得分达到冻结门槛，
   任何"先通过、后失败"的序列都以最后一条为准；旧报告无法用于内容已变化的新候选
   （摘要不同即 `AI_SERVICE_EVAL_MISSING`），改门槛也会改变摘要，必须新建候选并重新评测。

## 3. 关键约束与安全语义

- **发布版本不可原地修改**：发布版本的内容列只在候选创建时写入，之后代码路径只更新 `status`/`version`；
  发布前还会用冻结内容**重算摘要**与库中摘要比对（防 SQL 直接改库），不一致即拒绝发布。
  IT 断言发布前后提示词、Schema、能力、摘要、门槛一字不变。
- **失败发布不改变当前 active**：预检查失败、CAS 失败、资源不可用等任一路径都在事务中回滚，
  IT 断言失败后 ACTIVE 仍指向旧版本且唯一。
- **资源禁用后新 run 拒绝**：版本快照里的绑定被解除（`ai_service_release_evaluation` 之外的
  `ai_service_resource` 行转 RELEASED）后，`resolveForNewRun` 返回 `AI_SERVICE_RESOURCE_UNAVAILABLE`；
  发布预检查同样拒绝把依赖已解除资源的候选推上线。
- **端点配置漂移即拒绝**：冻结后端点 `configRevision` 变化时，评测记录、发布与运行解析都返回
  `AI_SERVICE_ENDPOINT_CONFIG_CHANGED`（评测不能给无效证据盖章，运行不能用不同配置冒充冻结配置）。
- **调用方不能自报通过**：`evaluate` 只接受得分与用例数，`passed` 由平台按冻结门槛判定。
- **fail-closed**：预检查按"能力→配置版本→资源→完整性→授权→评测"顺序返回第一个阻塞项，任一不确定即拒绝。

## 4. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -pl basic-framework-module-ai test` | 0 | 180 例通过（S02 新增 17 例：服务 11、控制器 3、摘要 3） |
| `./mvnw -Pintegration -pl basic-framework-server test -Dtest=AiServiceReleaseIT` | 0 | 3 例通过（真实 MySQL：生命周期与别名切换、失败发布保持 active、评测绑定内容） |
| `sh .harness/verify.sh contracts` | 0 | 台账/权限/生命周期/字段目录全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单元测试、格式、架构、覆盖率检查通过 |
| `sh .harness/verify.sh integration` | 1 → 0 | 35 个 IT 全绿（含 `AiServiceReleaseIT` 3 例）；唯一失败是其尾部棘轮检查（4 个新文件尚未登记，属预期），随后 `--update` 登记并复验通过 |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 登记：`AiServiceReleaseServiceImpl` 96.67%、`AiServiceContentHash` 91.89%、控制器/评测 Mapper 100%；复验通过 |

## 5. 顺带修复的依赖缺口

`AiServiceResourceMapper.selectBinding(serviceId, null, ...)` 在 releaseId 为 null 时不做版本过滤，
会让**历史发布版本的绑定快照**参与草稿的重复绑定判定：一旦某个资源被固化进发布版本，
解绑后重新绑定同一资源会被误判为 409。新增 `selectDraftBinding`（仅 `release_id IS NULL`）
并在 `bindResource` 使用，草稿判定从此与版本快照互不干扰。

## 6. 未验证项

1. **版本回退**：`rollback`（把别名切回历史版本）与"运行沿用它开始的版本"的会话固定属 S03；
   本卡的 `publish` 已支持切换任意候选，回退所需的确定性重建与审核语义在 S03 补齐。
2. **评测执行器**：本卡交付评测**证据模型与门槛判定**；真正跑评测集、产出得分的执行器属 Q 系列
   （评测与质量门禁），当前得分由管理端调用方提交（通过与否不由调用方决定）。
3. **资源级停用**：目前"资源禁用"体现为服务版本绑定的解除；数据集/工具/知识库自身的停用状态
   在 D/K 系列接入同一个运行前闸门（`resolveForNewRun`）。
4. **调试与页面**：服务调试与上下文预算属 S04，服务配置页面属 S05。
