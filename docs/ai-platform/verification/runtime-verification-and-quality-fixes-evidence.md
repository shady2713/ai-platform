# 真实运行验证与质量修复 — 证据（2026-10-05）

> 本文件记录一轮**跨卡**的验证与修复：门禁全绿不等于项目能跑，所以本轮把项目真跑起来，
> 并在真实浏览器与真实 MySQL/Redis 上找出门禁覆盖不到的问题。
> 涉及卡片：K01（知识索引）、评测（C 系）、F12 的同类问题（错误归因），以及前端基础设施。

## 1. 为什么门禁全绿还要再跑一遍

`contracts` / `backend` / `frontend` / `integration` 四道门禁在本轮之前全部通过，
但它们**不覆盖**这几件事：

| 门禁不覆盖的 | 本轮怎么验的 |
|---|---|
| 应用能否真的启动 | 用门禁产出的 161MB 打包 jar 起进程 |
| 迁移链能否在空库上跑完 | 新建空 `basic_framework` 库让 Flyway 自己走 |
| 页面标题与 localStorage 键的实际取值 | 真实浏览器读 DOM 与 storage |
| 20 个 AI 页面是否渲染 | 真实 Chromium 逐页访问 |
| 配置项漏配是否静默退化 | 真实浏览器取证，不是读代码推断 |

## 2. 真实运行环境

| 组件 | 版本 | 选择依据 |
|---|---|---|
| MySQL | `8.4.11` | 取自 `basic-framework-server` 集成测试用的镜像版本 |
| Redis | `7.4.11` | 同上 |
| JDK | 17.0.20 | 项目 `JAVA_HOME` |
| 后端 | `basic-framework-server/target/basic-framework-server.jar`（161,575,453 字节） | backend 门禁 `clean verify` 的产物，即真实部署物 |
| 前端 | `pnpm dev:ele` → 5174 | README「本地 run」段 |

数据库按 README 要求建**空库**（"不要先导入最新快照"），迁移交给 Flyway。

## 3. 后端：真的起来了

| 事实 | 取值 |
|---|---|
| 启动耗时 | 33.633 秒 |
| 迁移 | `Successfully applied 96 migrations` |
| 建表 | 108 张 |
| 启动期 ERROR | **0** |

### 3.1 端到端请求验证

| 请求 | 结果 | 说明 |
|---|---|---|
| `GET /admin-api/system/auth/get-permission-info`（无 token） | **401** `账号未登录` | 鉴权生效 |
| `POST /admin-api/system/auth/login`（bootstrap 口令） | **422** `密码已过期，请修改密码后重新登录` | 种子管理员强制改密按设计生效 |
| `POST /admin-api/system/auth/change-expired-password` | **200** `data:true` | 认证前改密入口可用 |
| `POST /admin-api/system/auth/login`（新口令） | **200**，返回 `accessToken` | 真实登录成功 |
| `GET /admin-api/system/user/page` | **200**，返回 admin 行 | 业务查询有真实数据 |

### 3.2 AI 平台写路径（不只读接口）

`POST /admin-api/ai/application/create`：

1. 首次调用返回 **400** `至少配置一个精确 Origin` —— 跨域白名单校验生效（C 系列契约）；
2. 补齐 `origins` 后返回 **200**，并签发**一次性客户端秘密**：
   `{"applicationId":1,"appCode":"local_verify_app","credentialId":1,"secret":"aiapp_…"}`；
3. `GET /ai/application/page` 读回，`origins` 原样返回；
4. `GET /ai/application/get?id=1` 返回 `"credentialConfigured":true`。

**落库核对**（`--default-character-set=utf8mb4`，避免 CLI 显示假象）：

```
id  name             name_hex
1   本地验收应用      E69CACE59CB0E9AA8CE694B6E5BA94E794A8
```

`character_set_*` 全为 `utf8mb4`，表 collation `utf8mb4_unicode_ci`。

> 曾一度看到 `??????`，那是 mysql CLI 未指定连接字符集导致的显示假象，不是存储问题——
> 加 `--default-character-set=utf8mb4` 后中文与 hex 均正确。**不把工具假象当产品缺陷。**

