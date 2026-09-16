# 05 数据模型、安全与运行契约

这是目标设计，不是已执行 SQL。字段长度、枚举和默认值在实施副本的字段目录登记后成为代码契约；本文件修改必须同步 Schema、任务与测试。下表列出业务必需字段，实施时通过迁移验证最终DDL、索引和删除规则。

## 1. 通用字段与类型

| 字段/类型 | 约定 |
|---|---|
| id | 内部 BIGINT 主键，沿用框架ID策略；对外不直接暴露 |
| public_id | VARCHAR(40)，ASCII大小写语义固定，唯一；类型前缀+服务端生成随机/ULID标识；不是访问凭证 |
| app_id / subject_id | 内部外键标识；跨模块关系用索引+拥有方契约校验；不存在tenant_id |
| name/title | VARCHAR(100/200)，分别按字段目录定义；用户可写说明有明确长度 |
| version | INT 非负乐观锁；更新带 expectedVersion；并发冲突409 |
| 状态 | VARCHAR(32)稳定枚举code，未知拒绝；不使用ordinal |
| JSON | 仅存经schema校验的配置、结果或上下文；限定序列化字节数；不以JSON藏未登记秘密 |
| 金额 | DECIMAL按明确币种/精度设计；API为十进制字符串；禁止double累加金额 |
| 时间 | DB DATETIME(3)统一UTC；业务日期保留timezone；API RFC3339 |
| 通用审计 | creator/create_time/updater/update_time，继承BaseDO；创建人不等于业务所有者 |
| 秘密 | 可恢复：CredentialCipher密文+上下文AAD；随机token/客户端secret摘要：BINARY(32) SHA-256 |

所有外部主体资源查询包含app/subject或明确资源授权判定。任意 public_id 仍须鉴权。对外id参数作为字符串验证前缀/长度；内部Long id仍使用@Positive。

## 2. 目标数据表目录

记号：H=hard-delete，A=append-retention。配置表H表示只有停用且无引用时允许物理删除，不等于随意删除；已发布/被引用对象必须先完成保留与解除引用。优先避免无恢复语义的软删除。

### 2.1 应用、模型、服务

| 表 | 核心字段 | 唯一/索引 | 生命周期及权限 |
|---|---|---|---|
| ai_application | public_id, app_code, name, status, allowed_origins, quota_policy, authz_revision, version | uk(public_id), uk(app_code) | H；控制面应用管理员；停用先撤销访问 |
| ai_app_credential | app_id, key_id, secret_hash, status, expires_at, revoked_at | uk(key_id), idx(app_id,status) | H；摘要禁出；轮换有到期与撤销 |
| ai_subject | app_id, public_id, external_user_id, subject_kind, status, authz_revision | uk(app_id,subject_kind,external_user_id), uk(public_id) | H；主体停用撤销token；APP使用保留external键 |
| ai_access_token | token_hash, app_id, subject_id, requested_scopes, expires_at, revoked_at, created_at | uk(token_hash), idx(app_id,subject_id), idx(expires_at) | H；到期批量清理；当前授权另查，不能快照旧授权 |
| ai_resource_grant | principal_kind, principal_id, app_id, resource_kind, resource_id, actions, version | uk(principal_kind,principal_id,resource_kind,resource_id), idx(resource_kind,resource_id) | H；逐对象当前授权；删授权立即影响访问 |
| ai_model_endpoint | public_id, name, provider, base_url, model_id, capability_codes, credential_cipher, config_revision, status | uk(public_id), idx(status) | H；管理权限；秘密不回显；有release引用禁止删 |
| ai_model_endpoint_revision | endpoint_id, revision, nonsecret_spec, capability_evidence_ref, created_by | uk(endpoint_id,revision) | A；非秘密配置不可变；历史凭据不复制，当前credential_revision独立轮换 |
| ai_service | public_id, service_code, name, active_release_id, draft_spec, status, version | uk(service_code), uk(public_id) | H；有运行/报表引用先停用保留 |
| ai_service_release | service_id, release_no, spec_json, model_endpoint_id, model_config_revision, schema_version, created_by | uk(service_id,release_no) | A；内容不可改；引用释放后按保留策略清理 |
| ai_service_resource | release_id, resource_kind, resource_id, resource_version | uk(release_id,resource_kind,resource_id) | H；release删除时同事务清理；当前资源授权仍独立查 |

