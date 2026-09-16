-- V3：schema 一致性修正

-- 1. 三张关联表补充组合唯一约束
-- 种子数据已核对无 (role_id, menu_id) / (user_id, role_id) / (user_id, post_id) 重复行
-- 注意：关联表删除走逻辑删（@TableLogic），唯一键与"取消授权后再次授权"存在
-- 交互风险，删除路径改物理删除由后续切片处理
ALTER TABLE `system_role_menu`
    ADD UNIQUE KEY `uk_role_id_menu_id` (`role_id`, `menu_id`);
ALTER TABLE `system_user_role`
    ADD UNIQUE KEY `uk_user_id_role_id` (`user_id`, `role_id`);
ALTER TABLE `system_user_post`
    ADD UNIQUE KEY `uk_user_id_post_id` (`user_id`, `post_id`);

-- 2. system_user_role 租户基字段可空性/默认值对齐同类表（system_role_menu、system_user_post）
-- 先回填历史 NULL 行，再收紧为 NOT NULL，避免严格模式下迁移失败
UPDATE `system_user_role` SET `create_time` = CURRENT_TIMESTAMP WHERE `create_time` IS NULL;
UPDATE `system_user_role` SET `update_time` = CURRENT_TIMESTAMP WHERE `update_time` IS NULL;
UPDATE `system_user_role` SET `deleted` = b'0' WHERE `deleted` IS NULL;
ALTER TABLE `system_user_role`
    MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    MODIFY COLUMN `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除';

-- 3. infra_file.size int -> bigint（字节数会溢出 int；FileDO.size 已是 Long，无需改 DO）
ALTER TABLE `infra_file`
    MODIFY COLUMN `size` bigint NOT NULL COMMENT '文件大小';

-- 4. infra_api_error_log.process_user_id int -> bigint
-- user_id 列本身已是 bigint；process_user_id 是全库唯一仍为 int 的用户编号列，
-- 对齐为 bigint NOT NULL DEFAULT '0'（ApiErrorLogDO.processUserId 已是 Long，表种子数据为空）
ALTER TABLE `infra_api_error_log`
    MODIFY COLUMN `process_user_id` bigint NOT NULL DEFAULT '0' COMMENT '处理用户编号';

-- 5. 删除无代码消费者的孤儿表（后端无对应 DO/Mapper/Service，前端无调用）
DROP TABLE IF EXISTS `system_mail_account`;
DROP TABLE IF EXISTS `system_mail_log`;
DROP TABLE IF EXISTS `system_mail_template`;
DROP TABLE IF EXISTS `system_oauth2_approve`;
DROP TABLE IF EXISTS `system_oauth2_code`;
