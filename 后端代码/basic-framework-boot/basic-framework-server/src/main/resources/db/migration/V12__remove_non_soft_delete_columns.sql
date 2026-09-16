-- 删除策略已从通用 BaseDO 拆分；仅 soft-delete 表保留 deleted 列。
-- hard-delete 表中的敏感或关联数据必须物理移除，append-retention 表仅由保留期任务物理清理。

ALTER TABLE `infra_file` DROP COLUMN `deleted`;
ALTER TABLE `infra_file_content` DROP COLUMN `deleted`;
ALTER TABLE `system_oauth2_access_token` DROP COLUMN `deleted`;
ALTER TABLE `system_oauth2_refresh_token` DROP COLUMN `deleted`;
ALTER TABLE `system_role_menu` DROP COLUMN `deleted`;
ALTER TABLE `system_sms_code` DROP COLUMN `deleted`;
ALTER TABLE `system_user_post` DROP COLUMN `deleted`;
ALTER TABLE `system_user_role` DROP COLUMN `deleted`;

ALTER TABLE `infra_api_access_log` DROP COLUMN `deleted`;
ALTER TABLE `infra_api_error_log` DROP COLUMN `deleted`;
ALTER TABLE `infra_job_log` DROP COLUMN `deleted`;
ALTER TABLE `system_login_log` DROP COLUMN `deleted`;
ALTER TABLE `system_notify_message` DROP COLUMN `deleted`;
ALTER TABLE `system_operate_log` DROP COLUMN `deleted`;
ALTER TABLE `system_sms_log` DROP COLUMN `deleted`;
