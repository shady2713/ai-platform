# 恢复/回退演练报告模板（可直接填）

| 项目 | 内容 |
|---|---|
| 用途 | 每一次**备份恢复**或**回退**演练填一份；填完即成为 ADR 0006（RTO 4h / RPO 1h）的持续验收证据 |
| 关联 | 步骤与判据：[升级检查单](../operations/upgrade-checklist.md)、[部署与回退说明](../deployment/deployment-and-rollback.md)、[部署 Runbook §4](../deployment.md) |
| 覆盖的验收项 | **AT-030**（索引快照恢复）、**AT-063**（空库/旧版本库迁移）、**AT-066**（索引/DB 回退演练，说明数据窗口）；AT-064 由 `PackagedJarBootSmokeIT` 承载，可在 §5 顺带登记 |
| 填写纪律 | 只填**实际执行**的内容；未执行的项写"未执行 + 原因"，不得留空后宣称通过。一次性日志/容器 ID/临时端口只进本地归档（`.local-state/`，已 gitignore），本报告只留摘要与可定位引用（[08 §6](../ai-platform/08-testing-acceptance.md)） |

## 0. 演练头

| 字段 | 填写 |
|---|---|
| 演练编号 | `DR-<YYYYMMDD>-<序号>` |
| 类型 | ☐ 备份恢复到独立库 ☐ 发布回退（前端/后端/索引/密钥） ☐ 两者同窗 |
| 日期与起止时间 | 开始：____ 结束：____ （含时区：Asia/Shanghai） |
| 执行人 / 复核人 | ____ / ____ |
| 目标环境 | ☐ 本机 ☐ 预发布 ☐ 生产（需授权单号：____） |
| 代码版本 | commit：____ ；jar/image digest：____ ；前端产物归档路径：____ |
| 数据库版本 | 迁移最大编号 `V____`；快照声明 `through V____`；`flyway_schema_history` 条数 ____ |
| 环境规格 | CPU ____ / 内存 ____ / 磁盘 ____ / Docker ____ / MySQL ____ / Redis ____ / Qdrant ____ |
| 授权依据 | 用户授权记录（生产动作必需）：____ |

## 1. 目标与上界

| 项 | 目标值 | 实测值 | 结论 |
|---|---|---|---|
| RTO（端到端恢复耗时） | 4 小时（ADR 0006） | ____ | ☐ 达标 ☐ 未达标 |
| RPO（可接受丢失窗口） | 1 小时 | ____（备份点与实际恢复点之差） | ☐ 达标 ☐ 未达标 |
| 停机上界（本次） | 由发布负责人批注：____ | ____ | ☐ 未超 ☐ 超（必写处置） |

## 2. 演练前材料（逐项填路径/摘要，缺失必须写明）

| # | 材料 | 路径 / 摘要 | 校验（sha256 或数量） | 就位 |
|---|---|---|---|---|
| 1 | MySQL 备份文件 | ____ | `test -s` ☐ 通过；sha256 ____ | ☐ |
| 2 | 上一版后端 jar / 镜像 tag | ____ | digest ____ | ☐ |
| 3 | 上一版前端产物（web-ele/ai-chat dist；嵌入资产目录） | ____ | ____ | ☐ |
| 4 | Qdrant 集合快照（每个集合一个） | 集合 ____ → 快照名 ____ | 创建返回 200 ☐ | ☐ |
| 5 | MySQL 文档/ACL 版本水位（水位查询与结果） | ____ | ____ | ☐ |
| 6 | `CREDENTIAL_ENCRYPTION_KEY` 旧值可回取（仅轮换时需要） | 密钥管理系统条目号 ____ | — | ☐ / 不适用 |
| 7 | 恢复库/独立实例（不覆盖在运数据） | 名称 ____ / 端口 ____ | — | ☐ |
| 8 | 交付包（若本次演练针对某次发布） | `node deploy/package-delivery.mjs --offline` → `deploy/dist/<交付名>/` | `SHA256SUMS` 核对 ☐；`version.json` 摘要 ____ | ☐ / 不适用 |

## 3. 执行步骤记录（每步：时间、命令、退出码、输出摘要、判据结论）

