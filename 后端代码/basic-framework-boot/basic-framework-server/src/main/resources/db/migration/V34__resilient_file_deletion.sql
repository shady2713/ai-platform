-- 文件删除先持久化为待清理状态，再幂等删除外部对象；失败由 Quartz 持续补偿。

ALTER TABLE `infra_file`
    ADD COLUMN `delete_status` tinyint NOT NULL DEFAULT 0 COMMENT '删除状态：0 正常，1 等待清理' AFTER `size`,
    ADD COLUMN `delete_attempts` int NOT NULL DEFAULT 0 COMMENT '外部存储清理失败次数' AFTER `delete_status`,
    ADD COLUMN `delete_next_retry_time` datetime DEFAULT NULL COMMENT '下次清理重试时间' AFTER `delete_attempts`,
    ADD COLUMN `delete_last_error` varchar(128) DEFAULT NULL COMMENT '最近失败异常类型' AFTER `delete_next_retry_time`,
    ADD KEY `idx_delete_retry` (`delete_status`, `delete_next_retry_time`);

INSERT INTO `infra_job`
    (`id`, `name`, `status`, `handler_name`, `handler_param`, `cron_expression`,
     `retry_count`, `retry_interval`, `monitor_timeout`, `creator`, `create_time`,
     `updater`, `update_time`, `deleted`)
VALUES
    (31, '文件外部存储清理重试 Job', 1, 'fileDeletionRetryJob', '',
     '0 * * * * ?', 0, 0, 0, '1', '2026-08-29 00:00:00',
     '1', '2026-08-29 00:00:00', b'0');
