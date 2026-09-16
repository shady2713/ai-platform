# basic-framework

basic-framework 是一个基于 Spring Boot 3 / Java 17 的多模块后端基础脚手架，包含系统管理与基础设施模块，可作为内部业务系统的后端起点。

## 模块说明

- `basic-framework-dependencies`：统一依赖版本管理
- `basic-framework-core`：公共能力与 Spring Boot Starter
- `basic-framework-server`：应用启动模块
- `basic-framework-module-system-api`：system 对外契约薄模块（CommonApi + DTO）
- `basic-framework-module-system`：系统管理模块
- `basic-framework-module-infra-api`：infra 对外契约薄模块（CommonApi + DTO）
- `basic-framework-module-infra`：基础设施模块

## 运行要求

- JDK 17+
- 使用仓库内 Maven Wrapper（所有构建命令只运行 `./mvnw`）
- MySQL 8.x（仓库唯一随附驱动、迁移与集成验证的数据库）
- Redis

## 本地启动

1. 修改 `basic-framework-server/src/main/resources/application-local.yaml` 中的数据源与中间件配置。
2. 准备业务所需的数据库结构与初始化数据。
3. 执行 `./mvnw -q verify` 验证工程。
4. 运行 `basic-framework-server` 模块中的 `BasicFrameworkServerApplication` 启动项目。

数据库交付以 Flyway 迁移链为准。手工快照必须通过 contracts 的版本检查与 integration 的
真实 MySQL 导入、结构和关键种子比对；接管步骤见 [部署文档](../../docs/deployment.md)。

IntelliJ HTTP Client 的非敏感地址模板位于根目录 `http-client.env.json`；令牌只在本机
私有环境文件中维护，不写回仓库。

## 凭据配置

生产环境通过 `CREDENTIAL_ENCRYPTION_KEY` 注入 Base64 编码的 32 字节随机主密钥，用于短信渠道和文件存储凭据的 AES-GCM 加密。管理员初始化与数据库接管步骤见根目录 `docs/deployment.md`。

## 容器镜像

安全指标通过 Prometheus registry 导出，抓取端点默认关闭并使用独立认证链；接入步骤见
[监控部署说明](../../docs/deployment.md#2-健康检查与-actuator-暴露面)。

`basic-framework-server/Dockerfile` 将已构建 JAR 打包为非 root 运行镜像；根目录
`docker-compose.yaml` 提供 MySQL、Redis 与后端的最小生产形态。所有外部镜像同时锁定
补丁版本和 OCI 摘要，更新时必须同步通过仓库的容器镜像契约测试。生产 Secret 只由部署
环境注入，完整步骤见 `docs/deployment.md`。
