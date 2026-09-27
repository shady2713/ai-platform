# 配置手册（Q09 文档切片）

| 项目 | 内容 |
|---|---|
| 任务卡 | [Q09 交付安装包、配置模板与恢复演练](../ai-platform/tasks/Q09.md) |
| 本文件回答什么 | 部署一个 AI 中台实例**需要哪些配置项**、每一项的必填/默认与**秘密来源**、各组件的**最小可用组合**、健康检查与观测入口，以及部署前的逐项核对表 |
| 不重复的内容 | 操作步骤在 [部署与运维 Runbook](../deployment.md)；嵌入页在 [嵌入页部署](embed-page.md)；升级/回退在[部署与回退说明](deployment-and-rollback.md)与[升级检查单](../operations/upgrade-checklist.md) |
| 事实来源 | `后端代码/basic-framework-boot/.env.example`、`docker-compose.yaml`、`basic-framework-server/src/main/resources/application{,-prod,-local,-test}.yaml`、`ProductionConfigurationEnvironmentPostProcessor.java`、各模块 `*Properties.java`。**本文不新增第二事实源**：与代码不一致时以代码为准并回改本文 |
| 记录时间 | 2026-09-27（本机 `/home/ctyun/桌面/zhongtai/ai-platform`；`docker` 可用，无运行中容器，48080 无监听） |

## 0. 三条硬规则（先读）

1. **模板里永远不写秘密**。`.env.example` 只有占位串；真实值由密钥管理/环境注入，仓库只保留变量名。
   秘密清单：数据库两个密码、Redis 密码、`CREDENTIAL_ENCRYPTION_KEY`、`PROMETHEUS_SCRAPE_TOKEN`、
   `SMS_CALLBACK_TOKEN`、`BOOTSTRAP_ADMIN_PASSWORD`（一次性）、以及**运行期写入管理端的模型端点凭据与对象存储密钥**。
2. **配置分三层，改动位置不同**（见 §1）。改错层的典型症状是"重启后配置被覆盖/丢失"。
3. **prod 是 fail-closed**：`ProductionConfigurationEnvironmentPostProcessor` 在启动最早阶段校验，任一项不合规
   **直接抛 `IllegalStateException` 拒绝启动**，不会带病运行。

## 1. 配置分层与秘密来源

| 层 | 载体 | 生效时机 | 典型内容 | 秘密来源 |
|---|---|---|---|---|
| L1 进程环境 | `docker-compose.yaml` 的 `environment` + 同目录 `.env`（由 `.env.example` 复制） | 进程启动 | 数据源/Redis、主密钥、CORS 域名、观测令牌、预签名参数 | **密钥管理平台或部署环境注入**；`.env` 已在根 `.gitignore` 拦截，绝不提交 |
| L2 静态配置 | `application*.yaml` 与 `-D`/`--key=value` 启动参数 | 启动装配期 | AI 接缝（`basic-framework.ai.*`）、护栏、出站白名单、超时等 | 无秘密（`${...}` 占位符全部来自 L1） |
| L3 运行期配置 | MySQL 表，经管理端页面维护 | 运行期即时生效 | 模型端点与凭据、文件存储配置、AI 应用与允许域、知识库/服务/主题/评测 | **管理端写入后由 `CredentialCipher` 加密入库**（密文格式 `v1.<iv>.<ciphertext>`，AAD 绑定业务上下文） |

L3 的密文只带协议版本 `v1`、**不带密钥标识**（`CredentialCipher.java`）：更换 `CREDENTIAL_ENCRYPTION_KEY`
必须同时解密重加密全部 L3 凭据（模型端点 `credential_ciphertext`、`infra_file_config.config`、短信渠道配置），
否则这些配置在换钥后立即不可用。**本仓库当前没有提供批量重加密工具**（见 §7 待补）。

## 2. 完整配置清单

### 2.1 数据源 / 迁移（MySQL）

