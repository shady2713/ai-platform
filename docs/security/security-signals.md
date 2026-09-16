# 安全信号与告警

本文是安全信号指标的**语义与处置**归属地。指标由 `SecuritySignalMetrics` 生产，
**阈值、持续时间和严重级的唯一来源是** `ops/prometheus/security-signals.rules.yml`；
本文不再重复具体数值，避免两处漂移。

三个文件由 `node scripts/check-security-signals.mjs` 门禁保持一致：
每个指标都要在本文登记、每条告警都要有对应的处置章节。

## 为什么需要运行期信号

CI 门禁只能证明控制在**提交那一刻**成立，不能证明上线后仍然生效。威胁模型中
「必须保持的边界」若没有运行期信号，失效时无人知晓。

## 指标语义

| 指标 | 含义 | 对应威胁 | 严重级 |
|---|---|---|---|
| `basic_framework_auth_login_failures_total` | 账号密码登录失败次数 | T1 爆破与撞库 | warning |
| `basic_framework_auth_account_locks_total` | 账号因连续失败被锁定次数 | T1 爆破与撞库 | warning |
| `basic_framework_auth_refresh_replays_total` | 旧代刷新令牌重放检测次数 | T2 令牌重放 | critical |
| `basic_framework_auth_session_revocations_total` | 会话撤销次数（按 `reason` 标签） | T3 控制面接管 | warning |

> Prometheus 抓取时 `basic_framework.auth.login.failures` 会转写为
> `basic_framework_auth_login_failures_total`（点转下划线、计数器加 `_total` 后缀）。

## 处置步骤（Runbook）

抓取接入、凭据和网络边界见[部署文档](../deployment.md#2-健康检查与-actuator-暴露面)。

### RefreshTokenReplayDetected

重放检测为**零容忍**信号：正常用户不会触发。它意味着同一刷新令牌被使用两次，
按 RFC 6819 判定为凭据可能已被窃取。

1. 结合 `system_operate_log` 与告警标签定位账号。
2. 确认该用户会话已被吊销（`system_user_session` 中对应行应已删除）。
3. 若确认为多标签页并发刷新，属预期的假阳性：在告警注解记录后复核 ADR 0039 的权衡。
4. 若非并发场景，按凭据泄露处置：强制改密、复查该账号近期操作日志。

### LoginBruteForceSuspected

1. 按来源 IP 聚合，判断是单账号定向爆破还是广撒网撞库。
2. 确认 `basic-framework.web.trusted-proxies` 已按部署显式配置——未配置时按 IP 的限流
   与审计取到的是代理地址，无法定位真实来源。
3. 必要时在网关层封禁来源，不要长期依赖账号锁定。

### AccountLockSpike

1. 观察是否集中在少数账号：集中在少数说明是定向爆破，分散说明是撞库。
2. 与 `LoginBruteForceSuspected` 联动判断来源是否同一网段。
3. 排查是否为本方改密脚本或压测误触发。

### UnexpectedSessionRevocationSpike

1. 检查 `reason` 标签分布：`PASSWORD_CHANGED`、`USER_DISABLED` 等常规原因属正常业务。
2. 若出现与业务量不符的集中撤销，排查是否为批量改密或权限调整脚本误操作。

## 边界

- 指标不得影响业务结果：写入不参与事务判定，也不抛出异常。
- 会话撤销按事务提交后（`AFTER_COMMIT`）计数，未提交的撤销不计入。
- 撤销计数来自数据库实际删除行数：同一用户的多会话分别计数，没有会话时不增加；请求事件与删除结果事件分离。
- 锁定计数只记录 Redis 脚本返回的新增锁定，已锁定状态下的并发失败不会再次累计锁定次数。
- 本组信号覆盖 T1/T2/T3；T4–T10 的运行期信号尚未实现，新增前先修订本文。
