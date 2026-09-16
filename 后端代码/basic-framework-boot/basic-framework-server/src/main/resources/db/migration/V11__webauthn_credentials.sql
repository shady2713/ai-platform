ALTER TABLE `system_user_mfa_factor`
    ADD COLUMN `user_handle` varbinary(64) DEFAULT NULL COMMENT 'Opaque WebAuthn user handle' AFTER `credential_id`,
    ADD COLUMN `backup_eligible` bit(1) DEFAULT NULL COMMENT 'WebAuthn backup eligibility' AFTER `signature_count`,
    ADD COLUMN `backup_state` bit(1) DEFAULT NULL COMMENT 'WebAuthn current backup state' AFTER `backup_eligible`,
    ADD KEY `idx_mfa_factor_user_handle` (`user_handle`);
