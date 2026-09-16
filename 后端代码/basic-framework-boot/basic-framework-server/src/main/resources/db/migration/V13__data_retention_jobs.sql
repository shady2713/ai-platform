-- 为按创建时间分批清理补齐索引，并启用四类数据保留任务。

ALTER TABLE `system_sms_log`
    ADD KEY `idx_create_time` (`create_time`);

ALTER TABLE `system_notify_message`
    DROP INDEX `idx_read_status`,
    ADD KEY `idx_read_status_create_time` (`read_status`, `create_time`);

UPDATE `infra_job`
SET `status` = 1,
    `updater` = '1',
    `update_time` = '2026-08-23 00:00:00'
WHERE `id` IN (25, 26, 27);

INSERT INTO `infra_job`
    (`id`, `name`, `status`, `handler_name`, `handler_param`, `cron_expression`,
     `retry_count`, `retry_interval`, `monitor_timeout`, `creator`, `create_time`,
     `updater`, `update_time`, `deleted`)
VALUES
    (28, 'system 数据保留清理 Job', 1, 'systemDataRetentionCleanJob', '',
     '0 0 1 * * ?', 3, 0, 0, '1', '2026-08-23 00:00:00',
     '1', '2026-08-23 00:00:00', b'0');
