# 10 决策、验证关口与来源

日期：2026-09-16。静态阅读结论不替代构建、许可证清单或部署验证。

## 1. 设计决策台账

| ID | 本包建议 | 理由 | 状态/变更条件 |
|---|---|---|---|
| D01 | 单体AI业务模块 + 薄API + AI starter | 贴合现有模块边界，减少运行服务 | 设计默认；P0装配验证 |
| D02 | 保持Java17/Boot3.5，验证Spring AI 1.1维护分支 | 优先适配用户框架 | 候选1.1.7；重新查补丁/维护状态后锁版本；不默认上2.x |
| D03 | Spring AI Alibaba可选，不带完整Agent平台 | 只引入有明确消费者的组件 | 后续特定模型/流程确需时验证 |
| D04 | 外部API用/app-api/ai/v1 + MEMBER Provider | 复用现有安全路由接缝 | P0确认Provider唯一与身份隔离 |
| D05 | API、Chat共用运行与ResultBlock协议 | 避免两套实现漂移 | 设计默认 |
| D06 | Chat独立Vue入口 + iframe SDK | 宿主框架独立、UI集中维护 | 跨Origin、严格Cookie/CSP实测 |
| D07 | 主题tokens覆盖Chat/图表/报表 | 视觉适配且可控 | 禁任意CSS/脚本 |
| D08 | QueryPlan编译器执行SQL/API | 将语义正确性与执行边界显式化 | 首期不执行模型原始SQL |
| D09 | ReportSpec受控渲染 + AntV适配器 | 遵守现有HTML禁注入规则，可升级 | GPT-Vis或G2仅择一默认，P0测包体/CSP |
| D10 | Qdrant为向量存储候选 | 自托管、过滤、Spring AI适配参考 | 未获得用户对具体存储确认；P0需运维/质量/兼容通过，未通过暂停KB实现 |
| D11 | MySQL持久任务 + 现有Quartz/执行器 | 保持轻量，不恢复已删除MQ | 租约/重启/幂等验收 |
| D12 | 首期MySQL连接器 + 声明式HTTP连接器 | 可清晰验收单系统闭环 | 其他数据库后续适配 |
| D13 | 外部公开字符串ID，内部Long不全局改动 | 公开协议稳定、不破坏框架现状 | 新接口字段目录明确区分 |
| D14 | V1.0核心闭环，V1.1多模态，V1.2实时/扩展，V2跨系统 | 需求保留、分步交付 | 排期建议；调整需同步任务追踪 |

## 2. 尚待验证但已有处理方式

| 验证关口 | 需要的证据 | 失败处理 |
|---|---|---|
| G01 依赖组合 | Maven解析树、SBOM、安全扫描、启动 | 在Boot3.5兼容线换版本/适配；必要时提出独立框架升级决策 |
| G02 图表 | 固定包版本、构建大小、CSP、恶意输入、主题、卸载 | 同ChartRenderer下切G2，保留接口，不绕过CSP |
| G03 向量与解析 | 中文检索、前置ACL过滤、客户端/服务端兼容、备份恢复 | 给出替代索引方案及部署成本；不得假装Redis7自带向量搜索 |
| G04 身份 | Provider唯一、ADMIN/MEMBER隔离、跨站换票、撤销 | 扩展有文档的接缝；不改成匿名调用 |
| G05 文件 | 新薄契约、业务ACL回调、共享原文读取、删除补偿 | 修复文件契约，禁止跨模块直调FileService |
| G06 查询 | 金额/日期/粒度/权限/分页准确性 | 收紧数据集支持范围并提示，不开放原始SQL |
| G07 上游升级 | N/N-1、索引恢复、旧SDK与旧报表 | 暂停发布、保留旧版本，不删除失败测试 |

明确待评审的产品值：性能测试机器和阈值、数据保留天数、首批真实业务验收问题、单应用试点的数据规模、多模态提供商。缺少这些不阻止完成设计；进入对应发布任务前必须记录实际值。

## 3. 官方来源与核实结果