### 2.2 知识与文件

| 表 | 核心字段 | 唯一/索引 | 生命周期及权限 |
|---|---|---|---|
| ai_knowledge_base | public_id, name, owner_subject_id, status, active_index_generation_id, version | uk(public_id) | H；显式应用与主体授权；无文档且无服务引用才能删 |
| ai_document | public_id, kb_id, source_key, title, active_version_id, visibility_state, version | uk(kb_id,source_key), uk(public_id) | H；删除先关闭可见性，清理完成再物理删 |
| ai_document_version | document_id, revision, file_binding_id, content_hash, parse_state, parser_version, chunker_version, error_code | uk(document_id,revision), idx(parse_state) | A；成功索引后切active；旧引用保留受ACL控制 |
| ai_document_chunk | document_version_id, chunk_no, text_file_ref或受控正文, location_json, token_count, content_hash | uk(document_version_id,chunk_no) | H；私有正文；版本删除后清理 |
| ai_index_generation | kb_id, generation_no, embedding_endpoint_id, model_revision, dimensions, parser_version, chunker_version, collection_name, status | uk(kb_id,generation_no), uk(collection_name) | A；新旧索引双运行验证；旧索引保留到回退窗结束 |
| ai_file_binding | public_id, infra_file_id, app_id, owner_subject_id, business_type, business_id, purpose, state | uk(public_id), idx(infra_file_id), idx(business_type,business_id) | H；业务ACL由拥有方SPI判定；共享引用计数由真实引用查询/锁保证 |

向量payload只含可过滤的内部标识、版本与必要检索信息；原文件和知识正文默认内部敏感资源。向量存储是否保存正文由适配器契约固定，重复正文副本同样执行删除与备份策略。

### 2.3 数据与工具

| 表 | 核心字段 | 唯一/索引 | 生命周期及权限 |
|---|---|---|---|
| ai_connector | public_id, owner_app_id, type, name, config_json, credential_cipher, config_revision, status | uk(public_id), idx(owner_app_id,type) | H；受控网络目标；被数据集/工具引用时不可删 |
| ai_dataset | public_id, owner_app_id, connector_id, name, active_version_id, status, version | uk(public_id), idx(owner_app_id) | H；停用阻止新查询；报表引用须保留 |
| ai_dataset_version | dataset_id, revision, schema_hash, semantic_spec, permission_policy_ref, readiness_state | uk(dataset_id,revision) | A；表/列/指标/粒度/枚举版本化 |
| ai_tool | public_id, owner_app_id, name, active_version_id, status, version | uk(public_id) | H；默认禁止执行；有action引用不能删 |
| ai_tool_version | tool_id, revision, connector_id, operation_spec, input_schema, output_schema, effect_type, execution_policy, idempotency_mode | uk(tool_id,revision) | A；具体参数及政策可追溯；执行还检查当前禁用/撤销 |

schema_hash基于允许的结构快照；变化后旧dataset version不能直接继续运行。权限策略引用必须可执行，禁止只有说明文字。

### 2.4 运行、会话、报表