| # | 步骤 | 命令（照[升级检查单](upgrade-checklist.md)/[Runbook §4](../deployment.md) 原文） | 时间 | 退出码 | 输出摘要 | 判据结论 |
|---|---|---|---|---|---|---|
| 1 | 建恢复库并导入备份 | `docker compose exec mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "CREATE DATABASE basic_framework_restore"'` + `... basic_framework_restore < "$backup_file"` | ____ | ____ | ____ | ☐ |
| 2 | 抽验：表数量与核心表行数 | `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = "basic_framework_restore"; SELECT COUNT(*) FROM basic_framework_restore.system_users;` | ____ | ____ | 表 ____ / 用户 ____ | ☐ |
| 3 | 空库迁移（AT-063） | 空库启动 → 观察 Flyway | ____ | ____ | `now at version v____` | ☐ |
| 4 | 索引快照恢复（AT-030） | `POST /collections/<c>/snapshots` → `PUT .../snapshots/recover`（[升级检查单 §2.4](upgrade-checklist.md)） | ____ | 200 / 200 | 恢复后可检索点数 ____ | ☐ |
| 5 | 回退动作（若本次演练回退） | 按[部署与回退说明 §3.2](../deployment/deployment-and-rollback.md) 的反向顺序逐步填写 | ____ | ____ | ____ | ☐ |
| 6 | 恢复后跑业务用例 | `./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiAuthorizationMatrixIT,AiKnowledgeIndexingIT,AiReportPersistenceIT'` | ____ | ____ | ____ 例通过 / ____ 例失败 | ☐ |

## 4. 恢复正确性抽验（"文件存在"不算通过）

| 面 | 抽验内容 | 期望 | 实测 | 结论 |
|---|---|---|---|---|
| 授权 | 撤销/越权的真实拒绝（AT-010/025 口径） | 跨主体拒绝、撤销后拒绝 | ____ | ☐ |
| 引用 | 引用可核验、不输出不存在引用（AT-026 口径） | 全部可核验 | ____ | ☐ |
| 报表 | 生成/刷新/改版（AT-045/047 口径） | 版本可追溯、失败标注 | ____ | ☐ |
| 评测 | 套件修订冻结、逐例判定可复核 | 可复核 | ____ | ☐ |
| 观测 | `/actuator/health`、`/actuator/prometheus`、`/ai/observability`、`/ai/usage` | UP / 200 / 有数据 | ____ | ☐ |
| 队列/任务 | `infra_job` id=32/33 留痕（[队列文档](queue-backlog-and-resilience-observability.md)） | 有留痕 | ____ | ☐ |
| 索引（AT-030） | 内容/ACL/版本、旧别名/旧代可用性 | 一致 | ____ | ☐ |
| 保密 | 恢复后响应/日志无 token/secret/正文 | 无 | ____ | ☐ |

## 5. 数据损失窗口与限制（AT-066 必须写清）

| 项 | 本次事实 |
|---|---|
| 备份点时间 | ____ |
| 恢复到的时间点 | ____ |
| **数据损失窗口** | ____ 分钟（= 从备份点至今的写入量：运行 ____ / 任务 ____ / 文档变更 ____） |
| 索引快照点与 DB 点的差值 | ____（快照晚于备份的部分需重放；重放记录：____） |
| 已执行迁移是否回滚 | 否（Flyway 不可逆）；本次选择：☐ 前滚修复 ☐ 从备份恢复 |
| 旧代/退役代说明 | 旧代与切片保留为回退窗；**不支持激活已退役代**，回退=重建（K07 §9.2） |
| Redis | 不纳入业务数据恢复目标；恢复期间已限制匿名入口 ☐ |
| AT-064 顺带登记 | `PackagedJarBootSmokeIT` 本次是否运行：☐ 是（退出码 ____） ☐ 否（原因 ____） |

## 6. 发现项与处置

| # | 发现 | 严重度 | 处置 | 责任人 | 状态 |
|---|---|---|---|---|---|
| 1 | ____ | ☐ 阻断 ☐ 观察 ☐ 优化 | ____ | ____ | ☐ 已闭环 ☐ 挂起 |

> 阻断项未闭环不得在发布说明里写"回退方案可用"。若发现"回退到上一版本的材料缺失"，
> 按 [Q08 §4.4](../upgrades/q08-upgrade-rehearsal-and-rollback.md) 的写法登记并先补材料。

## 7. 结论与签字

| 项 | 内容 |
|---|---|
| 本次是否验证 RTO/RPO | ☐ 是，RTO 实测 ____、RPO 实测 ____ ☐ 否（原因） |
| 回退路径是否成立 | ☐ 成立（材料、步骤、判据均已实测） ☐ 不成立（缺 ____） |
| 遗留未验证项 | ____ |
| 执行人签字 / 日期 | ____ |
| 复核人签字 / 日期 | ____ |

## 8. 附件（只填可定位引用，不要把日志粘进仓库）

| 附件 | 位置（本地归档 / CI 产物） |
|---|---|
| 原始命令与输出 | `.local-state/`（gitignore）或 CI 产物路径：____ |
| 备份文件摘要（sha256） | ____ |
| 浏览器/接口关键截图 | ____ |
| 用例报告（failsafe-reports 路径） | `basic-framework-server/target/failsafe-reports/...`：____ |