| 变量 / 配置键 | 用途 | 必填 | 默认 | 示例占位 | 秘密 |
|---|---|---|---|---|---|
| `DB_HOST` | 主库主机 | 是（prod） | `127.0.0.1`（local/test） | `mysql` | 否 |
| `DB_PORT` | 主库端口 | 否 | `3306` | `3306` | 否 |
| `DB_NAME` | 库名 | 否 | `basic_framework` | `basic_framework` | 否 |
| `DB_USERNAME` / `spring.datasource...master.username` | 运行时应用账号（仅业务 DML，[ADR 0009](../adr/0009-separate-flyway-database-principal.md)） | 是 | 无 | `framework_app` | 账号名否；**密码是** |
| `DB_PASSWORD` | 应用账号密码 | 是 | 无 | `replace-with-strong-app-password` | **是** |
| `FLYWAY_USERNAME` / `spring.flyway.user` | 迁移账号（DDL + Flyway 元数据） | 是 | 无 | `framework_migrator` | 账号名否 |
| `FLYWAY_PASSWORD` | 迁移账号密码 | 是 | 无 | `replace-with-strong-flyway-password` | **是** |
| `FLYWAY_URL` | 迁移连接串（默认=主库串） | 否 | 主库 URL | 同主库 | 否 |
| `FLYWAY_ENABLED` | 是否执行迁移 | 否 | `true` | `true` | 否 |
| `MYSQL_ROOT_PASSWORD` | 仅容器初始化与运维处置；**不注入应用** | 是（compose） | 无 | `replace-with-strong-root-password` | **是** |
| `DRUID_USERNAME` / `DRUID_PASSWORD` | Druid 监控台登录（**仅 local/test**；prod 关闭控制台） | 否 | `admin` / 空 | — | local 用 |

prod 校验（`ProductionConfigurationEnvironmentPostProcessor`）：主库 url/username/password、flyway url/user/password
必须非空且不是弱默认值（`admin`/`root`/`password`/`123456`… 全表见该文件 `UNSAFE_DEFAULT_VALUES`）；
`spring.flyway.user` **必须与应用账号不同**。

### 2.2 Redis（缓存 / 验证码 / 限流 / 会话状态）

| 变量 | 用途 | 必填 | 默认 | 示例占位 | 秘密 |
|---|---|---|---|---|---|
| `REDIS_HOST` | 主机 | 是（prod） | `127.0.0.1` | `redis` | 否 |
| `REDIS_PORT` | 端口 | 否 | `6379` | `6379` | 否 |
| `REDIS_DATABASE` | 库号 | 否 | `0` | `0` | 否 |
| `REDIS_PASSWORD` | 访问密码 | 是 | 无 | `replace-with-strong-redis-password` | **是** |

Redis **不承载业务数据**（会话与业务数据都在 MySQL）：恢复期间可丢失、可重建；但要限制匿名入口，
避免状态丢失重置防爆破窗口（[Runbook §4](../deployment.md)）。

### 2.3 安全与身份

| 变量 / 配置键 | 用途 | 必填 | 默认 | 示例占位 | 秘密 |
|---|---|---|---|---|---|
| `CREDENTIAL_ENCRYPTION_KEY` / `basic-framework.security.credential-encryption-key` | L3 凭据加密主密钥：32 字节随机值的 **标准 Base64**（`openssl rand -base64 32`） | 是（prod） | 空（禁用凭据能力） | 44 字符 Base64 | **是** |
| `CORS_ALLOWED_ORIGIN` / `basic-framework.web.cors-allowed-origins` | 管理端精确 HTTPS Origin（可多个，YAML 列表） | 是 | prod 模板值 `https://admin.example.com`（会被校验拒绝） | `https://admin.framework.internal` | 否 |
| `basic-framework.security.refresh-cookie.secure` | refresh Cookie 仅 HTTPS | 是（prod 必须 `true`） | prod `true`、local `false` | — | 否 |
| `BOOTSTRAP_ADMIN_PASSWORD` | 一次性激活种子管理员；**激活后删除该变量** | 首次部署时 | 空（账号保持禁用） | `replace-with-one-time-strong-password` | **是（一次性）** |
| `LOGIN_MAX_FAILED_ATTEMPTS` | 登录失败锁定阈值 | 否 | `5` | `5` | 否 |
| `LOGIN_LOCK_DURATION` | 锁定时长 | 否 | `15m` | `15m` | 否 |
| `SESSION_ACCESS_TOKEN_TTL` | 访问令牌 TTL | 否 | `30m` | `30m` | 否 |
| `SESSION_REFRESH_TOKEN_TTL` | 刷新令牌 TTL | 否 | `30d` | `30d` | 否 |