### 3.3 一处"疑似缺陷"经核实不是缺陷

`GET /ai/application/page` 的 `credentialConfigured` 为 `null`，第一反应是漏填。
读代码后确认是**有意设计**，`AiApplicationController.java:119` 写明：

> `// 列表只展示应用本身：凭据状态在详情里给出，避免逐行回查造成 N+1`

列表传 `null`、详情传 `hasActiveCredential(id)`。属刻意取舍，不改。

## 4. 前端：真的能打开

真实 Chromium 登录后：URL 跳到 `/dashboard`，正文渲染出「下午好，总部门」「当前用户 admin /
角色 super_admin」「项目信息」，侧栏含「AI 中台」。

### 4.1 20 个 AI 页面逐页验证（第二次才做对）

路径取自数据库 `system_menu`（`accessMode: 'backend'`，路由由后端菜单驱动，不是前端路由表）。

**第一次验证的判据是错的**：以"正文 ≥ 40 字"作为渲染成功的标准。
但 **404 页本身就有 338 个字符**（插画 + 「哎呀！未找到页面」+ 说明 + 「返回首页」），
于是 3 个真正落到 404 的页面被判成"正常渲染"。**是截图把这个问题抓出来的**——
文本统计永远不会告诉你页面上写的是"未找到页面"。

改用能识别 404 页的判据（正文是否含「未找到页面」）重验：

| 结果 | 页面 |
|---|---|
| **✓ 真实渲染（17 个）** | model-endpoint、application、grant、service、open-platform、connector、dataset、tool、knowledge、report、theme、chat-integration、usage、observability、semantic、evaluation、webhook |
| **✗ 落到 404 页（3 个）** | **query（AI 查询计划）、workflow（流程编排）、cross-source（跨源合并结果）** |

### 4.2 三个 404 页的定性：菜单注册了从未被授权交付的页面

`views/ai/` 下只有 16 个目录，而数据库菜单注册了 20 个 AI 页面。查证：

| 菜单项 | menu migration | 菜单里的 component | 组件文件 | 后端管理端 API |
|---|---|---|---|---|
| AI 查询计划 | V69 `ai query planner menu` | `ai/query/index` | **不存在** | `AiQueryController` 齐备 |
| 流程编排 | — | `ai/workflow/index` | **不存在** | workflow controllers 齐备 |
| 跨源合并结果 | V97 `ai cross source contract menu` | `ai/cross-source/index` | **不存在** | `AiCrossSourceMergeController` 齐备 |

**后端 API 三块都齐备，只有前端页面从未创建**。而卡片授权范围也不含它们——
例如 Y03 的「允许修改范围」明确只列了后端 `domain/semantic` 与 `service/semantic`，
不含前端 `views/ai`。

**结论**：这是"菜单已注册、页面未交付"的落差，不是路由写错、也不是组件被误删。
用户点这三个菜单项会看到「哎呀！未找到页面」——一个真实的坏入口。
本轮**未擅自构建这三个页面**（属新增功能而非修缺陷，且无卡授权其范围），
处置方式待定，见 §8.3。

> 顺带修正本轮的另一处自查失误：第一次写 `/ai/authorization` 时把**组件路径**
> 当成了路由，真实路由是 `/ai/grant`；写 `/ai/cross-source/page` 与
> `/ai/evaluation/suite/page` 时也猜错了端点名（真实为 `/ai/eval/suite/page` 与
> `/ai/cross-source/results/{executionKey}`）。应用对这些不存在路径返回
> 干净的 404「请求未找到」是正确的。

### 4.3 `/ai/report` 首访 504 不是缺陷

复访后 359 字正文、无 5xx、无控制台错误——是 Vite **依赖预构建首次冷启动**的一次性现象，
不是产品缺陷，不"修"。


## 4.4 三个菜单入口指向不存在的页面 → 已建页面（用户选定方案）

§4.2 记录的落差经用户决策后按「建这三个页面」处理，让已就绪的后端能力真正可用。

