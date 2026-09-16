# 12 关键实现蓝图与防误实现约束

本文件把风险最高的任务继续细化。类名和方法是拟新增契约，实施时在F07冻结；不是声称框架已经有这些类。若与目标副本真实规范冲突，应修订契约，不能另写一套平行实现。

## 1. 身份与范围：A02–A08

### 1.1 核心输入输出

- `ClientCredentialVerifier.verify(appKey, secret)`：返回已启用应用及凭据版本；失败统一脱敏，恒定时间比较高熵摘要。
- `ExternalSubjectResolver.resolve(appId, subjectKind, externalUserId)`：返回应用内主体，不能从外部ID直接获得管理员ID。
- `SubjectScopeResolver.resolve(subject, resource, action)`：返回可执行的当前范围；没有范围返回DENY，不能返回“全部”补救。
- `AiAuthorizationService.authorize(context, action, resource)`：同时检查应用、主体、scope、服务绑定、资源状态与ACL。
- `AiAccessTokenService.issue/revoke/check`：原始随机token只在签发时返回，持久层仅存摘要。

`AiExecutionContext`只包含服务端建立的appId、subjectId、subjectKind、scope上限、资源授权版本和traceId。客户端context、模型输出、队列payload都不能覆盖这些字段。

### 1.2 行数据权限首期默认

先实现登记的范围解析器，例如“可信业务后端授权接口返回当前用户可访问的客户ID”。该接口和返回Schema必须在连接器配置中登记，不能由模型选择URL。

范围解析结果使用逻辑字段和有类型的值，例如`customer_id IN [C001]`，由SQL/API执行器强制附加。范围获取失败、无映射、空授权集合或超过预算时拒绝查询，空集合不等于无限制。大量ID不拼接无限IN列表；另行评审受权视图/源端授权接口。

管理员调试先选择测试应用与外部主体，再走同一范围解析。不能把平台部门ID直接传给外部业务数据库。

### 1.3 换票时序

1. 验证TLS、Basic头格式与请求大小，凭据失败计入受限登录防护。
2. 校验应用与凭据未停用/过期，解析APP或USER主体。
3. 请求scope/service范围与应用许可求交，拒绝没有必要执行资格的票据。
4. 生成至少32随机字节token，保存摘要、主体、scope上限、到期时间。
5. 返回票据及实际scope；响应不缓存，访问日志不采集正文/Authorization。
6. 后续每个请求重新检查当前状态；票据scope只能作为上限，不保留被撤销资源权限。

完成A08的最低证据：ADMIN/MEMBER混用拒绝，appA/appB隔离，Alice/Bob隔离，失权资源在历史/文件/报表中不可读，Provider缺失/重复拒绝，票据数据库无原值。

## 2. 运行、任务、幂等：O01–O06

### 2.1 接收运行

`AiRunCommandService.accept(command, trustedContext, idempotencyKey)`的顺序：

1. 校验服务、当前主体、请求Schema、附件和context；会话存在时检查归属和固定release。
2. 如果请求serviceId与会话绑定服务不一致，拒绝并要求新建会话，不能静默迁移历史。
3. 对逻辑请求规范化后计算摘要。摘要包括service/conversation/message/附件/context，不包括traceId或当次token原值。
4. 在短事务中插入幂等记录、run和首个持久任务；唯一索引处理并发。事务成功后才返回202。
5. 同键同摘要返回原run；同键不同摘要409；运行未完成不能再创建替代run。
6. 真实新请求每次生成新的runId；重新生成按钮使用新幂等键并保留旧结果。

队列不能只存在内存。即使受理后立即杀死Java进程，数据库中已接受任务仍可发现。

### 2.2 领取与租约

`AiTaskClaimService.claim(workerId, limit)`使用短事务行锁或CAS，条件包括PENDING/RETRY_WAIT、到期时间和租约。写入leaseOwner、leaseUntil、attempt、version后提交，再执行网络调用。

worker续租和最终落库都带任务版本/领取代次作为栅栏。旧worker失去租约后即使得到上游响应，也不能覆盖新worker结果。只依赖`leaseUntil`没有完成写入的CAS检查是不合格实现。

内部结果通过task businessKey/run stepKey唯一约束去重。无法证明幂等的外部写操作不能自动重领执行；写调用崩溃时进入UNKNOWN并核对。只读模型重试也可能产生多次实际用量，每个上游调用分别计量。

### 2.3 取消、恢复与SSE

取消通过持久标志和状态版本协调。进入终态使用CAS；晚到成功响应不能覆盖已取消终态。若上游不能真正中断，用户界面停止展示后续输出，记录上游可能继续消耗。

每个run的seq由数据库并发安全地分配；持久事件与对应状态更新在同一短事务提交。不要用多个worker的本地计数器。heartbeat是注释，不参与seq。

连接断开不取消run；重连只重放。token到期应结束该订阅，客户端在下一认证请求收到401后换票并按seq恢复；票据到期本身不等于整个run失败。应用/主体/源资源被撤销时，则由当前授权检查阻止后续执行及受限输出。

