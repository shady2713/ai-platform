-- 缺失渠道的模板无法安全改绑，且当前框架无历史数据恢复要求，迁移直接删除无效配置。
DELETE template
FROM `system_sms_template` template
LEFT JOIN `system_sms_channel` channel ON channel.`id` = template.`channel_id`
WHERE channel.`id` IS NULL;

-- 已软删渠道仍保留物理父行；将其活动模板同步软删，避免运行时继续使用失效渠道。
UPDATE `system_sms_template` template
JOIN `system_sms_channel` channel ON channel.`id` = template.`channel_id`
SET template.`deleted` = b'1',
    template.`updater` = '1',
    template.`update_time` = '2026-08-24 00:00:00'
WHERE template.`deleted` = b'0'
  AND channel.`deleted` = b'1';

ALTER TABLE `system_sms_template`
    ADD INDEX `idx_channel_id` (`channel_id`),
    ADD CONSTRAINT `fk_sms_template_channel`
        FOREIGN KEY (`channel_id`) REFERENCES `system_sms_channel` (`id`) ON DELETE RESTRICT;
