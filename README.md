# 企业 AI 中台

> 企业AI中台开发副本：先阅读[开发入口](开发入口.md)，产品需求、集成方案和任务卡位于[docs/ai-platform](docs/ai-platform/README.md)。以下保留基础框架的使用说明与工程入口。

当前已具备基础框架与产品开发方案，AI功能按任务卡逐项实施。目录、Git迁移及原框架基线管理见[项目初始化与迁移](docs/ai-platform/00-project-bootstrap.md)。

Spring Boot 3 / Java 17 后端 + Vue 3 / TypeScript 管理端的基础框架单体仓库。
工程规则的唯一权威来源是根 `AGENTS.md`；业务开发从 `docs/development-guide.md` 进入。

## 仓库布局

```
后端代码/basic-framework-boot/     Maven 多模块后端
  basic-framework-dependencies/    统一依赖版本（BOM）
  basic-framework-core/            starters = 能力接缝（目录见 docs/capability-catalog.md）
  basic-framework-module-system-api/  system 对外契约薄模块（CommonApi + DTO）
  basic-framework-module-system/   系统管理模块
  basic-framework-module-infra-api/   infra 对外契约薄模块（CommonApi + DTO）
  basic-framework-module-infra/    基础设施模块
  basic-framework-server/          启动装配（唯一应用入口）
前端代码/basic-framework-admin/    pnpm + turbo monorepo（主应用 apps/web-ele）
数据库文件/                        手工 SQL 快照（受控，见下文数据库节）
docs/                              契约、ADR、安全规范、开发指南
scripts/                           仓库级校验脚本（CI 直接调用）
.harness/                          DeepSeek Harness 工程模型的可执行门禁适配层
ops/prometheus/                    Prometheus 告警规则（安全信号，威胁模型 T1/T2/T3）
```

## 环境要求

- JDK 17（Maven 由 `./mvnw` 包裹，无需本机安装）
- Node.js >= 20.19，pnpm 10.28.2（`corepack enable` 自动对齐）
- MySQL 8、Redis 7（本地开发可用 Docker 起）
- Docker（`dependencies` 漏洞扫描和 `integration` 集成测试需要）

## 首次 bootstrap

以下命令使用 Bash，从仓库根目录执行；子 shell 结束后自动返回根目录。
这是安装与后端快速检查，完整验证见下方 verify。

```bash
# 1. 创建空数据库，启动时由 Flyway 执行完整迁移链（不要先导入最新快照）
mysql -uroot -p -e "CREATE DATABASE basic_framework CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"

# 2. 后端：编译、单测、模块覆盖率门槛、格式与架构检查
(cd 后端代码/basic-framework-boot && ./mvnw -q verify)

# 3. 前端：安装锁定依赖
(cd 前端代码/basic-framework-admin && corepack enable && pnpm install --frozen-lockfile)
```

种子管理员默认禁用。首次启动前请通过环境变量 `BOOTSTRAP_ADMIN_PASSWORD` 设置每个部署独立的强口令，激活后移除该变量，并按登录页提示轮换密码；详见 [部署初始化](docs/deployment.md)。

## 本地 run

分别打开两个终端，均从仓库根目录开始。以下为 Bash 命令；PowerShell 使用
`mvnw.cmd`，并将 `cd` 与构建命令分行执行。

```bash
# 后端（默认 local profile，端口 48080；数据库/Redis 连接走 application-local.yaml）
# 先 install 让本地仓库拿到全部兄弟模块 jar，再在 server 模块启动
cd 后端代码/basic-framework-boot && ./mvnw -q install -DskipTests
./mvnw spring-boot:run -pl basic-framework-server
```

```bash
# 前端（Element Plus 主应用）
cd 前端代码/basic-framework-admin && pnpm dev:ele
```

容器化部署与运维处置（compose 编排、健康检查、Flyway 修复、备份恢复演练）见
`docs/deployment.md`。

## verify（提交前必跑，可直接映射为阻断 CI job）

