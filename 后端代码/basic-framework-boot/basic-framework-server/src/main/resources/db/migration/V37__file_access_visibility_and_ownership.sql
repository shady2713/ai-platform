-- 旧文件保持历史公开语义；新文件默认私有并记录业务主体，下载接口据此做授权判断。

ALTER TABLE `infra_file`
    ADD COLUMN `access_type` tinyint NOT NULL DEFAULT 2 COMMENT '读取策略：1 公开读取，2 私有读取' AFTER `size`,
    ADD COLUMN `owner_user_id` bigint DEFAULT NULL COMMENT '文件所有者用户编号' AFTER `upload_user_type`,
    ADD COLUMN `owner_user_type` tinyint DEFAULT NULL COMMENT '文件所有者用户类型' AFTER `owner_user_id`,
    ADD CONSTRAINT `ck_file_access_type` CHECK (`access_type` IN (1, 2));

UPDATE `infra_file`
SET `access_type` = 1,
    `owner_user_id` = `upload_user_id`,
    `owner_user_type` = `upload_user_type`;