CORS 校验规则：必须是精确 `https://host`，**拒绝**通配符、含路径/查询/片段、示例域名与
localhost/127.x/::1（本机 `*.example.com` 之外的内网域名可正常通过）。

### 2.4 观测（Prometheus）

| 变量 | 用途 | 必填 | 默认 | 示例占位 | 秘密 |
|---|---|---|---|---|---|
| `PROMETHEUS_ENABLED` | 是否开放 `/actuator/prometheus` | 否 | `false`（端点拒绝访问） | `true` | 否 |
| `PROMETHEUS_SCRAPE_TOKEN` | 抓取令牌：独立生成的 32 字节随机值，64 位十六进制 | 启用观测时**必填**（否则启动失败） | 空 | `openssl rand -hex 32` 的输出 | **是** |

抓取仅支持 GET + `Authorization: Bearer <令牌>`；业务令牌/登录 Cookie 无抓取权限，抓取令牌也不能访问业务接口。
配置样例与告警规则：[ops/prometheus/prometheus.example.yml](../../ops/prometheus/prometheus.example.yml)、
[security-signals.rules.yml](../../ops/prometheus/security-signals.rules.yml)。

### 2.5 短信回调与文件预签名

| 变量 / 配置键 | 用途 | 必填 | 默认 | 秘密 |
|---|---|---|---|---|
| `SMS_CALLBACK_ENABLED` | HTTP 短信回执接收开关 | 否 | `false` | 否 |
| `SMS_CALLBACK_TOKEN` | 回调校验令牌（`openssl rand -hex 32`） | 启用时必填 | 空 | **是** |
| `SMS_CALLBACK_MAX_PAYLOAD_LENGTH` | 回调体上限 | 否 | `1048576` | 否 |
| `FILE_PRESIGNED_UPLOAD_TTL` / `basic-framework.file.presigned-upload.ttl` | 预签名上传有效期 | 否 | `15m` | 否 |
| `FILE_PRESIGNED_UPLOAD_MAX_SIZE` | 预签名单文件上限 | 否 | `16MB` | 否 |
| `spring.servlet.multipart.max-file-size` / `max-request-size` | 普通上传上限 | 否 | `16MB` / `32MB` | 否 |
| `basic-framework.file.archive.max-entries` / `max-expanded-size` / `max-compression-ratio` | ZIP 解压防护 | 否 | `1000` / `128MB` / `100` | 否 |

### 2.6 AI 能力接缝（L2，当前模板未包含，须部署方显式注入）

`basic-framework.ai.*` 在 `.env.example`、`docker-compose.yaml` 与本卡交付包模板
`deploy/config/app.env.example`（交付包切片，见 [deploy/README.md](../../deploy/README.md)）中
**都没有环境变量**：模板只覆盖 MySQL/Redis/安全/会话/上传/短信/观测与嵌入产物目录。
部署时必须通过 `SPRING_APPLICATION_JSON`、额外的 `-D`/`--`启动参数或自建 profile 注入本节所列键。
**待补**：模板补齐 `basic-framework.ai.*` 占位（见 Q09 证据 §交付包）。

