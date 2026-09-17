# F01 门禁与启动证据（2026-09-16）

本记录是 [F01 建立授权工作副本与基线证据](../tasks/F01.md) 在本机（Linux 开发机）的执行证据。
六项 Harness 门禁、前后端启动与数据库初始化均已实测；原始命令输出保存在 `.local-state/f01-gates/`（Git 忽略）。

## 1. 工作副本与基线核对

| 项目 | 实测值 |
|---|---|
| 工作副本 | 本仓库根（开发副本，非只读源框架） |
| 上游基线 commit | `23a7edb375939a84bdfd01e8d1d68e7b016aab59`（只读参考） |
| 快照标签 | `framework-baseline-23a7edb37593`，解析为提交 `b41fdc185851a41f39bc553f50a06e8af977a4a2`，树 `49f9a5c82dcd9bbac2eba1994bd675a61ba11114`（与 `docs/framework-baseline.json` 约定值一致） |
| 台账条目 | 2576 条；随机抽查 8 个文件 `gitBlob` 与 HEAD 全部一致 |
| 分支与 HEAD | `main` / `e736266` |
| 工具链 | OpenJDK 17.0.20、Node 22.23.2、pnpm 10.28.2、Docker 29.7.2、MySQL 8.4.11、Redis 7.4.11 |

台账中 `sha256` 字段语义为“Windows 工作树规范化前字节”，与 Linux 克隆的 LF 行尾天然不同，因此以 `gitBlob` 作为规范内容比对依据；抽查未发现内容漂移。

## 2. 六项门禁结果

统一命令：`sh .harness/verify.sh <gate>`，工作目录为仓库根。

| 门禁 | 退出码 | 关键结论 |
|---|---|---|
| contracts | 0 | 字段目录漂移 0；生命周期台账 39 张表全部登记；权限目录 76 个权限码；门禁接线 16 个检查脚本双 provider 对齐；例外台账 1 条未过期 |
| lockfile | 0 | `pnpm install --frozen-lockfile --ignore-scripts` 通过，锁文件与依赖图一致 |
| backend | 0 | `clean verify`：编译、单测、JaCoCo 模块覆盖率门槛、Spotless、ArchUnit 模块边界全部通过 |
| frontend | 0 | cspell 897 文件 0 问题、lint、生产构建（166 个 JS 产物通过 no-undef 校验）、Vitest 语句覆盖率 89.82%、单文件覆盖率棘轮通过 |
| dependencies | 0 | 四段扫描全部 0 命中：后端 CycloneDX SBOM（Java/jar 0）、前端锁文件（pnpm 0）、Dockerfile 配置 0 项、应用镜像扫描 passed |
| integration | 0 | Testcontainers MySQL/Redis、Flyway 迁移链与快照接管、真实 SQL、`PackagedJarBootSmokeIT` 等 24 个 IT 全部通过 |

测试用例汇总（surefire + failsafe 报告）：198 个报告文件、947 个用例、0 失败、0 错误、0 跳过。

### 2.1 首次执行失败与修复

`dependencies` 首次执行退出码 1，两阶段原因：

1. 环境：Trivy 漏洞库默认源 `mirror.gcr.io` 在本网络连接超时（详见第 5 节环境适配）。
2. 真实发现：前端锁文件命中 4 个 HIGH（后端 SBOM、Dockerfile、应用镜像均 0）：

| 库 | CVE | 修复版本 |
|---|---|---|
| js-yaml 3.15.1 | CVE-2026-84375 | 3.15.2 |
| js-yaml 4.3.1 | CVE-2026-84375 | 4.3.2 |
| smol-toml 1.6.1 | CVE-2026-85730 | 1.7.1 |
| svgo 4.0.2 | CVE-2026-84370 | 4.1.0 |

修复方式为把 `pnpm-workspace.yaml` 既有的安全钉版升级到厂商修复版（未放宽规则、未加例外），变更见第 4 节；复验后 `dependencies` 退出码 0。

## 3. 前后端启动与数据库初始化

| 项目 | 结果 |
|---|---|
| 后端 | `./mvnw spring-boot:run -pl basic-framework-server`（local profile，端口 48080），`/actuator/health` 返回 `{"status":"UP"}`，根路径 401 为安全拦截预期行为 |
| 数据库 | Flyway 对既有库执行 V39 → V46 共 7 个迁移，`Successfully applied 7 migrations ... now at version v46`；`validate-on-migrate` 通过 |
| 前端 | `pnpm dev:ele`（端口 5174）HTTP 200；接口直连 `http://127.0.0.1:48080/admin-api`，CORS 预检与实际请求均返回 `Access-Control-Allow-Origin: http://localhost:5174` |
| 已知非阻断现象 | local profile 显式排除 `QuartzAutoConfiguration`，`JobStartupRegistrar` 仍会在启动时记录一条“定时任务启动注册失败”ERROR；调度器在本地按设计不启用，功能不受影响 |

