# C05 实现独立 embed 页面与安全头 — 完成证据

| 项目 | 内容 |
|---|---|
| 任务卡 | [C05](../tasks/C05.md) |
| 状态 | DONE（HTTP 层与产物证据齐全；**跨源真实浏览器验收见"未验证项"**） |
| 需求 | FR-14（嵌入与 SDK）、FR-40（全链路安全） |
| 依赖 | C03（消息渲染，证据 `c03-*`）、C04（主题发布，证据 `c04-*`）、A05（MEMBER Provider 与 scope 表达式，证据 `a05-*`） |
| 工作副本 | `/home/ctyun/桌面/zhongtai/ai-platform` |
| 变更范围 | 后端：`module-ai/controller/app/v1/embed`、`server/src/main`（安全链）；前端：`apps/ai-chat`（暂存脚本与脚本入口）；文档：`docs/deployment`、`docs/contracts/ai/scope-catalog.md`、`docs/integrations/open-api/ai-open-api.json`、错误码台账 |

## 1. 变更文件清单

### 后端：嵌入入口（`basic-framework-module-ai/.../controller/app/v1/embed`）

- `AiEmbedShellController.java`：三个**公开**端点——启动壳 HTML、公开启动配置、自托管资产；
  响应头按应用配置给出精确 `frame-ancestors`，缓存键 = 应用 + 配置版本 + 主题指纹，支持 `If-None-Match` → 304。
- `AiEmbedPolicy.java`：纯策略类——允许域复用 A01 的 `ApplicationOrigins`（校验 + 归一化，不复制规则）、
  CSP 组合（无 `unsafe-inline`/`unsafe-eval`）、缓存键与 ETag。
- `AiEmbedAssetCatalog.java`：**清单即白名单**的产物读取（单段文件名、禁穿越/隐藏文件、大小上限、后缀→媒体类型显式映射）。
- `AiEmbedApplicationResolver.java`：`appCode` → 精确匹配的**已启用**应用（未启用与不存在同语义，扫描页数有界）。
- `AiEmbedProperties.java`：`basic-framework.ai.embed.assets-directory` / `max-asset-bytes`。
- `vo/AiEmbedBootstrapRespVO.java`：公开启动配置（应用标识、协议版本、允许域、品牌名、主题 token 与来源）。

### 后端：安全头（`basic-framework-server/src/main`）

- `AiEmbedSecurityConfiguration.java`：`securityMatcher("/app-api/ai/v1/embed/**")` 的独立过滤链，
  **只对该前缀**关闭 `X-Frame-Options`；其余路径（含 `/admin-api/**`）继续走框架链（SAMEORIGIN + 认证）。
- `enums/AiErrorCodeConstants.java`：新增 4 个错误码（`1_003_007_025`–`028`）。

### 前端：产物（`apps/ai-chat`）

- `scripts/stage-embed-assets.mjs`：把构建输出摊平成 `<目标目录>/assets/<哈希文件名>` 并写出
  `asset-manifest.json`（sha256、入口 JS/CSS；跳过 `.map`/隐藏文件）。
- `package.json`：新增 `stage:embed` 与 `build:embed` 脚本。

### 台账与文档

- `docs/contracts/ai/scope-catalog.md`：匿名端点表新增三条（与契约测试双向登记）。
- `docs/integrations/open-api/ai-open-api.json`：新增三个开放路径与"嵌入页"标签。
- `docs/contracts/ai/error-code-map.md`：同步 4 个新错误码（并写明**状态由常量名后缀推导**）。
- `docs/deployment/embed-page.md`：部署手册（构建/暂存、配置、响应头矩阵、反代注意事项、撤销语义）。
- `AiAppEndpointScopeContractTest`：`REVIEWED_ANONYMOUS_ENDPOINTS` 登记三个嵌入端点。

### 测试

