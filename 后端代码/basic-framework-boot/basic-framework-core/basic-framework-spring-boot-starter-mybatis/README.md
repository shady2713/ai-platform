# basic-framework-spring-boot-starter-mybatis

数据源、MyBatis-Plus 与通用持久化能力接缝。

## 提供的能力

- 自动配置数据源、Mapper 扫描、分页拦截器与数据库主键生成器。
- `BaseDO`、`SoftDeletableDO` 统一审计字段和逻辑删除模型。
- `BaseMapperX`、`QueryWrapperX`、`LambdaQueryWrapperX` 提供受控的通用查询扩展。
- `DefaultDBFieldHandler` 从 `CurrentUserProvider` 获取当前用户并填充审计字段。
- 显式携带 JSON 类型处理器直接需要的 Jackson 与 Java Time 模块，不依赖无关驱动的传递依赖。
- 只随框架交付并验证 MySQL 8 驱动；其它数据库适配分支属于扩展点，不捆绑第三方驱动。

## 使用约束

- 业务 Mapper 只继承本 starter 的公开扩展，不跨模块访问其他模块的 Mapper 或数据对象。
- 展示字段由业务查询显式批量补全，不引入隐式字段翻译组件或隐藏查询，以免绕过数据权限。
- SQL 与表结构以 Flyway 迁移为准；分页、批量和联表查询必须显式限制结果规模。
- 新增逻辑删除表必须继承 `SoftDeletableDO`，并同步生命周期与数据权限契约。
- 数据库类型无法识别或主键生成器不受支持时启动失败，不允许回退到不确定实现。
- 切换到其它数据库时，应用必须自行声明 JDBC 驱动、提供完整迁移与集成测试；本仓库不声明其开箱即用。

## 验证

模块测试覆盖自动配置、查询包装器、审计字段、数据库类型与主键生成分支，并由 ArchUnit、JaCoCo 和数据契约门禁共同阻断回归。