| 表 | 核心字段 | 唯一/索引 | 生命周期及权限 |
|---|---|---|---|
| ai_conversation | public_id, app_id, subject_id, service_release_id, title, state, version | uk(public_id), idx(app_id,subject_id,update_time) | H；用户删除关闭访问并排队清理私有消息/附件 |
| ai_message | public_id, conversation_id, run_id, role, content_blocks, resource_refs, version_no | uk(public_id), idx(conversation_id,id) | H；业务正文受控保留；失权片段读取时隐藏/拒绝 |
| ai_run | public_id, app_id, subject_id, conversation_id, service_release_id, status, input_ref, plan_ref, result_ref, error_code, started_at, finished_at, version | uk(public_id), idx(app_id,subject_id,create_time), idx(status) | A；正文引用按较短保留期清理，审计元数据可保留 |
| ai_run_event | run_id, seq, event_type, payload, occurred_at | uk(run_id,seq), idx(occurred_at) | A；只保留可恢复必要事件；清理后用run快照 |
| ai_task | public_id, app_id, subject_id, run_id, task_type, business_key, state, attempt, lease_owner, lease_until, next_attempt_at, cancel_requested, result_ref, error_code, version | uk(task_type,business_key), uk(public_id), idx(state,next_attempt_at,lease_until) | H；完成后按保留期清理；当前主体重建授权 |
| ai_tool_action | public_id, run_id, tool_version_id, app_id, subject_id, argument_hash, argument_ref, policy, state, challenge_hash, expires_at, result_ref, version | uk(public_id), idx(run_id), idx(state,expires_at) | A；确认与实际参数绑定；业务副作用未知保留核对 |
| ai_report | public_id, app_id, owner_subject_id, title, mode, active_version_id, state, version | uk(public_id), idx(app_id,owner_subject_id) | H；私人报表；删除停止刷新并清理引用 |
| ai_report_version | report_id, revision, report_spec, query_refs, resource_refs, result_ref, data_as_of, completeness, theme_revision, schema_version | uk(report_id,revision) | A；不可变；存量版本重读仍鉴权 |
| ai_idempotency | app_id, subject_id, operation, key_hash, request_hash, state, resource_ref, expires_at | uk(app_id,subject_id,operation,key_hash), idx(expires_at) | H；数据库唯一性承担并发保障；结果未知不能直接删除重跑 |

### 2.5 配置、观测与评测

| 表 | 核心字段 | 唯一/索引 | 生命周期及权限 |
|---|---|---|---|
| ai_theme | public_id, app_id, revision, tokens_json, layout_json, publication_state | uk(app_id,revision), uk(public_id) | A；发布版本不可修改；运行时覆盖不写库 |
| ai_usage_ledger | invocation_id, run_id或task_id, app_id, subject_id, model_ref, model_revision, input_tokens, output_tokens, usage_source, duration_ms, status | uk(invocation_id), idx(run_id), idx(task_id), idx(app_id,create_time) | A；每次实际上游调用一条，重复写入去重；重试产生真实新调用应另记；只含计量元数据 |
| ai_eval_suite | public_id, name, purpose, status, version | uk(public_id) | H；评测配置权限 |
| ai_eval_case | suite_id, case_code, input_spec, fixture_ref, expected_spec, severity | uk(suite_id,case_code) | H；仅合成/脱敏样本，修改增加套件版本 |
| ai_eval_run | public_id, suite_id, suite_version, service_release_id, model_revision, state, summary | uk(public_id), idx(suite_id,create_time) | A；与版本固定绑定 |
| ai_eval_result | eval_run_id, case_code, actual_ref, verdict, reason_code, duration_ms | uk(eval_run_id,case_code) | A；完整保留失败类别，正文受限 |

## 3. 关系与迁移纪律

模型配置历史只保存非秘密配置。服务release绑定配置revision，run记录实际revision；修改上游模型标识、参数或能力生成新revision并重新验证发布。provider/baseUrl被发布服务引用后变为稳定边界，地址迁移创建新端点并切换服务。凭据轮换独立使用credential_revision，运行时使用该端点当前有效凭据，客户端缓存同时包含配置和凭据版本；不能借旧release读取过期密钥，或把新地址的密钥发送到旧地址。当前停用、网络限制和资源授权始终覆盖历史配置。

发布先冻结候选release，再用该候选完成评测；通过后才切换active_release_id。候选记录存在不代表对业务应用发布。发布状态/评测状态单独登记，spec_json一旦冻结不可修改，避免“必须先上线才能评测”的循环。

- 同模块强关系（service→release、dataset→version、report→version等）按生命周期选物理FK；active_version_id的循环关系可用明确逻辑引用+同事务校验，禁止迁移时靠暂时关foreign_key_checks。
- 跨module引用infra_file_id等不直接访问infra表；由FileCommonApi校验，逻辑引用有索引与孤儿检查。
- 每张AI表采用app/subject/resource ACL替代部门模型时，必须在data-permission-exemptions登记该表自己的生产证据；禁止整个ai_*无条件豁免。
- 新增migration、最新SQL快照、字段目录、生命周期、数据权限、权限码、覆盖率与安全信号必须同任务同步。
- 删除顺序按引用设计；父配置停用即阻止新操作，物理删除需要无引用或已完成保留。用户撤销权限不等待清理任务。
- 新版本发布遵循expand/migrate/contract，兼容窗内不删除N-1仍读取的列。

