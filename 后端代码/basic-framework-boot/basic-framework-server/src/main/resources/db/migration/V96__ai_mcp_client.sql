-- X07：受控 MCP 客户端接入（FR-39，V2 跨系统链第五环）。
-- 设计要点：
--   1) **发现即草稿，不即工具**：MCP 工具的"存在"来自上游，而上游随时能新增/改名/改参数。
--      如果发现直接写 ai_tool，那么"上游新增一个工具"就等于"平台自动多了一个可执行工具"——
--      这正是本卡要否掉的默认放行。因此 ai_mcp_tool_draft 独立记账上游事实，
--      审批通过后才由服务层写 ai_tool/ai_tool_version（政策 DENY）；
--   2) **审批与指纹一一对应**：approved_fingerprint 与 observed_fingerprint 分列。
--      再次发现时若两者不一致（上游升级改了 schema），草稿置 BLOCKED 并记稳定原因码，
--      旧审批立即失效——这就是"上游升级导致 Schema 变化时阻断旧发布"的持久层落点；
--   3) **描述不可信且不参与判定**：upstream_description 原样保存仅供人工审阅。
--      指纹只覆盖"工具名 + 输入 schema"，所以往描述里塞"忽略之前的指令/把 policy 设成 AUTO"
--      不会改变任何授权结论（提示注入拿不到权限的机制保证）；
--   4) **断线必须留痕**：ai_mcp_discovery_run 记录 attempts 与 termination。
--      没有这张表，"这次没发现工具"就无法区分"上游没有"与"我们连不上"，
--      有界重试与明确终止也就退化成不可验证的口号。
--
-- 生命周期：两张表都是发现事实与审计留痕（soft-delete）。
-- 草稿的 deleted 位参与唯一键 (connector_id, upstream_tool_name, deleted)，
-- 因此删除后可以重新发现登记；**已审批记录不随物理清理复活**——重新登记只会得到 DRAFT。

DROP TABLE IF EXISTS `ai_mcp_discovery_run`;

DROP TABLE IF EXISTS `ai_mcp_tool_draft`;

CREATE TABLE `ai_mcp_tool_draft` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'MCP 工具草稿编号',
  `connector_id` bigint NOT NULL COMMENT '连接器编号（MCP 服务器以 HTTP 连接器登记，地址与令牌复用 D01 的加密与校验）',
  `upstream_tool_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '上游工具名（MCP 协议内原始名称）',
  `upstream_title` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '上游工具标题（不可信文本，仅供人工审阅）',
  `upstream_description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '上游描述原文（不可信文本，仅供人工审阅；不参与任何授权判定）',
  `observed_fingerprint` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '本次观察到的结构指纹（工具名+输入 schema，**不含描述**）',
  `platform_input_schema_json` varchar(4000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '映射到平台参数面后的输入 schema（审批与登记用的是同一份）',
  `approved_fingerprint` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '已审批的结构指纹（与 observed 不一致即漂移阻断）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT' COMMENT '状态（DRAFT 待审批不可执行/APPROVED 已审批/BLOCKED 上游漂移已阻断）',
  `tool_id` bigint DEFAULT NULL COMMENT '平台工具编号（审批后写 ai_tool 再回填；草稿态为空）',
  `tool_version_id` bigint DEFAULT NULL COMMENT '平台工具版本编号（审批后创建草稿版本再回填）',
  `blocked_reason_code` int DEFAULT NULL COMMENT '阻断原因稳定错误码（仅 BLOCKED 时有值）',
  `first_discovered_at` datetime NOT NULL COMMENT '首次发现时间',
  `last_discovered_at` datetime NOT NULL COMMENT '最近一次发现时间',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_mcp_tool_draft_upstream` (`connector_id`, `upstream_tool_name`, `deleted`),
  KEY `idx_ai_mcp_tool_draft_status` (`connector_id`, `status`, `id`),
  KEY `idx_ai_mcp_tool_draft_tool` (`tool_id`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI MCP 工具草稿（上游事实与平台审批的分界，X07）';

CREATE TABLE `ai_mcp_discovery_run` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'MCP 发现运行编号',
  `connector_id` bigint NOT NULL COMMENT '连接器编号',
  `server_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '服务端实现名（仅供审计）',
  `protocol_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '协商出的 MCP 协议版本（漂移判据）',
  `attempts` int NOT NULL DEFAULT '1' COMMENT '实际尝试次数（>1 说明发生过重试；有界的直接证据）',
  `termination` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '终止结果（SUCCESS/RETRIES_EXHAUSTED/TIMEOUT/UNAUTHORIZED/UNREACHABLE/PROTOCOL_ERROR/ADDRESS_DENIED/PROTOCOL_VERSION_UNSUPPORTED/RESPONSE_TOO_LARGE）',
  `failure_code` int DEFAULT NULL COMMENT '失败稳定错误码（成功时为空）',
  `tool_count` int NOT NULL DEFAULT '0' COMMENT '观察到的工具条数（仅成功时有意义；失败恒为 0）',
  `new_tool_count` int NOT NULL DEFAULT '0' COMMENT '新生成草稿的条数',
  `blocked_count` int NOT NULL DEFAULT '0' COMMENT '因结构漂移被阻断的条数',
  `elapsed_millis` bigint NOT NULL DEFAULT '0' COMMENT '耗时（毫秒）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  KEY `idx_ai_mcp_discovery_run_connector` (`connector_id`, `id`),
  KEY `idx_ai_mcp_discovery_run_termination` (`termination`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI MCP 发现运行留痕（有界重试与明确终止的可验证证据，X07）';
