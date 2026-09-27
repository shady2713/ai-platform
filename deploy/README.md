# deploy —— 可复现交付包

本目录是 Q09 交付包切片的唯一入口：一条命令从权威来源生成交付物清单并逐项校验。
产物与版本事实全部来自构建输出、`db/migration`、CycloneDX SBOM 与 `pnpm licenses`，
清单里不出现手写版本号或手写许可结论。

## 1. 出包

```bash
# 在仓库根执行；后端需要 JDK 17（JAVA_HOME），前端需要 pnpm（PATH）
node deploy/package-delivery.mjs --offline
```

| 参数 | 作用 |
|---|---|
| `--output=<目录>` | 指定输出目录（默认 `deploy/dist/<交付名>`） |
| `--no-build` | 复用已有构建输出，只重新收集与校验 |
| `--offline` | Maven 加 `-o` 构建后端（CycloneDX 插件要求在线模式，SBOM 步骤仍走在线 Maven） |
| `--skip-sbom` | 跳过 SBOM（降级排查用；产物被标记为不完整） |

脚本幂等：默认先删除并重建输出目录，清单逐次重算；构建失败、产物缺失、嵌入产物清单与
磁盘 sha256 不一致、快照声明版本与迁移链不一致都会直接以非零退出码失败。

## 2. 产物结构

```
backend/basic-framework-server.jar      Spring Boot 可执行 jar（含 Flyway 迁移）
backend/db-migration/                   db/migration 迁移集副本（Flyway CLI 升级/排查用）
frontend/admin/                         web-ele 生产构建产物（nginx 镜像内容）
frontend/chat/                          ai-chat 生产构建产物
frontend/embed-assets/                  嵌入页自托管产物（asset-manifest.json + assets/）
frontend/sdk/ai-embed-sdk-<版本>.js     版本化嵌入 SDK 单文件产物
sbom/backend-bom.json                   CycloneDX 聚合 SBOM（Maven 解析结果）
sbom/frontend-licenses.json             pnpm licenses list --prod 原始输出（工作区视角）
NOTICE                                  由上述两份清单聚合的许可清单（脚本生成）
migrations/manifest.json|MIGRATIONS.md  迁移清单（版本/描述/字节/sha256，脚本生成）
config/app.env.example                  部署配置模板（占位符，无真实凭据）
MANIFEST.json / SHA256SUMS / version.json  交付清单、校验和与版本事实
```

后端 jar 不含前端产物：管理端由独立 nginx 镜像提供，Chat/嵌入页资产由后端按
`basic-framework.ai.embed.assets-directory` 指向的目录提供服务（见配置模板与
`docs/deployment.md`）。校验方式：`unzip -l backend/basic-framework-server.jar | grep -c 'static/'` 为 0。

## 3. 配置模板与秘密

`config/app.env.example` 是部署变量模板：只含占位符，逐项说明秘密来源（环境变量 /
密钥管理平台）与 prod 启动期 fail-closed 校验规则。嵌入产物目录不是环境变量直连，
通过 `ARGS="--basic-framework.ai.embed.assets-directory=..."` 注入，说明写在模板内。

## 4. 与验收的关系

- 交付物可用性（AT-064）：jar 冒烟由 `PackagedJarBootSmokeIT` 承担；嵌入产物直出由
  `PackagedJarEmbedAssetsIT` 在本包产物就位后执行。
- 迁移一致性（AT-063）：`ReleasedBaselineUpgradeIT` 在真实 MySQL 上验证空库全链迁移、
  已发布基线（V46）升级与快照 Schema 一致；`migrations/manifest.json` 同时记录快照声明版本。
- 本目录只做本地/容器演练，不执行任何生产动作。