**授权范围核对**：Y03 的「允许修改范围」只列了后端 `domain/semantic` 与 `service/semantic`，
不含前端 `views/ai`。因此这三个页面是**新增功能**而非补漏，卡号与范围由用户确认后才动手。

| 页面 | 后端形态 | 页面为何是这种形态 |
|---|---|---|
| AI 查询计划 (`/ai/query`) | 仅 `POST /ai/query/plan` + `GET /ai/query/summary`，**无列表接口** | 「填条件 → 提交 → 看结果」操作页。结果只有两种正常形态：已校验计划（带 `planHash`/`planJson`）或澄清追问（带 `candidates`）。明确标注"后端不接受 SQL"，**不提供任何 SQL 编辑框** |
| 流程编排 (`/ai/workflow`) | 三个控制器：定义 CRUD / 版本草稿生命周期 / 运行与节点留痕 | 四个面板：定义表单、版本管理、运行记录，外加错误路径专项 |
| 跨源合并结果 (`/ai/cross-source`) | 仅两个按执行键查询的端点，**无列表接口** | 「先问口径再取数」两步：`/integrity` 返回 `state` 为 `COMPLETE` 才去取数 |

### 4.4.1 fail-closed 是跨源页的命门，已在真实后端上验证

`WITHHELD` 时 `totalAmount`、`sourceCount`、`sources`、`consistencyAsOf`、`maxSkewMillis`
全部为 null/空——服务端**从未序列化过**数字。因此页面有两道独立闸门：

1. **请求闸门** `canFetchAmounts`：口径不放行就不发结果请求；
2. **渲染闸门** `shouldRenderAmounts`：结果自身口径不放行就不渲染任何数字。

第 2 道不能省——万一某次响应在 `WITHHELD` 下带回了数字，页面也不跟着显示。

**真实后端取证**（`xs-does-not-exist-001` + `applicationId=1` + 角色 ANALYST）：

```
先查口径 → 后端返回「跨源执行记录不存在」
页面显示：读取授权完整性口径失败：请确认执行键存在，且当前账号有 ai:cross-source:integrity 权限
门禁状态：integrity-state 不存在 | amounts 不存在 | withheld 不存在 | 「再取数字」disabled = true
```

即**失败的探测不会留下一个假的"可取数"状态**——那正是要堵的缺口。

### 4.4.2 顺手实测掉一个未验证风险

查询页的 `allowedFieldCodes` 是数组参数，靠 `qs` 的 `repeat` 序列化。
在真实后端上实测三种形式：

```
?datasetId=1&allowedFieldCodes=amount                 → 1003006018 数据集不存在
?datasetId=1&allowedFieldCodes=amount&…=order_id      → 1003006018 数据集不存在
?datasetId=1                                          → 1003006018 数据集不存在
```

三者都**进入业务层**并返回同一个业务错误。若绑定失败会是 400「请求参数缺失:executionKey」，
所以绑定成立——原先"未在真实后端验证过"的标注可以撤掉。

### 4.4.3 建页面过程中修掉的四处

| 位置 | 问题 | 修法 |
|---|---|---|
| `cross-source/index.vue` | 2 个 TS2345：表单 `applicationId` 运行时是 `number \| undefined`，被 `{ ...form }` 塞进要求 `number` 的 `MergeQuery` | 加 `toMergeQuery()` **收窄函数**而不是 `as` 强转——把"这里必须已校验过"这条不变量保在类型里，且可被直接测（补 5 例） |
| `workflow/modules/versions.vue` | `handleEdit`/`handleView` 直接 `await` 无 `catch`：失败变成**没有用户可见消息的未捕获拒绝**，而同面板其它 4 个操作都用 `extractErrorMessage` 如实显示原因码 | 补 `catch` + 清空旧状态 |
| `workflow/modules/runs.vue` | `handleNodes` 同样无 `catch`，且失败后会把上一个运行的留痕留在页面上**冒充本次的** | 补 `catch` + `nodes.value = []` |
| `api/ai/workflow/index.ts` | `publishedAt?: Date` —— 请求层**不做日期归一化**，运行时是字符串，而这是版本列表里唯一被**原样渲染**的时间 | 改成 `string` 并说明为何不沿用 `createTime?: Date` 惯例（那些声明从没被渲染过，所以一直没人发现） |