| 配置键 | 用途 | 必填 | 默认 | 失败行为 |
|---|---|---|---|---|
| `basic-framework.ai.enabled` | AI 能力总开关 | 启用 AI 时必填 | `false`（不注册任何 AI Bean） | 关闭时管理端 AI 页面功能不可用 |
| `basic-framework.ai.capabilities` | 平台声明需要的能力集合，取值 `TEXT` / `TEXT_STREAM` / `STRUCTURED_OUTPUT` / `TOOL_CALLING` / `EMBEDDING` | 启用时**非空** | 空 | 启用但为空 → 启动失败 |
| `basic-framework.ai.http.allowed-hosts` | 出站主机名**精确**白名单（不支持下级通配） | 有出站调用时必填 | 空 = **拒绝一切出站** | 未列入即拒绝 |
| `basic-framework.ai.http.allowed-ports` | 出站端口 | 否 | `[443]` | 非白名单端口拒绝 |
| `basic-framework.ai.http.allow-private-targets` | 允许已批准的内网/环回目标 | 内网模型/连接器必填 `true` | `false` | 私网目标拒绝 |
| `basic-framework.ai.http.connect-timeout` / `read-timeout` | 连接/读取超时 | 否 | `5s` / `30s` | — |
| `basic-framework.ai.http.max-response-bytes` / `max-header-count` | 响应体/头部上限 | 否 | `1048576` / `32` | 超限中断 |
| `basic-framework.ai.model.max-attempts` | 单次调用最大尝试（含首次） | 否 | `3`（1–5，越界启动失败） | — |
| 其余 `ai.model.*`（`retry-backoff` 200ms、`max-output-chars` 262144、`stream-idle-timeout` 30s、`stream-queue-capacity` 64、`max-repair-steps` 3、`max-embedding-batch` 64） | 调用护栏 | 否 | 见左 | 越界启动失败 |
| `basic-framework.ai.outbound.default-level` | 未显式配置端点的外发上限 | 否 | `L2_INTERNAL` | 未识别等级启动失败 |
| `basic-framework.ai.outbound.endpoints."<端点编号>".level` / `.allowed-resources` | 端点级外发等级（`L1_PUBLIC` < `L2_INTERNAL` < `L3_PERSONAL` < `L4_SECRET`） | 需外发 L3/L4 时 | 空 | L3/L4 未显式列入即拒绝外发 |
| `basic-framework.ai.outbound.metering.estimate-when-missing` / `chars-per-token` | 上游缺失 usage 时的估算 | 否 | `false` / `4` | — |
| `basic-framework.ai.embed.assets-directory` | 嵌入页产物目录（**不含凭据**） | 用嵌入页时必填 | `embed-assets`（相对进程工作目录） | 缺 `asset-manifest.json` → 422 `AI_EMBED_ASSETS_NOT_STAGED` |
| `basic-framework.ai.embed.max-asset-bytes` | 单资产上限 | 否 | `5242880`（5 MiB，最小 1024） | 超限按"产物未就位"处理 |

### 2.7 L3 运行期配置（管理端，无环境变量）

| 配置对象 | 维护入口 | 关键字段 | 本轮事实 |
|---|---|---|---|
| 模型端点 | `/ai/model-endpoint`（菜单 4001；权限 `ai:model-endpoint:*`） | `base_url`、provider、上游 modelId、能力、`embeddingDimension`、`credential_ciphertext` + `credential_revision` | 凭据加密入库；轮换用独立动作，普通修改不重置密钥（[05-data-security-contracts](../ai-platform/05-data-security-contracts.md)） |
| 文件存储 | `/infra/file-config`（菜单 1237） | 存储器类型、`config` 密文（S3 的 endpoint/bucket/accessKey/accessSecret/region 等） | **快照中没有预置任何 `infra_file_config` 行**（`grep -c` = 0）：未配置主配置前文件上传不可用 |
| AI 应用 / 允许域 | `/ai/application`（菜单 4010） | `appCode`、状态、允许域 | 改允许域 → 配置版本 +1，嵌入壳 ETag 立即变化 |
| 知识库 / 文档 | `/ai/knowledge`（菜单 4090） | `retention_days`、入库与索引代状态 | 保留期自动撤销**尚未接线**（K07 证据 §9.4） |
| 服务与发布版本 | `/ai/service`（菜单 4030） | 资源绑定、发布版本、评测 | — |
| 评测套件 | `/ai/evaluation`（菜单 4120） | 套件/用例/运行 | 夹具为合成数据 |

