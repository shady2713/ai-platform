-- 预签名上传先登记为不可见的待完成记录；一次性令牌只保存摘要，过期对象复用删除补偿任务清理。

ALTER TABLE `infra_file`
    ADD COLUMN `upload_status` tinyint NOT NULL DEFAULT 1 COMMENT '上传状态：0 等待上传，1 已完成，2 校验中' AFTER `size`,
    ADD COLUMN `upload_staging_path` varchar(512) DEFAULT NULL COMMENT '校验前的私有临时对象路径' AFTER `upload_status`,
    ADD COLUMN `upload_token_hash` char(64) DEFAULT NULL COMMENT '一次性上传令牌 SHA-256 摘要' AFTER `upload_staging_path`,
    ADD COLUMN `upload_expires_at` datetime DEFAULT NULL COMMENT '预签名上传过期时间' AFTER `upload_token_hash`,
    ADD COLUMN `upload_user_id` bigint DEFAULT NULL COMMENT '预签名签发用户编号' AFTER `upload_expires_at`,
    ADD COLUMN `upload_user_type` tinyint DEFAULT NULL COMMENT '预签名签发用户类型' AFTER `upload_user_id`,
    ADD UNIQUE KEY `uk_config_path` (`config_id`, `path`),
    ADD UNIQUE KEY `uk_upload_token_hash` (`upload_token_hash`),
    ADD UNIQUE KEY `uk_upload_staging_path` (`config_id`, `upload_staging_path`),
    ADD KEY `idx_upload_expiry` (`upload_status`, `upload_expires_at`);
