ALTER TABLE `system_sms_channel`
    MODIFY COLUMN `api_key` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL
        COMMENT '短信 API 账号的版本化 AES-GCM 密文';
