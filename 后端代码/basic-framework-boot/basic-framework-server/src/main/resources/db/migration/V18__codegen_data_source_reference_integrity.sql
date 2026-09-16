-- data_source_config_id=0 表示不落库的主数据源，因此该关系只能使用逻辑引用。
-- 历史孤儿不自动改绑主库；审计失败后应删除失效代码生成配置，并从正确数据源重新导入。

INSERT INTO `infra_job`
    (`id`, `name`, `status`, `handler_name`, `handler_param`, `cron_expression`,
     `retry_count`, `retry_interval`, `monitor_timeout`, `creator`, `create_time`,
     `updater`, `update_time`, `deleted`)
VALUES
    (30, 'infra 数据完整性审计 Job', 1, 'infraDataIntegrityAuditJob', '',
     '0 45 2 * * ?', 0, 0, 0, '1', '2026-08-24 00:00:00',
     '1', '2026-08-24 00:00:00', b'0');
