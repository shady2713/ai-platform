# Q07 容量实测与故障注入运行手册（后端）

| 项目 | 内容 |
|---|---|
| 任务卡 | [Q07 验证容量、慢消费者与任务故障恢复](../ai-platform/tasks/Q07.md)（后端切片） |
| 本文件回答什么 | 怎么**重复**跑出 Q07 后端的性能/韧性数字；注入了哪些故障；实测得到的容量与边界；哪些结论**不能**从这些数字推出来 |
| 证据报告 | [Q07 后端切片完成证据](../acceptance/q07-backend-performance-resilience-evidence.md)（结论与原始测量） |
| 无关内容 | 队列积压的**观察与判读**（表/Job 日志/接口/日志）见 [队列积压与韧性可观测量](queue-backlog-and-resilience-observability.md)；本文件不重复 |
| 最近一次实测 | 2026-09-27，Linux 6.8.0-138-generic x86_64 / 16 vCPU / 31 GiB（JVM `maxHeap` 8024 MiB）/ Java 17.0.20；Testcontainers `mysql:8.4.11` + `redis:7.4.11` |

## 1. 可重复测量：命令与产物

```bash
cd /home/ctyun/桌面/zhongtai/ai-platform/后端代码/basic-framework-boot
umask 022
export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64

# NFR-04：20 并发受理 / 状态查询 / 完整运行（固定 50ms Mock 模型响应）
./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiPlatformCapacityIT'
#   → 退出码 0；原始测量追加写入 basic-framework-server/target/q07-performance/nfr04-raw-measurements.txt
#   → 也可在控制台 grep "Q07-MEASURE"

# AT-016/017/018：取消与晚到完成、慢消费者、重启恢复
./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiRunResilienceAcceptanceIT'

# AT-039：分页截断/失败 → PARTIAL/FAILED，不宣称完整
./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiApiCompletenessAcceptanceIT'

# AT-059：并发配额与异常释放（当前"不超发"一项失败，见证据报告 §3.3）
./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiQuotaConcurrencyAcceptanceIT'

# 改了 Java 测试后先格式化，否则 spotless:check 会让构建在编译前停住
./mvnw -q -o -pl basic-framework-server spotless:apply
```

产物位置（都在构建目录，不进仓库）：

| 产物 | 路径 |
|---|---|
| 原始测量（NFR-04） | `basic-framework-server/target/q07-performance/nfr04-raw-measurements.txt` |
| 各用例报告 | `basic-framework-server/target/failsafe-reports/com.basicframework.server.integration.<类名>.txt` / `.xml` |

注意：`clean verify` 会清空 `target/`，需要保留原始结果时先复制出来。

## 2. 参数调整

测量参数是测试类里的常量（无外部配置文件），语义如下：

| 常量 | 位置 | 当前值 | 调整影响 |
|---|---|---:|---|
| `CONCURRENCY` | `AiPlatformCapacityIT` | 20 | NFR-04 的并发维度；调大即更高并发（受本机 CPU/MySQL 容器能力限制） |
| `FIXED_MODEL_MILLIS` | `AiPlatformCapacityIT` | 50 | 固定模型响应延迟；"平台开销 = 端到端 − 该值"，调大更接近真实模型 |
| `ROUNDS`（受理/查询轮数） | `AiPlatformCapacityIT` | 2 / 10 | 样本数 = 轮数 × 并发；P95 需要 ≥20 个样本才有意义 |
| `RACE_ROUNDS` | `AiRunResilienceAcceptanceIT` | 12 | AT-016 竞争轮数；越大越可能同时命中两种胜者顺序 |
| `CANCEL_STORM_WORKERS` | `AiRunResilienceAcceptanceIT` | 8 | 取消风暴并发 |
| `BACKLOG_EVENTS` | `AiRunResilienceAcceptanceIT` | 450 | 事件积压规模（必须 > 服务端单批上限 200 才能验证有界） |
| `LIMIT` / `WORKERS` | `AiQuotaConcurrencyAcceptanceIT` | 5 / 24 | 配额上限与争抢并发 |

服务端侧与容量有关的**硬上限**（改这些要改生产代码，属新卡）：

| 上限 | 值 | 位置 |
|---|---:|---|
| 单次事件重放批量 | 200 | `AiRunEventServiceImpl.MAX_REPLAY_BATCH`、`AiRunController.MAX_STREAM_BATCH` |
| SSE 连接最长存活 | 300 s | `AiRunController.STREAM_TIMEOUT_MILLIS` |
| 任务单次领取批量 / 恢复批量 / 清理批量 | 50 / 500 / 1000 | `AiTaskServiceImpl` |
| 租约时长上限 | 3600 s | `AiTaskServiceImpl.MAX_LEASE_SECONDS` |
| 配额占位租约上限 | 2 h | `AiQuotaServiceImpl.MAX_LEASE` |

## 3. 故障注入记录（本次真实执行过的）

