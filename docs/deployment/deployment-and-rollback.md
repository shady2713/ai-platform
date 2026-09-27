# 部署与回退说明（交付包 → 运行）

| 项目 | 内容 |
|---|---|
| 任务卡 | [Q09 交付安装包、配置模板与恢复演练](../ai-platform/tasks/Q09.md)（文档切片） |
| 本文件回答什么 | ① 从**交付包**到**可运行实例**的最小步骤与判据；② **回退条件与步骤**（反向执行、数据损失窗口）；③ **AT-067（禁公共 CDN 环境）在本机的实测边界**与已登记缺口 |
| 不重复的内容 | 配置项全集见[配置手册](configuration-manual.md)；compose 编排/健康检查/Flyway 修复/备份恢复见[部署与运维 Runbook](../deployment.md)；嵌入页产物与响应头见[嵌入页部署](embed-page.md)；升级与验证见[升级检查单](../operations/upgrade-checklist.md) |
| 执行状态 | 本文的**部署命令未在生产/预发布环境执行**（无授权、无目标环境）；AT-067 的数字来自 Q06/F04 的本机实测证据（见 §4） |

## 1. 交付包（内容与校验）

交付物清单口径来自 [06 §9 发布物清单](../ai-platform/06-upstream-upgrade.md)：server jar/镜像、admin/chat 静态资源、
版本化 SDK、开放 API 规范、协议 Schema、Flyway 迁移与快照、SBOM、LICENSE/NOTICE、上游注册表、
**配置模板（无秘密）**、备份恢复手册、变更说明、兼容矩阵、测试证据摘要、已知限制。

- 打包脚本（Q09 交付包切片，已在本仓库）：在仓库根执行 `node deploy/package-delivery.mjs --offline`
  （参数 `--output=<目录>` / `--no-build` / `--skip-sbom`；幂等，默认重建输出目录）。
  产物结构与逐项校验规则见 [deploy/README.md](../../deploy/README.md) §1–§2：
  `backend/basic-framework-server.jar`、`backend/db-migration/`、`frontend/{admin,chat,embed-assets,sdk}/`、
  `sbom/*`、`NOTICE`、`migrations/{manifest.json,MIGRATIONS.md}`、`config/app.env.example`、
  `MANIFEST.json` / `SHA256SUMS` / `version.json`。校验：核对 `SHA256SUMS` 与 `MANIFEST.json`，
  并确认 `version.json` 的版本事实与实际构建一致。
  **待补：见 Q09 证据 §交付包**：最近一次出包的实跑命令、退出码与清单摘要（本文件不代替运行证据）。
- 配置模板校验：交付包模板 `deploy/config/app.env.example` 与仓库 `.env.example` 都只含 `replace-with-*` 占位；
  `CREDENTIAL_ENCRYPTION_KEY`、`PROMETHEUS_SCRAPE_TOKEN`、`SMS_CALLBACK_TOKEN` 必须为空或占位。
  仓库自带秘密扫描：`sh .harness/verify.sh contracts`（含 `scripts/secret-scan.mjs`）。

## 2. 从交付包到运行的最小步骤

> 工作目录除注明外为 `后端代码/basic-framework-boot`。

### 2.0 前置

- 目标机：Linux + Docker（集成/备份路径需要）；JDK 17（本地源码运行/构建）；MySQL 8.4 与 Redis 7。
- `.env` 由 `.env.example` 复制并替换全部占位（[配置手册 §5](configuration-manual.md) 核对表）。
- **阻塞提示（本机实测）**：`docker compose --env-file /dev/null -f docker-compose.yaml config -q` 返回
  退出码 1，`go-yaml load error ... L65.C27: mapping values are not allowed in this context`
  （`SMS_CALLBACK_TOKEN`/`PROMETHEUS_ENABLED`/`PROMETHEUS_SCRAPE_TOKEN` 三项缩进多一级）。
  **compose 编排当前无法渲染**，因此 §2.1 的 compose 路径在本机不可用；需一次串行补丁修正
  （该文件不在本切片允许路径内）。临时验证可用 §2.1 方式 B 与 §2.2 的"直接进程"路径，它们不依赖 compose。

### 2.1 数据层（MySQL + Redis）

