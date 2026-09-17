# M05 模型外发策略与调用计量证据（2026-09-17）

本记录是 [M05 实现模型外发策略与调用计量](../tasks/M05.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 外发等级词汇 | `module-ai/domain/policy/AiOutboundLevel`：`L1_PUBLIC`/`L2_INTERNAL`/`L3_PERSONAL`/`L4_SECRET`，与 `docs/security/data-classification.md` 一致，提供 `within(ceiling)` 与解析 |
| 外发策略 | `AiOutboundPolicy`（接口）+ `DefaultAiOutboundPolicy`：按端点给出等级上限与允许资源集合，`assertAllowed` 在被拒绝时抛 403 稳定错误 |
| 策略配置 | `AiOutboundPolicyProperties`（`basic-framework.ai.outbound.*`）：`default-level`（默认 L2）、端点覆盖 `endpoints.<id>.level/allowed-resources`、`metering.estimate-when-missing/chars-per-token` |
| 计量记录 | `service/usage/AiModelInvocationRecord`：invocationId、端点与版本身份、能力、状态、耗时、用量（UNKNOWN/ESTIMATED）、失败原因；不含提示词、响应正文与凭据 |
| 计量出口 | `AiModelUsageRecorder`（SPI，Q02 持久化用）；未接线时记录仍随结果返回，**不提前创建账本表** |
| 计量工厂 | `AiModelInvocationMeter`：成功/失败记录、UNKNOWN 与 ESTIMATED 的区别、字符数估算（向上取整、最小 1） |
| 调用编排 | `service/model/AiModelInvocationService(+Impl)`：先策略、后解析、再调用；每次实际调用一个 invocationId；失败落 FAILED 记录后原样抛出 |
| 错误码 | `AI_MODEL_OUTBOUND_BLOCKED = 1_003_002_006`（403）+ `error-code-map.md` 同步 |
| 测试 | `DefaultAiOutboundPolicyTest`(5)、`AiModelInvocationMeterTest`(6)、`AiModelInvocationServiceImplTest`(5) |

## 2. 与卡片逐步实施的对应

1. **按端点标识外发等级与允许资源类别**：策略按端点编号解析上限与显式允许集合；
   未配置端点使用平台默认上限（L2），L3/L4 必须显式列入 `allowed-resources`。
   显式清单是白名单：未列入的等级即使低于上限也不放行（用例覆盖）。
2. **调用前校验、不做隐式跨供应商失败转移**：`invoke` 的第一条语句就是 `assertAllowed`；
   被拒绝时**端点解析与上游调用都不会发生**（用例断言 `endpointService`/`clientResolver` 零交互）。
   解析只针对调用方指定的端点，编排里没有任何候选端点遍历，失败按稳定原因抛出。
3. **提取 usage/耗时/状态，不记录正文与秘密**：记录只含计数、耗时、状态与稳定原因码；
   `promptTokens`/`completionTokens` 按敏感字段目录排除出 `toString`（计数不是凭据，但避免日志噪声）。
4. **每次调用分配 invocationId 并产生自有计量记录**：`AiModelInvocationMeter.newInvocationId()` 每调用一次；
   记录同时随 `AiModelInvocationResult` 返回并通过 `AiModelUsageRecorder` 交给上层，
   在 Q02 交付前不落库、不建表。

## 3. 关键约束与安全语义

- **默认拒绝**：未配置任何策略时敏感等级（L3/L4）一律拒绝；`resourceLevel` 为空也拒绝。
- **UNKNOWN/ESTIMATED 区别**：上游给用量 → 原样记录 `estimated=false`；上游没给且未开启估算 → 计数留空
  （UNKNOWN，**不写 0**）；开启估算 → 字符数估算并标记 `estimated=true`，不参与结算。
- **失败也计量**：上游失败写入 FAILED 记录（含 `ModelException.Reason` 名称）后再抛出，
  "调用过" 可在账本核对，失败不会被静默丢弃。
- **单一端点**：失败不切换端点，杜绝把数据转到未授权端点。

## 4. 验证结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -pl basic-framework-module-ai verify` | 0 | 62 例通过（新增策略 5 + 计量 6 + 编排 5） |
| `sh .harness/verify.sh contracts` | 0 | 台账与静态检查通过（含敏感字段 toString 门禁） |
| `sh .harness/verify.sh backend` | 0 | 全量后端构建与架构门禁 |
| `sh .harness/verify.sh integration` | 0 | Testcontainers 集成用例与棘轮检查全部通过 |

| `sh .harness/verify.sh integration` | 0（maven 部分） | Testcontainers 集成用例全部通过；命令末尾的覆盖率棘轮检查报出 4 处覆盖下降/未登记（见下） |

**覆盖率棘轮（已收口）**：补测后 `node scripts/check-coverage-ratchet.mjs --update` 登记基线，
`node scripts/check-coverage-ratchet.mjs all` 复验通过（只升不降）。AI 文件的登记值：

| 文件 | 行覆盖率 |
|---|---|
| `SpringAiModelClient` | 98.45% |
| `SpringAiModelClientFactory` | 100% |
| `AiModelClientResolver` | 100% |
| `AiModelInvocationServiceImpl` | 97.83% |
| `core/model/*`（M03/M04 新增契约） | 100%（`StructuredJsonOutput` 94.44%） |
| `AiModelCapabilityProbeServiceImpl` | 95.08% |
| `SpringAiModelStream` | 86.26% |

## 5. 未验证项

1. **账本持久化（Q02）**：本卡只产生记录与 SPI，不做表设计与写入；Q02 接线后需补幂等与对账用例。
2. **策略配置的来源**：策略目前来自部署配置（`basic-framework.ai.outbound.*`）；
   管理界面上的端点级外发等级编辑器属后续卡片。
3. **数据分级的人工判定**：资源等级由调用方声明，尚无自动化分级扫描；
   调用方（运行/知识库）接入时需按字段目录标注等级。
