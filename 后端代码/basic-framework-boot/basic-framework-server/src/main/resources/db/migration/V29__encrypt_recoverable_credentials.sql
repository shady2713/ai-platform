ALTER TABLE `system_sms_channel`
    MODIFY COLUMN `api_secret` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL
        COMMENT '短信 API 密钥的版本化 AES-GCM 密文';

ALTER TABLE `infra_file_config`
    MODIFY COLUMN `config` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL
        COMMENT '文件客户端配置的版本化 AES-GCM 密文';
