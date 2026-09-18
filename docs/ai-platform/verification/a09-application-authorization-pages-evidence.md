# A09 应用接入与授权页面证据（2026-09-18）

本记录是 [A09 交付应用接入与授权页面](../tasks/A09.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 应用 API 模块 | `apps/web-ele/src/api/ai/application/index.ts`：分页/详情/创建/修改/启停/轮换/吊销/删除（与 A01 控制器一一对应） |
| 授权 API 模块 | `apps/web-ele/src/api/ai/grant/index.ts`：分页/详情/新增/改动作白名单/撤销（与 A03 控制器一一对应） |
| 应用接入页面 | `views/ai/application/{index.vue,data.ts}` + `modules/{form.vue,rotate.vue,secret.vue}`：精确 Origin 校验、启停、轮换/吊销、删除 |
| 授权页面 | `views/ai/authorization/{index.vue,data.ts}` + `modules/form.vue`：新增/改动作白名单/撤销，变更提示作用范围 |
| 菜单对齐 | 迁移 `V55__ai_authorization_menu_component.sql`：把 V52 种子的组件路径从 `ai/grant/index` 对齐到 `ai/authorization/index`（权限码不变）；快照同步 |
| 测试 | 应用 `data.test.ts`(4) + `index.test.ts`(4) + `modules/modules.test.ts`(4)、授权 `data.test.ts`(5) + `index.test.ts`(4) + `modules/form.test.ts`(4)、两个 API 契约测试(3+2) |

## 2. 与卡片逐步实施的对应

1. **应用创建/Origin/凭据轮换/服务及资源授权页面**：
   - 应用页支持创建（精确 Origin 多行输入 + 前端逐行校验，规则与后端 `ApplicationOrigins` 一致）、
     编辑（appCode 只读语义：后端禁止修改）、启停、轮换凭据、吊销凭据、删除；
   - 授权页支持按应用/主体类型/外部用户/资源类型查询，新增授权、修改动作白名单（自动带乐观锁版本）、撤销。
2. **secret 只一次展示且离开清理**：创建与轮换的响应把秘密交给 `modules/secret.vue` 展示，
   弹窗关闭时清空明文（组件内 `onOpenChange(false)` 清值，用例断言"再次打开不残留上一次明文"）；
   列表只显示"已配置/未配置"，表单不含回填字段（用例断言）。
3. **授权变更提示作用范围并使用独立权限**：新增/修改/撤销三处都展示
   "立即生效、撤销后新请求与历史产物读取被拒绝、修改递增授权版本"提示；
   页面按钮使用独立权限码 `ai:grant:{create,update,revoke,query}`（与 V52 种子一致，用例断言），
   无权用户看不到入口（服务端 `@PreAuthorize` 仍是最终门禁）。

## 3. 关键约束与安全语义

- **无权用户不可授权他人**：授权页的所有写入口都带独立权限码（`ai:grant:*`），
  且服务端 A03 控制器逐接口 `@PreAuthorize`；前端隐藏不是安全边界，仅减少误操作。
- **多选资源只列有权项**：资源标识选项来自"该主体已有授权"（`getGrantPage` 结果去重），
  列表项标注"已授权 · 资源类型"；新标识必须显式输入并二次确认（确认框同时展示作用范围提示），
  确认取消则不发起请求（用例断言）。
- **刷新不回显 secret**：秘密只存在于一次性响应与弹窗内；页面不缓存、列表不展示、
  接口层没有"读取凭据"的端点（A01 语义）。
- **撤销/停用提示具体后果**：popConfirm 文案写明"停用后无法换票与调用""吊销后旧秘密立即失效"。

## 4. 验证结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `pnpm exec vitest run --dom apps/web-ele/src/views/ai apps/web-ele/src/api/ai` | 0 | 44 例通过（A09 新增 18 例） |
| `pnpm exec eslint apps/web-ele/src/views/ai apps/web-ele/src/api/ai` | 0 | 无 error/warning |
| `sh .harness/verify.sh frontend`（check/lint/test:coverage/构建/棘轮） | 见交接记录 | 全量前端门禁 |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 新页面文件基线登记（新文件下限 80%）并复验通过 |
| `sh .harness/verify.sh contracts` / `backend` / `integration` | 0 / 0 / 0 | 迁移 V55 与快照同步通过全量后端门禁 |

## 5. 未验证项

1. **浏览器端到端**：按卡片约束，跨源/渲染/权限用例需在 Q06 与 G5 用真实浏览器补齐；
   本卡交付组件级用例（挂载 + 交互 + 请求断言）。
2. **服务与资源授权的"服务"维度**：AI 服务（S 系列）与工具/数据集资源尚未落地，
   授权页当前覆盖 REPORT/KNOWLEDGE_BASE/FILE/TOOL/DATASET 五类词表，服务级授权随 S 系列扩展。
3. **Origin 的后端二次校验**：前端逐行校验与后端一致；真实的跨源请求校验在 A04 票据链路（已交付）。
