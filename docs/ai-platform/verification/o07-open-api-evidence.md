# O07 开放 API 规范与契约回归证据（2026-09-20）

本记录是 [O07 生成开放API规范与契约回归](../tasks/O07.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| OpenAPI 规范 | `docs/integrations/open-api/ai-open-api.json`：OpenAPI 3.1，**仅含开放端点**（`/app-api/ai/**`，20 个路径 / 32 个 Schema），带请求与响应示例 |
| 接入文档 | `docs/integrations/open-api/README.md`：认证流程、状态码语义、SSE 语义、公开文档红线与扩展流程 |
| 请求/响应与事件夹具 | `docs/integrations/open-api/examples/`：`run-accept.request.json`、`run-accept.response.json`、`run-progress.response.json`、`run-event.json`、`error-401/403/429.json`、`run-events.stream.txt`（SSE 样例，含心跳注释） |
| 漂移与暴露检查 | `basic-framework-server/src/test/java/com/basicframework/server/AiOpenApiContractTest.java`：7 例，覆盖双向漂移、公开文档红线、状态码登记、夹具结构与僵尸 Schema |
| 规范分组 | 只登记开放端点（`controller.app` 包下所有 `@RestController` 的实际映射），管理端接口不在文档内 |

## 2. 与卡片逐步实施的对应

1. **生成仅包含开放端点的 OpenAPI 分组和正式示例**：规范按 `controller.app` 包的实际映射逐条登记
   （认证换票、会话 9 个、运行 5 个、任务 3 个、文件 3 个），每个端点带 summary/description/参数/请求体/
   响应与示例；示例同时以文件形式落在 `examples/`，供文档与测试共用。
2. **登记 HTTP202/502/503/504 与 SSE 响应契约**：受理运行登记 200（`status=ACCEPTED`，语义即"已受理"）
   与 400/409/**502/503/504**（上游失败/依赖不可用/超时，均为稳定错误且可重试）；
   通用错误登记 401/403/429；事件流登记 `text/event-stream`，说明里写明"先鉴权再开流"与"心跳是注释、不推进序号"。
3. **加入规范漂移与未授权接口暴露检查**：`AiOpenApiContractTest` 双向比对"规范登记的端点"与
   "代码里实际的开放端点"（文档多登记 → 未实现；代码多实现 → 未登记），并扫描公开文档红线
   （管理端路径 `/admin-api`、秘密字段如 `credential`/`appSecret`/`tokenDigest`/`inputDigest` 等）。
   漂移与红线检查都是纯函数，测试用**合成输入**证明"漂移会被拒绝"，而不是只断言当前是绿的。
4. **冻结已实现核心接口，后续卡片逐项补全**：README 的"扩展流程"固定了顺序（先改规范与夹具 → 再改实现 →
   契约测试校验），本卡只冻结已实现的核心接口，Q10 在此基础上验证完整发布目录。

## 3. 关键约束与安全语义

- **公开文档不含管理/秘密字段**：规范里不出现任何 `/admin-api` 路径；秘密字段名（凭据、秘密、摘要、
  连接串相关）一律禁止，**唯一例外**是换票响应的 `token`（"只返回一次"的语义，已在 Schema 描述中显式说明），
  且该例外按 `Schema.field` 精确登记——同一字段出现在别的 Schema 里同样会失败（测试用合成文档证明）。
- **请求/响应夹具匹配规范**：夹具按必填键、类型与枚举校验（幂等键长度、数据分级枚举、运行业务键正则、
  任务状态枚举、错误响应码等），SSE 样例必须含事件帧、心跳注释与序号。
- **401/403/429 状态真实**：这三个状态码在规范中逐端点登记，并与 `docs/contracts/ai/error-code-map.md`
  的错误码语义一致（未认证 401、无权限 403、限流 429）。
- **无僵尸契约**：规范里不允许存在没有任何引用的 Schema（信封 Schema 引用数据 Schema 也算引用）。

## 4. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -o -pl basic-framework-server test -Dtest=AiOpenApiContractTest` | 0 | 7 例通过（双向漂移、漂移拒绝、红线、红线拒绝、状态码登记、夹具结构、僵尸 Schema） |
| `sh .harness/verify.sh contracts` | 0 | 契约台账、权限目录、生命周期、字段目录、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单元测试、格式、架构与覆盖率检查通过 |
| `sh .harness/verify.sh integration` | 0 | 44 个 IT 类 / 127 例全绿（本卡未新增主代码文件，棘轮无需登记） |
| `node scripts/check-coverage-ratchet.mjs all` | 0 | 基线无新增、无下调 |

## 5. 顺带修复的依赖缺口

本卡未发现需要顺带修复的既有缺口。规范以"实际映射"为唯一事实来源（从控制器注解推导端点集合），
因此不存在"手写文档与代码各说各话"的空间；发现的两处自身问题（测试工作目录的仓库根层级、
僵尸 Schema 检查只扫描 paths）已在实现过程中修正。

## 6. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 全部通过 |
| `sh .harness/verify.sh backend` | 0 | 全部通过（含新增契约测试） |
| `sh .harness/verify.sh integration` | 0 | 全绿（无新主文件，棘轮直接通过） |
| `node scripts/check-coverage-ratchet.mjs all` | 0 | 基线未变化 |

## 7. 覆盖率

本卡只新增文档、夹具与一个测试类，未新增生产代码文件，因此不产生棘轮登记项；
覆盖率基线保持不变（`check-coverage-ratchet.mjs all` 通过）。

## 8. 未验证项

1. **运行时 OpenAPI 文档比对**：本卡校验的是"签入的规范 vs 控制器映射"；把运行时 springdoc 分组
   与签入规范做逐字节比对属 Q10（完整发布目录验证）范围。
2. **示例的可执行性**：夹具是结构与语义校验，未对真实服务发起调用；在线调试（使用短期受限测试票据）
   属 O08。
3. **后续能力域的端点**：知识库、数据集、报表、主题等端点在各自卡片实现时按本规范"扩展流程"补入，
   本卡只冻结已实现的核心接口。
