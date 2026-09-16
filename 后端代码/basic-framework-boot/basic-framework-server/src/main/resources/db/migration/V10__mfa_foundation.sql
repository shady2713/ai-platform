CREATE TABLE `system_user_mfa_factor` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `user_id` bigint NOT NULL,
    `factor_type` tinyint NOT NULL COMMENT '1 WebAuthn, 2 TOTP',
    `name` varchar(64) NOT NULL,
    `secret_ciphertext` varchar(512) DEFAULT NULL COMMENT 'AES-256-GCM ciphertext; TOTP only',
    `credential_id` varbinary(1024) DEFAULT NULL COMMENT 'WebAuthn credential id',
    `public_key_cose` blob DEFAULT NULL COMMENT 'WebAuthn COSE public key',
    `signature_count` bigint NOT NULL DEFAULT 0,
    `last_used_step` bigint DEFAULT NULL COMMENT 'last accepted TOTP time step',
    `transports` varchar(255) DEFAULT NULL COMMENT 'WebAuthn transports JSON',
    `enabled` bit(1) NOT NULL DEFAULT b'1',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_mfa_factor_user_type_name` (`user_id`, `factor_type`, `name`),
    UNIQUE KEY `uk_mfa_factor_credential_id` (`credential_id`),
    KEY `idx_mfa_factor_user_enabled` (`user_id`, `enabled`),
    CONSTRAINT `fk_mfa_factor_user` FOREIGN KEY (`user_id`) REFERENCES `system_users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='User MFA factors';

CREATE TABLE `system_user_mfa_recovery_code` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `user_id` bigint NOT NULL,
    `code_hash` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'HMAC-SHA256 digest',
    `used_time` datetime DEFAULT NULL,
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_mfa_recovery_user_hash` (`user_id`, `code_hash`),
    KEY `idx_mfa_recovery_user_unused` (`user_id`, `used_time`),
    CONSTRAINT `fk_mfa_recovery_user` FOREIGN KEY (`user_id`) REFERENCES `system_users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='One-time MFA recovery codes';
