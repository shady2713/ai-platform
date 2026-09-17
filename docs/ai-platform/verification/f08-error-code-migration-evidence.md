# F08 错误码与迁移规范证据（2026-09-17）

本记录是 [F08 建立 AI 字段错误码与迁移规范](../ai-platform/tasks/F08.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 错误码区间与 HTTP 映射 | `module-ai/enums/AiErrorCodeRanges.java`（1_003_xxx_xxx 与七个子区间）、`AiErrorCodeConstants.java`（12 个已冻结错误码） |
| 错误码映射契约 | `docs/contracts/ai/error-code-map.md`（区间分配 + 逐条 HTTP 语义） |
| 公用字段规则（后端） | `module-ai/enums/AiFieldRules.java`：三条业务键正则（Pattern 常量）+ 幂等键/消息长度 + 校验方法 |
| 公用字段规则（前端） | `apps/web-ele/src/adapter/field-rules.ts`：同值正则与长度常量 + `isAi*Value` 校验 |
| 字段目录登记 | `docs/contracts/field-catalog.yaml` 新增 `ai_service_key` / `ai_conversation_key` / `ai_run_key`（双端实现指向真实常量） |
| 生命周期与迁移策略 | `docs/data-lifecycle.md` 新增"AI 中台表策略"：分类规则、权限分类、迁移编号流程、引用规则 |
| 契约测试 | `AiFieldAndErrorCodeContractTest`（4 例）、前端 `field-rules.test.ts` 新增 3 例 |

## 2. 关键规则

1. **区间独占**：AI 中台使用 `1_003_xxx_xxx`，与框架（`1_001`）和 system（`1_002`）不交叉；子区间按能力域划分，
   新增域领新子区间，编号语义发布后不可更改。
2. **前端/后端/目录三方一致**：字段正则与长度在后端 `AiFieldRules`、前端 `field-rules.ts`、
   目录 `field-catalog.yaml` 三处登记，漂移由 `check-field-catalog.mjs` 阻断（演示见第 3 节）。
3. **迁移编号流程**：AI 首个建表迁移从 V48 起（V47 已被 F06 使用）；一卡一号、不预占、不改历史迁移、
   每次新增迁移同步快照，否则 `check-data-lifecycle.mjs` 失败。
4. **表分类先冻结**：运行/任务类不做逻辑删除（保留期清理），配置类使用逻辑删除并登记
   `data-lifecycle.json`；AI 表必须登记数据权限分类，不允许"未分类表"。

## 3. 门禁识别演示（可复核的拒绝证据）

| 演示 | 注入 | 门禁输出 | 还原后 |
|---|---|---|---|
| 未分类表 | 临时迁移 `V48__temp_probe.sql` 创建 `ai_temp_probe` | `FAIL 最终表 未登记：ai_temp_probe`、`FAIL 快照声明版本必须与最新 Flyway 迁移 V48 一致，当前：47`、`FAIL 快照表 登记项不存在：ai_temp_probe`（3 项失败） | 删除探针后 `生命周期/外键漂移检查通过` |
| 字段漂移 | 前端把 `AI_RUN_KEY_REGEX` 的 `{3,35}` 改成 `{3,64}` | `FAIL ai_run_key.implementations.frontendShared 与代码不一致：目录 /^run_[A-Za-z0-9_-]{3,35}$/，代码 /^run_[A-Za-z0-9_-]{3,64}$/` | 还原后 `一致性比对通过 39 项，失败 0 项` |

> 端点授权侧由既有 `EndpointAuthorizationContractTest` 持续阻断"未声明授权策略的映射"（见 F05 ADR 0049），
> 本卡未重复新增该演示；错误码唯一性由 server 的 `ErrorCodeUniquenessTest` 覆盖（AI 码已纳入扫描范围）。

## 4. 验证结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `node scripts/check-field-catalog.mjs` | 0 | 字段 9 个、一致性比对 39 项、失败 0 |
| `node scripts/check-data-lifecycle.mjs` | 0 | 39 张表全部登记、快照同步 |
| `./mvnw -pl basic-framework-module-ai verify` | 0 | 6 例通过；行覆盖率门槛达成（含新枚举/常量类） |
| `vitest run apps/web-ele/src/adapter/field-rules.test.ts` | 0 | 15 例通过（含 3 个 AI 字段用例） |
| `sh .harness/verify.sh contracts` / `backend` / `frontend` | 见交接记录 | 全量门禁复验 |

## 5. 未验证项

1. AI 表的实际创建与权限分类登记随各自实现任务（M01/A02/K02…）落地，本卡只冻结流程与分类原则。
2. 各能力域剩余的、尚未实现的错误码（模型凭据、知识解析、连接器、报表）在对应任务中按子区间追加，
   追加时必须同步 `error-code-map.md`（本卡未预占编号）。
3. HTTP 映射的运行时行为（400/403/404/409/429/502 实际响应码）需在对应端点实现后由集成/契约测试验证；
   本卡只冻结映射表与现有错误码常量。
