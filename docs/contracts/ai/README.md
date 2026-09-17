# AI 中台协议正式契约（v1）

本目录是 AI 中台跨语言协议的**正式契约**（F07 冻结）：QueryPlan、ReportSpec、ChartSpec、ResultBlock、
ThemeTokens 与 RunEvent。设计期草案位于 `docs/ai-platform/contracts/`，本目录为其正式归宿，
两者冲突时以本目录为准。

## 版本与拒绝规则

- 每个 Schema 都是 JSON Schema Draft 2020-12，标题形如 `<类型> v1 正式契约`。
- 带 `schemaVersion` 的契约使用 `const: "1.0"`：**未知版本必须拒绝**，不得"尽力解析"。
- 判别联合（ResultBlock、RunEvent）使用 `oneOf` + `additionalProperties: false`：未知 `kind` 或多余字段一律拒绝。
- 所有字符串字段都有长度上限；超长输入必须拒绝而不是截断。

## 金额精度

金额与需要精确表示的数值在协议中**必须是十进制字符串**（`^-?\d+(\.\d+)?$`），例如 `"12345678901234.56"`。
JSON number 会经过 IEEE-754 浮点转换，金额不得使用。`chart-spec.schema.json` 的 `$defs.chartValue`
同时接受 number 与十进制字符串，但金额语义只允许字符串形式；渲染层可做显示用数值转换，
**存储与传输必须保留原字符串**。

## 跨语言夹具

`samples/` 下的 JSON 是 Java（`basic-framework-module-ai-api`）与 TypeScript（`@vben/ai-contracts`）
共用的夹具，两侧测试读取同一份文件：

| 文件命名 | 期望 |
|---|---|
| `*.valid.json` | 必须被接受（含金额字符串精度保持） |
| `*.invalid.json` | 必须被拒绝（未知 kind/版本、超长字段、错误数值类型、非法颜色） |

命名约定是夹具契约的一部分：新增夹具必须按该后缀声明期望，且两侧测试都不能各自复制样例。

## 变更流程

1. 先改本目录的 Schema 与夹具，再改两侧实现（Java DTO、TS 类型），最后改消费者。
2. 版本升级必须同时提供旧版本样例的兼容结论（v1 样例即兼容基线）。
3. 两侧序列化一致性由夹具测试与 `packages/ai-contracts` 的 zod 校验共同保证。