```bash
# 方式 A（推荐）：compose 拉起（编排修好后）
docker compose up -d mysql redis
docker compose ps                       # 两个服务 healthy

# 方式 B：不用 compose 的等价容器（与 compose 同镜像 digest 口径）
docker run -d --name bf-mysql -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=replace-with-strong-root-password \
  -e MYSQL_DATABASE=basic_framework \
  -e DB_USERNAME=framework_app -e DB_PASSWORD=<secret> \
  -e FLYWAY_USERNAME=framework_migrator -e FLYWAY_PASSWORD=<secret> \
  -v "$PWD/script/mysql/init:/docker-entrypoint-initdb.d:ro" \
  -v bf-mysql-data:/var/lib/mysql \
  mysql:8.4.11@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb
docker run -d --name bf-redis -p 6379:6379 \
  redis:7.4.11@sha256:71da9275c5f3fcb97d0fa0c8c5b36cc995327265420f17a04bfd544f458059f7 \
  redis-server --requirepass <redis-password>
# 初始化脚本 script/mysql/init/01-create-accounts.sh 只需上面这 6 个环境变量（ADR 0009 双账号）；
# 密码不得含单引号/双引号/$/反引号（脚本约束），且仅首次初始化数据卷时执行。
```

建库策略（[Runbook §1](../deployment.md)）：

- 优先**空库**启动让 Flyway 跑完整迁移链；
- 若导入手工快照 `数据库文件/basic_framework.sql`：先用迁移账号
  `run_flyway baseline -baselineVersion=<快照文件头声明版本>`，再启动应用；**禁止**把快照按版本 1 接管；
- 已有 Flyway 历史的库直接升级，不执行 baseline。

判据：应用日志 `Successfully applied ... now at version v83`（本机迁移链最大 V83，82 个文件）、
`flyway_schema_history` 末版本 = 文件最大编号；种子管理员校验（跑完快照后）：

```sql
SELECT username, status, must_change_password + 0 AS must_change_password FROM system_users WHERE id = 1;
-- 期望：status = 1（禁用）、must_change_password = 1
```

### 2.2 后端

```bash
# 构建（交付包已含 jar 时跳过）
./mvnw -q clean package -DskipTests

# 方式 A：compose（编排修好后）
docker compose up -d --build app

# 方式 B：直接进程（PackagedJarBootSmokeIT 同口径的参数形态；本地验证用）
java -jar basic-framework-server/target/basic-framework-server.jar \
  --spring.profiles.active=prod \
  --spring.datasource.dynamic.datasource.master.url=jdbc:mysql://<host>:3306/basic_framework \
  --spring.datasource.dynamic.datasource.master.username=framework_app \
  --spring.datasource.dynamic.datasource.master.password=<secret> \
  --spring.flyway.url=jdbc:mysql://<host>:3306/basic_framework \
  --spring.flyway.user=framework_migrator --spring.flyway.password=<secret> \
  --spring.data.redis.host=<host> --spring.data.redis.port=6379 --spring.data.redis.password=<secret> \
  --basic-framework.web.cors-allowed-origins[0]=https://<管理端域名> \
  --basic-framework.security.credential-encryption-key=<32字节Base64>
```

判据：`curl -fsS http://127.0.0.1:48080/actuator/health` → `{"status":"UP"}`；
prod 校验不通过时应用**拒绝启动**（错误信息列出缺失/不安全项，见[配置手册 §0](configuration-manual.md)）。
冒烟/迁移口径：`.harness/verify.sh integration` 内的 `PackagedJarBootSmokeIT`（真实 MySQL/Redis+Flyway+健康端点，
包**打包 jar** 形态）与 `ReleasedBaselineUpgradeIT`（空库全链迁移、已发布基线升级、快照 Schema 一致）共同承载
**AT-063**；`PackagedJarBootSmokeIT` 同时承载 **AT-064** 的 jar 健康（两者由 Q09 交付包/演练切片补齐与登记）。

### 2.3 前端（管理端 + 嵌入页）

```bash
cd 前端代码/basic-framework-admin
pnpm install --frozen-lockfile --ignore-scripts
pnpm -F @vben/web-ele run build                 # 管理端产物 apps/web-ele/dist
pnpm -F @vben/ai-chat run build:embed           # Chat 产物 + 暂存嵌入资产（= build + stage:embed）
# 产物部署：dist → 站点根；部署期替换 _app.config.js 的接口基址
#   apps/web-ele/dist/_app.config.js: window._VBEN_ADMIN_PRO_APP_CONF_ = {"VITE_GLOB_API_URL":"https://<实际>/admin-api"}
```

自托管约束：静态脚本使用固定版本路径与内容哈希；**不要**为嵌入/静态资源启用公共 CDN 回源或 `latest` 浮动路径
（[embed-page.md §4](embed-page.md)）。前端镜像构建见
`前端代码/basic-framework-admin/scripts/deploy/{Dockerfile,build-local-docker-image.sh}`（node:22-slim 构建 + nginx 运行，8080）。

