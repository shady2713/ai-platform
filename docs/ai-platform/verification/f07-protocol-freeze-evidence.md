# F07 协议契约冻结证据（2026-09-17）

本记录是 [F07 冻结公共类型与协议样例](../ai-platform/tasks/F07.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 正式协议目录 | `docs/contracts/ai/`：`README.md`（版本/金额/夹具规则） |
| 正式 Schema（v1，Draft 2020-12） | `chart-spec`、`result-block`、`theme-tokens`、`run-event`（新增）；`query-plan`、`report-spec`（从设计契约提升） |
| 跨语言夹具 | `docs/contracts/ai/samples/` 共 11 份，命名约定即期望：`*.valid.json` 接受、`*.invalid.json` 拒绝 |
| Java 协议 DTO | `module-ai-api`：`AiChartSpecDTO`、`AiChartSeriesDTO`、`AiResultBlockDTO`、`AiRunEventDTO`（record + 显式 `validate()`） |
| TS 判别联合 | `@vben/ai-contracts`：`chartValueSchema`（number 或十进制字符串）、`themeSchema` 增 `colorScheme`、新增 `runEventSchema`/`runStatusSchema` |
| 渲染适配 | `ChartRenderer`：十进制字符串仅做显示用数值转换，不回写存储 |
| 两侧测试 | Java `AiProtocolFixtureTest`（6 例）；TS `protocol-fixtures.test.ts`（13 例） |

## 2. 关键冻结规则

1. **版本拒绝**：`schemaVersion` 使用 `const: "1.0"`，未知版本抛异常（夹具 `run-event.unknown-version.invalid.json` 验证）。
2. **判别联合**：ResultBlock/RunEvent 未知 `kind`、多余字段一律拒绝（`additionalProperties: false` + Java 显式 shape 校验）。
3. **金额精度**：协议中金额必须是十进制字符串；Java 用 `BigDecimal` 绑定（标度不变），写回用 `toPlainString()`；
   TS 侧 `chartValueSchema` 接受字符串并保留原值，测试断言 `"12345678901234.56"` 不经过浮点转换。
4. **公开 ID 与内部 Long 分离**：协议侧 ID 是业务键字符串（`run_xxx`，正则校验）；内部 Long 只存在于模块内 DO/DTO，
   `AiRunCommonApi` 等跨模块契约沿用内部标识，转换发生在边界映射层。
5. **旧版本兼容**：v1 样例（`query-plan.valid.json`、`report-spec.valid.json` 等）作为兼容基线保留在夹具目录。

## 3. 验证结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -pl basic-framework-module-ai-api test` | 0 | 9 例通过（含 `AiProtocolFixtureTest` 6 例：全部有效夹具接受、全部无效夹具拒绝、金额精度往返、形状不一致拒绝） |
| `vitest run packages/ai-contracts packages/ai-chat-ui` | 0 | 5 个测试文件 / 33 例通过（含夹具测试 13 例） |
| `sh .harness/verify.sh contracts` | 见第 4 节 | 文档/契约门禁 |
| `sh .harness/verify.sh backend` | 见第 4 节 | 后端全量门禁 |
| `sh .harness/verify.sh frontend` | 见第 4 节 | 前端全量门禁（含覆盖率棘轮） |

## 4. 门禁与后续

门禁结果与棘轮登记见交接记录；新增/变更文件的单文件覆盖率基线按
`node scripts/check-coverage-ratchet.mjs --update` 登记（只升不降）。

## 5. 未验证项

1. QueryPlan/ReportSpec 的**运行期**校验由 D 系列/R 系列实现（本卡只冻结 Schema 与夹具）；
   当前 Java 侧仅对 result-block/chart-spec/theme-tokens/run-event 做显式校验。
2. TS 侧尚未实现 QueryPlan/ReportSpec 的 zod 校验（由 D05/R01 消费时补齐），本卡只保证夹具可解析。
3. 多模态扩展（X01）会扩展 ChartSpec/ResultBlock 的枚举，需按变更流程先改本目录 Schema。
