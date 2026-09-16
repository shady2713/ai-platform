# ADR 0009：生产环境分离 Flyway 与应用数据库账号

- 状态：已接受

## 背景

可执行 jar 在真实 MySQL 8.4 上使用非 root 业务账号启动时，Flyway 11 需要读取
`performance_schema.user_variables_by_thread` 并执行 schema 迁移。把这些权限授予长期运行的
应用账号会扩大 Web 进程被利用后的数据库权限；继续使用 root 又违反生产环境 fail-closed 基线。

## 决策

1. `prod` profile 必须通过 `FLYWAY_USERNAME` / `FLYWAY_PASSWORD` 注入独立迁移账号；`FLYWAY_URL` 可覆盖默认沿用的主数据源地址。
2. 迁移账号拥有目标 schema 的 DDL/DML 及 Flyway 所需元数据读取权限，运行时应用账号只拥有业务 DML 权限。
3. `ProductionConfigurationEnvironmentPostProcessor` 在启动期拒绝缺失、弱默认值或与应用账号相同的迁移账号。
4. `PackagedJarBootSmokeIT` 用两个最小授权账号启动真实 fat jar，持续验证该权限边界、空库迁移和健康探测。

## 后果

- 生产部署需要管理两组数据库凭据，并在应用启动前完成账号授权。
- Web 应用进程不再因自动迁移而长期持有 DDL 权限；迁移凭据仍存在启动进程环境中，应由部署平台 Secret 管理并限制读取。
- Flyway 或 MySQL 升级若改变元数据权限需求，packaged-jar 冒烟测试会在发布前阻断。