## 3. 文档入库与权限检索：K03–K08

### 3.1 处理步骤

`DocumentIngestService`只建立document/version/fileBinding/task；不在Controller线程同步解析整份文件。`DocumentParserPort`返回页/段位置及文字，`Chunker`产生稳定chunk序号与hash，`EmbeddingPort`返回确定模型/维度的向量。

索引ID基于documentVersion/chunkId，重试upsert不重复。生成新版本READY以前，旧activeVersion继续提供检索；新版本一旦失败，不能部分片段进入在线搜索。索引写完后复核文档未删除、权限仍有效，再原子切active。

### 3.2 检索步骤

1. 根据当前主体、应用与service release求得允许KB和文档安全范围。
2. 构造类型化服务端过滤条件，不调用模型生成过滤语句。
3. 查询向量库并限制候选数与耗时。
4. 批量从MySQL复核chunk所属文档、版本、可见性及当前ACL。
5. 仅对通过的chunk读取正文，组装模型上下文；被拒内容不得进入日志或模型输入。
6. 引用候选列表由真实检索记录生成；模型只能选择已有引用编号。
7. 回答引用映射回原文位置，点击下载仍走FileCommonApi与业务ACL。

检索结果不足可以在相同授权过滤下有限扩大候选，不能移除ACL重搜。删除命令先关闭权威可见性，再异步删索引/正文，失败补偿必须可观察。

## 4. 自然语言到计划：D04–D05

### 4.1 模型看见什么

只提供当前服务已绑定且用户有权的数据集摘要、允许指标/维度/枚举、业务解释、可信时区/当前时间、QueryPlan Schema。默认不读取随机客户行样本，不暴露数据库密码、完整库名或未授权表。

模型先返回`PLAN`或`CLARIFICATION`的运行层判别结果。PLAN中的查询对象必须通过query-plan.schema.json；CLARIFICATION只包含问题和有限候选。执行器不能把一句“可能是销售额”当作可执行计划。

### 4.2 校验顺序

1. 结构校验：未知字段、SQL、非法操作符、超大集合立即拒绝。
2. 授权与版本：dataset存在、被服务绑定、当前主体有权、指定version仍可执行。
3. 指标与字段：从服务端目录解析code；校验类型、单位、聚合粒度和允许关系。
4. 时间：从可信固定时点解析，形成带时区的[start,end)；倒序、过宽或口径不明时澄清/拒绝。
5. 参数：过滤值类型、枚举、排序字段、limit合法；输出字段只能来自本次查询结果。
6. 生成`ValidatedQueryPlan`及稳定计划hash。只有此类型可进入执行器；不提供接收任意String SQL的公开方法。

失败只允许在原权限和元数据范围内有限修复。无法确定字段/业务口径应追问，不自动扩大查询。

## 5. 确定性SQL编译：D06

### 5.1 拟新增类型

- `ValidatedQueryPlan`：只能由校验器建立的已验证计划。
- `ResolvedDatasetVersion`：审核后的表/视图、逻辑到物理字段映射、指标AST和权限策略。
- `SqlParameter`：值、JDBC类型、敏感级别；toString不输出值。
- `CompiledQuery`：只读SQL结构、绑定参数、结果列Schema、超时/行数预算；不含数据库密码。
- `QueryPlanSqlCompiler.compile(plan, dataset, scope)`：纯确定性编译，不调用模型或网络。

### 5.2 明确支持范围

先实现一个审核数据集/视图上的维度分组与SUM/COUNT/MIN/MAX/AVG及已审核的十进制加减表达式。指标表达式不能直接使用管理界面的原始SQL字符串。NULL是否转0由指标定义决定，不能全局COALESCE。

过滤采用AND组合的首期子集，与Schema一致。值绑定`?`参数；排序、表名、列名只来自审核目录。不能用替换引号或关键字过滤替代参数化。

row scope在用户过滤之外强制AND，模型条件不能覆盖。API权限失败、空授权集合和元数据漂移时不生成可执行SQL。

### 5.3 黄金示例

在已审核逻辑视图`v_sales_authorized_source`中，字段映射明确时，编译结构可为：

```sql
SELECT customer_name, SUM(net_amount) AS net_amount
FROM v_sales_authorized_source
WHERE region = ?
  AND payment_status = ?
  AND order_time >= ? AND order_time < ?
  AND customer_id IN (?)
GROUP BY customer_name
ORDER BY net_amount DESC
LIMIT ?
```

这只是合成示例。真实物理名称来自登记元数据，`IN`按已校验数量展开参数；不得直接把上述视图名硬编码到产品。

Alice授权C001时结果为290.00；有全华东范围时返回450.00和290.00。客户排序键并列时追加已登记的稳定排序字段，以保证分页稳定。

订单与回款关联时，必须分别按客户聚合再关联；预审核视图或编译器固定派生表结构都可，但需独立测试。直接连订单明细和回款明细后SUM会放大金额；用DISTINCT不能普遍修复。

