-- X08：最小可视化流程编辑与受控运行（FR-39）。
-- 设计要点（V1.2 边界）：
--   1) 流程定义（ai_workflow）是按应用的配置面：应用内标识唯一、创建后 code 不可修改；
--      停用（DISABLED）即不再受理新运行；
--   2) 流程版本（ai_workflow_version）是**不可变快照**：图结构（nodes/edges JSON）只在 DRAFT 状态可编辑，
--      发布用 CAS（乐观锁）落 PUBLISHED 并冻结 graph_hash；同一流程同时最多一个打开的草稿
--      （函数唯一键 uk_ai_workflow_version_open_draft），发布后修改必须新建草稿版本——
--      运行受理时固定版本编号，编辑草稿/发布新版本都不影响已受理运行的语义；
--   3) 运行（ai_workflow_run）受理即固定版本：幂等键（应用 + 流程内唯一）防重复发起，
--      运行是同步有界执行（步数/耗时预算，超出按稳定原因受控结束），无常驻 Job；
--   4) 节点留痕（ai_workflow_run_node，append-retention）：每个节点一条事实（状态/稳定错误码/耗时），
--      支撑步骤可视化与失败定位；节点输出截断存储，不落上游正文全文与凭据；
--   5) 节点执行不绕开既有受控入口：模型走 M05（外发策略 + 计量）、知识检索走 K06（主体授权）、
--      数据查询走 R05（计划重校验 + 行范围强制拼 WHERE）、工具走 D08 政策闸门 + D02 受控执行——
--      需要人工确认的工具在流程运行里不能执行（受控结束，复用 1_003_006_046），不产生第二确认通道。

DROP TABLE IF EXISTS `ai_workflow_run_node`;
DROP TABLE IF EXISTS `ai_workflow_run`;
DROP TABLE IF EXISTS `ai_workflow_version`;
DROP TABLE IF EXISTS `ai_workflow`;