- 单元：`AiEmbedPolicyTest`（6）、`AiEmbedAssetCatalogTest`（7）、`AiEmbedShellControllerTest`（10）。
- 集成：`AiEmbedShellIT`（5，真实 MySQL + 真实安全过滤链 + MockMvc）。

## 2. 与卡片逐步实施的对应

| 卡步骤 | 实现 | 验证 |
|---|---|---|
| 1. 按 appCode 加载发布配置的 embed 入口 | `AiEmbedApplicationResolver`（精确 + 已启用）+ `AiEmbedShellController#shell` | `AiEmbedShellControllerTest`（未知/停用 404、无允许域 fail-closed）、`AiEmbedShellIT.unknownDisabledAndUnusableApplicationsFailClosed` |
| 2. 响应头生成精确 frame-ancestors，保留 admin 保护 | `AiEmbedPolicy.contentSecurityPolicy` + `AiEmbedSecurityConfiguration`（只对该前缀关闭 X-Frame-Options） | `AiEmbedShellIT.embedShellIsPublicWithExactAncestorsWhileOtherPathsKeepFrameProtection`（嵌入路径 200 且无 X-Frame-Options；`/admin-api/**` 与其它 `/app-api/**` 仍 401 + SAMEORIGIN） |
| 3. 所有静态资产自托管，拒绝未授权域与未知应用 | 清单白名单 + 应用解析前置（资产路径同样要求已启用 + 允许域可用）；无任何 CDN/外部域名 | `AiEmbedAssetCatalogTest`（穿越/隐藏文件/超限/入口缺失全拒绝）、`AiEmbedShellIT.assetsComeFromManifestOnly` |
| 4. 公开壳只输出固定 HTML 与最小公开主题，AI 调用仍要票据 | 壳 HTML 无内联脚本/样式（CSP 无 `unsafe-inline`）；主题走 `/bootstrap`；三个公开端点都不含凭据与资源清单 | `AiEmbedShellControllerTest`（壳内容与"无内联"断言、bootstrap 字段白名单）、`AiEmbedShellIT`（其它 `/app-api/**` 仍需认证） |
| 5. 应用与主题版本参与缓存键；撤销失效、不串用其他应用允许域 | 缓存键 = appCode + 应用配置版本 + 主题指纹；`frame-ancestors` 逐应用计算 | `AiEmbedPolicyTest`（键的三要素）、`AiEmbedShellIT.bootstrapPublishesAppThemeAndCacheKeyFollowsPublish`（发布主题后 ETag 变化、旧 ETag 不再 304）、`cacheKeyIsScopedPerApplication` |

### 卡片验收项

| 验收项 | 结论 | 证据 |
|---|---|---|
| AT-050 跨 Origin Chat 嵌入（Cookie 禁用仍可换票、对话） | **HTTP 层通过、浏览器层未验证**：嵌入路径匿名可达且不依赖任何 Cookie（无状态链、无 CSRF、票据走 Header）；Cookie 禁用的端到端行为由 Q06/G5 用真实浏览器验收 | `AiEmbedShellIT`（匿名 200、`Cache-Control`/CSP 断言）；未验证项见第 8 节 |
| AT-056 embed 与 admin 安全头 | 通过：嵌入只允许配置域（`frame-ancestors` + 无 X-Frame-Options），admin 保持 SAMEORIGIN 与认证要求 | `AiEmbedShellIT` 第 1 例（同一进程内两类路径对照） |
| AT-067 禁公共 CDN 环境 | 通过（产物侧）：壳只引用 `self` 下的自托管资产，CSP `default-src 'self'`，无外部域名；构建产物已实测生成并留 manifest 摘要 | `docs/deployment/embed-page.md`、`AiEmbedShellIT`（壳引用 `/app-api/ai/v1/embed/<app>/assets/...`） |
| meta CSP 不当作替代 | 通过：CSP 只由响应头给出（壳里没有任何 CSP meta；`frame-ancestors` 在 meta 中本来就无效） | `AiEmbedShellControllerTest`（壳 HTML 断言） |
| 无全局放宽 | 通过：独立过滤链只匹配 `/app-api/ai/v1/embed/**`；契约测试与 IT 对照证明其余路径不变 | `AiEmbedSecurityConfiguration` + `AiEmbedShellIT` 第 1 例 |
| 第三方 Cookie 禁用仍可用 | **未验证（浏览器）**：实现上不依赖 Cookie（无状态、无 Cookie 会话） | 结构证据见第 3 节；浏览器验收由 Q06/G5 承接 |

