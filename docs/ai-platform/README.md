# 企业 AI 中台产品与开发文档包

版本：1.0（规划基线）

编制日期：2026-09-16

适用对象：[shady]、产品负责人、架构评审人、后续编码模型和验收人员。

## 先读这段

这是基于实际框架只读检查形成的产品与实施方案，文中的AI目标文件、接口、数据表和任务均为拟开发内容，AI功能尚未实现。独立副本与迁移方式见[00](00-project-bootstrap.md)，实际执行的检查见[仓库复核记录](verification/repository-review.md)。业务集成与上游运行兼容仍需验证，不把候选版本当作已验证组合。

源框架`E:\kuangjia\2026-main`继续只读。用户现已授权建立独立开发副本，方案统一放在项目的`docs/ai-platform/`，从[初始化与迁移说明](00-project-bootstrap.md)开始。当前项目根目录可用于授权范围内开发；本文件不解除原框架只读约束，不授权提交、推送、部署或外部发布。

## 阅读顺序

| 文件 | 解决什么问题 |
|---|---|
| [00-project-bootstrap.md](00-project-bootstrap.md) | 已初始化的目录、独立Git、基线快照、新电脑开工和剩余验证 |
| [01-framework-baseline.md](01-framework-baseline.md) | 框架实际是什么、复用什么、缺少什么、强制遵循哪些代码要求 |
| [02-product-requirements.md](02-product-requirements.md) | 产品定位、角色、模块、功能规则、异常、验收和分期 |
| [03-architecture-integration.md](03-architecture-integration.md) | 代码落位、组件适配、认证、查询、RAG、任务与运行架构 |
| [04-api-chat-integration.md](04-api-chat-integration.md) | 对外 API、结构化结果、流式协议、Chat SDK、主题和第三方接入 |
| [05-data-security-contracts.md](05-data-security-contracts.md) | 数据模型、状态机、字段边界、权限、生命周期和配置 |
| [06-upstream-upgrade.md](06-upstream-upgrade.md) | 上游组件/基础框架升级、补丁管理、兼容、数据迁移和回退 |
| [07-development-plan.md](07-development-plan.md) | 阶段、任务依赖、执行顺序、开发门禁和交付条件 |
| [08-testing-acceptance.md](08-testing-acceptance.md) | 功能/安全/性能/浏览器/升级验收场景与证据 |
| [09-model-handoff.md](09-model-handoff.md) | 给编码模型的任务领取、禁止事项、暂停条件和交接模板 |
| [10-decisions-sources.md](10-decisions-sources.md) | 决策状态、兼容证据、待验证项和官方参考 |
| [11-control-plane-pages.md](11-control-plane-pages.md) | 应用/服务/知识关系、管理菜单、配置表单、状态与页面验收 |
| [12-critical-implementation-blueprints.md](12-critical-implementation-blueprints.md) | 身份、任务租约、检索、SQL/API、报表与Chat关键实现的细化顺序和禁止捷径 |
| [tasks/README.md](tasks/README.md) | 每个任务的独立执行卡、输入输出、文件范围、步骤、测试与验收 |
| [contracts/README.md](contracts/README.md) | 规划阶段的机器可读协议、样例和任务索引 |
| [verification/README.md](verification/README.md) | 本文档包检查结果与源框架只读复核 |

## 文档状态与裁决顺序

1. **已确定**：来自用户明确选择；见 PRD 的 C01–C13。
2. **设计默认**：为减少编码模型自行发挥而提出的具体方案；见 D01–D14。评审前是建议，不能描述成用户已经逐条确认。
3. **验证关口**：必须用真实构建/集成实验关闭；失败时记录证据并回到对应设计，不以跳过检查继续。
4. **后续规划**：有具体任务卡，但不自动进入一期。

用户指令、目标工作副本中最近的 AGENTS.md 与现有契约优先。工程事实归 01；需求与范围归 02；架构归 03；API/SDK 归 04；数据与状态归 05；升级归 06；任务执行信息归任务卡。出现冲突先修正文档和依赖任务，不由编码模型自行选一份照抄。

## 本包的核心设计

- 一个企业一个部署，保持单租户基础；一个业务系统先完成验证，应用模型保留后续多应用能力。
- 一个 Java 业务模块组织 AI 功能；新增必要的薄契约与 starter 接缝；继续由现有 server 装配。
- 模型部署在平台外，中台管理 API；首轮验证 OpenAI 兼容端点和 Ollama。
- 业务能力由本平台定义，Spring AI、AntV 等只在适配边界内使用。
- API、嵌入 Chat、自建前端共用服务和结果协议。
- 报表以受校验的结构描述生成网页；首期不执行模型产生的任意 HTML、JavaScript、SQL 或系统命令。
- 产品范围覆盖知识、文本、图像、语音、报表和工具；先交付单系统文本/知识/数据/报表闭环，再迭代多模态和跨系统。
- 上游版本、模型、提示词、嵌入模型、报表协议各自管理版本；更新需要回归，不能直接覆盖客户安装包。

## 如何开始后续开发

本包包含40项功能需求、72项编号验收场景和102张开发任务卡；P0为10项、V1.0为75项，其余17项属于后续版本。编号用例之外，各卡还有专项反向验证。

从任务 `F01` 开始，按 [开发计划](07-development-plan.md) 的依赖顺序领取任务。每次给编码模型一张任务卡及其引用文档；不要一次要求实现整个平台。涉及鉴权、凭据、权限或核心接缝的实现，在开始前满足用户已有工程基线要求的明确授权和评审；本次交付只进行设计。
