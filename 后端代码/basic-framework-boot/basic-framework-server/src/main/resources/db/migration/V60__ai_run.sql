-- O02：持久 run、幂等受理与首任务。
-- 设计要点：
--   1) 受理是**幂等**的：同一主体 + 同一幂等键 + 同一请求摘要只产生一个 run；
--      摘要只由服务/会话/消息/附件/业务上下文算出，**排除** traceId 与票据 token，
--      因此重试、换票、换链路追踪都不会被当成新请求；
--   2) 幂等记录、run 与首任务在**同一事务**内建立：任何一步失败都不留半成品；
--      并发冲突由唯一键兜底（(主体, 幂等键) 唯一），冲突后回读原记录比较摘要：
--      摘要相同返回原 run（不重新发起模型调用），摘要不同返回 409；
--   3) run 冻结受理时解析到的发布版本与端点配置版本（S03 的固定值），
--      运行链路只用快照，不重新读草稿；资源授权与停用状态仍在每次运行时按当前值判定；
--   4) 任务载荷只保存摘要，不保存正文：队列、日志与响应都不携带用户输入正文。

CREATE TABLE `ai_run`
(
    `id`                       bigint       NOT NULL AUTO_INCREMENT COMMENT '运行编号',
    `run_key`                  varchar(40)  NOT NULL COMMENT '运行业务键（run_ 前缀，主体内唯一）',
    `application_id`           bigint       NOT NULL COMMENT '应用编号',
    `subject_type`             varchar(16)  NOT NULL COMMENT '主体类型（APP/USER）',
    `external_user_id`         varchar(64)  NOT NULL DEFAULT '' COMMENT '可信外部用户标识',
    `conversation_id`          bigint       DEFAULT NULL COMMENT '会话编号',
    `service_id`               bigint       NOT NULL COMMENT '服务编号',
    `release_id`               bigint       NOT NULL COMMENT '受理时固定的发布版本编号',
    `model_endpoint_id`        bigint       NOT NULL COMMENT '受理时固定的模型端点编号',
    `endpoint_config_revision` int          NOT NULL COMMENT '受理时固定的端点配置版本',
    `content_hash`             char(64)     NOT NULL COMMENT '受理时固定的发布内容摘要',
    `input_digest`             char(64)     NOT NULL COMMENT '请求摘要（幂等判定；不含 traceId 与 token）',
    `status`                   varchar(16)  NOT NULL COMMENT '状态（ACCEPTED/RUNNING/SUCCEEDED/FAILED/CANCELLED）',
    `step_count`               int          NOT NULL DEFAULT 0 COMMENT '已执行步数（有界执行，O04）',
    `version`                  int          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`                  varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`              datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`                  varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`              datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`                  bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_run_key` (`application_id`, `subject_type`, `external_user_id`, `run_key`, `deleted`),
    KEY `idx_ai_run_subject` (`application_id`, `subject_type`, `external_user_id`, `id`),
    KEY `idx_ai_run_conversation` (`conversation_id`, `id`),
    KEY `idx_ai_run_release` (`release_id`, `id`),
    CONSTRAINT `fk_ai_run_app` FOREIGN KEY (`application_id`) REFERENCES `ai_application` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_ai_run_service` FOREIGN KEY (`service_id`) REFERENCES `ai_service` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_ai_run_release` FOREIGN KEY (`release_id`) REFERENCES `ai_service_release` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_ai_run_conversation` FOREIGN KEY (`conversation_id`) REFERENCES `ai_conversation` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 运行（受理即固定发布版本，O02）';

CREATE TABLE `ai_run_idempotency`
(
    `id`               bigint       NOT NULL AUTO_INCREMENT COMMENT '幂等记录编号',
    `application_id`   bigint       NOT NULL COMMENT '应用编号',
    `subject_type`     varchar(16)  NOT NULL COMMENT '主体类型',
    `external_user_id` varchar(64)  NOT NULL DEFAULT '' COMMENT '可信外部用户标识',
    `idempotency_key`  varchar(128) NOT NULL COMMENT '幂等键（调用方提供，主体内唯一）',
    `request_digest`   char(64)     NOT NULL COMMENT '请求摘要（与 run.input_digest 一致才算同一请求）',
    `run_id`           bigint       NOT NULL COMMENT '首次受理产生的运行编号',
    `version`          int          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`          varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`          varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_run_idempotency_key` (`application_id`, `subject_type`, `external_user_id`, `idempotency_key`, `deleted`),
    KEY `idx_ai_run_idempotency_run` (`run_id`),
    CONSTRAINT `fk_ai_run_idempotency_app` FOREIGN KEY (`application_id`) REFERENCES `ai_application` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_ai_run_idempotency_run` FOREIGN KEY (`run_id`) REFERENCES `ai_run` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 运行幂等记录（唯一键兜底并发，O02）';

CREATE TABLE `ai_run_task`
(
    `id`                bigint      NOT NULL AUTO_INCREMENT COMMENT '任务编号',
    `run_id`            bigint      NOT NULL COMMENT '运行编号',
    `task_kind`         varchar(32) NOT NULL COMMENT '任务类型（首任务为 RUN_STEP）',
    `status`            varchar(16) NOT NULL COMMENT '状态（QUEUED/RUNNING/SUCCEEDED/FAILED/UNKNOWN）',
    `attempt_count`     int         NOT NULL DEFAULT 0 COMMENT '已尝试次数',
    `next_attempt_time` datetime    DEFAULT NULL COMMENT '下次可尝试时间（重试等待）',
    `payload_digest`    char(64)    NOT NULL COMMENT '任务载荷摘要（正文不入队）',
    `version`           int         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`           varchar(64) DEFAULT '' COMMENT '创建者',
    `create_time`       datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`           varchar(64) DEFAULT '' COMMENT '更新者',
    `update_time`       datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           bit(1)      NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_run_task_kind` (`run_id`, `task_kind`, `deleted`),
    KEY `idx_ai_run_task_status` (`status`, `next_attempt_time`, `id`),
    CONSTRAINT `fk_ai_run_task_run` FOREIGN KEY (`run_id`) REFERENCES `ai_run` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 运行任务（首任务与重试等待；租约列由 O03 补齐，O02）';
