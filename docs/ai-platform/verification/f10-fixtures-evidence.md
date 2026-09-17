# F10 合成业务与协议测试夹具证据（2026-09-17）

本记录是 [F10 建立合成业务与协议测试夹具](../ai-platform/tasks/F10.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 共享夹具（唯一来源） | `packages/ai-contracts/fixtures/` 共 7 份：固定时钟、订单、回款、主体范围、API 分页、知识文档、Mock 模型响应 |
| Java 装载器 | `module-ai`（测试源）`fixtures/AiTestFixtures`：定位共享目录、解析 JSON、固定时钟常量、金额判定 |
| Java 不变量测试 | `module-ai`（测试源）`fixtures/AiTestFixturesTest`（7 例） |
| 前端不变量测试 | `packages/ai-contracts/src/__tests__/synthetic-fixtures.test.ts`（10 例） |
| 夹具目录文档 | [docs/testing/ai-fixtures.md](../../testing/ai-fixtures.md)：清单、关键不变量、使用约束、运行命令 |

## 2. 与卡片逐步实施的对应

1. **订单/回款/用户范围/API 分页**：订单 4 条（含大额、取消、待发货），金额一律十进制字符串且大额
   `12345678901234.56` 精度标度不变；回款覆盖"同一订单多笔（含 0.00）"的聚合口径；
   范围夹具含**空集合**（必须解释为无权限）；分页夹具含超限（pageSize=1001）拒绝样例。
2. **知识文档与固定时钟**：公共/私有/提示注入/已删除四类齐全；注入样本标注"只作为数据，不得当指令执行"；
   删除样例用于验证检索与引用不可见；`fixed-clock.json` 固定基线 `2026-09-17T02:00:00Z`，两侧测试断言该值。
3. **Mock 模型响应**：合法文本、合法工具调用、畸形 JSON、缺 `choices`、超时（≥60s）、上游 500；
   每条声明 `expect ∈ {ACCEPT, REJECT, TIMEOUT, UPSTREAM_ERROR}`；畸形样例在两侧都被断言**确实无法解析**。
   文档明确：Mock 不用于证明真实模型效果（真实效果归 Q04/Q10）。

## 3. 验证结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -pl basic-framework-module-ai verify` | 0 | 13 例通过（含 `AiTestFixturesTest` 7 例）；覆盖率门槛、Spotless 通过 |
| `vitest run --dom packages/ai-contracts` | 0 | 4 个测试文件 / 33 例通过（含新夹具用例 10 例） |
| `sh .harness/verify.sh contracts` / `backend` | 见交接记录 | 全量门禁复验（F10 变更后的完整链路） |

## 4. 一致性保证

两侧测试读取**同一份文件**，且断言同一组不变量：金额十进制字符串、空范围=无权限、分页超限拒绝、
知识文档四类可见性、Mock 期望词表与畸形真实性、固定时钟值。任一栈复制样例或改口径都会被另一栈的断言与
`docs/testing/ai-fixtures.md` 的登记要求暴露。

## 5. 未验证项

1. 夹具的**业务消费**（查询计划、报表、知识问答）在 D/R/K 系列任务中落地，本卡只交付夹具与不变量；
   消费方接入后应复用 `AiTestFixtures` 而不是各自内联数据。
2. `server/src/test/.../fixtures/ai` 目录保留但暂未使用：跨模块集成夹具（如把合成订单灌入真实 MySQL）
   随 K03/D11 的集成用例一起建设，避免现在创建空包。
3. 真实模型与真实业务库的端到端验收不属于本卡（Q10/D11）。