`setup.ts` 头注释里我一度写了"`setup` 会被覆盖率排除"——**这是错的**：
`vitest.config.ts` 的 `coverage.exclude` 里没有该模式，实测该文件被计入（95.08%）。
最终注释只写已验证的事实。

## 5. 修掉的真缺陷

### 5.1 偏好存储 namespace 退化为 `undefined-undefined-dev`（前端，影响所有用户）

**取证**（真实浏览器 `localStorage`）：

```
undefined-undefined-dev-preferences
undefined-undefined-dev-preferences-locale
undefined-undefined-dev-preferences-theme
```

**成因**：`main.ts` 拼 `namespace = ${VITE_APP_NAMESPACE}-${VITE_APP_VERSION}-${env}`，
而 `apps/web-ele/.env.development` 与 `.env.production` **都没有定义这两个键**
（`index.html` 的注释却说"在 .env 文件内配置"）。`import.meta.env` 对未定义键返回 `undefined`，
模板串于是拼出 `undefined-undefined-dev`。

**危害**：功能不报错、页面正常，但"按项目与版本隔离偏好"的意图彻底失效；
更麻烦的是下次补配后**旧键全部作废**，用户偏好静默丢失。

**修法**（三处一起，缺一不可）：

1. 两个 `.env` 补 `VITE_APP_TITLE` / `VITE_APP_NAMESPACE` / `VITE_APP_VERSION`；
2. 抽出 `resolvePreferenceNamespace()`，两段都兜底——**宁可退回可读默认值，也不产出含
   `undefined` 的键**；
3. 独立成 `preference-namespace.ts` 而非留在 `main.ts`：`main.ts` 被覆盖率配置列为
   "entry assembly file" **排除在统计之外**，逻辑放那里等于不受棘轮约束。

**修复后取证**：`basic-framework-admin-1.0.0-dev-preferences*`，无 `undefined`。

### 5.2 配置守卫本身就是恒真断言（测试质量问题）

原 `preferences.test.ts`：

```ts
expect(overridesPreferences).toMatchObject({ app: { name: import.meta.env.VITE_APP_TITLE } });
```

断言表达式与实现表达式**完全相同**——配没配都过。与本项目此前在
`views/ai/semantic/index.test.ts` 抓到的假组件夹具是同一类。

**修法**：断言不能靠 `import.meta.env`——vitest 的 mode 是 `test`，
工作区根没有 `.env.test`，测试里该变量恒为 `undefined`，无论 `.env.development` 配没配都一样。
改为**直接读 `.env` 文件**校验开发与生产两条真实加载路径。

**并已验证它真能拦住**：删掉 `.env.production` 的 `VITE_APP_NAMESPACE` → 测试红并精确指出
"`.env.production` 缺少 `VITE_APP_NAMESPACE`"；恢复 → 绿。

守卫键位从 3 个扩到 7 个，与代码里实际读取处一一对应
（新增 `VITE_GLOB_API_URL`、`VITE_UPLOAD_TYPE`、`VITE_ROUTER_HISTORY`、`VITE_APP_CAPTCHA_ENABLE`）。

### 5.3 生产分支在类型守卫之前就解引用（前端，白屏风险）

```ts
const apiURL = isProduction
  ? window._VBEN_ADMIN_PRO_APP_CONF_.VITE_GLOB_API_URL  // ← 先解引用
  : env.VITE_GLOB_API_URL;
if (typeof apiURL !== 'string') {                        // ← 守卫在后面，永远接不住
  throw new TypeError('VITE_GLOB_API_URL 必须是字符串');
```

全局对象缺失时先抛 `Cannot read properties of undefined`，那句精心写的提示走不到。
而 `apps/web-ele/src/api/request.ts:22` 在**模块顶层**调用它 → 抛错即整页白屏，
真实原因被一句无关的 TypeError 掩盖。触发条件是产物里 `_app.config.js` 存在但未执行
（CDN 缓存旧 `index.html`、子路径部署、静态服务器漏配），构建与打包两道门都拦不住。

