ALTER TABLE `infra_codegen_table`
    DROP INDEX `idx_data_source_config_id`,
    DROP COLUMN `data_source_config_id`;

DROP TABLE IF EXISTS `infra_data_source_config`;
