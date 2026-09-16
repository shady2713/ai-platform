#!/bin/sh
# 仅在 mysql-data 卷首次初始化时执行（docker-entrypoint-initdb.d 语义）；
# 已有账号不会随环境变量变化而更新；凭据轮换使用 ALTER USER，见 docs/deployment.md。
# 按 ADR 0009 创建分离的迁移/应用账号，授权范围与 PackagedJarBootSmokeIT 验证集一致。
# 密码经环境变量注入；为避免 SQL 拼接转义问题，密码不得包含单引号、双引号、$ 或反引号。
set -eu

mysql --user=root --password="${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE USER IF NOT EXISTS '${FLYWAY_USERNAME}'@'%' IDENTIFIED BY '${FLYWAY_PASSWORD}';
GRANT ALL PRIVILEGES ON \`${MYSQL_DATABASE}\`.* TO '${FLYWAY_USERNAME}'@'%';
GRANT SELECT ON performance_schema.user_variables_by_thread TO '${FLYWAY_USERNAME}'@'%';
CREATE USER IF NOT EXISTS '${DB_USERNAME}'@'%' IDENTIFIED BY '${DB_PASSWORD}';
GRANT SELECT, INSERT, UPDATE, DELETE ON \`${MYSQL_DATABASE}\`.* TO '${DB_USERNAME}'@'%';
FLUSH PRIVILEGES;
SQL
