-- K07：知识清理 Job 种子（infra_job id 36）。
-- 说明：清理本身不需要新表——它按"删除中"状态推进既有表（ai_knowledge_document/version/chunk/
-- index_generation）与向量服务的点，是状态驱动 + 分步幂等的，因此只登记 Job 本身。

INSERT INTO `infra_job`
(`id`, `name`, `status`, `handler_name`, `handler_param`, `cron_expression`,
 `retry_count`, `retry_interval`, `monitor_timeout`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (36, 'AI 知识清理 Job', 1, 'aiKnowledgeCleanupJob', '', '0 5/10 * * * ?', 0, 0, 0, '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');