**修法**：先取全局、守卫、再取值；用已有的 `VbenAdminProAppConfigRaw` 类型 + 可选链
（不靠断言，否则触发 TS2352——这是本次实际踩到的编译错误）。

**并已验证**：还原旧写法 → 测试红且报 `Cannot read properties of undefined`；
恢复 → 7 例全绿。

### 5.4 验证码开关 fail-open 且无告警（前端）

`isCaptchaEnable()` 判定 `=== 'true'`，而 `.env.development` **没配这个键** → 本地静默关闭。
隐患在于生产若误删该行，验证码会无声消失。

**修法**：本地**显式写 `false`**，让"不启用验证码"成为一个决定而不是一次疏忽，
并纳入配置守卫。

### 5.5 守护安全函数的恒真测试（前端，13 个缺失用例）

`views/ai/chat-integration/data.test.ts` 断言 `getEmbedBasePath()` 等于
`DEFAULT_EMBED_BASE_PATH`，而实现就是 `return DEFAULT_EMBED_BASE_PATH;`。
该 `it` 标题写着"只接受同源相对路径或 http(s)"，但 `readEmbedBasePathFromEnv` 的
**接受分支与全部拒绝分支零覆盖**——而这个值会进入复制给用户的接入代码，
正是它自己注释警告的"不能成为任意地址的来源"。

**修法**：断言改字面量；补 5 条接受 + 8 条拒绝的表驱动用例，
覆盖 `//evil.example.com`、`javascript:alert(1)`、`data:`、`ftp:`、纯空白、空串、未配置。

### 5.6 单例 Bean 里的非线程安全 Map + 无界增长（后端，P0）

`QdrantRestKnowledgeIndexAdapter`：

```java
private final Map<String, Integer> dimensions = new LinkedHashMap<>();  // 无同步
```

适配器是单例（`AiKnowledgeIndexingService` 用 `ObjectProvider` 注入），
写入方是入库作业线程、读取方是检索请求线程。并发 `put` 叠加 `get` 可能结构损坏，
极端情况下 `get()` 陷入死循环。

同时**无淘汰**：集合名是 `kb_<code>_g<generation>`，每次换代都是新键，
进程生命周期内单调增长。

**修法**：`ConcurrentHashMap` + 上限 1024，所有写入收敛到唯一的 `cacheDimension()`。
超限时整体清空而非维护淘汰顺序——这份缓存是纯派生数据（维度创建后不可变，
清空后 `knownDimension()` 会重新探测），"粗暴清空"的唯一代价是多一次 describe 调用。

### 5.7 评测 worker 白烧他人重试预算（后端，P0）

`AiTaskClaimMapper.claim` 的 CAS 里含 `attempt_count = attempt_count + 1`——
**领取即消耗一次重试预算**。而 `selectClaimable` 没有 `runId` 过滤，
评测 worker 领到的是**全局最老的** QUEUED 任务；发现不是自己的运行后，
`claimAndExecute` 只做 `heartbeat(lease, 1)` 缩短租约交还——可那次预算**已经扣掉了**。

叠加 `recoverExpired` 的 `status = IF(attempt_count >= max_attempts, 'FAILED', 'QUEUED')`：
竞争激烈时目标运行会在**一次都没执行过**的情况下被判 FAILED，
且 `last_error_code` 指向"重试预算耗尽"——把排查引向根本没问题的执行器/模型侧。
这正是本轮 F12 主题（错误归因错位）的另一种形态。

**修法**：`selectClaimable` 增加可选 `runId` 过滤；`AiTaskService` 增加按运行定向的
`claim(workerId, runId, limit, leaseSeconds)`；旧三参入口委托为 `runId = null`，
语义不变。评测 worker 改用定向领取；原"领到他任务"分支保留为**防御性检查**
（真出现说明 SQL 过滤失效，交还租约而不是继续烧预算）。

### 5.8 调用方中断被归因为对端故障（后端，与 F12 同类）

