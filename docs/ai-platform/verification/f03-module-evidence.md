# F03 模块与边界证据（2026-09-16）

本记录是 [F03 新增后端 AI 模块及边界门禁](../ai-platform/tasks/F03.md) 的验收证据。
原始日志与构建输出归档在 `.local-state/f03-backend/`（Git 忽略）。

## 1. 交付内容

| 交付物 | 位置 | 说明 |
|---|---|---|
| AI 能力接缝 | `basic-framework-core/basic-framework-spring-boot-starter-ai` | 自有契约 `ModelPort`/`ModelCapability`、装配期 fail-closed 校验、`provider.springai` 为唯一 Spring AI 引用区 |
| AI 业务模块 | `basic-framework-module-ai` | 包结构与领域枚举（`AiTaskStatusEnum`），按任务卡逐项扩展 |
| AI 薄契约模块 | `basic-framework-module-ai-api` | `AiRunCommonApi` 与 `AiRunStatusEnum`（与开放 API 契约 RunStatus 对齐） |
| 边界门禁 | `basic-framework-server/src/test/.../ModuleBoundaryArchitectureTest.java` | 新增规则 E/F/G（AI 与 system/infra 双向隔离）、H（厂商类型边界）、I（service/dal 不依赖 VO） |
| 架构拒绝测试 | `basic-framework-server/src/test/.../ModuleBoundaryArchitectureRejectionTest.java` | 6 个用例证明规则可被代表性违例触发，且合规写法不被误伤 |
| 结构化 README | 三个新模块 | 能力、启用条件、默认行为、约束与测试命令 |

装配：根 POM 增加 `module-ai-api`、`module-ai`；core 增加 `starter-ai`；server 依赖 `module-ai`；
coverage 聚合新增三个生产模块；`basic-framework-dependencies` 登记 `module-ai-api` 与 `starter-ai` 版本。

## 2. 关键设计决策

1. **零消费者的能力缝**：starter-ai 当前无仓库内消费点，按
   [ADR 0028](../../adr/0028-framework-seams-may-have-zero-in-repo-consumers.md) 处理——README 说明启用条件与
   默认行为（默认关闭；启用必须声明能力且装配唯一 `ModelPort`，否则启动失败），契约测试钉住行为，
   能力目录只声明"已交付并通过契约测试"。
2. **v1 契约范围**：`ModelPort` v1 只声明能力集合；调用契约（超时、取消、资源关闭与稳定错误）
   随 M02/M03 扩展，避免在无 provider 实现时先冻结易返工的调用面。
3. **厂商依赖**：starter 声明 `spring-ai-model`（F02 冻结的 spring-ai-bom 1.1.8 管理版本），
   规则 H 保证 `org.springframework.ai` 类型只出现在 `provider.springai` 包。
4. **规则 I 收窄**：仅禁止依赖平台自有 `com.basicframework..vo..`；第三方库自带的 `vo` 包
   （如验证码组件 `com.anji.captcha.model.vo`）不属于平台协议层类型，避免误伤既有生产代码。

## 3. 验证结果

| 命令（仓库根） | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 字段/生命周期/权限目录/门禁接线/例外台账通过；starter 文档契约通过（新增 starter 有 README） |
| `sh .harness/verify.sh backend` | 0 | 编译、单测、JaCoCo 模块覆盖率门槛、Spotless、ArchUnit 全部通过 |
| `./mvnw -pl <starter-ai,module-ai-api,module-ai> verify` | 0 | 新模块 14 个测试通过；行覆盖率门槛 100% 达成 |
| `./mvnw -pl basic-framework-server test -Dtest='ModuleBoundary*'` | 0 | 10 条边界规则 + 6 个拒绝用例通过 |
| `sh .harness/verify.sh integration` | 0 | Testcontainers MySQL/Redis、Flyway 迁移链、`PackagedJarBootSmokeIT` 通过（打包 jar 启动不缺 Bean） |

覆盖率基线：`docs/contracts/coverage-baseline.json` 由 `node scripts/check-coverage-ratchet.mjs --update` 登记新增文件
（脚本只升不降：`Math.max(当前, 既有底线)`），新增文件底线满足 `newFileMinimum` 要求。

## 4. 拒绝证据（架构规则可变红）

| 规则 | 违例夹具 | 断言 |
|---|---|---|
| E：AI 不得依赖 system 内部实现 | `AiDependsOnSystemInternalsFixture` | 违规命中夹具类 |
| F：AI 不得依赖 infra 内部实现 | `AiDependsOnInfraInternalsFixture` | 违规命中夹具类 |
| G：system/infra 不得依赖 AI 内部实现 | `InfraDependsOnAiInternalsFixture` | 违规命中夹具类，且**不**命中只消费薄契约的 `InfraUsingPublishedAiContractFixture` |
| H：厂商类型边界 | `VendorLeakFixture`（包外引用 Spring AI） | 违规命中；`provider.springai` 包内的 `VendorAllowedFixture` 不违规 |
| I：service/dal 不依赖 VO | `VoLeakServiceFixture` | 违规命中夹具类 |

夹具位于 server 测试源码的 `archfixture` 包，生产门禁以 `ImportOption.DoNotIncludeTests` 排除，
不会成为真实依赖（规则评估仍覆盖全部主源码类）。

## 5. 变更文件（按职责）

- 新增模块：`basic-framework-module-ai/`、`basic-framework-module-ai-api/`、
  `basic-framework-core/basic-framework-spring-boot-starter-ai/`（含 README、pom、源码与测试）
- 装配：根 `pom.xml`、`basic-framework-core/pom.xml`、`basic-framework-server/pom.xml`、
  `basic-framework-coverage/pom.xml`、`basic-framework-dependencies/pom.xml`
- 门禁：`ModuleBoundaryArchitectureTest.java`（新增 5 条规则与 AI 契约允许清单）、
  `ModuleBoundaryArchitectureRejectionTest.java`（新增）
- 台账：`docs/contracts/coverage-baseline.json`（登记新增文件覆盖率）、`docs/capability-catalog.md`（新增 entry）

## 6. 未验证项

1. 启用 AI 能力（`basic-framework.ai.enabled=true`）的真实提供方启动路径：provider 实现随 M02 落地，
   当前只有 `ApplicationContextRunner` 级的装配契约测试。
2. `ModelPort` 调用契约（超时/取消/错误）与真实模型端点：M02/M03 范围。
3. 知识索引与厂商 SDK（Qdrant）类型边界：随 K01 引入 Qdrant 客户端后，规则 H 同样覆盖该厂商类型。