判据：管理端登录页可打开且触发登录；嵌入入口 `GET /app-api/ai/v1/embed/{appCode}` 200（
`basic-framework.ai.embed.assets-directory` 指向暂存目录，否则 422 `AI_EMBED_ASSETS_NOT_STAGED`）。

### 2.4 首次初始化（管理端，L3 配置）

顺序建议：种子管理员 → 文件存储 → 模型端点 → AI 应用/允许域 → 知识库/服务（可选）。

| 步骤 | 入口 | 判据 |
|---|---|---|
| 激活种子管理员 | 设 `BOOTSTRAP_ADMIN_PASSWORD` 启动一次 → 登录改密 → **删除该变量** | 首登强制改密；删除后重启不覆盖 |
| 主文件存储 | `/infra/file-config`（菜单 1237） | 保存后上传/下载成功；快照无预置行，未配置前上传不可用 |
| 模型端点 | `/ai/model-endpoint`（菜单 4001） | 连接测试/能力探测通过；凭据加密入库、不回显 |
| AI 应用与允许域 | `/ai/application`（菜单 4010） | 允许域为精确 Origin；改动使嵌入壳 ETag 变化 |
| 可选 AI 面 | `/ai/knowledge`、`/ai/service`、`/ai/evaluation` | 见[配置手册 §3](configuration-manual.md) 最小组合 |

### 2.5 观测与交付后检查

```bash
curl -fsS http://127.0.0.1:48080/actuator/health
PROMETHEUS_ENABLED=true PROMETHEUS_SCRAPE_TOKEN=<64hex>   # 启用后重启
curl -fsS -H "Authorization: Bearer <token>" http://127.0.0.1:48080/actuator/prometheus | head
```

管理端：`/ai/usage`（菜单 4115）、`/ai/observability`（菜单 4116）。**仓库没有 `/observe` 入口**，
监控探针不要按它写（[配置手册 §4](configuration-manual.md)）。

## 3. 回退

### 3.1 回退条件（与[升级检查单 §3](../operations/upgrade-checklist.md) 同一判据）

健康在停机上界（RTO 4h，ADR 0006）内未恢复；授权/引用/报表关键用例真实失败；索引快照恢复后内容/ACL/版本不一致；
L3 凭据大面积解密失败；错误率/积压持续超基线无收敛；秘密泄漏。
任一命中即回退，不允许"带病观察"。

### 3.2 回退步骤（反向执行；= 升级顺序的逆序）

| 序 | 对象 | 命令 / 动作 | 判据 |
|---|---|---|---|
| 1 | 前端产物 | 换回归档的 `apps/web-ele/dist`（与上一版 `_app.config.js`）；嵌入资产恢复旧暂存目录 | 页面加载正常；嵌入入口 200；晚到浏览器可能仍缓存新 chunk（文件名带内容哈希，风险有界） |
| 2 | 索引 | 停止索引写入 → 用升级前快照`recover` 或保留的旧集合/实例（[升级检查单 §2.4](../operations/upgrade-checklist.md)） | 检索内容/ACL/版本正确；**禁止用旧镜像直接挂载已升级的数据目录**（[06 §4.3](../ai-platform/06-upstream-upgrade.md)） |
| 3 | 密钥 | 若升级中换过主密钥：先恢复旧密钥（或对全部 L3 凭据重加密回旧值）再重启 | 模型端点连接测试、文件上传、短信渠道均不报解密错 |
| 4 | 文件 | 文件引用/对象存储配置不需回滚（数据在 MySQL 权威侧）；若配置被改过，恢复为升级前行 | 上传/下载成功 |
| 5 | 后端二进制 | 停止 -> 部署上一版 jar/镜像 tag -> 启动 | 健康 UP；但注意下条限制 |
| 6 | MySQL | **不做二进制回退**：已执行 Flyway 不回滚。二选一：**前滚修复**（新增迁移/修复数据）或**从备份恢复**到独立库后按业务核对单割接（[Runbook §4](../deployment.md)） | 恢复库抽验通过；割接前完成业务核对 |

### 3.3 数据损失窗口（必须写进发布说明）

- **Flyway 迁移不可逆**：回退应用二进制不会撤销已执行迁移；已在升级期间写入的业务数据**不能靠回退代码撤销**，
  只能业务补偿/核对（[06 §8](../ai-platform/06-upstream-upgrade.md)）。
- **RPO 1 小时**（ADR 0006）：从备份恢复意味着最多丢失最近 1 小时的已提交写入（前提是备份按 ≤1h 频率且可恢复）。
- **索引**：快照点是索引的水位；快照之后到回退时点的入库需要**重放入库任务**（[06 §4.3](../ai-platform/06-upstream-upgrade.md)）。
  旧代保留只是"回退窗"，**不支持激活已退役代**，回退 = 用旧数据重建（K07 证据 §9.2）。