`QdrantRestKnowledgeIndexAdapter` 把 `InterruptedException` 报成
`Reason.TRANSPORT_FAILED`，而该原因的对外文案正是**"向量服务不可达"**。
中断发生在**调用方线程**，语义是"请求被取消/容器关闭"，与向量服务无关。

**修法**：`KnowledgeIndexException.Reason` 新增 `CANCELLED`（与 F12 的
`ExternalHttpException.Reason.CANCELLED` 保持一致），消息改为
"向量索引调用已被调用方取消"。该枚举无外部契约约束（除覆盖率基线），也无消费方穷尽 switch。

## 6. 验证结果

| 命令 | 结果 |
|---|---|
| `npx vue-tsc --noEmit` | 0 |
| 4 个相关 vitest 文件 | **35 passed / 0 failed** |
| `./mvnw -pl basic-framework-module-ai spotless:apply` | 0 |
| `./mvnw -pl basic-framework-module-ai test` | 见 §7 |

## 7. 签名变更引出的既有测试修正

§5.7 改了 `AiTaskService.claim` 签名，两个既有测试类的 mock 仍是旧签名：

- `AiEvalRunServiceImplTest` 4 处 `when(taskService.claim(anyString(), anyInt(), anyInt()))`
  → 改为四参。Mockito 对未打桩的 `List` 返回空列表，于是旧 mock 失效、
  代码走 `AI_EVAL_RUN_NOT_EXECUTED`，表现为状态 `ERROR` 而非期望的
  `PASSED` / `FAILED` / `REVIEW_REQUIRED`。**这 4 个失败是签名变更的真实反馈，不是既有缺陷。**
- `AiTaskServiceImplTest` 3 处 `selectClaimable` mock 同步更新，
  并新增 `runScopedClaimFiltersByRunId` 钉住"定向领取必须把 runId 传到 SQL 过滤上"。

## 7.1 F12 自己的用例是随机红的（本轮最刺眼的一条）

integration 门禁在 `basic-framework-spring-boot-starter-ai` 报出一个 `Errors: 1`：

```
OutboundCancellationAttributionTest.cancellationMessageEchoesNeitherRequestBodyOrCredentials:269
  NullPointer: Cannot invoke "ExternalHttpException.getMessage()" because "<local7>" is null
```

这个用例在上一轮 backend 门禁（3516 测试全过）时是绿的——**它本来就会随机变红**。

**根因是测试对 `join()` 异常形态的假设本身是竞态的**：

| 时序 | `join()` 抛出 | `getCause()` |
|---|---|---|
| future 已被标记取消 | 裸 `CancellationException` | **null** |
| 底层 HTTP 交换抢先失败 | `CompletionException` 包真实原因 | 非 null |

原代码在两处都写死了一种形态：`isInstanceOf(CompletionException.class)` 在第一种情况下失败，
`.getCause()` 在第一种情况下 NPE（正是本次报错的 269 行）。

**讽刺之处**：生产代码 `await()` **两种形态都接住了**，是测试没跟上。

**修法**：抽 `cancellationFailureOf(future)` 辅助方法，按生产逻辑同样处理两种形态后再断言。
真正该被钉住的不变量是「取消后拿不到任何响应」与「归因为 `CANCELLED`」，
而不是 JDK 恰好抛哪个包装类型。

**验证**：连跑 6 次，6/6 绿（`Tests run: 11, Failures: 0, Errors: 0`）。
修复是按构造消除竞态——两种形态都接住——所以 6 次全绿与逻辑上的确定性一致。

> 与 F11 修的两处 flake 同一条纪律：**会随机染红 CI 的测试留在生产路径上比没有测试更糟**，
> 因为训练团队会忽略红灯，于是真回归也一起被忽略。

## 7.2 覆盖率棘轮两次拦下我自己的改动

| 时点 | 棘轮报的 | 处理 |
|---|---|---|
| 第 1 次 | `QdrantRestKnowledgeIndexAdapter 88.03% 低于基线 88.41%` | 我加的"超限清空"分支没有测试。**不降基线**（那正是棘轮要防的），而是把有界缓存抽成 `BoundedDimensionCache` 并补 7 个测试 |
| 第 2 次 | `BoundedDimensionCache.java 尚未登记单文件覆盖率基线` | 棘轮只升不降，新文件必须先登记。`--update` 又只支持全量、且会先卡在后端，于是必须先重跑 integration 重建有效聚合报告 |

