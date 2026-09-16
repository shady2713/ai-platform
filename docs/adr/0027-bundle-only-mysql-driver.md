# ADR 0027：默认只交付 MySQL 驱动

- 状态：已接受

## 背景

仓库的 Flyway 迁移、数据库快照和 Testcontainers 集成验证均以 MySQL 8 为事实源，但
MyBatis starter 仍直接声明 Oracle、PostgreSQL、SQL Server、达梦、人大金仓、openGauss
和 TDengine 驱动。即使这些依赖标记为 optional，它们仍进入 starter 自身的编译与测试
类路径；其中 TDengine 还传递引入旧版 Apache HttpClient 与 `commons-logging`，和 Spring
的 `spring-jcl` 形成冲突警告。仓库没有对应数据库迁移，无法证明这些驱动开箱即用。

## 决策

1. 框架只直接声明并验证 MySQL 8 驱动。
2. 保留经过单元测试的数据库方言、主键生成与 Quartz delegate 扩展分支，但不捆绑其它
   JDBC 驱动。
3. 采用其它数据库的应用必须自行声明驱动、提供匹配的迁移，并增加真实数据库集成测试；
   未满足这些条件时不得宣称受支持。
4. 删除 BOM 中不再被仓库消费的驱动版本，避免无效配置和供应链噪声。
5. Windows 与 Linux contracts gate 都运行数据库驱动边界测试，阻断上述驱动重新进入
   任一 Maven 模块，并验证 MyBatis 对 Jackson 的直接依赖没有退化。

## 后果

- 默认依赖图与 SBOM 更小，`commons-logging` 类路径冲突被消除。
- 数据库支持声明与实际迁移、测试证据一致。
- 方言扩展能力仍保留，但新增正式数据库支持必须同时交付驱动、迁移、文档和集成门禁。