## 3. 关键约束落地

- **公开面最小**：三个公开端点只输出启动壳、公开启动配置（品牌名/协议版本/允许域/主题）与哈希命名的静态资产；
  会话正文、资源清单、模型与连接器信息一律不出现在公开路径上；响应体与日志都不含凭据。
- **允许域单一来源**：`frame-ancestors` 直接来自应用配置的精确 Origin（A01 的归一化规则），
  本卡不新增一套更宽/更严的规则；配置脏数据 → fail-closed（不返回可被任意站点嵌套的壳）。
- **头部只做窄化不做放宽**：`X-Frame-Options` 仅对嵌入前缀关闭（由 CSP 精确控制祖先），
  其余安全头与认证语义完全沿用框架默认；没有全局关闭、没有通配符。
- **缓存与撤销**：壳与启动配置 `no-cache + ETag`（键含应用/配置版本/主题指纹），资产 `immutable`（内容哈希命名）；
  停用应用或改允许域后缓存键变化，新请求必然重新取。
- **资产不暴露目录**：只提供清单内文件名，且文件名必须单段、非隐藏；清单缺失/损坏/超限按"产物未就位"明确失败。

## 4. 实际执行的命令与结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`。

| 命令 | 退出码 | 结果 |
|---|---|---|
| `./mvnw -o -pl basic-framework-module-ai test -Dtest='AiEmbed*Test'` | 0 | **23 例通过**（策略 6 + 清单 7 + 控制器 10） |
| `./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiEmbedShellIT'` | 0 | **5 例通过**（真实 MySQL + 真实安全过滤链 + MockMvc） |
| `./mvnw -o -pl basic-framework-server test -Dtest='AiAppEndpointScopeContractTest,AiOpenApiContractTest,EndpointAuthorizationContractTest,AiSensitiveEndpointRegistryTest'` | 0 | 14 例通过（端点策略、开放 API 双向一致、敏感端点登记） |
| `pnpm -F @vben/ai-chat run build` | 0 | 构建产物：`index-C97Z0qqd.js` 69.45 kB、`vendor-vue` 62.61 kB、`index-CJH5oiAp.css` 1.38 kB、antv 分包 1 B |
| `pnpm -F @vben/ai-chat exec node scripts/stage-embed-assets.mjs /tmp/c05-embed-assets` | 0 | **embed 生产产物**：4 个文件、152 KB、`asset-manifest.json` 含逐文件 sha256（入口 `index-C97Z0qqd.js`） |
| `pnpm run check` | 0 | 环形依赖、依赖声明、显式 any、typecheck、cspell（1004 文件 0 问题） |
| `./mvnw -q -Pintegration clean verify` | 0（复跑） | 233 例 IT 全绿（failsafe 汇总 0 failures / 0 errors / 0 skipped） |
| 门禁链（contracts / backend / frontend / integration） | 见第 6 节 | 见第 6 节 |

## 5. 新增/变化的对外契约与上游差异

