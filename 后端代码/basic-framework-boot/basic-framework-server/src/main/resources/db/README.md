# Database Migration

本目录由 Flyway 管理数据库版本。

## 基线

- `migration/V1__baseline.sql` 是当前基础框架的极简基线 SQL。
- 新空库启动服务时会自动执行 V1 初始化。
- 非空库默认禁止自动 baseline。导入最新快照后须由迁移账号按快照版本显式执行 baseline，再启动应用；步骤见根目录 `docs/deployment.md`。

## 后续规则

- 后续数据库结构或种子数据变更，不再直接改历史版本文件。
- 新增迁移文件按 `V2__description.sql`、`V3__description.sql` 递增。
- 迁移编号存在历史空洞（如无 V7）；以现存文件的最大号继续递增，不代表漏提交。
- 禁止在迁移脚本中写 `CREATE DATABASE` 和 `USE database`，目标库由 JDBC 连接串决定。
- 禁止提交真实手机号、密钥、token、生产密码等敏感数据。
- 禁止启用 Flyway clean，当前配置已设置 `clean-disabled: true`。

## 手工 SQL

`数据库文件/basic_framework.sql` 保留为人工部署和排查用的完整最新快照，由完整 Flyway
迁移链生成并做空库导入复验；服务启动时仍以 Flyway 目录为准。