## 4. 状态机

### 4.1 配置

应用/模型/连接器：DISABLED → ENABLED → DISABLED；删除是受约束命令。服务草稿发布生成新的immutable release，service仅切active_release_id。禁止通用updateStatus任意跳状态。

### 4.2 Run

QUEUED → RUNNING → SUCCEEDED / FAILED / CANCELLED；RUNNING可进入WAITING_INPUT或WAITING_CONFIRMATION，再恢复RUNNING；取消先置cancel_requested。终态不可被晚到回调覆盖。等待状态有到期处理，过期明确FAILED或CANCELLED并携带原因。

### 4.3 Task

PENDING → RUNNING → SUCCEEDED / FAILED / CANCELLED；可重试失败转RETRY_WAIT→PENDING；外部副作用不确定转UNKNOWN，需要核对命令。lease到期不等同于业务失败。

### 4.4 文档

UPLOADED → QUEUED → PARSING → CHUNKING → EMBEDDING → READY；任一步→FAILED。删除先HIDDEN→DELETING→DELETED（清理完成后可物理删除）；新版本READY前旧activeVersion保持可用。

### 4.5 工具确认

PROPOSED→WAITING_CONFIRMATION→CONFIRMED→EXECUTING→SUCCEEDED / FAILED / UNKNOWN；也可REJECTED / EXPIRED / CANCELLED。CONFIRMED→EXECUTING使用数据库CAS；重复确认返回既有状态。执行前重新检查工具政策和参数hash。

### 4.6 报表刷新

原activeVersion可读；刷新创建task与候选version。成功原子切换activeVersion；失败保留旧版本，前端显示“刷新失败，仍为某时点数据”。不能先清空旧报表。

## 5. 建议配置默认值

这些值统一进入拥有方Properties并可验证，供首期测试基线使用，实施评审可调整。禁止复制成各处硬编码。

| 配置 | 建议值/规则 | 拥有方 |
|---|---|---|
| access-token-ttl | 10分钟 | ai身份 |
| token-entropy | 至少32随机字节 | ai身份 |
| app-secret-rotation-grace | 默认0；显式配置才允许短重叠且有上限 | ai身份 |
| input-message-max-chars | 16000 | ai运行 |
| context-max-bytes | 16KiB | ai运行 |
| JSON请求体 | 继续框架1MiB；上传走multipart | web |
| attachment-max-bytes | 20MiB/文件；按mime进一步收紧 | ai文件 |
| model-connect-timeout | 5s | ai starter |
| model-request-timeout | 120s；流式空闲超时30s，总时长10min | ai starter |
| data-query-timeout | 15s；数据集可降低 | ai数据 |
| result-max-rows | 1000；用于展示，统计应在数据源聚合 | ai数据 |
| api-pagination-budget | 最大100页/10000行；到上限标PARTIAL | ai连接器 |
| run-max-steps | 8 | ai运行 |
| tool-max-calls | 10/run | ai运行 |
| transient-read-retries | 最多2次，指数退避+抖动 | ai运行 |
| task-lease | 60s，20s心跳 | ai任务 |
| task-max-duration | 30min；按任务类型细化 | ai任务 |
| idempotency-retention | 24h，UNKNOWN不自动释放业务幂等 | ai运行 |
| replay-event-retention | 24h | ai会话 |
| conversation-retention | 默认30天，可按企业策略缩短；到期删除任务 | ai会话 |
| run-metadata-retention | 默认90天；正文按会话较短策略 | ai观测 |
| evaluation-retention | 默认90天，合成测试夹具随代码版本保存 | ai评测 |
| report-retention | 用户保存期间保留，删除后按企业策略清理；依赖授权即时生效 | ai报表 |
| index-rollback-window | 建议7天且覆盖版本验收；企业可调整 | ai知识 |

RTO/RPO沿用框架目标，必须实测。配置缺失或非法时fail-closed；关闭知识功能可不装向量库，启用知识服务时缺向量能力则拒绝发布，不能退回假检索。

