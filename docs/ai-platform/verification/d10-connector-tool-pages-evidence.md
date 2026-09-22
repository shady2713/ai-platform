# D10 连接器语义与工具管理页面证据（2026-09-22）

本记录是 [D10 交付连接器语义和工具管理页面](../tasks/D10.md) 的验收证据。
依赖 [D04](../tasks/D04.md)（数据集语义版本）、[D08](../tasks/D08.md)（工具注册与政策）、
[D09](../tasks/D09.md)（工具确认与步骤调度）、[F04](../tasks/F04.md)（前端工作区与请求规范）均已有证据文档。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 数据接入域 API | `apps/web-ele/src/api/ai/data/index.ts`：连接器/数据集/工具三个命名空间的类型化接口，路径与后端控制器一一对应 |
| 连接器页面 | `views/ai/connector/{index.vue,data.ts}` + `modules/{form.vue,operations.vue}`：列表/声明式配置表单/连接测试/接口导入与发布/试跑 |
| 数据集页面 | `views/ai/dataset/{index.vue,data.ts}` + `modules/{form.vue,versions.vue}`：来源声明/语义版本草稿/验证（漂移原因）/发布 |
| 工具页面 | `views/ai/tool/{index.vue,data.ts}` + `modules/{form.vue,versions.vue}`：注册/启停/版本与执行政策（AUTO/CONFIRM/DENY） |
| 测试 | `api/ai/data/index.test.ts`(5)、三个 `data.test.ts`(14)、三个 `index.test.ts`(14)、三个 `modules.test.ts`(13)，合计 **46 例** |

本卡**不新增数据库迁移**：菜单 4050/4060/4080 已在 D01/D04/D08 落地，且 component 路径
（`ai/connector/index`、`ai/dataset/index`、`ai/tool/index`）与本卡页面位置一致，无需改快照或台账。

## 2. 与卡片逐步实施的对应