- **Redis 不纳入业务数据恢复目标**（[Runbook §4](../deployment.md)）。

## 4. AT-067：禁公共 CDN 环境的本机实测边界

AT-067 的预期结果是"**管理/Chat/报表可用**"（在禁公共 CDN 环境）。本机把"可用"拆成三层证据，
**只有前两层的已跑部分可引用**：

| 层 | 手段 | 本机结果（引用证据） |
|---|---|---|
| 构建期静态扫描 | `node apps/ai-chat/scripts/check-public-cdn.mjs`（CDN 域名清单 + HTML/CSS 外部引用 + 未登记外部域名，登记表是数据文件 `public-cdn-registry.json`） | **通过**：313 个文件扫描，公共 CDN 0 命中、外部资源引用 0 命中、13 个外部域名全部登记并打印用途（F04 证据 §4 验收表与 §0 主管复核；Q08 演练文档 §3.3 复核为 0 命中） |
| 运行期真实浏览器 | `sh .harness/verify.sh smoke` / `Q06_FORCE_REBUILD=1 pnpm exec playwright test --config tests/playwright/playwright.config.ts`（网络断言） | 修复后 **24 passed / 0 failed / 3 skipped**；其中 **AT-067：2 pass + 1 fail（真实缺陷）+ 1 skipped** —— fail 项为管理端登录页运行期请求 `https://api.iconify.design/lucide.json`；该缺陷已修复（common-ui 内 9 处字符串图标改本地图标 + `packages/icons` 离线注册 4 个，含**拒绝型探针**证明断言非空跑）。修复后断言覆盖的**登录页已零外发**（Q06 证据 §2/§4） |
| 报表页 | 同一套件的报表页网络断言 | **skipped（未验证）**：本机无登录态/无后端（Q06 证据 §2、§5 第 1 条） |

### 4.1 仍存在的已登记缺口（Q06 §4.b，未修）

以下位置在运行期**仍可能外发**，本轮未修（超出 Q06 允许路径，登记为后续项）：

1. `apps/web-ele` 内约 **30 处** `lucide:*` 字符串图标（cropper/upload/table-action/infra 页面等）；
2. `packages/icons` 的 mdi/ant-design 字符串图标，含**登录后必渲染**的 `MdiKeyboardEsc`（全局搜索）、
   `AntdProfileOutlined`（头像菜单）；
3. `components/icon-picker/icons.ts`：**主动**拉取 `api.iconify.design/collection`（用户触发，按设计保留）。

运维处置建议（不改代码也能做）：网络层封禁公共 CDN 与 `api.iconify.design`，观察被阻断请求数并对照上述清单；
缺口清单进发布说明的"已知限制"，真修按 Q06 §4.b 的方法（换 `@vben/icons` 本地组件或就地离线注册）。

### 4.2 未验证项（不得当 pass）

- AT-056（embed/admin 安全头）**skipped**：本机无后端（`docker ps` 无容器、48080 无监听）；断言已写全，
  提供 `Q06_EMBED_APP_CODE`/`Q06_EMBED_ALLOWED_ORIGIN` 即可在有后端环境跑。
- AT-067 报表页；真实网关（Nginx 头部透传/`frame-ancestors` 经反代）未验证。
- 依赖模型/凭据的页面步骤（对话内容、报表修改）标注 `model-dependent: not configured in this environment`。

## 5. 与既有文档的差异说明

- [Runbook](../deployment.md)：容器化操作与故障处置；本文补"交付包→运行"和"回退决策/顺序/窗口"。
- [嵌入页部署](embed-page.md)：嵌入产物与响应头策略；本文只给构建命令与部署期替换点。
- [配置手册](configuration-manual.md)：不重复配置项；本文只引用其核对表与最小组合。
- [Q08 升级演练](../upgrades/q08-upgrade-rehearsal-and-rollback.md)：上游依赖版本置换与回退；本文是**平台发布**的部署与回退。

## 6. 待补与未验证

| # | 项 | 状态 |
|---|---|---|
| 1 | 交付包内容/校验命令/SHA256SUMS | **待补：见 Q09 证据 §交付包** |
| 2 | 恢复演练实测（含一起演练"回退"） | **待补：见 Q09 证据 §恢复演练** |
| 3 | `docker-compose.yaml` 的 YAML 修复 | 本机阻塞；需串行补丁（不在本切片路径） |
| 4 | 生产/预发布实跑本文步骤 | 未执行（无授权、无目标环境） |
| 5 | AT-067 报表页与 AT-056 | 环境缺口，见 §4.2 |