## 4. 本次变更（依赖安全钉版升级）

变更文件：`前端代码/basic-framework-admin/pnpm-workspace.yaml`、`前端代码/basic-framework-admin/pnpm-lock.yaml`。

| 覆盖项 | 原值 | 新值 | 原因 |
|---|---|---|---|
| `svgo` | 4.0.2 | 4.1.0 | CVE-2026-84370 |
| `smol-toml`（新增钉版） | 1.6.1（cspell 传递依赖） | 1.7.1 | CVE-2026-85730 |
| `js-yaml@<4.0.0` | 3.15.1 | 3.15.2 | CVE-2026-84375 |
| `js-yaml@>=4.0.0` | 4.3.1（范围 `<4.3.1`） | 4.3.2（范围 `<4.3.2`） | CVE-2026-84375 |

连带变化（均由 svgo 4.1.0 自身依赖决定）：`css-select` 5.2.2 → 6.0.0、新增 `css-what` 7.0.0、`sax` 1.6.0 → 1.6.1。锁文件共 7 行差异，无其他包变动。

复验（全部退出码 0）：lockfile、frontend（含生产构建与 947 用例）、dependencies、contracts。

## 5. 环境适配（不修改门禁语义）

1. **JDK**：本机无系统 JDK 且 sudo 需要密码，免 root 安装 OpenJDK 17.0.20 到用户目录（`~/.local/opt/jdk17`），并修复了解压产物中指向 `/etc/java-17-openjdk`、`/etc/ssl/certs/java/cacerts` 的失效软链；由于该 JDK 的 JSSE 按 JKS 解析默认信任库，重新生成 JKS 格式 cacerts（121 个 Mozilla CA），否则 Maven 任何 HTTPS 请求报 `trustAnchors parameter must be non-empty`。
2. **sudo 透传垫片**：`dependencies` 门禁含两处 `sudo chmod`（放宽 `docker save` 归档与报告文件权限）。本机 `docker save` 产出的归档属当前用户（0600），以属主身份 chmod 与提权 chmod 结果等价；执行时仅在 PATH 前部放置透传垫片，未修改 `.harness/verify.sh`。
3. **Trivy 数据预热**：默认源 `mirror.gcr.io` 在本网络不可达。改用同源官方 `ghcr.io`（漏洞库、Java 库、checks bundle，镜像本身按 harness 固定 digest 校验）预热命名卷 `basic-framework-trivy-cache` 后，门禁原样命令全部通过；未改动门禁脚本与其固定镜像 digest。
4. **umask**：本机 umask 0007 使生成文件为 0660，而 Trivy 容器以非属主身份运行（`--cap-drop ALL`，无 DAC_OVERRIDE）无法读取。门禁执行采用 CI 等价 umask 022，并对工作树执行 `a+rX`。

## 6. 与基线快照的差异清单

相对标签 `framework-baseline-23a7edb37593`：新增 141 个文件（`docs/ai-platform/**`、`.gitattributes`、`docs/adr/0048-*.md` 等）、修改 5 个（`.gitignore`、`README.md`、`lefthook.yml`、`scripts/secret-scan.mjs`、`scripts/secret-scan.test.mjs`）、删除 0 个。

工作树（未提交）差异 7 个文件：`后端代码/basic-framework-boot/mvnw` 权限位 100644→100755（[开发入口](../../../开发入口.md) 要求的 Linux 步骤）；`scripts/vsh`、`scripts/turbo-run` 的 bin 权限位与 `dist` stub 重建（`pnpm install` postinstall 产生）；本次依赖钉版升级的 2 个文件。

## 7. 未验证项

- 真实模型调用、外部业务系统接入、许可义务与真实端到端链路（Q06 建立真实浏览器门禁前，浏览器验收命令不存在）。
- 生产部署、容量与故障恢复验证（Q07/Q09 范围）。
- 原只读源框架不在本机，未做源目录只读复核（初始化阶段已完成并有记录）。

## 8. 结论

F01 的放行条件（六项门禁、前后端启动、数据库初始化、基线与差异清单）已满足；第 4 节的依赖钉版升级属本次新增变更，提交评审时应一并确认。