1. **连接测试、operation 草稿选择、表/视图发现和字段口径编辑**：
   - 连接器页面提供"连接测试"命令，结论原样展示稳定原因码（如 `TARGET_NOT_ALLOWED`）与耗时，
     不翻译成模糊的"成功/失败"；
   - 接口管理弹窗只接受**导入 OpenAPI 文档**产生草稿，草稿必须显式"发布"后才可"试跑"，
     试跑只能填该操作**声明过的参数**（界面展示参数名与必填标记）；
   - MySQL 连接器配置只填主机/端口/库/只读账号/传输模式/**授权对象**（每行一个 `schema.table`），
     表单把这些结构化字段拼成声明式 `configJson`——没有整段连接串输入框。
2. **发布前展示权限/粒度/能力检查**：数据集版本弹窗先"验证"再"发布"，
   验证结论把漂移讲清楚（上游缺少列 / 类型不再兼容 / 仅新增列），不可发布时**带原因**展示；
   工具版本弹窗把政策与类型写在同一行（如"只读工具 · 需人工确认后执行"），
   发布失败（写工具、来源未发布）原样展示后端原因。
3. **工具政策和版本界面使用明确命令**：政策在版本创建时**显式选择**（缺省 DENY），
   界面没有"临时放开一次"之类的旁路；版本列表展示政策、来源与状态，发布是独立命令。

## 3. 关键约束与安全语义

- **无权限不能连接/发布**：所有命令按钮都带 `auth: [权限码]`（与 V66/V67/V68/V70 菜单种子一致），
  无权用户看不到入口；后端仍二次校验（本卡不承担授权判定，只做入口可见性）。
- **漂移和不可执行显示原因**：`describeVerification` 把"缺列/类型不兼容/仅新增列"分别表述；
  验证与发布失败都把后端稳定原因（含错误码语义）展示出来，不做静默重试。
- **界面不开放任意 SQL/脚本执行**：
  - 三张页面的表单与版本 schema 字段名经过测试断言：不含 `sql`/`script`/`statement` 类字段；
  - 连接器试跑请求体只允许 `connectorId`/`operationKey`/`arguments`（测试断言键集合），
    没有 URL、请求头、方法、SQL 输入面；
  - 数据集"语义定义"是声明式 JSON（粒度/字段/指标/维度/单位/权限策略），页面明确标注"不是 SQL"；
  - 工具版本的输入/输出 schema 同样是声明式 JSON，执行政策与来源都来自版本快照。
- **复用工作区规范**：`useVbenVxeGrid` + `TableAction` + `useVbenModal`/`useVbenForm`（与 S05/O08 同一套），
  未新增显式 `any`（显式 any 棘轮 6/8 通过）、未使用 `v-html`/`innerHTML`。

## 4. 验收与失败分支对照

| 验收项 | 覆盖点 | 证据 |
|---|---|---|
| 无权限不能连接/发布 | 命令按钮权限码与菜单种子一致 | 三个 `index.test.ts` 的权限断言 + 三个 `data.test.ts` 的权限常量断言 |
| 漂移和不可执行显示原因 | 缺列/类型不兼容原因、发布失败原因 | `dataset/data.test.ts`（describeVerification 四态）、`dataset/modules.test.ts`（漂移与发布失败展示）、`tool/modules.test.ts`（写工具发布失败原因） |
| 界面不开放任意 SQL/脚本执行 | 无 SQL/脚本字段、试跑请求面固定 | `connector/data.test.ts`、`dataset/data.test.ts`、`tool/data.test.ts`（字段名断言）+ `api/ai/data/index.test.ts`（试跑载荷键集合） |
| 状态测试（加载/空/失败/失权/销毁） | 空结果、失败原因、权限入口、弹窗销毁 | `index.test.ts` 的空结果与失败分支、`modules.test.ts` 的 `destroyOnClose` 弹窗与失败分支 |

## 5. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `pnpm vitest run apps/web-ele/src/views/ai/connector apps/web-ele/src/views/ai/dataset apps/web-ele/src/views/ai/tool apps/web-ele/src/api/ai/data` | 0 | 46 例通过（10 个测试文件） |
| `pnpm exec eslint <新目录>` | 0 | 无 lint 错误（含 `no-non-null-assertion`、正则与模板格式规则） |
| `pnpm run check:type` | 0 | 37/37 任务通过（含 `vue-tsc` 全量 typecheck） |
| `pnpm run check:explicit-any` | 0 | 显式 any 棘轮 6/8 通过 |
| `pnpm exec cspell lint <新文件>` | 0 | 13 个文件 0 拼写问题 |
| `sh .harness/verify.sh contracts/backend/frontend/integration` + 棘轮 | 见第 7 节 | 见第 7 节 |

新增文件覆盖率（`coverage/coverage-summary.json`，行覆盖）：

| 文件 | 行覆盖 |
|---|---|
| `api/ai/data/index.ts` | 100% |
| `views/ai/connector/data.ts` | 100% |
| `views/ai/connector/index.vue` | 96.47% |
| `views/ai/connector/modules/form.vue` | 96.34% |
| `views/ai/connector/modules/operations.vue` | 92.59% |
| `views/ai/dataset/data.ts` | 100% |
| `views/ai/dataset/index.vue` | 91.78% |
| `views/ai/dataset/modules/form.vue` | 93.84% |
| `views/ai/dataset/modules/versions.vue` | 95.37% |
| `views/ai/tool/data.ts` | 100% |
| `views/ai/tool/index.vue` | 91.78% |
| `views/ai/tool/modules/form.vue` | 93.75% |
| `views/ai/tool/modules/versions.vue` | 94.61% |

## 6. 顺带修复的缺口（自查）

1. **测试误伤其他卡片文件（自查并已确认无影响）**：批量脚本按通配符改写测试文件时曾把
   其他页面的测试文件一并重写（内容未变，`git status` 无差异）——后续批量改写限定到本卡目录。
2. **`#/utils/form` 不存在（typecheck 暴露）**：表单重置最初引用了工作区里不存在的工具模块，
   改为 `formApi.resetForm()`。
3. **模板格式规则冲突（lint 暴露）**：`vue/html-closing-bracket-newline` 与 prettier 对
   "多行属性 + 内联文本"的期望相反，导致同一处反复报错；改为用纯文本渲染参数标签
   （`parameterLabel()`），既消掉冲突也更易读。
4. **测试中的非空断言（lint 暴露）**：工作区禁止 `!` 断言，改为显式守卫助手
   （`requireElement`/`requireButton`/`requireModalConfig`），失败信息更明确。

## 7. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、权限目录、生命周期、字段目录、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单测、格式、架构与覆盖率检查通过 |
| `sh .harness/verify.sh frontend` | 1 | **仅尾部单文件棘轮**：依赖/类型/拼写检查、lint、前端覆盖率测试（**1707 例全通过**）与生产构建（11/11 任务）全部通过；13 个新增前端文件尚未登记基线 |
| `sh .harness/verify.sh integration` | 1 | 175 例中 174 例通过；1 例失败为 **D09 证据第 7 节已定位的既有系统用例**（`UserProfilePersistenceIT` 在负载下的自竞争锁等待），导致 `mvn clean verify` 提前结束 |
| `node scripts/check-coverage-ratchet.mjs frontend` | 1 | 与前端门禁一致：仅报告 13 个新增文件未登记（预期尾部棘轮） |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 1 / 1 | 因上一步后端聚合报告不完整而失败——**未下调任何基线、未删除任何登记**；D10 前端文件将在下一次完整运行中登记 |

### 门禁偏差说明（如实记录，未掩盖）

`--update` 需要**完整的** jacoco 聚合报告；后端集成门禁中那 1 例既有系统用例失败会中断 reactor，
使聚合报告不完整，因此 D10 的 13 个前端文件**本次未能登记基线**（前端自身覆盖率阈值检查已通过）。
该用例与本卡无关（D10 不含后端改动），定位与证据见
[d09 证据第 7 节](d09-tool-action-evidence.md)。本卡未采用跳过/排除用例的方式让门禁"变绿"。

## 8. 未验证项

1. **真实浏览器验收**：本卡只做组件级状态测试；跨源、渲染与权限的真实浏览器用例按计划在
   Q06 建立浏览器测试能力后补齐（卡片明确"早期组件测试不能替代最终浏览器验收"）。
2. **端到端联调**：页面调用的是真实后端路径（API 契约测试断言路径与方法），
   但"页面 → 后端 → 真实连接器/数据集"的端到端链路依赖 D11 黄金集验收与真实环境；
   本机出站策略默认拒绝，连接器试跑只能验证到"上游被拒 → 稳定原因码"。
3. **表/视图发现的可视化**：连接器页面的接口管理覆盖 operation 导入与发布；
   "表/视图发现"的可视化选择（MySQL 元数据浏览）在 D04 数据集来源选择里以授权对象文本形式表达，
   图形化浏览器未实现。
4. **移动端/多分辨率**：本卡按桌面管理端布局实现，未做响应式与移动端验证。
