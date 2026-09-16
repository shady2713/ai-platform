-- 访问令牌与刷新令牌始终一对一，合并为单行会话并为刷新轮换提供原子更新条件。
CREATE TABLE `system_user_session` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '会话编号',
    `user_id` bigint NOT NULL COMMENT '用户编号',
    `user_type` tinyint NOT NULL COMMENT '用户类型',
    `user_info` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户信息',
    `access_token_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '访问令牌 SHA-256 摘要',
    `refresh_token_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '刷新令牌 SHA-256 摘要',
    `access_expires_time` datetime NOT NULL COMMENT '访问令牌过期时间',
    `refresh_expires_time` datetime NOT NULL COMMENT '刷新令牌绝对过期时间',
    `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_session_access_token_hash` (`access_token_hash`),
    UNIQUE KEY `uk_user_session_refresh_token_hash` (`refresh_token_hash`),
    KEY `idx_user_session_user` (`user_id`, `user_type`),
    KEY `idx_user_session_refresh_expires_time` (`refresh_expires_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='用户会话';

INSERT INTO `system_user_session` (
    `id`, `user_id`, `user_type`, `user_info`, `access_token_hash`, `refresh_token_hash`,
    `access_expires_time`, `refresh_expires_time`, `creator`, `create_time`, `updater`, `update_time`
)
SELECT access_token.`id`, access_token.`user_id`, access_token.`user_type`, access_token.`user_info`,
       access_token.`access_token_hash`, access_token.`refresh_token_hash`, access_token.`expires_time`,
       refresh_token.`expires_time`, access_token.`creator`, access_token.`create_time`,
       access_token.`updater`, access_token.`update_time`
FROM `system_oauth2_access_token` access_token
JOIN (
    SELECT `refresh_token_hash`, MAX(`id`) AS `latest_access_token_id`
    FROM `system_oauth2_access_token`
    GROUP BY `refresh_token_hash`
) latest ON latest.`latest_access_token_id` = access_token.`id`
JOIN `system_oauth2_refresh_token` refresh_token
  ON refresh_token.`refresh_token_hash` = access_token.`refresh_token_hash`;

DROP TABLE IF EXISTS `system_oauth2_access_token`;
DROP TABLE IF EXISTS `system_oauth2_refresh_token`;

INSERT INTO `system_menu` (
    `id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`,
    `status`, `visible`, `keep_alive`, `always_show`, `creator`, `updater`
) VALUES
    (3010, '用户会话', 'system:session:page', 2, 8, 1, 'session', 'ep:connection',
     'system/session/index', 'SystemSession', 0, b'1', b'1', b'1', '1', '1'),
    (3011, '会话查询', 'system:session:page', 3, 1, 3010, '', '', '', NULL, 0, b'1', b'1', b'1', '1', '1'),
    (3012, '会话撤销', 'system:session:revoke', 3, 2, 3010, '', '', '', NULL, 0, b'1', b'1', b'1', '1', '1');

INSERT INTO `system_role_menu` (`role_id`, `menu_id`, `creator`, `updater`)
SELECT 1, menu_id, '1', '1'
FROM (SELECT 3010 AS menu_id UNION ALL SELECT 3011 UNION ALL SELECT 3012) session_menus;
