-- 文件元数据保留可访问 URL，因此历史失效配置引用降级为空；DB 文件内容无法脱离配置使用，直接清理。
UPDATE `infra_file` f
LEFT JOIN `infra_file_config` c ON c.`id` = f.`config_id` AND c.`deleted` = b'0'
SET f.`config_id` = NULL
WHERE f.`config_id` IS NOT NULL
  AND c.`id` IS NULL;

DELETE fc
FROM `infra_file_content` fc
LEFT JOIN `infra_file_config` c ON c.`id` = fc.`config_id` AND c.`deleted` = b'0'
WHERE c.`id` IS NULL;

-- 物理外键保护最终清理；软删除仍由 FileConfigService 的事务锁和引用检查保护。
ALTER TABLE `infra_file`
    ADD INDEX `idx_config_id` (`config_id`),
    ADD CONSTRAINT `fk_file_config`
        FOREIGN KEY (`config_id`) REFERENCES `infra_file_config` (`id`) ON DELETE RESTRICT;

ALTER TABLE `infra_file_content`
    ADD INDEX `idx_config_id` (`config_id`),
    ADD CONSTRAINT `fk_file_content_config`
        FOREIGN KEY (`config_id`) REFERENCES `infra_file_config` (`id`) ON DELETE RESTRICT;