## 3. 各组件的"最小可用组合"

> 目标：让 `GET /actuator/health` 返回 UP、管理端可登录、AI 面按需可用。逐项都是**最小集**，
> 不是推荐生产值。

| # | 组件 | 最小可用组合 | 判据（怎么知道成了） |
|---|---|---|---|
| 1 | MySQL | 一个 8.4 实例 + 应用账号（DML）+ 迁移账号（DDL）+ `DB_*`/`FLYWAY_*` 全部注入；**空库**启动让 Flyway 跑完整迁移链 | `flyway_schema_history` 末版本 = `db/migration` 最大编号（当前 **V84**，83 个文件）；启动日志 `Successfully applied ... now at version v84`；`PersistenceLifecycleIT` 同口径 |
| 2 | Redis | 一个 7.x 实例 + `REDIS_PASSWORD`；`REDIS_HOST/PORT` | 健康检查 UP 且能登录（验证码/会话走 Redis） |
| 3 | 对象存储/文件通道 | 管理端新建一个**主配置**（本地磁盘或 S3 兼容）并保存；S3 私有桶另配 `.pending/` 生命周期（[Runbook §1](../deployment.md)） | 文件上传成功且 `infra_file_config` 有 1 行 `master=1`；未配置时 `getMasterFileClient()` 返回 null |
| 4 | 模型端点 | `ai.enabled=true` + `capabilities` 至少含 `TEXT`；`ai.http.allowed-hosts` 列入端点主机（内网再加 `allow-private-targets=true`）；管理端建端点并注入凭据 | 管理端"连接测试/能力探测"通过；运行一次对话得到终态 run（能力探测确认的集合才是可发布范围） |
| 5 | 嵌入通道 | 在 #4 基础上 `capabilities` 加 `EMBEDDING`；端点 `embeddingDimension` 与目标向量集合维度一致 | 嵌入调用返回向量且维度校验通过；**换模型/换维度必须新建 index generation**（[06 §8](../ai-platform/06-upstream-upgrade.md)） |
| 6 | 向量索引 | Qdrant `qdrant/qdrant:v1.19.1@sha256:12364fe8…c9246a10`（K01 实测组合）+ HTTPS 基址 + API Key + 快照目录 | **生产装配待补**：`QdrantRestKnowledgeIndexAdapter` 目前**只在测试里构造**，`src/main` 没有把基址/API Key 变成配置键或 Bean；检索服务用 `ObjectProvider#getIfAvailable` 容错（未装配时不抛错，但知识检索不可用）。见 §7 |
| 7 | 嵌入页（可选） | 构建并暂存产物 → `ai.embed.assets-directory` 指向暂存目录 | `GET /app-api/ai/v1/embed/{appCode}` 200 且资产路径 200；未暂存返回 422（[embed-page.md](embed-page.md)） |
| 8 | 观测（可选） | `PROMETHEUS_ENABLED=true` + `PROMETHEUS_SCRAPE_TOKEN` | 无令牌 401 / 正确令牌 200；Prometheus target UP |

## 4. 健康检查与观测入口

