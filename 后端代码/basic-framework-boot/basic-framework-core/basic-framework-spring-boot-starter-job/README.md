# Job Starter

`basic-framework-spring-boot-starter-job` 提供 Quartz 调度和 Spring Async 异步执行能力。

## Quartz 重启契约

- 生产、测试配置使用 JDBC JobStore；`QRTZ_*` 表由服务端 Flyway 迁移维护，Quartz 自身禁止自动建表。
- `JobHandlerInvoker` 通过 Quartz 的非并发标记保证同一个 JobDetail 在集群内不并行执行。
- `JobHandlerInvoker` 由 Spring 的 Quartz `JobFactory` 创建并使用构造器注入；替换 `JobFactory` 时也必须保留 Spring Bean 创建语义。
- Cron trigger 使用 `DO_NOTHING` misfire 策略：应用停机期间错过的执行窗口在重启后跳过，不集中补跑。
- Job 不请求故障恢复。进程可能在外部副作用完成、Quartz 尚未确认之间退出，因此框架不承诺 exactly-once；每个 `JobHandler` 必须可重入、幂等，或自行持有业务唯一键/分布式互斥。
- 管理端“同步任务”对已有记录原地更新、对缺失记录补建，并按 `infra_job.status` 对齐暂停状态；启用缺失任务时也会先补建。
- 任务结果写库失败时只记录任务状态和脱敏后的有界异常堆栈，不把任务返回内容或异常正文写入运行日志；任务处理器也不得在返回值中携带凭据。
- 任务执行失败持久化异常类型而非异常正文，避免第三方异常中的凭据或业务数据进入任务日志表。
- 普通任务异常进入失败日志与重试策略；JVM `Error` 不转换为可重试任务失败，直接交由运行时故障处理链路处置。

真实 MySQL 重启验证位于 `basic-framework-server` 的 `QuartzRestartIT`，覆盖空库迁移、调度器关闭重建、Job/Trigger 唯一持久化、misfire 与 recovery 标志。

## 异步执行器契约

- Spring Boot 提供名为 `applicationTaskExecutor` 的受管 `ThreadPoolTaskExecutor`，供 `@Async` 与程序化提交共用。
- starter 通过 `ThreadPoolTaskExecutorCustomizer` 应用下表参数，不声明同名 Bean，也不依赖自动配置排序或 Bean 覆盖。
- 所有 `ThreadPoolTaskExecutor` 和 `SimpleAsyncTaskExecutor` 都通过 `TtlRunnable` 传递线程上下文。
- 关闭应用时等待在途任务完成，最长等待时间由 `basic-framework.async.await-termination-seconds` 控制。

## 配置项

| 配置 | 默认值 | 说明 |
| --- | ---: | --- |
| `basic-framework.async.core-pool-size` | `8` | 核心线程数 |
| `basic-framework.async.max-pool-size` | `32` | 最大线程数 |
| `basic-framework.async.queue-capacity` | `100` | 等待队列容量 |
| `basic-framework.async.keep-alive-seconds` | `60` | 空闲线程存活秒数 |
| `basic-framework.async.await-termination-seconds` | `30` | 关闭时等待任务完成的最长秒数 |

所有数值在配置绑定期校验：核心/最大线程数必须至少为 `1` 且前者不得大于后者；队列容量、空闲存活时间和关闭等待时间不得为负数。错误配置会在应用启动时失败，不等待执行器首次初始化。
