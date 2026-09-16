# 部署与运维 Runbook

> 定位：容器化部署与运维处置的唯一入口。配置事实不在此重复——环境变量与暴露面以
> `application-prod.yaml` 为准，账号分离前提见
> [ADR 0009](adr/0009-separate-flyway-database-principal.md)，RTO/RPO 目标见
> [ADR 0006](adr/0006-greenfield-product-operational-baseline.md)；本文只讲操作步骤。

本文 shell 示例使用 Bash 与 Linux 容器；除另有说明外，从后端目录
`后端代码/basic-framework-boot` 执行。生产操作前在隔离环境验证对应发布版本。

## 1. compose 编排与初始化

编排文件：[`后端代码/basic-framework-boot/docker-compose.yaml`](../后端代码/basic-framework-boot/docker-compose.yaml)
（MySQL 8.4 + Redis 7 + 后端 prod profile）。外部镜像同时锁定补丁版本和 OCI 摘要；升级
镜像时必须显式更新摘要并通过容器镜像契约测试。秘密与域名全部经 `.env` 注入，模板见同目录
`.env.example`（占位值不可运行，必须全部替换；缺失变量时 compose 直接拒绝渲染）。

```bash
# 1. 构建可执行 jar（镜像分层拷贝依赖 target/basic-framework-server.jar）
./mvnw -q clean package -DskipTests
# 2. 配置环境并替换全部占位值
cp .env.example .env
# 3. 构建镜像并启动（app 等待 MySQL/Redis 健康后启动）
docker compose up -d --build
# 4. 观察状态与日志
docker compose ps
docker compose logs -f app
```

MySQL 的迁移/应用双账号由 `script/mysql/init/` 在数据卷首次初始化时创建（授权范围与
packaged-jar 冒烟测试的验证集一致）。已有数据卷不会重新执行初始化脚本，仅修改 `.env`
不会改变数据库账号密码。已有实例按下文轮换凭据；不得用 `docker compose down -v`
处理密码变更，该命令会删除数据库、Redis 和日志卷。生产平台按 ADR 0009 配置同等权限的两组账号。

种子管理员默认禁用，密码字段是不可用于登录的封存标记。仅当部署者显式提供
`BOOTSTRAP_ADMIN_PASSWORD` 时，启动初始化器才按密码策略校验并激活该账号；口令必须在
每个部署中独立生成。首次登录仍要求用户改密。激活后删除该环境变量，重启不会覆盖已激活
账号。迁移只封存仍持有旧交付物固定摘要的种子账号，已经自行改密的账号不受影响。
旧默认口令泄漏时，首登改密不能防止他人抢先激活，因此不能把强制改密当作独立初始化凭据的替代。

首次建库优先使用空库并让 Flyway 执行完整迁移链。[手工快照](../数据库文件/basic_framework.sql) 的版本以文件头声明为准，仅用于
人工导入或排查；它没有 Flyway 历史记录。若选择导入该快照，必须先使用迁移账号执行
使用第 3 节的 `run_flyway baseline -baselineVersion=<快照文件头声明的版本号>` 建立基线，再启动应用。
禁止将最新快照按版本 1 接管；默认 `baseline-on-migrate=false` 会拒绝这种非空库启动。
已有 Flyway 历史的数据库直接升级，不执行 baseline，不修改历史校验和。

### 含 V40-V46 发布的升级注意

**种子管理员改密口径**：V40 将 id=1 的 `must_change_password` 无条件置位。即使部署者已在
旧版本自行改密，升级到本发布后该账号首次登录仍会被要求再改密一次；这是有意的
fail-closed 语义，不代表凭据泄漏。

**V45 联系方式唯一性预检**：V45 为 `system_users` 的 mobile/email 建立"未删除用户内唯一"的
函数式唯一索引，迁移本身不自动清理重复数据（禁止自动覆盖或删除）。升级前用迁移账号执行
只读预检：

```sql
-- 分组表达式与 V45 函数式唯一索引严格一致：TRIM 后空白按 NULL 处理（不参与约束）
SELECT NULLIF(TRIM(mobile), '') AS mobile_key, COUNT(*) FROM system_users
 WHERE deleted = b'0' GROUP BY mobile_key HAVING COUNT(*) > 1;
SELECT NULLIF(TRIM(email), '') AS email_key, COUNT(*) FROM system_users
 WHERE deleted = b'0' GROUP BY email_key HAVING COUNT(*) > 1;
```