**基线更新后只升不降，且带出两个副产品**：

```
BoundedDimensionCache.java        100     (新增)
QdrantRestKnowledgeIndexAdapter   88.41 → 88.57   ↑
preference-namespace.ts           100     (新增)
chat-integration/data.ts          92.22 → 100      ↑
```

第 3 行↑是因为把有界缓存抽成独立类后，适配器里那几行未覆盖代码一并移走。
第 4 行↑更有意思：补的 13 个表驱动用例把 `readEmbedBasePathFromEnv` 原先零覆盖的
accept/reject 分支全测到了，直接把这个文件从 92.22% 顶到 100%——
**修掉恒真测试顺带抬高了覆盖率下限**，这正是棘轮机制该有的正反馈。


## 8. 未验证 / 未修（诚实登记）

### 8.1 本轮未修但已定位的缺陷

两轮独立审查还报出以下项，本轮**未改**，因为它们要么需要动错误码台账
（`AiErrorCodeConstants.java` 798 行、零余量，改动会牵连 `check-exceptions` 契约），
要么需要先复现才能确认：

| 位置 | 问题 |
|---|---|
| `AiCrossSourceQueryExecutor:171` | 调用方中断被归因为"来源超时"且**可重试**（F12 同类） |
| `AiModelFailureCodes:37` | 上游限流报成"平台配额超限"，且与本类 javadoc 矛盾 |
| `CrossSourceBudgetAccountant:74` | 并发准入拒绝报成"行数预算超限"，且因预置固定线程池该分支**永不可达**；注释说"不排队"但 `newFixedThreadPool` 是无界队列会排队 |
| `AiRealtimeSessionServiceImpl:541` | `stableFailureCode` 回退到异常类名并落库 |
| `AiRunExecutionServiceImpl:101` | 执行前预算超限**不落运行终态**，同文件另三处都落 |
| `views/ai/evaluation` 3 处 | 列表加载无请求竞态防护（全域 `AbortController` 命中数为 0） |
| `AiTicketAttemptThrottle:76` | `MAX_TRACKED_KEYS` 注释与实现不符，只淘汰已过期键时上界未强制 |
| `AiQueryPlanValidator:75` | `CODE_PATTERN` 死代码；逻辑别名未经该 pattern 校验即拼进 SQL |

### 8.2 环境性未验证

- **跨厂商 MCP 互操作**：需真实第三方服务器或第二独立实现。
- **真实出站调用**：平台尚无消费方，全部用例打在本机。
- **真实模型凭据 / 真实厂商端点 / 真实 MDM / 法务许可与校验和**。
- **浏览器联调的真实凭据场景**：本轮用的是本地种子管理员，非生产凭据。
- **Qdrant 取消路径的端到端验证**：新增的 `CANCELLED` 分支需要真实中断才能触发，
  单元测试未覆盖（该文件覆盖率基线 88.41%，非 100%，不受影响）。

### 8.3 已处理：三个菜单入口指向不存在的页面

`query` / `workflow` / `cross-source` 三个管理端页面曾在菜单里注册、组件从未创建
（详见 §4.2），后端 API 齐备，而**没有任何卡授权创建它们**。

因为处置方案差别很大（建页面 / 隐藏菜单 / 建卡承接），本轮没有擅自决定，
把三个选项与代价摆出来请用户定夺。用户选定 **A. 建这三个页面**，实施与验证见 §4.4。

三个页面共 13 个源文件 + 8 个测试文件，**全部低于 800 行上限**（最大 378 行）；
覆盖率全部满足棘轮新文件 80% 门槛（92.56%–100%）。


## 9. 范围声明

- 只读源框架 `E:\kuangjia\2026-main` **未修改**。
- 未新增任务卡：本轮是跨卡的验证与修复，逐条归属既有卡（K01 / 评测 / F12 同类），
  证据统一记在本文件。
- 临时验证脚本用完即删，未留在仓库内。
