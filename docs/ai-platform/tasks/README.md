# 开发任务卡

共 **102** 张卡；全部PLANNED。这里只是规划，未实施任何源框架代码。
阶段数量：{"P0": 10, "V1.0": 75, "V1.1": 6, "V1.2": 5, "V2": 6}。

事实源为[index.json](index.json)；卡片由[render-task-cards.py](../scripts/render-task-cards.py)生成。计划改变先更新index，再生成卡片，避免两份事实源分叉。

## 领取方法

1. 先读[开发计划](../07-development-plan.md)和[模型交接](../09-model-handoff.md)。
2. 第一个可准备任务是F01；工作副本未授权前仅做只读检查。
3. 所有dependsOn为DONE且证据可读才是READY。一次只给执行模型一张卡及它引用的契约。
4. 实际进度保存在用户指定的任务系统/交接记录；不把运行日志和进度文件写进.harness。
5. 任务估算总计约167–269人工作日，含后续能力，未含需求等待与环境审批。不能将此数除以模型并发数当作日历工期。

## 路径别名

所有路径相对授权副本，源框架仍只读。ROOT表示仓库级基线或卡片明确的子路径，不能理解为无限修改授权。DB表示新迁移及最新快照，LEDGER表示本次变更相关台账。

| 别名 | 展开路径 |
|---|---|
| ROOT | `.` |
| B | `后端代码/basic-framework-boot` |
| F | `前端代码/basic-framework-admin` |
| CORE | `后端代码/basic-framework-boot/basic-framework-core` |
| AIM | `后端代码/basic-framework-boot/basic-framework-module-ai` |
| AI | `后端代码/basic-framework-boot/basic-framework-module-ai/src/main/java/com/basicframework/module/ai` |
| AIT | `后端代码/basic-framework-boot/basic-framework-module-ai/src/test/java/com/basicframework/module/ai` |
| AA | `后端代码/basic-framework-boot/basic-framework-module-ai-api` |
| ASM | `后端代码/basic-framework-boot/basic-framework-core/basic-framework-spring-boot-starter-ai` |
| AS | `后端代码/basic-framework-boot/basic-framework-core/basic-framework-spring-boot-starter-ai/src/main/java/com/basicframework/framework/ai` |
| BT | `后端代码/basic-framework-boot/basic-framework-server/src/test/java/com/basicframework/server` |
| ADM | `前端代码/basic-framework-admin/apps/web-ele/src` |
| DOC | `docs` |
| VERIFY | `.harness` |
| SCRIPTS | `scripts` |
| DB | `后端代码/basic-framework-boot/basic-framework-server/src/main/resources/db/migration`<br>`数据库文件/basic_framework.sql` |
| LEDGER | `docs/contracts/field-catalog.yaml`<br>`docs/contracts/data-lifecycle.json`<br>`docs/contracts/data-permission-exemptions.json`<br>`docs/contracts/permission-catalog.json`<br>`docs/data-lifecycle.md`<br>`docs/security` |

## F 基础与验证

| 任务 | 阶段 | 前置 | 风险 | 估算 |
|---|---|---|---|---|
| [F01 建立授权工作副本与基线证据](F01.md) | P0 | — | HIGH | M |
| [F02 验证并冻结第一组上游依赖](F02.md) | P0 | F01 | HIGH | L |
| [F03 新增后端AI模块及边界门禁](F03.md) | P0 | F02 | MEDIUM | M |
| [F04 建立前端包与图表集成验证](F04.md) | P0 | F02 | MEDIUM | L |
| [F05 冻结开放身份与安全扩展ADR](F05.md) | P0 | F01, F03 | HIGH | M |
| [F06 发布文件薄契约与业务授权SPI](F06.md) | P0 | F03, F05 | HIGH | L |
| [F07 冻结公共类型与协议样例](F07.md) | P0 | F03, F04 | MEDIUM | M |
| [F08 建立AI字段错误码与迁移规范](F08.md) | P0 | F03, F07 | HIGH | M |
| [F09 建立受控外部HTTP传输边界](F09.md) | P0 | F03, F05, F08 | HIGH | L |
| [F10 建立合成业务与协议测试夹具](F10.md) | P0 | F03, F07, F08 | LOW | M |

## M 模型中心

