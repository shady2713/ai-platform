# S05 服务配置发布调试页面证据（2026-09-19）

本记录是 [S05 交付服务配置发布调试页面](../tasks/S05.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 服务页面 | `apps/web-ele/src/views/ai/service/index.vue`：服务列表 + 草稿编辑 / 标记可发布 / 发布与评测 / 版本历史与回退 / 调试 / 删除，动作权限码与迁移种子一一对应 |
| 草稿编辑器 | `views/ai/service/modules/form.vue`：模型端点、提示词、输入/输出 Schema、所需能力、运行主体、评测门槛，以及草稿资源绑定/解绑（越权由后端拒绝） |
| 发布与评测 | `views/ai/service/modules/release.vue`：创建候选 → 记录评测 → 预检查阻塞项 → 发布；阻塞项非空时发布按钮禁用 |
| 版本历史与回退 | `views/ai/service/modules/versions.vue`：当前生效版本、历史版本状态、评测记录与**回退影响说明**，回退后刷新当前版本 |
| 调试区 | `views/ai/service/modules/debug.vue`：显式测试主体 + 数据分级 + 业务上下文，展示阶段摘要、分区统计与可见输出；与发布结果完全分开呈现 |
| 数据与规则 | `views/ai/service/data.ts`：权限码、状态文案、`validateJsonObjectSchema`（即时提示）、`currentRelease`、`describeRollbackImpact`、`describeReleaseContent`、各表单 schema |
| API 客户端 | `api/ai/service/index.ts`：草稿、资源绑定、发布/评测/回退/停用、运行解析与调试，路径与后端控制器一一对应 |
| 组件与契约测试 | `api/ai/service/index.test.ts`(6)、`views/ai/service/data.test.ts`(6)、`index.test.ts`(6)、`modules/modules.test.ts`(9)，共 27 例 |

本卡**不需要新的 Flyway 迁移**：页面使用的权限码（`ai:service:query/create/update/delete/publish/bind/release/activate/evaluate/debug`）
全部由 V56–V58 迁移种子提供，未新增表或字段，因此 `数据库文件/basic_framework.sql`、生命周期台账与豁免台账不变。

## 2. 与卡片逐步实施的对应

1. **模型/提示词/资源/Schema/执行限制编辑器**：草稿表单覆盖模型端点、提示词模板、输入/输出 Schema、
   所需能力、运行主体与评测门槛；资源绑定区在同一弹窗内增删绑定（绑定/解绑各走真实接口，
   越权绑定由后端 A03 判定拒绝）。Schema 字段变化即校验（`validateJsonObjectSchema`），
   非法时显示"不是合法 JSON / 必须是 JSON 对象"提示，并在提交前再次拦截；
   **后端仍会独立拒绝**（S01 已在草稿保存与候选创建两处做 JSON 对象校验，S02 证据第 2 节）。
2. **调试与发布结果明确区分**：调试是独立弹窗与独立权限（`ai:service:debug`），
   结果区只渲染阶段摘要（RESOLVE/AUTHORIZE/CONTEXT/MODEL）、分区统计与可见输出，
   并明示"使用当前生效版本与显式测试主体、不回显提示词正文"；发布结果只在发布弹窗内展示
   （候选/评测/预检查/版本列表），两个结果不会混在同一个区域。
3. **版本历史和回退显示具体影响**：版本弹窗列出全部版本与状态（生效中/候选/已退役），
   选中版本后展示回退影响：`当前生效版本 → 目标版本`、"只影响后续新运行"、
   "已固定版本的会话仍按原版本执行"、"历史版本不会恢复旧权限"、"预检查未通过时不改变当前生效版本"，
   并给出该版本的评测记录；回退调用成功后重新拉取版本列表，**当前生效版本随之更新**（用例断言 v2 → v1）。

## 3. 关键约束与安全语义

- **无发布权不能发布**：发布/回退入口与"标记可发布"分别绑定 `ai:service:release`、
  `ai:service:activate`、`ai:service:publish`（与 V56–V58 种子一致），无权用户看不到入口；
  后端方法上的 `@PreAuthorize` 仍然独立校验（S03 控制器契约测试断言）。
- **即时提示不是放行依据**：前端 Schema 校验只做提示与提交前拦截，判定权始终在后端；
  非法 Schema 既不会因为前端"没提示"而通过，也不会因为前端"提示了"而被前端改写。
- **调试不越权、不回显正文**：调试请求必须显式给出测试主体与数据分级；
  平台按该主体的当前授权判定绑定动作（S04 服务层实现），页面不提供"以管理员身份调试"的入口；
  调试结果区不包含拼装后的提示词（S04 的 VO 字段名单里没有 `prompt`/`reasoning`）。
- **回退影响对操作者可见**：回退不是"再发布一次"的别名，页面明确写出"只影响后续运行"与
  "不恢复旧权限"，避免操作者误以为回退能恢复历史授权。
- **失败不伪造成功**：调试调用失败时只显示失败提示，不渲染任何结果块（用例断言）。

## 4. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `pnpm exec vitest run --dom apps/web-ele/src/api/ai/service apps/web-ele/src/views/ai/service` | 0 | 27 例通过（API 契约 6、数据规则 6、页面 6、模块 9） |
| `pnpm check` | 0 | 依赖/循环/显式 any/类型/拼写检查通过 |
| `pnpm lint` | 0 | prettier + eslint 通过（含 perfectionist 排序与 unicorn 规则） |
| `sh .harness/verify.sh frontend` | 1 → 0 | 类型、lint、覆盖率测试与构建全部通过（构建产物正常）；唯一失败是尾部棘轮（新文件未登记，属预期），`--update` 登记后复验通过 |

## 5. 顺带修复的依赖缺口

本卡未发现需要顺带修复的后端缺口：S03（回退与运行解析）、S04（调试与上下文预算）交付的接口
已满足页面所需的全部数据（版本列表含状态与乐观锁版本、预检查返回阻塞项文案、
调试返回阶段摘要与分区统计）。页面因此没有新增任何后端接口或契约字段。

## 6. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、权限目录、生命周期、字段目录、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单元测试、格式、架构与覆盖率检查通过 |
| `sh .harness/verify.sh frontend` | 1 → 0 | 类型/lint/覆盖率/构建通过；唯一失败是尾部棘轮（新文件未登记，属预期），`--update` 后复验通过 |
| `sh .harness/verify.sh integration` | 0 | 37 个 IT 类 / 101 例全绿（含后端棘轮） |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 新增 7 个前端文件（最低 85.51%）、后端 0 项；无基线下调、无登记删除，复验通过 |

## 7. 覆盖率

新增前端文件（数字取自 `coverage/coverage-summary.json`，行覆盖率）：

| 文件 | 行覆盖率 |
|---|---|
| `api/ai/service/index.ts` | 100% |
| `views/ai/service/data.ts` | 85.51% |
| `views/ai/service/index.vue` | 94.05% |
| `views/ai/service/modules/debug.vue` | 92.63% |
| `views/ai/service/modules/form.vue` | 91.5% |
| `views/ai/service/modules/release.vue` | 88.19% |
| `views/ai/service/modules/versions.vue` | 91.66% |

## 8. 未验证项

1. **浏览器端到端**：本卡按 F04 的组件测试基线交付；跨源、渲染与真实权限的浏览器验收在
   Q06 建立浏览器测试后补齐（任务卡明确要求不得以组件测试替代最终浏览器验收）。
2. **真实模型输出**：调试区的成功路径需要可用模型端点；本机与 CI 未装配模型客户端工厂，
   因此端到端只验证到"上游失败以稳定错误结束、不返回假成功"（S04 的 IT 同样覆盖）。
3. **服务页面在权限矩阵下的可见性**：前端以 `auth` 声明入口权限，真实角色-权限矩阵的
   端到端验证属 A08/Q 系列的门禁范围。