在仓库根目录执行。Windows 本地与 Linux GitHub Actions 使用同一组 Harness 命名门禁。Windows 完整 Harness 执行
`& .\.harness\verify.ps1 all`；Linux 执行 `sh .harness/verify.sh all`。将 `all`
替换为 `--list` 可查看单独边界。`all` 包含下表六个 Harness 门禁；Git 历史秘密扫描
由 CI 的独立 job 执行，提交钩子另行运行，不属于 `all`。

| 命令 | 门禁职责 |
|---|---|
| Harness `backend` | backend-build：编译、单测、JaCoCo 模块覆盖率门槛、Spotless、ArchUnit 模块边界（规则 A-D） |
| Harness `integration`（需 Docker） | backend-integration：Testcontainers MySQL/Redis、Flyway 迁移与快照接管、真实 SQL、生产配置 packaged jar 健康探测，以及后端单文件覆盖率棘轮 |
| Harness `frontend` | frontend-build：循环依赖、依赖完整性、显式 any 棘轮、typecheck、cspell、lint、Vitest 覆盖率、生产构建与产物检查、单文件覆盖率棘轮 |
| Harness `dependencies`（需 Docker） | dependency-scan：扫描 Maven 解析态 SBOM、pnpm 锁文件、容器配置和最终应用镜像，阻断 HIGH/CRITICAL；不需要 NVD API Key |
| Harness `contracts` | repo-contracts：脚本测试及字段、敏感数据处理、源码质量、starter 文档、控制器校验、数据生命周期、数据权限、权限目录、安全信号、门禁接线与拒绝测试、例外台账检查 |
| Harness `lockfile` | lockfile-integrity：冻结安装验证依赖图与已提交锁文件一致 |
| `bash scripts/doctor.sh`（仓库根） | 本地环境体检（非 CI 门禁）：JDK≥17/Node/pnpm/wrapper/锁文件五查，MySQL 缺失仅告警 |
| 提交时 lefthook 自动执行 | repo-hygiene：空白/换行、secret-scan、commitlint |

`.github/workflows/verify.yml` 将六个 Harness 门禁和历史秘密扫描映射为阻断 job，并以 `aggregate` 作为唯一
聚合检查；GitHub 仓库仍需在 `main` 的 branch protection/ruleset 中把 `aggregate`
配置为合并必需检查。门禁职责和维护约束见 `.harness/README.md`，工程规则仍以根
`AGENTS.md` 为唯一权威来源。`backend-integration` 直接使用 GitHub 托管 Ubuntu
runner 的 Docker daemon；自托管 runner 必须使用 Linux、安装 Docker 并允许 runner
账号访问 daemon。Runner 能力不足属于 CI 环境失败，不得跳过集成门禁。

## 数据库纪律

- `数据库文件/basic_framework.sql` 是便于人工初始化/审阅的受控最新快照，结构变更时随新增 migration 同步；Flyway migration 才是运行时权威历史。
- 一切结构变更在 `basic-framework-server/src/main/resources/db/migration/` 新增 `V{n}__<说明>.sql`；已执行迁移永不修改。
- 本地/CI 启动时 Flyway 自动 `validate + migrate`；默认禁止自动接管非空库，快照接管须先显式建立对应版本基线，见 `docs/deployment.md`。
- 生产环境必须分别注入 `FLYWAY_USERNAME` / `FLYWAY_PASSWORD` 与运行时 `DB_USERNAME` / `DB_PASSWORD`：迁移账号持有 DDL 和 Flyway 所需元数据读取权限，应用账号只持有业务 DML 权限；两者相同会在启动期失败。

## 新增业务入口

新增实体/字段/模块的唯一流程入口是 `docs/development-guide.md`；字段契约登记在
`docs/contracts/field-catalog.yaml`（改动会被 CI 漂移检查拦截）。非平凡决策在
`docs/adr/` 新增一条记录。数据分级与威胁边界分别见
`docs/security/data-classification.md` 和 `docs/security/threat-model.md`；高风险操作入口与授权边界见
`docs/security/high-risk-operations.md`。
