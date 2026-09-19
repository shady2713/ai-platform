-- O06：任务查询、人工重试与保留期清理。
-- 设计要点：
--   1) 清理按**引用关系**分步：先事件、再任务、再运行，最后才是会话与消息；
--      每一步都带"仍被引用则不删"的守卫（非终态运行的事件/任务/运行、被非终态运行引用的会话），
--      因此清理失败或中途中断都不会删掉仍有用引用；
--   2) 清理只处理**终态且超过保留期**的行，批次与批次数都有上限（不扫全表、不长事务）；
--   3) 人工重试是显式动作：只有可重试的失败任务能被重试，且重试前必须重建当前身份与授权；
--      UNKNOWN（结果未知）任务不可普通重试——重复执行可能产生第二份副作用。

CREATE INDEX `idx_ai_run_status_updated` ON `ai_run` (`status`, `update_time`);
CREATE INDEX `idx_ai_conversation_status_updated` ON `ai_conversation` (`status`, `update_time`);

-- AI 保留期清理 Job（每小时；handler 名与 bean 名一致）
INSERT INTO `infra_job`
(`id`, `name`, `status`, `handler_name`, `handler_param`, `cron_expression`,
 `retry_count`, `retry_interval`, `monitor_timeout`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (33, 'AI 保留期清理 Job', 1, 'aiRetentionCleanupJob', '', '0 15 * * * ?', 0, 0, 0, '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');