| 任务 | 阶段 | 前置 | 风险 | 估算 |
|---|---|---|---|---|
| [M01 实现模型端点持久化与管理命令](M01.md) | V1.0 | F03, F08 | HIGH | M |
| [M02 实现动态模型客户端工厂](M02.md) | V1.0 | M01, F09 | HIGH | L |
| [M03 实现文本流与结构化输出适配](M03.md) | V1.0 | M02, F07, F10 | MEDIUM | M |
| [M04 实现嵌入模型与能力探测](M04.md) | V1.0 | M02, F10 | MEDIUM | M |
| [M05 实现模型外发策略与调用计量](M05.md) | V1.0 | M03, M04 | HIGH | M |
| [M06 交付模型管理页面](M06.md) | V1.0 | M01, M04, F04 | MEDIUM | M |

## A 应用身份与授权

| 任务 | 阶段 | 前置 | 风险 | 估算 |
|---|---|---|---|---|
| [A01 实现应用及客户端凭据管理](A01.md) | V1.0 | F05, F08 | HIGH | L |
| [A02 实现外部主体与范围映射](A02.md) | V1.0 | A01, F08 | HIGH | M |
| [A03 实现应用和主体资源授权](A03.md) | V1.0 | A02, F07 | HIGH | L |
| [A04 实现应用后端换票与票据存储](A04.md) | V1.0 | A02, A03, F09 | HIGH | L |
| [A05 接入MEMBER认证Provider与scope表达式](A05.md) | V1.0 | A04, F05 | HIGH | L |
| [A06 实现撤销与执行上下文重建](A06.md) | V1.0 | A05 | HIGH | L |
| [A07 实现AI文件授权Provider](A07.md) | V1.0 | A03, F06 | HIGH | L |
| [A08 实现授权和身份反向集成套件](A08.md) | V1.0 | A05, A06, A07, F10 | HIGH | M |
| [A09 交付应用接入与授权页面](A09.md) | V1.0 | A01, A03, A08, F04 | HIGH | M |

## S AI服务

| 任务 | 阶段 | 前置 | 风险 | 估算 |
|---|---|---|---|---|
| [S01 实现服务草稿与资源绑定](S01.md) | V1.0 | M01, A03, F07, F08 | HIGH | M |
| [S02 实现发布预检查和不可变版本](S02.md) | V1.0 | S01, M04 | HIGH | M |
| [S03 实现版本回退与运行快照解析](S03.md) | V1.0 | S02 | HIGH | M |
| [S04 实现服务调试和上下文预算](S04.md) | V1.0 | S03, M03, A08 | HIGH | M |
| [S05 交付服务配置发布调试页面](S05.md) | V1.0 | S04, F04 | MEDIUM | L |

## O 开放API与运行

| 任务 | 阶段 | 前置 | 风险 | 估算 |
|---|---|---|---|---|
| [O01 实现会话和消息存储](O01.md) | V1.0 | A08, S03, F08 | HIGH | M |
| [O02 实现持久run和幂等受理](O02.md) | V1.0 | O01, F07, F08 | HIGH | L |
| [O03 实现可恢复任务领取与租约](O03.md) | V1.0 | O02, A06 | HIGH | L |
| [O04 接入文本运行与有界步骤](O04.md) | V1.0 | O03, M03, S04 | HIGH | L |
| [O05 实现SSE事件、重放和取消](O05.md) | V1.0 | O04 | HIGH | L |
| [O06 实现任务查询、重试和清理](O06.md) | V1.0 | O03, O05 | HIGH | M |
| [O07 生成开放API规范与契约回归](O07.md) | V1.0 | O05, O06, F07 | MEDIUM | M |
| [O08 交付开放平台目录和在线调试](O08.md) | V1.0 | O07, S05 | HIGH | M |

## K 知识库

