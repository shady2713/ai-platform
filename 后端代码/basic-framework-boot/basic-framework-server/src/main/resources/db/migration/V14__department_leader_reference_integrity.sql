-- 修复清洗后遗留的部门负责人孤儿引用，并为在线校验与反向查询补齐索引。

UPDATE `system_dept` d
LEFT JOIN `system_users` u ON u.`id` = d.`leader_user_id` AND u.`deleted` = b'0'
SET d.`leader_user_id` = NULL,
    d.`updater` = '1',
    d.`update_time` = '2026-08-23 00:00:00'
WHERE d.`leader_user_id` IS NOT NULL
  AND u.`id` IS NULL;

ALTER TABLE `system_dept`
    ADD KEY `idx_leader_user_id` (`leader_user_id`);

INSERT INTO `infra_job`
    (`id`, `name`, `status`, `handler_name`, `handler_param`, `cron_expression`,
     `retry_count`, `retry_interval`, `monitor_timeout`, `creator`, `create_time`,
     `updater`, `update_time`, `deleted`)
VALUES
    (29, 'system 数据完整性审计 Job', 1, 'systemDataIntegrityAuditJob', '',
     '0 30 2 * * ?', 0, 0, 0, '1', '2026-08-23 00:00:00',
     '1', '2026-08-23 00:00:00', b'0');
