# basic-framework-module-ai

AI 中台业务模块：模型中心、应用与授权、AI 服务、开放 API 运行、知识库、数据与工具、报表与 Chat 集成。
遵循单体模块化：各功能是包边界，不拆微服务；跨模块调用只走薄契约模块。

当前状态：包结构与边界门禁已建立，模型中心（M 系列）按任务卡逐项实现；其余能力域按 A/S/O/K/D/R/C/Q 系列推进。

## 包结构（与架构说明 03 一致）

```text
com.basicframework.module.ai
  controller/admin/        控制面 REST 与 VO
  controller/app/v1/       开放 API 与 VO（业务系统接入）
  convert/                 边界映射（DO/VO/DTO）
  service/                 业务用例，输入 DTO/业务 command
  domain/                  QueryPlan、ReportSpec、权限与状态领域模型
  dal/dataobject/          DO
  dal/mysql/               Mapper 与查询参数
  adapter/                 API、MySQL、向量库、文件与执行器适配
  framework/security/      MEMBER Provider 与权限表达式实现
  framework/datapermission/ 本地 AI 表数据权限登记
  job/                     恢复、清理、同步 JobHandler
  enums/                   稳定 code 与领域枚举
```

## 已交付内容

| 类型 | 位置 | 说明 |
| --- | --- | --- |
| `AiTaskStatusEnum` | `com.basicframework.module.ai.enums` | 任务生命周期词汇：可领取（`PENDING`/`RETRY_WAIT`）、终态、`UNKNOWN` 核对分支 |
| 薄契约实现位 | `com.basicframework.module.ai.api.run` | `AiRunCommonApi` 的实现位，随 O 系列任务落地 |
| 模型端点（M01） | `controller/admin/model`、`service/model`、`dal/*/model` | 非秘密配置走不可变版本（`ai_model_endpoint_revision`），凭据只在端点行上保存密文并单独轮换；引用后地址冻结；全部写操作带乐观锁 CAS |
| 模型客户端解析（M02） | `adapter/model/AiModelClientResolver` | 启用端点 → 当前版本 → 解密凭据 → 组装快照 → 接缝工厂取受管客户端；`invalidate` 在改配置/轮换/停用后关闭旧客户端 |
| 外部主体与范围（A02） | `domain/identity`、`service/subject`、`dal/*/subject` | 主体唯一键 (applicationId, subjectType, externalUserId)：同一外部用户名跨应用互不冲突；范围由可信 `SubjectScopeResolver` 在请求期解析，解析失败/空集合/超预算/主体停用统一 DENY，不接受客户端提交的角色或部门；范围来源与范围版本随主体记录，业务侧撤销同步为 DISABLED |
| 应用与凭据（A01） | `controller/admin/application`、`service/application`、`domain/application`、`dal/*/application` | appCode 全局唯一且创建后不可修改；Origin 只接受精确来源（无路径/通配，写入前归一化）；客户端秘密只存 SHA-256 摘要、明文只在创建/轮换响应出现一次；轮换与吊销默认无重叠，旧秘密立即失效 |
| 评测套件与执行器（Q04） | `controller/admin/evaluation`、`service/evaluation`、`dal/*/evaluation` | 套件/样例是配置（草稿→冻结→新修订），运行与结果是事实（执行即冻结套件摘要与逐例快照）；执行走与真实运行相同的运行服务与授权（套件登记的合成主体），判定由确定性规则给出（金额/日期/引用/结构/版本/无秘密），模型评分不参与 |
| 能力探测（M04） | `service/model/AiModelCapabilityProbeService`、`controller/admin/model/AiModelCapabilityProbeController` | 真实调用探测连接/文本/流式/结构化/工具/嵌入六类能力，结论落 `ai_model_probe`（只存稳定码与耗时）；可发布范围 = 声明能力 ∩ 探测确认能力；嵌入维度首写记录、改变即拒绝写既有索引 |
| 多模态媒体准入（X01） | `adapter/model/AiMediaCapabilityGate` | 媒体调用（图片理解/OCR/生成/编辑、非实时 STT/TTS）的唯一入口：端点启用 → 能力已声明 → 该能力探测结论 `SUPPORTED` 且配置版本一致，全部通过才解析客户端；未开通时在任何网络请求前抛 `AI_MODEL_CAPABILITY_NOT_ENABLED`（400），不退化为文本调用、不切换其它端点/供应商。媒体请求/响应契约与词汇见 `basic-framework-spring-boot-starter-ai`；媒体探测项由 X02–X04 在 `provider` 实现，`probeAll` 纳入媒体探测项由 X02 同步 |
| 媒体任务（X03/X04） | `service/media`、`service/image`、`service/speech`、`job/AiMediaTaskJob` | 受理即落库（幂等键 + 固定端点/配置版本 + 租约栅栏终态）；执行器按操作分发：图片生成/编辑（X03）与**非实时 STT/TTS**（X04，`AiSpeechStepExecutor`）——转写全文与合成音频都先验后存为平台私有文件，产物只给 `fileId`；音频输入受理与执行两次核验（A07 归属 + 格式/字节/摘要），失权即拒绝；用量只记上游真实计数，缺失记 `UNKNOWN` 且数值为空 |
| 应用端语音接口（X04） | `controller/app/v1/speech` | `/app-api/ai/speech/**` 五个端点（`@AuthenticatedOnly`）：转写/合成受理 + 任务查询/分页/取消，复用 X03 的任务模型；请求先收窄音频格式/时长/文本/音色/语言（X01 冻结取值），协议里没有上游地址 |
| 受控业务写工具（X06） | `service/tool/AiToolWriteBinding`、`service/tool/AiToolWriteGate`、`service/tool/action`、`controller/app/v1/action` | 写工具版本必须声明**业务幂等键参数**与**已发布的核对查询**（版本输出 schema 的保留键 `write`；幂等键必须是输入 schema 的必填字符串参数，且写操作本身要声明它），政策不得为 `AUTO`；写调用只经确认动作执行：`CONFIRMED → EXECUTING`（尝试代数）后落 `EXECUTED/FAILED/UNKNOWN`，同工具 + 同业务键在 `uk_ai_tool_action_business` 下最多一条"可能已生效"的动作；结果未定只能经 `POST /ai/action/reconcile`（PROGRAM 走登记的核对查询、MANUAL 记录人工结论）收敛，绝不自动重放 |

## 边界约束

- 与 `module-system`、`module-infra` 双向隔离：只允许消费对方薄契约模块发布的 CommonApi 与 DTO，
  不访问对方 Service、Mapper、DO；反向同理。规则由 `ModuleBoundaryArchitectureTest` 阻断。
- 不直接依赖 Spring AI：模型调用统一走 `basic-framework-spring-boot-starter-ai` 发布的 `ModelPort`。
- 厂商类型（Spring AI、Qdrant SDK 等）不得进入本模块的公开 API 与长期存储协议。
- Service/DAL 不反向导入 Controller VO；越界引用被架构门禁拒绝。

## 构建与测试

```sh
cd 后端代码/basic-framework-boot
./mvnw -pl basic-framework-module-ai test
```