| 任务 | 阶段 | 前置 | 风险 | 估算 |
|---|---|---|---|---|
| [K01 验证向量索引适配和恢复组合](K01.md) | V1.0 | F02, M04, A03 | HIGH | L |
| [K02 实现知识库和文档版本数据模型](K02.md) | V1.0 | K01, A07, F08 | HIGH | M |
| [K03 实现文档上传和入库任务](K03.md) | V1.0 | K02, O03 | HIGH | M |
| [K04 实现受限文档解析器](K04.md) | V1.0 | K03, F02 | HIGH | L |
| [K05 实现切片嵌入和版本化索引](K05.md) | V1.0 | K04, M04, K01 | HIGH | L |
| [K06 实现授权检索与引用读取](K06.md) | V1.0 | K05, A03, A07 | HIGH | L |
| [K07 实现文档同步、撤销和清理](K07.md) | V1.0 | K06, O06 | HIGH | L |
| [K08 接入知识问答运行链路](K08.md) | V1.0 | K06, O04, S04 | HIGH | M |
| [K09 交付知识库管理与检索调试页面](K09.md) | V1.0 | K07, K08, F04 | HIGH | L |

## D 数据与工具

| 任务 | 阶段 | 前置 | 风险 | 估算 |
|---|---|---|---|---|
| [D01 实现连接器配置与秘密管理](D01.md) | V1.0 | F09, A03, F08 | HIGH | M |
| [D02 实现声明式HTTP和OpenAPI导入](D02.md) | V1.0 | D01, F07, F10 | HIGH | L |
| [D03 实现独立MySQL只读连接器](D03.md) | V1.0 | D01, F10 | HIGH | L |
| [D04 实现数据集语义版本管理](D04.md) | V1.0 | D02, D03, A03, F07 | HIGH | L |
| [D05 实现自然语言查询计划生成与澄清](D05.md) | V1.0 | D04, M03, S04 | HIGH | L |
| [D06 实现QueryPlan到参数化SQL编译](D06.md) | V1.0 | D05, D03, F10 | HIGH | L |
| [D07 实现API查询参数与结果归一化](D07.md) | V1.0 | D05, D02, F10 | HIGH | L |
| [D08 实现工具注册及执行政策](D08.md) | V1.0 | D01, A03, F07 | HIGH | M |
| [D09 实现工具确认和分析步骤调度](D09.md) | V1.0 | D08, D06, D07, O04 | HIGH | L |
| [D10 交付连接器语义和工具管理页面](D10.md) | V1.0 | D04, D08, D09, F04 | HIGH | L |
| [D11 完成单系统查询黄金集验收](D11.md) | V1.0 | D06, D07, D09, F10 | HIGH | M |

## R 智能报表

| 任务 | 阶段 | 前置 | 风险 | 估算 |
|---|---|---|---|---|
| [R01 实现ReportSpec服务端校验与数据绑定](R01.md) | V1.0 | F07, D11 | HIGH | M |
| [R02 实现AntV图表适配组件](R02.md) | V1.0 | F04, F07, R01 | MEDIUM | L |
| [R03 实现自然语言报表生成步骤](R03.md) | V1.0 | R01, R02, O04 | HIGH | M |
| [R04 实现报表保存与版本存储](R04.md) | V1.0 | R03, A07, F08 | HIGH | L |
| [R05 实现报表对话修改](R05.md) | V1.0 | R04, D05 | MEDIUM | M |
| [R06 实现刷新任务与结果原子切换](R06.md) | V1.0 | R04, O06, D11 | HIGH | L |
| [R07 交付报表预览与个人报表页面](R07.md) | V1.0 | R02, R05, R06 | HIGH | L |

## C Chat与SDK

| 任务 | 阶段 | 前置 | 风险 | 估算 |
|---|---|---|---|---|
| [C01 实现开放API前端客户端](C01.md) | V1.0 | O07, F04 | HIGH | L |
| [C02 实现会话状态与消息组件](C02.md) | V1.0 | C01, O01 | HIGH | L |
| [C03 实现结构化消息与引用附件渲染](C03.md) | V1.0 | C02, K08, R07 | HIGH | L |
| [C04 实现主题发布与共享设计token](C04.md) | V1.0 | F07, A01, F08 | HIGH | M |
| [C05 实现独立embed页面与安全头](C05.md) | V1.0 | C03, C04, A05 | HIGH | L |
| [C06 实现SDK握手、换票和实例隔离](C06.md) | V1.0 | C05, C01 | HIGH | L |
| [C07 实现SDK展示形态与生命周期](C07.md) | V1.0 | C06 | MEDIUM | M |
| [C08 实现业务上下文和宿主事件](C08.md) | V1.0 | C07, S04 | HIGH | M |
| [C09 交付Chat集成与主题管理页面](C09.md) | V1.0 | C04, C08, A09 | HIGH | L |
| [C10 交付第三方宿主示例与兼容验收](C10.md) | V1.0 | C09, O08, R07 | HIGH | L |

