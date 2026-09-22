# K09 知识库管理与检索调试页面证据（2026-09-23）

本记录是 [K09 交付知识库管理与检索调试页面](../tasks/K09.md) 的验收证据。
依赖 [K07](../tasks/K07.md)、[K08](../tasks/K08.md)、[F04](../tasks/F04.md) 均已有证据文档。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| API 模块 | `apps/web-ele/src/api/ai/knowledge/index.ts`：知识库 CRUD/启停/删除、文档上传（multipart）/删除/版本/任务/重试、检索/引用/原文读取 |
| 页面 | `views/ai/knowledge/index.vue`（知识库列表 + 动作）、`data.ts`（权限码/状态与原因映射/列/表单） |
| 子模块 | `modules/form.vue`（新增/编辑，标识与嵌入模型编辑态禁用）、`modules/documents.vue`（上传/状态/版本/任务/重试/删除/原文预览）、`modules/debug.vue`（检索调试） |
| 菜单 | 迁移 `V75__ai_knowledge_debug_menu.sql`（4096 `ai:knowledge:debug`）+ SQL 快照 V75 + `permission-catalog.json` 的 `unusedCatalog` 登记 |
| 测试 | `data.test.ts` 6 例（权限码、状态/原因映射、关注态、可见性、编辑禁用、列映射）、`index.test.ts` 3 例（分页查询、权限码、启停/删除）、`modules/modules.test.ts` 7 例（分页、上传、版本/任务面板、重试/删除、原文预览、权限码）、`api/ai/knowledge/index.test.ts` 4 例（各接口路径与 multipart 组装） |

## 2. 与卡片逐步实施的对应

1. **KB 授权、文档上传/状态/版本/失败重试/删除**：知识库页面提供 CRUD 与启停（标识/可见性/嵌入模型与维度编辑态禁用）；
   文档页提供"文件 + 幂等键 + 标题"上传（幂等键决定复用或新版本，界面无"强制覆盖"旁路）、
   状态与失败原因的可读映射、版本/任务面板、失败任务人工重试、删除（提示"先撤可见性、后台回收"）。
   知识库**授权**沿用 A03 授权目录（A09 的授权页面），本卡不新建第二套授权入口。
2. **检索调试使用指定有权主体并展示真实引用**：调试台只发问题、展示后端返回的候选与引用
   （含标题/版本/位置/片段）与过滤观测（候选数、复核丢弃数、检索知识库数），
   过滤条件由服务端按当前主体授权生成；界面**不能**构造过滤条件（无参数入口），
   引用片段回读走 `/ai/knowledge/citation`，原文预览按文档编号走受控 API `/ai/knowledge/document/content`。

## 3. 关键约束与安全语义

- **无管理权不可写**：所有写操作按钮带 `ai:knowledge:*` 权限码（create/update/delete/ingest），
  由框架按权限判定显示；后端仍会独立校验（前端隐藏不是安全边界）。
- **状态可理解**：`FAILED`/`DELETING`/`READY`/`PENDING`/`PARSING`/`INDEXING` 有中文文案，
  失败原因码有可读解释（如 `parse-ocr_required` → 扫描件需要 OCR），**未知状态与未知原因原样展示**
  （便于发现后端新增状态，不吞信息）。
- **需 OCR 可识别**：文档行有 `parseNote` 时高亮（`needsAttention`），提示扫描件需 OCR（首期不支持）。
- **原文预览走受控 API**：不直连存储、不拼路径；引用标识做 URL 编码。
- **检索调试不越权**：调试台与运行链路共用同一后端检索（授权目录 + 候选复核），界面无过滤条件入口。

## 4. 验收用例对照

| 验收项 | 结论 | 证据 |
|---|---|---|
| 无管理权不可写 | 写操作按钮均带 `ai:knowledge:*` 权限码 | `index.test.ts`（权限码断言）、`modules.test.ts`（ingest/delete/version/query） |
| 失败/删除中/需 OCR 状态可理解 | 状态与原因映射、关注态高亮、未知值原样展示 | `data.test.ts`（4 组断言）、`modules.test.ts`（面板展示失败原因） |
| 原文预览走受控 API | 预览按钮调用 `/ai/knowledge/document/content` 并打开二进制 | `modules.test.ts`（`readDocumentContent` 断言）、`api/ai/knowledge/index.test.ts` |
| 菜单 | 迁移 4096 + 快照 + 权限目录登记 | `V75__ai_knowledge_debug_menu.sql`、`check-data-lifecycle`/`check-permission-catalog` 通过 |
| 状态与授权组件测试 | 14+ 例组件/数据用例 | 上表四处测试文件 |

## 5. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `pnpm vitest run apps/web-ele/src/views/ai/knowledge apps/web-ele/src/api/ai/knowledge` | 0 | **20 例通过** |
| `pnpm --filter @vben/web-ele run typecheck` | 0 | 类型检查通过 |
| `node scripts/check-explicit-any.mjs` | 0 | 显式 any 6/8（未增加） |
| `node scripts/check-data-lifecycle.mjs` / `check-permission-catalog.mjs` | 0 / 0 | 快照 V75 同步、权限目录一致 |

## 6. 顺带修复的依赖缺口（真实暴露）

1. **快照脚本产生重复 `VALUES`**：生成 V75 菜单块时把 INSERT 头与 VALUES 行都带上了，得到
   `VALUES VALUES (...)`，`AuthenticationMigrationIT` 加载快照时报语法错误。已修正并重跑该用例通过。
2. **API 模块的导出形态**：函数最初写在 `namespace` 内，与既有模块（`api/ai/data`）的"类型在 namespace、函数在顶层"形态不一致，导致导入报错；已统一。
3. **`TableAction` 不接受 `type: 'link'`**：按仓库既有类型改用默认按钮类型。
4. **显式 any 棘轮**：测试替身里的 `as any[]` 使棘轮从 8 升到 10；改为显式 `ActionStub` 接口后回落到 6。

## 7. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、字段目录、权限目录、生命周期、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单测、格式、架构与覆盖率检查通过（本卡未改后端主代码） |
| `sh .harness/verify.sh frontend` | 见第 5 节 | 依赖/类型/拼写、lint、覆盖率与生产构建通过；首跑仅尾部棘轮未登记新文件，登记后复跑通过 |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 登记本卡新增前端文件；无下调、无删除 |

## 8. 未验证项

1. **真实浏览器验收**：本卡只做组件测试（vitest + happy-dom）；跨源、渲染与权限的浏览器验收由 Q06/G5 用真实浏览器补齐。
2. **上传链路的端到端交互**：组件测试断言了上传按钮的权限码与入口存在，真实的"选文件 → 上传 → 状态变化"链路
   由 K03 的服务端用例与阶段验收覆盖（未在浏览器里验证）。
3. **知识库授权入口**：授权沿用 A09 的授权页面（A03 授权目录），本卡未在知识库页面内嵌授权编辑（避免第二套授权入口）。
4. **检索调试的真实模型问答**：调试台只展示检索与引用（确定性链路）；模型回答与引用校验的端到端表现由 Q04/Q10 评测。
