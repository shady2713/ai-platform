# M02 动态模型客户端工厂证据（2026-09-17）

本记录是 [M02 实现动态模型客户端工厂](../ai-platform/tasks/M02.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 自有模型契约 | `starter-ai` `core/model`：`ModelRequest`、`ModelResponse`、`ModelEndpointKey`、`ModelEndpointSnapshot`、`ModelClientFactory`、`ModelException`；`ModelPort` 扩展 `generate(ModelRequest)` |
| 受管客户端 | `provider/springai/SpringAiModelClient`：自有请求↔厂商调用互转、失败收敛为稳定原因、能力校验、关闭后拒绝 |
| 受管工厂与有界缓存 | `provider/springai/SpringAiModelClientFactory`：按 `(endpointId, configRevision, credentialRevision)` LRU 有界缓存、容量淘汰即关闭、`invalidate(endpointId)`、`close()`；创建前校验提供方与出站允许清单 |
| 装配 | `BasicFrameworkAiAutoConfiguration`：启用 AI 时注册 `ModelClientFactory`（`destroyMethod=close`） |
| 业务侧解析器 | `module-ai/adapter/model/AiModelClientResolver`：取启用端点 → 当前不可变版本 → 解密凭据 → 组装快照 → 工厂取客户端；提供 `invalidate` |
| 依赖补充 | starter 增加 `spring-ai-openai`（版本由 F02 冻结的 spring-ai-bom 管理） |
| 测试 | `ModelContractTest`（4）、`SpringAiModelClientFactoryTest`（5）、`SpringAiEndpointIsolationTest`（2）、`AiModelClientResolverTest`（5） |

## 2. 与卡片逐步实施的对应

1. **自有请求/事件/能力契约**：`ModelRequest`/`ModelResponse`/`ModelCapability`（事件与流式随 M03 扩展）；厂商类型只出现在 `provider/springai`。
2. **按 revision 创建受管客户端**：`AiModelClientResolver` 组装快照（含端点三个版本身份与解密后的凭据），工厂据此创建客户端；配置文件中的全局 baseUrl 不参与。
3. **有界缓存、轮换失效、取消与关闭**：LRU 容量上限（默认 32）淘汰即关闭；`invalidate(endpointId)` 关闭该端点全部客户端；`close()` 全关；客户端关闭后拒绝新请求。
4. **按指定 revision 重建；禁用与网络策略生效**：解析器只接受**启用**端点（停用经 M01 服务拒绝）、只读当前配置版本；工厂创建前校验提供方与目标主机/端口是否在 `AiHttpProperties` 允许清单内（与 F09 边界同一策略来源）；历史版本表不含凭据列（M01 集成用例断言）。
5. **缓存键含三个版本身份**：`ModelEndpointKey`；轮换后 `credentialRevision` 变化即得到新键，旧客户端不被复用（隔离用例断言服务端先后收到 sk-old 与 sk-new）。

## 3. 关键约束与安全语义

- **凭据不进键/日志**：`ModelEndpointKey` 只含版本身份；`ModelEndpointSnapshot.toString()` 强制 `apiKey=***`，契约测试断言 toString 不含明文。
- **失败不外泄上游内容**：`ModelException` 只暴露 7 个稳定原因；`mapFailure` 按异常类型映射（超时→TIMEOUT，其余→UPSTREAM_FAILED），消息不含厂商报文。
- **端点隔离**：并发调用两个端点时 baseUrl、凭据、modelId 各自独立（真实 Spring AI 客户端 + 两个假 OpenAI 端点验证）。

## 4. 验证结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -pl basic-framework-core/basic-framework-spring-boot-starter-ai verify` | 0 | 46 例通过（新增厂商响应转换 4 例）；模块覆盖率 ≥ 地板 0.860（F03 骨架期 1.000 不再适用，已在 pom 注明依据与只升不降） |
| `./mvnw -pl basic-framework-module-ai verify` | 0 | 32 例通过（解析器 5 + 服务 10 + 控制器 4 + 夹具 7 + 契约 6） |
| `SpringAiEndpointIsolationTest` | 0 | **AT-001**：两端点并发调用响应内容与凭据/模型标识均不串线；**AT-004**：轮换后新请求使用新密钥、`invalidate` 释放旧客户端 |
| `sh .harness/verify.sh contracts` / `backend` / `integration` | 见第 5 节 | 全量门禁 |

## 5. 门禁与棘轮

在授权副本根目录（`/home/ctyun/桌面/zhongtai/ai-platform`）按卡片顺序执行（2026-09-17 16:44–17:22）：

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约、字段目录、权限、台账与文档检查通过 |
| `sh .harness/verify.sh backend` | 0 | 全量后端构建、单元测试、ArchUnit 模块边界与覆盖率地板通过 |
| `sh .harness/verify.sh integration` | 0 | Testcontainers（MySQL 8.4）集成用例通过，含 M01 端点持久化全链路 |
| `node scripts/check-coverage-ratchet.mjs --update` | 0 | 首次登记三个新文件基线：`SpringAiModelClient` 行 97.7%、`SpringAiModelClientFactory` 行 100%、`AiModelClientResolver` 行 100% |
| `node scripts/check-coverage-ratchet.mjs all` | 0 | 前后端单文件基线全部通过 |

登记口径说明：棘轮以后端**唯一权威**聚合报告
（`basic-framework-coverage/target/site/jacoco-aggregate/jacoco.xml`，由 server 的
`jacoco:report-aggregate` 合并全部模块 exec 生成）为准；模块单独构建产生的
`target/site/jacoco/jacoco.xml` 只作为开发期参考。首次登记前该聚合报告里
`SpringAiModelClient` 行覆盖率为 79.07%（低于新文件 80% 下限），补齐厂商响应转换的
空响应/缺用量/结束原因/超时分支用例后达到 97.7%，随后才登记，未放宽下限。

## 6. 未验证项

1. **请求级传输治理**：Spring AI 客户端自身的 HTTP 传输尚未替换为 F09 的 `GuardedExternalHttpClient`；当前在**创建期**校验出站允许清单、并在解析层拒绝停用端点，请求级强制（每请求经过受控边界）待后续任务接线。这是本卡明确的剩余风险。
2. **流式事件与结构化输出**：M03。
3. **真实模型端点**：AT-001/AT-004 用假 OpenAI 兼容端点验证协议与隔离；真实厂商端点调用属环境验收（需凭据）。
4. 平台级（管理员配置 → 调用）端到端链路待 M03/M05 接线后再补。