| 编号 | 来源 | 本次确认的事实 |
|---|---|---|
| S01 | [Spring AI Getting Started](https://docs.spring.io/spring-ai/reference/getting-started.html) | 当前主线2.x面向Boot4系列，不能直接以最新文档套用Boot3.5 |
| S02 | [Spring AI 1.1.7 POM](https://github.com/spring-projects/spring-ai/blob/v1.1.7/pom.xml) | Java17、Boot3.5.14基线、Qdrant Java client1.13.0；并非本产品已验证组合 |
| S03 | [1.1.7官方发布](https://spring.io/blog/2026/05/23/spring-ai-1-0-8-1-1-7-2-0-0-M7-available-now/) | 该版本正式发布，并包含安全修复 |
| S04 | [CVE-2026-22729](https://spring.io/security/cve-2026-22729/) | 1.1.0–1.1.2过滤转换相关漏洞，修复为1.1.3；不能选旧示例版本 |
| S05 | [CVE-2026-41863](https://spring.io/security/cve-2026-41863/) | 1.1.0–1.1.6相关路径写入漏洞，修复为1.1.7；仍需当前完整扫描 |
| S06 | [Spring AI Alibaba版本表](https://java2ai.com/docs/versions/) | 文档列1.1.2.0/AI1.1.2/Boot3.5组合；兼容表不等于安全准入 |
| S07 | [GPT-Vis package](https://github.com/antvis/GPT-Vis/blob/ai/package.json) | ai分支包声明1.0.1、MIT、依赖G2/G6等；npm实际产物与tag待冻结时核实 |
| S08 | [GPT-Vis源码](https://github.com/antvis/GPT-Vis/blob/ai/src/gpt-vis/index.ts) | 解析配置并选择图表实例，支持复用/销毁；适配器可包装 |
| S09 | [G2许可证](https://github.com/antvis/G2/blob/v5/LICENSE) | MIT；交付仍需收集精确版本的传递许可证 |
| S10 | [AVA package](https://github.com/antvis/AVA/blob/ai/package.json) | ai分支4.0.0-alpha.1；本包将其定位为设计参考 |
| S11 | [AVA网页生成](https://github.com/antvis/AVA/blob/ai/src/visualization/generator.ts) | GPT-Vis描述套HTML模板，不能据此声称已有完整企业报表平台 |
| S12 | [Spring AI Qdrant参考](https://docs.spring.io/spring-ai/reference/api/vectordbs/qdrant.html) | 存在VectorStore与元数据过滤适配；实际1.1.x API以选定tag源码为准 |
| S13 | [Qdrant许可证](https://github.com/qdrant/qdrant/blob/master/LICENSE) | Apache2.0；准入仍按最终版本和SBOM |
| S14 | [Qdrant升级](https://qdrant.tech/documentation/upgrades/) | 连续小版本升级、客户端兼容、单节点维护窗口 |
| S15 | [Qdrant快照](https://qdrant.tech/documentation/operations/snapshots/) | 快照恢复版本限制；集合快照不包含别名 |
| S16 | [Qdrant兼容FAQ](https://qdrant.tech/documentation/faq/) | 不支持直接降级已升级存储 |
| S17 | [Ollama兼容接口](https://docs.ollama.com/api/openai-compatibility) | OpenAI兼容是子集，应逐能力验证 |
| S18 | [Apache Tika](https://tika.apache.org/) | 文件内容提取项目；解析器和安全版本需单独锁定 |
| S19 | [postMessage](https://developer.mozilla.org/en-US/docs/Web/API/Window/postMessage) | 跨域通信需验证来源并使用精确targetOrigin |
| S20 | [frame-ancestors](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy/frame-ancestors) | 控制可嵌入祖先Origin，用响应头设置 |
| S21 | [Web Components](https://developer.mozilla.org/en-US/docs/Web/API/Web_components) | 组件封装能力可用于后续深度UI集成 |

本次Context7工具未配置，使用官方文档、官方GitHub源码和安全公告。Spring AI版本化文档URL和Maven metadata抓取有失败；已通过官方tag POM和发布公告交叉核实候选。不能把抓取失败解释为不存在，也不能把默认分支package.version当作已发布版本证明。

## 4. 明确未做的工作

没有修改源框架、没有复制实施仓库、没有安装/启动Spring AI/AntV/Qdrant、没有执行模型或数据库调用、没有运行产品门禁、没有验证许可证义务已全部履行。文档包自身进行了结构与一致性检查，结果见verification。