- **开放 API**：`GET /app-api/ai/v1/embed/{appCode}`、`GET …/bootstrap`、`GET …/assets/{file}`（均为匿名公开，已登记 scope 目录与开放 API 规范）。
- **错误码**：`1_003_007_025`（应用不存在/未启用，404）、`026`（允许域不可用，422）、`027`（产物未就位，422）、`028`（资产不在清单内，404）。
- **配置**：`basic-framework.ai.embed.assets-directory` / `max-asset-bytes`。
- **上游差异与依赖缺口（如实记录）**：
  1. **HTTP 状态由错误码常量名后缀推导**（`GlobalExceptionHandler.resolveHttpStatus` + `ErrorCodeNameRegistry`，ADR 0003）：
     只有 `*_NOT_EXISTS` 才是 404，含 `EXISTS/CONFLICT/DUPLICATE` 才是 409，其余一律 422。
     因此本卡把"应用/资产不存在"命名为 `AI_EMBED_APP_NOT_EXISTS` / `AI_EMBED_ASSET_NOT_EXISTS`（后缀承重），
     并在错误码台账里写明实际状态；同时**已有的若干码（如 `AI_REPORT_NOT_FOUND` 声称 404、`*_UNAVAILABLE` 声称 503）
     按此规则实际会落到 422**——这是既有台账与实现的口径差，属其它卡片范围，本卡只在证据里记录，未擅自改动他人码。
  2. **缺少"按 appCode 精确查询应用"的公开服务方法**：`AiApplicationService` 只有按标识模糊分页，
     `service/application` 不在本卡允许路径内，故 `AiEmbedApplicationResolver` 在分页查询上做精确过滤（页数有界）。
     建议后续卡片补一个 `getApplicationByAppCode` 并替换这里的适配。
  3. **允许域规则沿用 A01**（http/https 均可，开发期本地地址因此可用）；本卡不额外收紧，
     生产环境的 https 收敛由 A01 的配置入口负责（已在部署文档中提示）。
  4. **嵌入产物不入版本库**：`apps/ai-chat/embed-assets/` 是构建输出（与 `dist/` 同类），
     本卡未修改 `.gitignore`（不在允许路径内）；部署文档已说明该目录不应提交。

## 6. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 全部契约脚本通过（首轮被 `check-sensitive-tostring` 拦下 `tokensJson`，已按规范加 `@ToString.Exclude` 后复跑通过） |
| `sh .harness/verify.sh backend` | 0 | 编译、单测（含本卡 23 例）、格式、架构与覆盖率检查通过 |
| `sh .harness/verify.sh frontend` | 0（复跑） | 首轮 exit 1 只因为**既有**负载敏感用例 `packages/@core/base/design/src/__tests__/design-tokens.test.ts` 5 秒超时（与本卡无关，本卡未改该包）；复跑 `Test Files 348 passed / Tests 1883 passed`，含生产构建与前端棘轮 |
| `./mvnw -q -Pintegration clean verify`（integration 门禁的 maven 段） | 0（复跑） | **233 例 IT 全部通过**（failsafe 汇总 failures 0 / errors 0 / skipped 0）：新增 `AiEmbedShellIT` 5 例 + 既有 228 例（含 `PackagedJarBootSmokeIT`、`PersistenceLifecycleIT`） |
| `sh .harness/verify.sh integration`（整体） | 1 | **唯一失败项是尾部棘轮**：5 个新后端文件"尚未登记单文件覆盖率基线"（新文件的预期状态）；maven 段本身 exit 0 |
| `node scripts/check-coverage-ratchet.mjs --update` | 0 | 登记 5 个新文件基线（`AiEmbedPolicy` 100%、`AiEmbedAssetCatalog` 92.86%、`AiEmbedApplicationResolver` 89.29%、`AiEmbedShellController` 94.94%、`AiEmbedSecurityConfiguration` 100%），1 个既有条目上浮，**无下降、无删除** |
| `node scripts/check-coverage-ratchet.mjs all` | 0 | `all 单文件基线通过` |