| 入口 | 地址 / 命令 | 说明 |
|---|---|---|
| 健康检查 | `curl -fsS http://127.0.0.1:48080/actuator/health` | prod 暴露 `health` + `prometheus`；源：[application-prod.yaml](../../后端代码/basic-framework-boot/basic-framework-server/src/main/resources/application-prod.yaml) |
| 容器健康探测 | 镜像内置 `HEALTHCHECK`：`curl -fsS http://127.0.0.1:48080/actuator/health`（interval 30s / timeout 5s / start-period 90s / retries 3） | 非 root 运行；首次启动含 Flyway 迁移故放宽 start-period（[Dockerfile](../../后端代码/basic-framework-boot/basic-framework-server/Dockerfile)） |
| 指标抓取 | `GET /actuator/prometheus` + `Authorization: Bearer <PROMETHEUS_SCRAPE_TOKEN>` | 默认关闭；令牌规程见 [Runbook §2](../deployment.md) |
| 运行监控页 | 管理端 `/ai/observability`（菜单 4116，权限 `ai:observability:query`；重试按钮 `ai:observability:retry`） | 列表/详情/时间线/重试：`/ai/observability/run/{page,get,timeline,retry}`（Q03） |
| 用量与限额页 | 管理端 `/ai/usage`（菜单 4115，权限 `ai:usage:query`） | `/ai/usage/{page,summary,service-summary,quota-active}`（Q02/Q03） |
| 队列与韧性判读 | 见[队列积压与韧性可观测量](../operations/queue-backlog-and-resilience-observability.md) | 表/Job 日志/接口/应用日志的可观察量总表（O1–O9） |
| **不存在的入口** | `/observe` | 本仓库**没有** `/observe` 路由或其等价实现；最接近的是上面的两个管理端页面与 `/actuator/*`。不要按 `/observe` 写监控探针 |
| AI 业务指标 | 不存在 | `module-ai/src/main` 内无 `MeterRegistry`/`Counter`/`Gauge`/`@Timed`；`/actuator/prometheus` 只有框架级 JVM/HTTP/安全信号（队列文档 §2） |

### 4.1 与本卡相关的 Harness 门禁

统一入口 `sh .harness/verify.sh <gate>`，工作目录=仓库根（[.harness/verify.sh](../../.harness/verify.sh)）。

| 门禁 | 命令 | 与本卡的关系 |
|---|---|---|
| contracts | `sh .harness/verify.sh contracts` | 快照版本/表/权限/生命周期台账一致；迁移编号同步的**机器判据** |
| lockfile | `sh .harness/verify.sh lockfile` | 依赖图冻结（交付包可复现前提） |
| backend | `sh .harness/verify.sh backend` | 编译、单测、覆盖率棘轮、架构边界 |
| frontend | `sh .harness/verify.sh frontend` | 类型/lint/构建（前端交付产物前提） |
| integration | `sh .harness/verify.sh integration` | Testcontainers MySQL/Redis + Flyway 迁移链 + **打包 jar 冒烟**（承载 AT-063/064 的机器部分） |
| smoke | `sh .harness/verify.sh smoke` | 真实浏览器跨源套件（承载 AT-067 的运行期断言）；ADR 0046：nightly/release 层，PR 层不跑 |
| dependencies | `sh .harness/verify.sh dependencies` | SBOM + 镜像/锁文件漏洞扫描（HIGH/CRITICAL 即失败）。**本机因 Trivy 漏洞库镜像不可达无法运行**——环境缺口，不降低门禁（见 §7） |
| all | `sh .harness/verify.sh all` | lockfile + verify + dependencies + integration |

## 5. 部署前逐项核对表