## Q 质量与交付

| 任务 | 阶段 | 前置 | 风险 | 估算 |
|---|---|---|---|---|
| [Q01 接入AI审计、隐私日志与安全信号](Q01.md) | V1.0 | O06, A08, K08, D09 | HIGH | M |
| [Q02 实现用量账本与可恢复配额控制](Q02.md) | V1.0 | M05, O06, A08, F08 | HIGH | L |
| [Q03 交付运行监控与用量管理页面](Q03.md) | V1.0 | Q01, Q02, O08 | HIGH | L |
| [Q04 实现评测套件、样例版本与执行器](Q04.md) | V1.0 | F10, O06, S03, F08 | MEDIUM | L |
| [Q05 交付质量评测页面与发布阻断规则](Q05.md) | V1.0 | Q04, D11, K08, R07, S05 | HIGH | L |
| [Q06 落实真实浏览器门禁与双平台接线](Q06.md) | V1.0 | C10, Q03, Q05, F04 | HIGH | L |
| [Q07 验证容量、慢消费者与任务故障恢复](Q07.md) | V1.0 | Q02, Q06, K07, D11 | HIGH | L |
| [Q08 建立升级台账与兼容回归包](Q08.md) | V1.0 | F02, C10, K07, Q06 | HIGH | L |
| [Q09 交付安装包、配置模板与恢复演练](Q09.md) | V1.0 | Q07, Q08, K07, Q01 | HIGH | L |
| [Q10 完成单业务系统端到端验收与候选发布评审](Q10.md) | V1.0 | M06, A09, S05, O08, K09, D10, R07, C10, Q03, Q05, Q09 | HIGH | L |

## X 多模态及后续扩展

| 任务 | 阶段 | 前置 | 风险 | 估算 |
|---|---|---|---|---|
| [X01 扩展多模态能力契约与端点验证矩阵](X01.md) | V1.1 | Q10 | MEDIUM | M |
| [X02 交付图片理解与OCR闭环](X02.md) | V1.1 | X01, A07, K04 | HIGH | L |
| [X03 交付图片生成与编辑闭环](X03.md) | V1.1 | X01, A07, O06 | HIGH | L |
| [X04 交付语音转文字与语音合成](X04.md) | V1.1 | X01, A07, O06 | HIGH | L |
| [X05 交付实时语音会话与打断恢复](X05.md) | V1.2 | X04, C07 | HIGH | L |
| [X06 开放受控业务写工具与结果核对](X06.md) | V1.1 | Q10, D09, Q02 | HIGH | L |
| [X07 接入受控MCP客户端适配器](X07.md) | V1.2 | Q10, F09, D08 | HIGH | L |
| [X08 交付最小可视化AI流程编辑与受控运行](X08.md) | V1.2 | Q10, X06 | HIGH | L |
| [X09 交付组件级Chat集成包与宿主适配规范](X09.md) | V1.2 | Q10, C10 | HIGH | L |
| [X10 交付受控异步结果Webhook](X10.md) | V1.1 | Q10, F09, O06 | HIGH | L |
| [X11 交付报表受控分享与权限撤销](X11.md) | V1.2 | Q10, A08, R07 | HIGH | L |

## Y 跨系统

| 任务 | 阶段 | 前置 | 风险 | 估算 |
|---|---|---|---|---|
| [Y01 支持多业务系统授权发现与范围选择](Y01.md) | V2 | Q10 | HIGH | M |
| [Y02 建立跨系统业务对象与主数据映射](Y02.md) | V2 | Y01 | HIGH | L |
| [Y03 定义跨系统指标口径与关联粒度校验](Y03.md) | V2 | Y02, D11 | HIGH | L |
| [Y04 实现有界跨源查询执行与统一结果](Y04.md) | V2 | Y03, D06, D07, O06 | HIGH | L |
| [Y05 验证跨系统权限、撤销与完整性](Y05.md) | V2 | Y04, A08 | HIGH | L |
| [Y06 完成跨系统报告与升级验收](Y06.md) | V2 | Y05, R07, Q08 | HIGH | L |

## 追踪与验证

[需求→任务→验收映射](traceability.md)；[文档检查说明](../verification/README.md)。
