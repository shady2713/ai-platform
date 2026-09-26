-- Q04：评测套件、样例版本与执行器。
-- 说明：
--   1) 套件（ai_eval_suite）与样例（ai_eval_case）是**配置**：软删除，编辑受乐观锁保护；
--      冻结（FROZEN）后修订号 +1，历史运行不受后续编辑影响（运行行保存套件摘要与逐例快照摘要）。
--   2) 运行（ai_eval_run）与结果（ai_eval_result）是**事实**：只追加（append-retention），
--      保留期清理由运维作业承担；结果内只有稳定判定（期望/实际/规则/摘要），不含提示词正文。
--   3) 夹具是合成数据：样例数据分级只允许 L1_PUBLIC/L2_INTERNAL，执行器拒绝更高分级，
--      因此报告里不存在真实客户秘密（docs/security/ai-eval-fixtures.md）。

DROP TABLE IF EXISTS `ai_eval_suite`;

CREATE TABLE `ai_eval_suite` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '套件编号',
  `application_id` bigint NOT NULL COMMENT '所属应用编号',
  `code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '套件标识（应用内唯一）',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '套件名称',
  `description` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '说明',
  `service_id` bigint NOT NULL COMMENT '被评测的服务编号（执行走同一运行服务与授权）',
  `subject_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'USER' COMMENT '执行主体类型（APP/USER）',
  `external_user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'eval-runner' COMMENT '执行主体标识（评测专用合成主体）',
  `data_level` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'L2_INTERNAL' COMMENT '样例数据分级（只允许 L1_PUBLIC/L2_INTERNAL）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT' COMMENT '状态（DRAFT/FROZEN）',
  `revision` int NOT NULL DEFAULT '1' COMMENT '修订号（冻结一次 +1）',
  `case_count` int NOT NULL DEFAULT '0' COMMENT '样例数（冻结时的事实值）',
  `frozen_time` datetime DEFAULT NULL COMMENT '最近冻结时间',
  `content_digest` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '冻结内容摘要（套件 + 样例规范化的 SHA-256）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_ai_eval_suite_code` (`application_id`,`code`,`deleted`),
  KEY `idx_ai_eval_suite_service` (`service_id`,`id`),
  CONSTRAINT `fk_ai_eval_suite_app` FOREIGN KEY (`application_id`) REFERENCES `ai_application` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_ai_eval_suite_service` FOREIGN KEY (`service_id`) REFERENCES `ai_service` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 评测套件（配置，Q04）';

DROP TABLE IF EXISTS `ai_eval_case`;

CREATE TABLE `ai_eval_case` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '样例编号',
  `suite_id` bigint NOT NULL COMMENT '所属套件编号',
  `case_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '样例标识（套件内唯一）',
  `title` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '标题',
  `severity` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'MAJOR' COMMENT '严重级别（BLOCKER/MAJOR/MINOR）',
  `question` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '合成问题（不提真实客户数据）',
  `expect_version` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '期望的模型/service 版本标识',
  `checks_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '期望规则（确定性核对词表，见 docs/security/ai-eval-fixtures.md）',
  `needs_review` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否需要人工复核（结果需复核后才算通过）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_ai_eval_case_key` (`suite_id`,`case_key`,`deleted`),
  CONSTRAINT `fk_ai_eval_case_suite` FOREIGN KEY (`suite_id`) REFERENCES `ai_eval_suite` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 评测样例（配置，Q04）';

DROP TABLE IF EXISTS `ai_eval_run`;

CREATE TABLE `ai_eval_run` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '评测运行编号',
  `suite_id` bigint NOT NULL COMMENT '套件编号',
  `application_id` bigint NOT NULL COMMENT '应用编号（快照）',
  `service_id` bigint NOT NULL COMMENT '服务编号（快照）',
  `suite_revision` int NOT NULL COMMENT '执行时的套件修订号',
  `suite_digest` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '执行时的套件内容摘要（套件后续编辑不改变本值）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态（RUNNING/COMPLETED/FAILED）',
  `case_total` int NOT NULL DEFAULT '0' COMMENT '样例总数',
  `passed_count` int NOT NULL DEFAULT '0' COMMENT '通过数',
  `failed_count` int NOT NULL DEFAULT '0' COMMENT '失败数',
  `error_count` int NOT NULL DEFAULT '0' COMMENT '错误数（未能执行，如上游不可用）',
  `summary_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '逐例快照摘要（caseKey + digest + severity + needsReview，执行即冻结）',
  `started_time` datetime NOT NULL COMMENT '开始时间',
  `finished_time` datetime DEFAULT NULL COMMENT '结束时间',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_ai_eval_run_suite` (`suite_id`,`id`),
  CONSTRAINT `fk_ai_eval_run_suite` FOREIGN KEY (`suite_id`) REFERENCES `ai_eval_suite` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_ai_eval_run_app` FOREIGN KEY (`application_id`) REFERENCES `ai_application` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 评测运行（事实，只追加，Q04）';

DROP TABLE IF EXISTS `ai_eval_result`;

CREATE TABLE `ai_eval_result` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '结果编号',
  `run_id` bigint NOT NULL COMMENT '评测运行编号',
  `case_id` bigint DEFAULT NULL COMMENT '样例编号（样例删除后仍保留标识与快照摘要）',
  `case_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '样例标识（快照）',
  `severity` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '严重级别（快照）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '判定（PASSED/FAILED/ERROR/REVIEW_REQUIRED）',
  `expect_version` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '期望版本标识（快照）',
  `observed_version` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '实际版本标识（如模型端点修订）',
  `run_ref` bigint DEFAULT NULL COMMENT '本次评测用例产生的运行编号（走同一运行服务时给出）',
  `verdict_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '逐条核对结论（规则/期望/实际/说明，不含正文）',
  `failure_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '错误码（未能执行时给出稳定码）',
  `case_digest` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '冻结的样例摘要（可复现：同摘要同期望）',
  `result_digest` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '结果摘要（样例摘要 + 实际事实 + 结论）',
  `review_status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'NOT_REQUIRED' COMMENT '人工复核（NOT_REQUIRED/PENDING/APPROVED/REJECTED）',
  `review_note` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '复核备注',
  `reviewed_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '复核人',
  `reviewed_time` datetime DEFAULT NULL COMMENT '复核时间',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_ai_eval_result_case` (`run_id`,`case_key`),
  KEY `idx_ai_eval_result_status` (`run_id`,`status`,`id`),
  CONSTRAINT `fk_ai_eval_result_run` FOREIGN KEY (`run_id`) REFERENCES `ai_eval_run` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 评测结果（事实，只追加，Q04）';

-- 评测菜单与权限点（4120 页面；查看/维护/执行/复核分开鉴权）
INSERT INTO `system_menu`
(`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
 `icon`, `component`, `component_name`, `status`, `visible`,
 `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (4120, '评测套件', 'ai:eval:query', 2, 16, 4000, 'evaluation', 'ep:data-analysis',
        'ai/evaluation/index', 'AiEvaluation', 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4121, '套件维护', 'ai:eval:manage', 3, 17, 4120, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4122, '执行评测', 'ai:eval:run', 3, 18, 4120, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4123, '人工复核', 'ai:eval:review', 3, 19, 4120, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');