- [ ] `.env` 由 `.env.example` 复制，**全部占位串已替换**；`grep -n "replace-with" .env` 无输出。
- [ ] 三个密码（MySQL 应用/迁移、Redis）与主密钥均来自密钥管理，未写入任何模板/工单/聊天记录。
- [ ] `DB_USERNAME != FLYWAY_USERNAME`（prod 校验会拒绝相同账号）。
- [ ] `CREDENTIAL_ENCRYPTION_KEY` 是 32 字节 Base64（`echo -n '<key>' | base64 -d | wc -c` = 32）。
- [ ] `CORS_ALLOWED_ORIGIN` 是精确 HTTPS 域名（无通配符/路径/本机地址）。
- [ ] 启用观测时 `PROMETHEUS_SCRAPE_TOKEN` 合规（64 位十六进制），且只以 Secret 文件挂载给 Prometheus。
- [ ] 数据库用**空库**或按 Runbook §1 先 baseline 快照；**不要**在已有 Flyway 历史的库上 baseline。
- [ ] 需要 AI 时，L2 的 `basic-framework.ai.*`（至少 `enabled` + `capabilities` + `http.allowed-hosts`）已随部署注入。
- [ ] 管理端留出初始化动作：激活/改密种子管理员、配置主文件存储、建模型端点与凭据、建 AI 应用与允许域。
- [ ] 备份与恢复演练已按 [Runbook §4](../deployment.md) 真实跑过一次（RTO/RPO 口径见 ADR 0006）。

## 6. 与既有文档的差异说明

- [部署与运维 Runbook](../deployment.md)：只讲操作步骤与 compose 编排，本文补它的"配置项全集 + 秘密来源 + 最小组合"。
- [嵌入页部署](embed-page.md)：嵌入页的构建/响应头/撤销细节，本文只保留两个配置键与判据。
- [部署与回退说明](deployment-and-rollback.md)：交付包→运行的最小步骤、回退条件与 AT-067 实测边界。

## 7. 已知缺口与待补（不得当作已完成）

| # | 缺口 | 影响 | 补齐条件 |
|---|---|---|---|
| 1 | **`docker-compose.yaml` 第 65 行 YAML 解析失败**（`SMS_CALLBACK_TOKEN`/`PROMETHEUS_*` 三项缩进多一级） | `docker compose config` 退出码 1（本机实测），**compose 编排当前无法渲染**，最小可用组合只能手工或用 Runbook §1 的 compose 命令之外的路径 | 需一次串行补丁修正缩进（文件不在本切片允许路径内，见汇报 §④） |
| 2 | AI 面在 `docker-compose.yaml`/`.env.example`/`deploy/config/app.env.example` 中均无变量/占位 | 部署方须自行注入 `basic-framework.ai.*`，易漏 | 交付包模板补齐 AI 接缝键后回填：**待补：见 Q09 证据 §交付包** |
| 3 | Qdrant 生产装配缺失（仅有测试构造） | 知识检索在本机无"生产可用"结论 | 需卡片授权在 `module-ai`/`server` 装配基址与 API Key 配置键；**待补：见 Q09 证据 §索引装配** |
| 4 | `dependencies` 门禁（Trivy）不可运行 | 交付包/镜像的漏洞结论未出 | 漏洞库可达的环境 |
| 5 | 凭据主密钥轮换无批量重加密工具 | 换钥需人工逐条重写 L3 凭据 | 需新卡 |
| 6 | `infra_file_config` 无种子数据 | 新装环境文件上传默认不可用（需管理端配置） | 属部署步骤，已写入 §3 #3 |

## 8. 未验证项（如实列出）

1. 本机 **没有运行中的 MySQL/Redis/后端**：本文所有命令与判据来自代码、模板与既有证据文档，
   **未在本机对生产 profile 实跑**；生产授权与真实凭据均未提供。
2. 真实模型端点、真实 Qdrant 生产装配、TLS 端到端（K01 §5）未验证。
3. `sh .harness/verify.sh dependencies` 因环境缺口未跑（F02 §7、Q03 §8）。
4. L3 配置的加密链路只在测试与框架单测中覆盖（`FileConfigCredentialCodec` 等），未在真实库演练换钥。
