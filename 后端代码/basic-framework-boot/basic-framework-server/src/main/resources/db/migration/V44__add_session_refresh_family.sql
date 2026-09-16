-- 稳定撤销凭据仅保存摘要，保证刷新提交后的旧 Cookie 仍能撤销同一会话。
-- 旧会话在首次刷新时升级，无需使存量登录强制退出。
ALTER TABLE `system_user_session`
    ADD COLUMN `refresh_family_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL
        COMMENT '刷新会话稳定撤销凭据 SHA-256 摘要' AFTER `refresh_token_hash`,
    ADD UNIQUE KEY `uk_user_session_refresh_family_hash` (`refresh_family_hash`);