### 5.4 最低测试文件职责

`QueryPlanSqlCompilerTest`验证结构、绑定、非法字段/函数和空权限；`MysqlQueryExecutionIT`在Testcontainers MySQL验证日期时区、DECIMAL、null、聚合与只读账号；`QueryPermissionIT`验证每个受限关联域都受授权约束。实际命名服从模块集成测试发现规则。

## 6. 业务API查询：D02、D07

只允许从已发布operation中选择。路径变量编码后不能改变host、scheme或逃离登记path模板；请求头中身份/凭据由服务器生成。URL字段不能来自模型自由输出。

分页器有固定页数、行数、总时长、响应体大小和重复游标检测。源支持聚合时优先调用其统计接口；源只给明细时，必须拿全授权数据再计算完整统计。不能先取前1000行再声称全企业总额。

单页失败后，不把已读部分标COMPLETE。PARTIAL必须携带已读页/条数与原因；无法判断完整性的源返回UNKNOWN，模型解释、表格和图表使用同一完整性标记。

DTO中金额归一为十进制字符串，单位/币种单独保存；数字解析失败不能悄悄变成0。空列表是合法零行结果，和超时/无权不同。

## 7. 报表生成与刷新：R01–R06

`ReportSpecValidator`依次校验Schema、唯一ID、布局边界、引用存在、图表字段类型、当前资源授权。模型选图和排版，不能向数据结果中新增“合理的”金额。

数据结果和ReportSpec分开保存，通过resultRef绑定。图表渲染可以转换数值供可视化尺度使用，表格、标签和汇总保留精确十进制；不能用JavaScript浮点重新计算财务合计。

报表中的文字分析标为AI生成的辅助解释，可信数值来自结构化数据块。模型未能给出支持引用的事实，不因出现在网页上就变成已核实结论。

对话修改先判断仅布局变化还是数据变化：前者生成新Spec版本，后者重新规划并执行，再生成新版本。更换柱图为折线图不能无故重新调用业务数据库；“增加去年对比”则必须重新查询。

保存REFRESHABLE时保留查询计划、语义版本和源引用；刷新任务重新检查当前权限和元数据兼容。生成候选version成功后CAS切active，失败保留旧版及时间提示。语义漂移不能静默改成新口径刷新。

快照还需记录生成时的scope指纹。读取时通过可信解析器重取范围；首期范围指纹变化且不能证明是原范围的超集时，整个受影响数据块拒绝显示。旧文字总结无法精确定位来源时按整个run范围处理。不得只校验报表所有者或数据集级授权，而让已失去客户权限的用户继续看旧金额。

## 8. 嵌入Chat：C05–C08

### 8.1 首期页面交付

建议由专用开放控制器提供`GET /app-api/ai/v1/embed/{appCode}`，输出构建后的固定Chat入口HTML；公共appCode仅用于查找已启用应用与已发布公开主题，不能插入任意HTML。静态脚本以自托管固定版本路径加载。

该页面属于公开UI启动壳，可在会话层PermitAll，但不提供任何匿名模型调用、会话正文、文件或数据读取。未知/停用应用返回404。必要公开bootstrap配置另设只读接口，仅含品牌、主题、协议版本及握手允许域，不含内部模型/连接器/资源详情。

页面响应按app配置精确frame-ancestors，不全局删除后台X-Frame-Options。缓存必须包含应用及发布版本，配置撤销立即失效；不能把某应用的允许域缓存给其他应用。HTML/text响应在Web统一包装中的处理必须登记测试。

### 8.2 Bridge状态机

`CREATED → WAITING_READY → AUTHENTICATING → INITIALIZED → DESTROYED`，失败进入可恢复ERROR；未完成READY的消息不接受AUTH/业务指令。

每个消息同时检查协议版本、instanceId、event.source、event.origin和数据Schema。token只通过已校验通道发送；来源必须来自应用发布允许域，不能相信消息自身声明的origin。过期续票回调single-flight。

宿主退出或切用户：先增加session generation并关闭旧流，再清空token/会话，重新握手或reset。旧generation晚到事件全部丢弃，不能只清空可见消息列表。

destroy清理AbortController、timeout、resize observer、事件监听器、图表实例和DOM。独立页不接受通过URL传长期票据，需同样有可信后端换票入口。

## 9. 上游升级任务：Q08

升级PR/变更材料必须同时包含：上游旧新版本及官方差异、适配器diff、传递依赖diff、SBOM与许可证、固定回归结果、存量产物兼容、数据迁移/回退方案、已知限制。

准入判定不能只有“单测全绿”：还要检查实际打包运行、多端点身份隔离、流式协议、图表CSP、索引过滤与版本恢复。候选版本未知就保持候选状态。

基础框架同步还要读新的AGENTS与契约，核对自己的改动是否仍合法；框架迁移编号冲突不能修改已经执行的SQL文件。没有演练过的数据回退不写成“可直接回滚”。