CREATE TABLE `ai_workflow` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '流程编号',
  `application_id` bigint NOT NULL COMMENT '应用编号（流程属于某个 AI 应用；运行按该应用主体执行）',
  `code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '流程标识（应用内唯一，创建后不可修改）',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '流程名称',
  `description` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '流程说明',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ENABLED' COMMENT '状态（ENABLED/DISABLED；DISABLED 不受理新运行）',
  `latest_version_no` int NOT NULL DEFAULT '0' COMMENT '最新版本序号（草稿与发布共用递增）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_workflow_code` (`application_id`, `code`, `deleted`),
  KEY `idx_ai_workflow_app` (`application_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 可视化流程定义（配置面，X08）';

CREATE TABLE `ai_workflow_version` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '流程版本编号（运行受理固定到该编号）',
  `workflow_id` bigint NOT NULL COMMENT '流程编号',
  `version_no` int NOT NULL COMMENT '版本序号（同一流程内递增，发布后不可变）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT' COMMENT '状态（DRAFT 可编辑/PUBLISHED 不可变/DISCARDED 已废弃）',
  `graph_json` varchar(16000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '流程图 JSON（nodes/edges 受控契约；节点类型见 AiWorkflowNodeType）',
  `graph_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '图内容摘要（sha-256，发布时冻结）',
  `node_count` int NOT NULL DEFAULT '0' COMMENT '节点数（发布期有界：1-32）',
  `edge_count` int NOT NULL DEFAULT '0' COMMENT '边数（发布期有界：0-64）',
  `published_at` datetime DEFAULT NULL COMMENT '发布时间',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_workflow_version_no` (`workflow_id`, `version_no`, `deleted`),
  UNIQUE KEY `uk_ai_workflow_version_open_draft` ((if((`status` = 'DRAFT'), `workflow_id`, NULL))),
  KEY `idx_ai_workflow_version_status` (`workflow_id`, `status`, `version_no`),
  CONSTRAINT `fk_ai_workflow_version_workflow` FOREIGN KEY (`workflow_id`) REFERENCES `ai_workflow` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 流程版本（不可变图快照 + 单开草稿，X08）';

CREATE TABLE `ai_workflow_run` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '流程运行编号',
  `workflow_id` bigint NOT NULL COMMENT '流程编号',
  `workflow_version_id` bigint NOT NULL COMMENT '流程版本编号（受理时固定的不可变快照）',
  `idempotency_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '受理幂等键（同一流程内唯一；重复受理返回首次运行）',
  `request_digest` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '受理请求摘要（同键异摘要拒绝）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'RUNNING' COMMENT '状态（RUNNING/SUCCEEDED/FAILED；终态只能写一次）',
  `data_level` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '数据等级（L1_PUBLIC/L2_INTERNAL；模型节点外发等级）',
  `input_text` varchar(4000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '运行输入（开始节点的透传文本）',
  `output_text` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '运行输出（结束节点的上游文本，截断存储）',
  `error_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '失败稳定原因码（成功为空；不含上游正文）',
  `current_node_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '当前（或最后）执行的节点键',
  `node_total` int NOT NULL DEFAULT '0' COMMENT '流程图节点总数（发布时冻结）',
  `node_executed` int NOT NULL DEFAULT '0' COMMENT '已执行节点数',
  `max_steps` int NOT NULL DEFAULT '16' COMMENT '步数预算（本次受理快照，上限见 AiWorkflowBudget）',
  `max_duration_millis` bigint NOT NULL DEFAULT '60000' COMMENT '耗时预算（毫秒，本次受理快照）',
  `started_time` datetime NOT NULL COMMENT '受理时间',
  `finished_time` datetime DEFAULT NULL COMMENT '结束时间',
  `duration_ms` bigint DEFAULT NULL COMMENT '执行耗时（毫秒）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本（终态写入用 CAS）',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_workflow_run_accept` (`workflow_id`, `idempotency_key`, `deleted`),
  KEY `idx_ai_workflow_run_workflow` (`workflow_id`, `id`),
  CONSTRAINT `fk_ai_workflow_run_workflow` FOREIGN KEY (`workflow_id`) REFERENCES `ai_workflow` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_ai_workflow_run_version` FOREIGN KEY (`workflow_version_id`) REFERENCES `ai_workflow_version` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 流程运行（受理固定版本 + 同步有界执行，X08）';

CREATE TABLE `ai_workflow_run_node` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '节点留痕编号',
  `run_id` bigint NOT NULL COMMENT '运行编号',
  `node_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '节点键（图内唯一）',
  `node_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '节点类型快照（AiWorkflowNodeType）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态（SUCCEEDED/FAILED）',
  `output_text` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '节点输出摘要（截断存储；不含凭据与上游正文全文）',
  `error_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '稳定错误码（成功为空）',
  `started_time` datetime NOT NULL COMMENT '节点开始时间',
  `finished_time` datetime NOT NULL COMMENT '节点结束时间',
  `duration_ms` bigint NOT NULL DEFAULT '0' COMMENT '节点耗时（毫秒）',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_workflow_run_node` (`run_id`, `node_key`),
  KEY `idx_ai_workflow_run_node_run` (`run_id`, `id`),
  CONSTRAINT `fk_ai_workflow_run_node_run` FOREIGN KEY (`run_id`) REFERENCES `ai_workflow_run` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 流程运行节点留痕（append-retention，步骤可视化与失败定位，X08）';

-- 控制面菜单：流程编排（挂在 AI 中台目录 4000 下，V89）。
INSERT INTO `system_menu`
(`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
 `icon`, `component`, `component_name`, `status`, `visible`,
 `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (4129, '流程编排', 'ai:workflow:query', 2, 18, 4000, 'workflow', 'ep:connection',
        'ai/workflow/index', 'AiWorkflow', 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4130, '流程维护', 'ai:workflow:manage', 3, 1, 4129, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4131, '流程运行', 'ai:workflow:run', 3, 2, 4129, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4132, '流程删除', 'ai:workflow:delete', 3, 3, 4129, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');
