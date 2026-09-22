-- K03：知识文档入库任务（异步衔接 + 租约恢复）。
-- 设计要点：
--   1) 入库是"版本 + 任务"的同一事务：文档版本创建成功才可能有任务，任务缺失就不可能被后台处理——
--      因此不存在"版本已可见但没有任务"的中间态（失败时整笔回滚，文件由上传方补偿解除引用）；
--   2) 租约语义沿用 O03（ai_run_task）的既有设计：QUEUED → RUNNING（CAS 领取 + epoch 栅栏）→
--      SUCCEEDED/FAILED/UNKNOWN；续租与落库都带 (owner, epoch)，租约过期被接管后旧 worker 写不进结果；
--   3) 同一版本同一任务只有一条存活行（函数唯一索引）：重复入库复用版本时不产生重复任务，
--      也就不会出现"重复可见版本"；
--   4) 失败只落**脱敏稳定原因码**（last_error_code），不落解析异常正文。

CREATE TABLE `ai_knowledge_ingestion_task`
(
    `id`                  bigint      NOT NULL AUTO_INCREMENT COMMENT '任务编号',
    `knowledge_base_id`   bigint      NOT NULL COMMENT '知识库编号',
    `document_id`         bigint      NOT NULL COMMENT '文档编号',
    `document_version_id` bigint      NOT NULL COMMENT '文档版本编号（任务针对的版本）',
    `task_kind`           varchar(16) NOT NULL COMMENT '任务类型（PARSE 解析/INDEX 切分与向量化）',
    `status`              varchar(16) NOT NULL DEFAULT 'QUEUED' COMMENT '状态（QUEUED/RUNNING/SUCCEEDED/FAILED/UNKNOWN）',
    `attempt_count`       int         NOT NULL DEFAULT 0 COMMENT '已尝试次数',
    `max_attempts`        int         NOT NULL DEFAULT 3 COMMENT '最大尝试次数（达到上限置 FAILED）',
    `next_attempt_time`   datetime    NOT NULL COMMENT '下次可领取时间',
    `lease_owner`         varchar(64) DEFAULT NULL COMMENT '租约持有者（worker 标识）',
    `lease_expires_time`  datetime    DEFAULT NULL COMMENT '租约到期时间',
    `heartbeat_time`      datetime    DEFAULT NULL COMMENT '最近续租时间',
    `claimed_epoch`       int         NOT NULL DEFAULT 0 COMMENT '领取代数（栅栏：接管后旧 worker 失效）',
    `last_error_code`     varchar(64) DEFAULT NULL COMMENT '最近失败原因（脱敏稳定原因码）',
    `version`             int         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`             varchar(64) DEFAULT '' COMMENT '创建者',
    `create_time`         datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`             varchar(64) DEFAULT '' COMMENT '更新者',
    `update_time`         datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`             bit(1)      NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_knowledge_ingestion_version` ((if(`deleted` = b'1', NULL, concat(`document_version_id`, ':', `task_kind`)))),
    KEY `idx_ai_knowledge_ingestion_claim` (`status`, `next_attempt_time`, `id`),
    KEY `idx_ai_knowledge_ingestion_version` (`document_version_id`, `status`),
    CONSTRAINT `fk_ai_knowledge_ingestion_base` FOREIGN KEY (`knowledge_base_id`) REFERENCES `ai_knowledge_base` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_ai_knowledge_ingestion_document` FOREIGN KEY (`document_id`) REFERENCES `ai_knowledge_document` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_ai_knowledge_ingestion_version` FOREIGN KEY (`document_version_id`) REFERENCES `ai_knowledge_document_version` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 知识文档入库任务（租约 + 栅栏 + 重试上限，K03）';

-- 知识入库 Job 与租约恢复 Job（infra_job id 34/35；与 V61/V64 的 Job 种子同一形态）
INSERT INTO `infra_job`
(`id`, `name`, `status`, `handler_name`, `handler_param`, `cron_expression`,
 `retry_count`, `retry_interval`, `monitor_timeout`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (34, 'AI 知识入库 Job', 1, 'aiKnowledgeIngestionJob', '', '0/20 * * * * ?', 0, 0, 0, '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (35, 'AI 知识入库恢复 Job', 1, 'aiKnowledgeIngestionRecoveryJob', '', '30 * * * * ?', 0, 0, 0, '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');
