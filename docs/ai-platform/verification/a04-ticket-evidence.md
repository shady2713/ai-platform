# A04 换票与票据证据（2026-09-17）

本记录是 [A04 实现换票与票据](../tasks/A04.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 票据持久化 | 迁移 `V53__ai_access_ticket.sql`：`ai_access_ticket`（`token_digest char(64)` 唯一索引、范围快照、范围指纹、授权版本、到期时间、状态机）；快照同步 |
| 换票/校验服务 | `service/auth/AiTicketService(+Impl)`：`issue`（凭据+断言+裁剪+签发）、`verify`（重新读状态）、`revokeTickets` |
| 失败节流 | `service/auth/AiTicketAttemptThrottle`：按"客户端 IP + appCode"统计失败（默认 60 秒 10 次），超限 429；成功清零；键数有界 |
| 应用端接口 | `controller/app/v1/auth/AiAuthController`（`POST /app-api/ai/auth/ticket`，`@PermitAll` + 凭据鉴权 + 节流） |
| 错误码 | `1_003_003_010` AI_TICKET_INVALID（401：不存在/撤销/过期/应用或主体不可用同语义）+ 映射同步 |
| 测试 | `AiTicketServiceImplTest`(5)、`AiTicketAttemptThrottleTest`(3)、`AiAuthControllerTest`(4) |

## 2. 与卡片逐步实施的对应

1. **校验客户端凭据及 USER 可信断言**：换票先 `applicationService.authenticate(appCode, secret)`
   （应用启用 + 凭据 ACTIVE + 摘要常量时间匹配），再按主体类型解析；`USER` 主体必须有可信
   `externalUserId`（服务层校验），浏览器只有 Origin 或前端会话时拿不到任何用户票据。
2. **32 随机字节 token、只存 SHA-256 摘要、返回裁剪 scope**：`SecureRandom` 生成 32 字节 → Base64URL；
   库里只有 `token_digest`（用例断言摘要 ≠ 明文且不含明文）；返回的 `resourceKeys` 经**裁剪**：
   只保留主体范围与请求的交集（用例：越界对象被裁掉）。
3. **限速、错误脱敏、token 到期**：失败节流给出 429（`AI_QUOTA_EXCEEDED`），成功清零；
   票据到期时间默认 10 分钟（`basic-framework.ai.ticket.ttl`）；所有失败路径共用同一 401 语义
   （不存在/撤销/过期/应用停用/主体撤销），错误消息不含内部细节。
4. **唯一主体键与随机 token 摘要索引；事务成功后返回**：主体键来自 A02 的
   `(applicationId, subjectType, externalUserId)`；`token_digest` 建唯一索引；签写在
   `@Transactional` 内完成，事务未提交时票据对其他事务不可见，回滚不会留下可用票据。
5. **不缓存、拒绝无凭据换票、限制失败频率**：换票与校验都不缓存（校验每次重新读取应用与主体状态，
   因此撤销后未过期票据立即失效）；无 `appSecret` 直接 401；失败节流限制暴力尝试。

## 3. 关键约束与安全语义

- **错误不可枚举**：票据不存在、已撤销、已过期、应用停用、主体撤销统一返回 `AI_TICKET_INVALID`。
- **敏感字段不出 toString**：`appSecret`、`token`、`tokenDigest` 均按敏感字段目录排除（契约门禁通过）。
- **范围只来自服务端**：请求里没有角色/部门字段，范围由 A02 解析器给出并按请求裁剪。
- **越界裁剪/拒绝**：请求的对象全部越界且主体无组织范围时**拒绝签发**，不产生"空票据"。
- **依赖边界**：节流是进程内实现（模块无 Redis/限流 starter 依赖）；跨节点全局限流由部署侧承担（见未验证项）。

## 4. 验证结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约、台账（生命周期/数据权限/错误码映射）、敏感字段与接口授权策略检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 全量后端构建、单元测试与架构门禁通过 |
| `sh .harness/verify.sh integration` | 0（集成用例部分） | Testcontainers（MySQL 8.4）**全部集成用例通过**（含本卡新增用例）；命令末尾的覆盖率棘轮检查在登记基线前报"未登记"，登记后复验通过 |
| `node scripts/check-coverage-ratchet.mjs --update` | 0 | 新增文件基线登记（新文件下限 80%，只升不降） |
| `node scripts/check-coverage-ratchet.mjs all` | 0 | 前后端单文件基线全部通过 |
| `sh .harness/verify.sh frontend` | 0 | 前端门禁（check/lint/test:coverage/构建/前端棘轮）通过（本卡未改动前端，沿用 M06 收口结果） |

## 5. 未验证项

1. **全局限流**：`@RateLimiter` 属于 protection starter，`module-ai` 未依赖它；当前用进程内失败节流，
   跨节点限流需在部署入口（网关/WAF）配置，或后续为 module-ai 引入 protection starter（超本卡范围）。
2. **票据刷新与续期**：短期票据到期后必须重新换票，没有 refresh 语义（符合"短期 token"取向）。
3. **浏览器端到端**：换票的应用端链路需要在真实前端（Q 系列）与浏览器验收中补端到端用例。
4. **票据与服务端会话的绑定**：当前票据只绑定应用与主体；与会话/设备的绑定（防票据被复制使用）
   属后续安全增强卡。