| 注入 | 方式 | 观察到的行为 | 判读要点 |
|---|---|---|---|
| 取消 vs 晚到完成竞争 | 同一栅栏起跑两个线程（`AiRunEventService.cancel` / `AiRunTerminalWriter.finish`），12 轮 | 恰好一个生效；终态唯一；助手结果 0 或 1 份；版本只 +1；终态事件不重复 | 两种胜者顺序在多次运行中都出现过（2/10、0/12、9/3） |
| 取消风暴 | 8 路并发取消同一运行 | 1 路成功、7 路稳定 `AI_RUN_ALREADY_TERMINAL`；只落 1 条 CANCELLED 事件 | "取消是显式动作、幂等"成立，不会重复写终态 |
| 慢消费者（拉取慢） | 450 条积压后按 `afterSeq` 分批拉 | 单次严格 ≤200 条；4 次拉完 450 条、无丢无重 | 服务端不因消费慢而堆积内存：每次拉取是一次 DB 读 |
| 消费者卡住（不拉取） | 拉一批后停止，观察期间并发跑 20 个状态查询 | 查询全部成功（最慢 28–39 ms）；积压行数不变 | 卡住的消费者既不阻塞服务，也不改写积压 |
| 客户端落后于保留窗口 | 删除最早 300 条事件后从 seq=10 续读 | 稳定 `AI_RUN_EVENT_WINDOW_EXPIRED`；快照给出 `latestSeq=450`/`earliestSeq=301`；从 300 续读拿到 150 条 | 恢复路径是"报错 + 转快照"，不是静默丢事件 |
| 进程中断（持租约崩溃） | 领取后不心跳不落库，把 `lease_expires_time` 置为过去 | `countActiveLeases()=0`；恢复 Job 把任务放回 QUEUED（47–85 ms）；新 worker `attempt=2`/`epoch=2` 领取并完成 | 旧 worker 的落库命中栅栏 0 行；真实执行入口对终态运行回 `AI_RUN_ALREADY_TERMINAL` |
| 配额持有者崩溃 | 占位租约 `lease_until` 置为过去 | `activeCount=0`；名额可重新占满；被回收占位 `renew` 返回 false | 不永久占位成立；续租失败必须让调用方停手 |
| 释放风暴 | 20 路并发释放同一批占位 | 释放幂等、`activeCount=0`、无残留、名额可复用 | 无"卡住"的占位 |
| **并发争抢配额（失败）** | 24 并发抢 `limit=5`，3 轮 | **超发：8 / 9 / 14 个生效** | 见证据报告 §3.3；修复需生产代码改动 |

## 4. 容量建议（来自本次实测，不夸大）

1. **单实例受理能力**：20 并发下受理 P95 189–217 ms（两次运行，P50 156–196 ms）。受理是纯数据库写路径
   （幂等记录 + 运行 + 首任务 + 用户消息），**瓶颈在 MySQL 往返次数与连接池**，不在 CPU。
   压测时先看连接池等待，再考虑加实例。
2. **状态查询**：20 并发 × 10 轮（200 样本）P95 91–128 ms、P50 9–10 ms。查询按主体过滤且只读，
   可以接受更高的并发；管理端/前端轮询建议退避（例如 1→2→5 s），避免把查询压力当成"服务慢"。
3. **保留 P99 余量**：本次受理 P99 196–236 ms、查询 P99 111–141 ms，均低于 NFR-04 的 P95 阈值，
   但样本只有 40/200 个、两次运行波动 10–30%，**不能**据此承诺更高并发或更差硬件下的 P95。
4. **平台执行开销**：固定 50 ms 模型响应下，20 并发完整运行端到端 P95 355–463 ms（两次运行），
   其中平台开销 P95 约 305–413 ms（身份重建、发布版本判定、输入回放、上下文构建、外发策略、终态写入等）。
   做容量规划时按"平台开销 ~0.3–0.4 s/次 + 模型真实耗时"估算，而不是把端到端当成平台开销。
5. **积压恢复速度**：恢复扫描单轮处理 5 个过期租约耗时 47–85 ms（批量上限 500）。
   恢复吞吐远高于"每分钟一次"的 Job 频率，积压恢复的瓶颈是 Job 周期（每分钟）而不是扫描本身。
6. **事件积压**：客户端每次连接最多补发 200 条、连接最长 5 分钟；450 条积压需要 3 次拉取/重连。
   离线很久的客户端会撞上保留窗口进而转快照——这是设计行为，不是故障；运维判读时先看
   `ai_run_event` 的最早 seq 与 `ai_run.event_seq` 的差值。
7. **配额**：在 `AiQuotaServiceImpl` 的超发缺陷修复之前，**不要**把 `acquire` 接进限流关键路径，
   也不要依赖 `activeCount` 做准入判定（并发下会放行超过上限的请求）。

## 5. 判读边界：这些数字不能证明什么

- 本机是 16 vCPU/31 GiB，NFR-04 建议基线是 8 vCPU/16 GB：**达标结论未在基线硬件复测**。
- 模型是固定 50 ms 的 Mock 响应：真实模型的长尾、限流、超时未覆盖。
- 链路是文本运行，不含 MySQL 只读连接器/HTTP 连接器：数据源耗时未测（NFR-04 要求"模型与数据源耗时
  单独记录"，数据源部分由 D 系列证据承担）。
- 未做长时间稳定性、数千并发、真网关/多机、真实 JVM 重启与 jar 冒烟（AT-018 的重启是模拟）。
- AT-017 的"有界内存"是行为断言（单次拉取有界 + 服务端无未确认缓冲），不是堆直方图/RSS 曲线。
- 故障注入全部在测试进程内完成；生产环境的网络分区、Docker/K8s 驱逐、MySQL 主从切换不在覆盖范围。
