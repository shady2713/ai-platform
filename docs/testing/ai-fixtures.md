# AI 中台测试夹具（F10）

夹具的唯一来源是 `前端代码/basic-framework-admin/packages/ai-contracts/fixtures`，
Java（`basic-framework-module-ai`，见 `AiTestFixtures`）与 TypeScript（`@vben/ai-contracts`）
读取**同一份文件**，两侧各有一个不变量测试，禁止在任一栈复制样例。

## 夹具清单

| 文件 | 内容 | 关键不变量 |
|---|---|---|
| `fixed-clock.json` | 固定时钟基线 `2026-09-17T02:00:00Z` | 用例不得使用系统当前时间；夹具内时间戳相对该基线 |
| `business-orders.json` | 4 条合成订单（含大额、取消、待发货） | 金额必须是十进制字符串；大额 `12345678901234.56` 精度与标度不变 |
| `business-payments.json` | 3 条回款（同一订单多笔，含 0.00） | 覆盖部分回款聚合口径，不得按"取首条"实现 |
| `user-scopes.json` | APP/USER 主体范围（含**空集合**） | 空集合 = 无权限，不得解释为无限制 |
| `api-pagination.json` | 两页数据 + 超限样例（pageSize=1001） | 超限必须拒绝，不得截断 |
| `knowledge-documents.json` | 公共/私有/提示注入/已删除文档 | 注入样本只作为数据；已删除文档必须不可检索、不可引用 |
| `mock-model-responses.json` | 合法文本、合法工具调用、畸形 JSON、缺字段、超时、上游 500 | 每条声明 `expect ∈ {ACCEPT, REJECT, TIMEOUT, UPSTREAM_ERROR}`；畸形样例必须真的无法解析 |

## 使用约束

1. **Mock 不能证明模型效果**：这些响应只用于协议解析、失败路径与降级逻辑验证；
   真实模型效果由 Q04 评测套件与 Q10 端到端验收负责。
2. **金额只用字符串**：任何夹具与消费方都不得把金额转成浮点参与比较或存储。
3. **删除与可见性必须走授权**：`knowledge-documents.json` 的删除样例用于验证
   "检索与引用都返回不可见"，不得被测试绕过。
4. 新增夹具必须同时更新：本文件、两侧不变量测试、以及需要消费它的任务卡。

## 运行

```sh
# Java（在 后端代码/basic-framework-boot 下）
./mvnw -pl basic-framework-module-ai test -Dtest=AiTestFixturesTest

# 前端（在 前端代码/basic-framework-admin 下）
pnpm vitest run --dom packages/ai-contracts
```