任一查询非空即停止升级，由业务方核查每条联系方式的归属后手工归并或清空，再重新升级。
V45 失败的处置遵循第 3 节通用流程：MySQL DDL 隐式提交，失败时前置的空白值转 NULL 与
列默认值调整可能已落库（均为幂等变更，重跑安全）；修正数据后按第 3 节执行
`flyway repair` 清除失败记录，再启动应用让 Flyway 重放 V45。

contracts 门禁校验快照声明版本与迁移链一致；integration 门禁在真实 MySQL 上执行快照导入、
显式基线以及与空库迁移结果的结构和关键种子比对。更新快照必须同时通过这两道门禁。

导入快照后、启动应用前，执行以下只读校验确认种子管理员处于封存态：

```sql
-- bit(1) 列需 +0 转数值，避免 mysql CLI 按原始字节渲染导致误判
SELECT username, status, must_change_password + 0 AS must_change_password FROM system_users WHERE id = 1;
-- 期望：status = 1（禁用）、must_change_password = 1；不满足时停止接管并核对快照来源
```

### 已有实例的数据库凭据轮换

1. 确认备份可恢复，核对实际数据库账号及 Host，准备新的独立密码。账号管理由 DBA 执行，
   不给应用账号增加管理权限；秘密只经受控客户端或 Secret 平台传递，不写入命令历史或仓库。
2. 对应用账号、迁移账号分别使用 MySQL `ALTER USER ... IDENTIFIED BY ... RETAIN CURRENT PASSWORD`
   设置新主密码并暂时保留旧密码。此步骤要求账号支持双密码且操作者具备账号管理权限；
   不满足时安排停机窗口，停止应用后改密并同步配置。
3. 更新部署 Secret；compose 部署同步 `.env` 的 `DB_PASSWORD`、`FLYWAY_PASSWORD`，执行
   `docker compose up -d --no-deps --force-recreate app`。单实例重建会短暂中断服务。
   确认新建连接使用新密码、Flyway 校验成功、健康检查与业务读写正常。
4. 所有消费者切换后，由 DBA 执行 `ALTER USER ... DISCARD OLD PASSWORD`。
   用新连接验证旧密码已拒绝、新密码仍可用，记录轮换结果。

