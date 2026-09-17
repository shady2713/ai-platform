# M06 模型管理页面证据（2026-09-17）

本记录是 [M06 交付模型管理页面](../tasks/M06.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| API 模块 | `apps/web-ele/src/api/ai/model-endpoint/index.ts`：列表/详情/创建/修改/启停/轮换/删除/版本/探测（POST）/最近探测（GET）/能力总览 |
| 页面数据 | `apps/web-ele/src/views/ai/model-endpoint/data.ts`：查询表单、创建编辑表单、轮换表单、列表列、能力与探测词汇、权限码常量 |
| 页面 | `views/ai/model-endpoint/index.vue`：列表 + 查询 + 创建/编辑 + 启停 + 轮换凭据 + 能力探测 + 删除（删除按钮对已引用端点禁用） |
| 弹窗 | `modules/form.vue`（创建/编辑，凭据留空即保留）、`modules/rotate.vue`（轮换）、`modules/probe.vue`（六类探测结论 + 可发布范围 + 重新探测） |
| 测试 | `api/ai/model-endpoint/index.test.ts`(4)、`views/ai/model-endpoint/data.test.ts`(6)、`views/ai/model-endpoint/index.test.ts`(5) |

## 2. 与卡片逐步实施的对应

1. **列表/创建编辑/启停/轮换/能力测试页面**：全部动作都在页面上有入口；
   探测走真实调用（POST）并在弹窗内展示六类结论、明细码、耗时与可发布范围。
2. **共享校验/反馈/CRUD 组合函数**：表单使用 `@vben/common-ui` 的 `z` 与项目 `useVbenForm`；
   提示统一走 `#/utils/feedback` 的 `showSuccessMessage`；删除/启停使用 `TableAction` 的 `popConfirm`
   （与 infra/system 页面同一套组合方式，未自造第二套）。
3. **菜单权限经 migration 登记、状态与秘密不回填**：权限码 `ai:model-endpoint:{query,create,update,delete}`
   由 V48、`ai:model-endpoint:probe` 由 V49 登记，页面按钮按同一组权限码显隐（用例断言常量值）；
   表单不含任何回填凭据的字段，只有 `InputPassword` 且提示"留空表示保留已有凭据"，
   列表只显示"已配置/未配置"（用例断言列名与占位文案）。

## 3. 验证结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `pnpm exec vitest run --dom apps/web-ele/src/views/ai apps/web-ele/src/api/ai` | 0 | 15 例通过（API 契约 4 + 页面数据 6 + 页面行为 5） |
| `pnpm run check:cspell` | 0 | 920 个文件 0 问题 |
| `pnpm --filter @vben/web-ele run typecheck` | 0 | 类型检查通过 |
| `sh .harness/verify.sh frontend`（`pnpm check` + `lint` + `test:coverage` + 构建 + 棘轮） | 0 | 全量前端门禁通过 |

**前端门禁结果**：`sh .harness/verify.sh frontend` 退出码 0 —— `pnpm check`（circular/dep/explicit-any/typecheck/cspell）、
`pnpm lint`、`pnpm test:coverage`、构建与前端覆盖率棘轮全部通过。新页面文件登记值：

| 文件 | 行覆盖率 |
|---|---|
| `api/ai/model-endpoint/index.ts` | 100% |
| `views/ai/model-endpoint/data.ts` | 99.46% |
| `views/ai/model-endpoint/index.vue` | 100% |
| `views/ai/model-endpoint/modules/form.vue` | 100% |
| `views/ai/model-endpoint/modules/probe.vue` | 97.87% |
| `views/ai/model-endpoint/modules/rotate.vue` | 94.33% |

组件用例共 20 例（API 契约 4 + 页面数据 6 + 页面行为 5 + 表单/轮换 6 中 3 + 探测 3），
覆盖创建/编辑不回填凭据、轮换携带乐观锁版本、探测结论与可发布范围展示、启停与删除。
## 4. 未验证项

1. **浏览器端到端验收**：按卡片约束，跨源/渲染/权限用例需在 Q06 与 G5 用真实浏览器补齐；
   本卡只交付组件级用例（挂载 + 交互 + 请求断言）。
2. **组织/部门数据权限**：AI 控制面表按功能权限管理（见数据权限豁免台账），
   页面未引入部门数据范围过滤。
3. **探测结果的实时刷新**：探测弹窗在打开时与点击"重新探测"时各拉取一次；
   多管理员并发探测的实时推送属后续卡片。
