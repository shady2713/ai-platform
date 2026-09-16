-- 产品不提供外部开放 API；删除无生产入口的客户端和授权范围模型，只保留内部用户会话。
DELETE FROM `system_oauth2_access_token` WHERE `user_id` IS NULL;
DELETE FROM `system_oauth2_refresh_token` WHERE `user_id` IS NULL;

ALTER TABLE `system_oauth2_access_token`
    DROP COLUMN `client_id`,
    DROP COLUMN `scopes`,
    MODIFY COLUMN `user_id` bigint NOT NULL COMMENT '用户编号';

ALTER TABLE `system_oauth2_refresh_token`
    DROP COLUMN `client_id`,
    DROP COLUMN `scopes`,
    MODIFY COLUMN `user_id` bigint NOT NULL COMMENT '用户编号';

DROP TABLE IF EXISTS `system_oauth2_client`;

DELETE FROM `system_dict_data` WHERE `dict_type` = 'system_oauth2_grant_type';
DELETE FROM `system_dict_type` WHERE `type` = 'system_oauth2_grant_type';