## 6. 核心类型契约

### 6.1 QueryPlan

固定字段：schemaVersion、datasetId、datasetVersion、metrics、dimensions、filters、timeRange、orderBy、limit。filters仅支持已登记字段和EQ/NE/IN/GT/GE/LT/LE/BETWEEN/IS_NULL；字段类型决定允许操作符。

权限谓词、数据库schema/table/列名、凭证、任意SQL、脚本或目标URL不允许出现在模型计划里。数据集version已过期时要求重新规划，不静默换字段。

### 6.2 ReportSpec

schemaVersion、title、themeRef、layout、blocks、datasetRefs、queryRefs、sources。block类型限定metric/text/table/chart；chart类型首期column/line/pie。布局采用有界栅格和排序，不接受任意CSS。

每个数据块引用run result或数据集查询，不把用户输入的数字伪装成已核验指标。图表轴字段必须出现在引用数据schema中；主题只引用ThemeTokens。

### 6.3 兼容与样例

机器可读样例位于[contracts](contracts/README.md)。schema示例证明协议结构，不证明真实SQL、模型正确性或权限实现；后续任务必须用真实服务测试。

## 7. 安全边界清单

| 边界 | 强制控制 | 反向验收 |
|---|---|---|
| 业务系统→换票 | 后端凭据、scope裁剪、可信主体声明、限流 | 浏览器伪造他人ID不能换票 |
| 应用→资源 | 当前app/subject/服务范围求交 | 直接改reportId/fileId/KB不能跨域读取 |
| 文档→模型 | 只提供已授权片段，内容指令无执行权 | 文档提示注入不能扩大检索/工具 |
| 模型→数据库 | QueryPlan校验+确定性编译+只读账号 | SQL片段、未知列、危险函数被拒 |
| 配置→外部网络 | Origin/IP/端口允许策略、DNS/重定向复核 | 连接测试不能访问未授权内部服务 |
| 模型→工具 | 服务端政策、参数schema、确认hash、幂等 | 改确认参数、重放旧确认不能执行 |
| 模型→浏览器 | 结构化渲染、URL白名单、无原始HTML执行 | script、事件属性、恶意链接无执行 |
| SDK→iframe | origin/source/instance/version/schema校验 | 其他frame发AUTH/主题/导航被拒 |
| 文件→解析器 | 魔数、大小、路径、展开量、资源上限 | ZIP炸弹、路径穿越、超时不拖垮服务 |
| 日志/观测 | 只存必要元数据和脱敏错误 | 密钥、正文、SQL参数不进入日志/标签 |

## 8. 权限撤销与缓存

授权变化更新资源/主体版本并撤销相应票据；每个新的执行步骤读取当前授权。流式运行在下一检查点停止继续发送已失权内容，目标为撤销完成后不再发送新受限块；已送达用户的内容无法远程收回，产品说明和测试明确此边界。

后台授权源不加入跨请求Spring Cache。资源缓存只存非秘密元数据并按版本失效。模型会话上下文重建时过滤失权历史资源；不能因会话创建时有权就永久信任。

报表快照、消息块和run结果的resource_refs还必须保存生成时的权限来源、scope版本/指纹及依赖资源版本。读取时重新解析当前业务范围；首期只要无法证明仍覆盖原数据范围，就拒绝受影响结果块/快照并提示按当前权限重新生成。不能仅检查“还可访问该数据集”就放行含已失权客户的旧聚合金额。业务侧权限变化必须能通过可信范围解析器或撤销同步被中台观测；该接缝缺失时数据集不得发布。

## 9. 数据保留与恢复

MySQL为配置/授权/状态权威；文件为原文与产物权威；索引可从版本化原文重建，但恢复耗时计入RTO。备份中同样存在敏感数据，访问和删除策略单独配置。主密钥或旧密钥缺失时加密凭据无法恢复，恢复演练必须包含密钥版本可用性，但不得把密钥写入普通备份说明或源码。

删除知识内容时先禁用读取，再执行索引/正文/附件清理；保留审计只存必要标识与动作。跨表、跨存储清理使用可重试任务与补偿，不能在单个数据库事务中声称已完成所有外部删除。