> 说明：棘轮 `--update` 需要**后端聚合报告（来自一次成功的 integration 运行）与前端覆盖率报告（来自 frontend 门禁）同时存在**；
> 本卡先跑了一次 integration（其中负载敏感用例失败 → 聚合不完整、棘轮数字为噪声），定向复跑确认后重跑整批，
> 再用完整报告登记。登记只改 `docs/contracts/coverage-baseline.json`（台账），不影响运行行为，
> 故未在登记后重跑整条 integration 门禁（该门禁两段均已满足），此边界如实记录。

## 7. 顺带修复的依赖缺口（真实暴露）

1. **错误码命名承重**：首轮 IT 里"未知应用"返回 422 而不是 404，根因是状态由常量名后缀推导；
   改为 `*_NOT_EXISTS` 后与设计契约（未知/停用应用返回 404）一致——这条规则已写进错误码台账。
2. **catch 变量遮蔽静态导入**：`catch (IllegalArgumentException exception)` 会遮蔽
   `ServiceExceptionUtil.exception(...)`，编译期就报错；已重命名 catch 参数并在代码里留了一句说明。
3. **AI 应用允许域是 JSON 文本**：`AiApplicationDO.origins` 存的是归一化 JSON 数组文本，
   初始实现按 `List<String>` 处理导致编译失败；改为复用 A01 的 `ApplicationOrigins`（读 + 校验 + 归一化），
   顺带避免了"两套 Origin 规则漂移"。
4. **主题来源字段**：启动配置原先只给指纹不给来源，宿主无法区分"平台默认"与"应用已发布"；
   已补 `themeSource`（不做假成功）。
5. **两个既有负载敏感用例在全量套件下会超时**（都不是本卡引入，也不在允许路径内）：
   - 后端 `PackagedJarBootSmokeIT`（120 秒健康等待）：本卡首次全量运行超时，
     定向复跑通过（`SMOKE_EXIT=0`），随后整批重跑 **233/233 全绿**；
   - 前端 `design-tokens.test.ts`（5 秒超时，纯样式入口导入）：本卡首次前端门禁超时，复跑通过（348 文件全绿）。
   处理口径与既有 `UserProfilePersistenceIT` 一致：**不跳过、不排除、不改他人用例**，如实记录并给出复跑证据。

## 8. 未验证项与已知边界

1. **跨源真实浏览器验收未完成（关键项未验）**：AT-050（Cookie 禁用下的换票与对话）、AT-056 的浏览器侧行为、
   AT-067 的网络断言都需要 Q06 建立的真实浏览器门禁与 G5 验收。本卡交付的是响应头策略 + HTTP 层证据。
2. **握手与实例隔离属 C06/C07**：壳只提供公开配置；HELLO/READY/AUTH/INIT、instanceId、session generation、
   过期续票等由后续卡实现，故"第三方 Cookie 禁用仍可用"目前只有结构证据（无 Cookie 依赖）。
3. **未接入独立 Chat 应用**：`apps/ai-chat` 本卡只加了产物暂存脚本；壳读取 `/bootstrap` 并渲染的手由 C06/C07 接线。
4. **嵌入产物未入库**：必须在部署时执行 `pnpm -F @vben/ai-chat run build:embed` 并配置 `assets-directory`；
   否则嵌入入口返回 422（明确失败）。本卡未新增自动化部署步骤。
5. **生产反向代理配置未验证**：`docs/deployment/embed-page.md` 给出注意事项，但真实网关（Nginx 等）的头部透传
   需要在试点环境验证。
6. **两个负载敏感用例的超时余量**：`PackagedJarBootSmokeIT`（120 秒启动等待）与 `design-tokens.test.ts`（5 秒）
   在本机连续跑整批套件时可能超时（见第 7 节第 5 条）。二者都不在本卡允许路径内，未做改动；
   若要收紧，应由测试卡统一调整超时或按环境分级。
7. **环境缺口（非本卡）**：`sh .harness/verify.sh dependencies` 因 Trivy 漏洞库镜像不可达仍失败。