具体权限与双密码语义见 [MySQL 密码管理](https://dev.mysql.com/doc/refman/8.4/en/password-management.html)。
本流程不改变账号名、授权范围或认证插件；root 密码与 Redis 密码按各自运维流程轮换。

客户端 IP 与可信代理：后端默认不采信 `X-Forwarded-For`/`X-Real-IP`（防伪造代理头绕过
限流与审计）。若部署在 Nginx/负载均衡之后，必须在 `application-prod.yaml` 把代理出口
IP 或网段配置到 `basic-framework.web.trusted-proxies`（如 `10.0.0.0/8`），否则按 IP
的限流与访问审计取到的是代理 IP；未配置时登录限流按代理出口聚合生效，不会比预期更宽松。

### S3 预签名上传的暂存清理

启用预签名上传的私有桶必须为框架保留目录 `.pending/` 配置对象存储生命周期规则。
应用会清理已登记的失败或过期上传，但已完成上传的 PUT 签名在到期前仍可重复使用，可能重新生成暂存对象；
因此一次应用删除不能代替存储端的持续清理。已发布文件使用不同对象键，只包含服务端验证过的字节。
签名复用语义见 [AWS 文档](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html)。

在现有桶生命周期配置中合并以下规则；S3 兼容服务使用等效规则。规则仅限该保留前缀，不能应用到业务文件目录。
框架拒绝把业务文件写入此目录；对象锁定或复制状态等可能阻止自动清理的能力不应作用于暂存对象。

```json
{
  "ID": "framework-upload-staging-expiry",
  "Status": "Enabled",
  "Filter": { "Prefix": ".pending/" },
  "Expiration": { "Days": 1 },
  "NoncurrentVersionExpiration": { "NoncurrentDays": 1 },
  "AbortIncompleteMultipartUpload": { "DaysAfterInitiation": 1 }
}
```

生命周期删除是异步操作；启用版本控制时还必须清理非当前版本，见
[AWS 对象过期说明](https://docs.aws.amazon.com/AmazonS3/latest/userguide/lifecycle-expire-general-considerations.html)。
部署验收应读取桶规则，并用隔离测试对象确认暂存目录清理与正式对象保留。此项属于外部存储部署验证，仓库单元测试不代表桶策略已生效。

## 2. 健康检查与 actuator 暴露面

- 生产环境暴露 health 和受独立认证保护的 prometheus，配置事实在
  [`application-prod.yaml`](../后端代码/basic-framework-boot/basic-framework-server/src/main/resources/application-prod.yaml)
  的 `management.endpoints.web` 段。
- 容器内 HEALTHCHECK 探测同一端点，实现见
  [`basic-framework-server/Dockerfile`](../后端代码/basic-framework-boot/basic-framework-server/Dockerfile)
  （非 root 运行、日志目录等事实同在该文件注释中）。
- 手动探测：`curl -fsS http://127.0.0.1:48080/actuator/health`。

Prometheus 抓取默认关闭（端点拒绝访问）。接入时设置 `PROMETHEUS_ENABLED=true`，并通过部署 Secret 注入
`PROMETHEUS_SCRAPE_TOKEN`：独立生成的 32 字节随机值，以 64 位十六进制编码。启用但未提供合规令牌时启动失败。
抓取仅支持 GET，客户端通过 `Authorization: Bearer <令牌>` 认证；业务访问令牌和登录 Cookie 不具备抓取权限，
抓取令牌也不能访问管理端业务接口。令牌不得放入 URL、日志或版本库。

使用 [Prometheus 配置示例](../ops/prometheus/prometheus.example.yml)，将令牌作为只读 Secret 文件挂载给
Prometheus；修改目标地址并加载现有告警规则。跨主机流量须走 TLS，入口仅允许监控网络访问，不向公网开放。
轮换时同时更新服务端 Secret 与抓取端文件，重启服务以加载新令牌。
验收应确认：无令牌及错误令牌均被拒绝，正确令牌能读取安全计数器，业务接口不接受抓取令牌，并检查 Prometheus target 为 UP。
框架提供导出端点和规则示例；部署方仍需将规则挂载到实际 Prometheus 并配置告警通知。

## 3. Flyway 迁移失败处置

停止应用写入，保存启动错误、迁移历史及可恢复备份。使用迁移账号（`FLYWAY_USERNAME`），
不使用仅有 DML 权限的应用账号或 root。先在隔离恢复库核对以下分支：

- **连接或权限错误**：修复连接和授权配置后重新校验，不执行 `repair`。
- **已成功迁移的 checksum 不匹配或文件缺失**：恢复该发布版本的原始迁移文件；
  不用 `repair` 接受改写的历史。已在共享环境执行的迁移保持不变。
- **失败迁移留下部分 DDL/DML**：明确失败版本和已提交副作用，清理或恢复到该迁移执行前的
  一致状态，再清除失败记录并重跑。不能先手工完成目标结构再直接重跑包含建表、建索引的迁移。
- **无法可靠回退副作用或已手工完成迁移**：停止通用修复流程，制定该版本专用恢复方案，
  从备份恢复或在隔离库验证历史接管方案；不直接编辑历史表或盲目 baseline。

以下函数用于 compose 的 `basic-framework` 网络与 `basic_framework` 数据库。
在与故障发布一致的后端目录执行，挂载该发布的**完整原始迁移集**；不能使用缺文件的目录。
CLI 版本与当前 BOM 的 Flyway 11.15.0 对齐，镜像固定摘要；升级 BOM 时同步核验此工具版本。
该镜像使用 MariaDB JDBC 驱动连接 MySQL。compose 私有网络内的首次密码认证需要服务器 RSA 公钥，
由受信任的 Docker 管理通道复制，不能允许客户端从不可信连接自动获取公钥。

```bash
# 临时目录保存服务器公钥；账号、数据库和数据卷均保持不变
flyway_key_dir=$(mktemp -d)
docker compose exec -T mysql cat /var/lib/mysql/public_key.pem > "$flyway_key_dir/public_key.pem"
test -s "$flyway_key_dir/public_key.pem" || exit 1

run_flyway() {
  docker run --rm --network basic-framework_default --env-file .env \
    --mount "type=bind,src=$(pwd)/basic-framework-server/src/main/resources/db/migration,dst=/flyway/sql,readonly" \
    --mount "type=bind,src=$flyway_key_dir/public_key.pem,dst=/flyway/server-public-key.pem,readonly" \
    --entrypoint /bin/sh \
    flyway/flyway:11.15.0@sha256:e2c4b8359280e920c8f6cbf8be6d41527eb04f8fa631da78abbe0e96a18e35a9 \
    -c 'export FLYWAY_USER="$FLYWAY_USERNAME";
        export FLYWAY_URL="jdbc:mysql://mysql:3306/basic_framework?serverRsaPublicKeyFile=/flyway/server-public-key.pem";
        export FLYWAY_LOCATIONS="filesystem:/flyway/sql";
        export FLYWAY_CLEAN_DISABLED=true;
        exec /flyway/flyway "$@"' sh "$@"
}

# 先只读检查迁移状态，核对目标库与完整迁移集
run_flyway info
```

`FLYWAY_PASSWORD` 由环境传给 CLI，密码不进入 CLI 命令参数。镜像和环境文件只供获授权的
运维人员使用。公钥路径按实际 MySQL 配置调整；公钥只保护密码交换，不提供整个 SQL 连接的 TLS
保护。跨主机或生产受管数据库应使用部署方提供的 CA、主机名校验及对应 TLS 连接配置。
认证选项见 [MariaDB JDBC 文档](https://mariadb.com/docs/connectors/mariadb-connector-j/about-mariadb-connector-j)。
`repair` 还会对齐 checksum，并将缺失迁移标记为 deleted，因此必须与迁移时
使用同一组文件，见 [Flyway repair 语义](https://documentation.red-gate.com/flyway/reference/commands/repair)。

仅在失败迁移的副作用已清理、原始文件及备份已核对后，运行 `run_flyway repair`。
检查输出只能包含预期失败项的移除；出现非预期 checksum 对齐或 deleted 项时停止发布并核查。
随后运行 `run_flyway migrate`、`run_flyway validate`，确认成功后执行 `docker compose up -d app`，
验收健康检查与关键业务读写。修复无法验证时按第 4 节从备份恢复。
完成本次 CLI 操作后删除临时公钥文件并移除空的 `flyway_key_dir` 目录；不要把它写入仓库。

## 4. 备份与恢复演练（RTO/RPO 的验证载体）

ADR 0006 设定 RTO 4 小时、RPO 1 小时，本节演练用于验证目标，不代表已经达标。演练对象是 MySQL 业务数据；
会话与业务数据一同保存在 MySQL。Redis 承载非授权缓存、验证码、限流与临时锁定；恢复期间须限制匿名入口，避免状态丢失重置防爆破窗口。Redis 不纳入业务数据恢复目标。

备份（RPO 落点）：

```bash
backup_file="backup-$(date +%F-%H%M).sql"
docker compose exec -T mysql sh -c \
  'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers basic_framework' \
  > "$backup_file" && test -s "$backup_file"
```

生产环境应将备份接入平台任务，检查命令退出状态，失败文件不进入可恢复备份集；备份间隔
不长于 1 小时（对齐 RPO）。文件存在且非空不代表可恢复，仍须通过下面的演练验证。

恢复演练（验收 RTO，恢复到独立库，避免覆盖在运数据）：

```bash
(set -eu
# 在当前 shell 设置 backup_file 为实际备份路径；同一会话可复用上面的变量
: "${backup_file:?请先设置实际备份文件路径}"
test -s "$backup_file" || exit 1
# 1. 建恢复库并导入备份
docker compose exec mysql sh -c \
  'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "CREATE DATABASE basic_framework_restore"'
docker compose exec -T mysql sh -c \
  'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" basic_framework_restore' < "$backup_file"
# 2. 抽验：表数量与核心业务表行数
docker compose exec mysql sh -c \
  'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = \"basic_framework_restore\"; SELECT COUNT(*) FROM basic_framework_restore.system_users;"'
# 3. 记录端到端恢复耗时并对照 RTO 4 小时；通过后清理恢复库与演练备份
docker compose exec mysql sh -c \
  'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "DROP DATABASE basic_framework_restore"'
)
```

演练结论（耗时、抽验结果、处置人）留档，作为 ADR 0006 恢复目标的持续验收证据。

## 5. 前端镜像

前端镜像构建事实在前端仓库自带文件中，不在此复制：

- [`前端代码/basic-framework-admin/scripts/deploy/Dockerfile`](../前端代码/basic-framework-admin/scripts/deploy/Dockerfile)：node:22-slim 构建 + nginx 运行，暴露 8080。
- [`前端代码/basic-framework-admin/scripts/deploy/build-local-docker-image.sh`](../前端代码/basic-framework-admin/scripts/deploy/build-local-docker-image.sh)：本地一键构建脚本。
