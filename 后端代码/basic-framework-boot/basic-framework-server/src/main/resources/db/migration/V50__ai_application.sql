-- A01：AI 应用与客户端凭据。
-- 设计要点：
--   1) appCode 全局唯一（逻辑删除参与唯一键），创建后不可修改：对接方按 appCode 换票，改名会静默失效；
--   2) origins 保存**精确 Origin 列表**（JSON 数组文本，已归一化）：禁止路径、查询、通配与用户信息，
--      校验在服务层完成，非法来源在写入前拒绝；
--   3) 客户端秘密只保存 SHA-256 摘要（char(64)），明文只在创建/轮换的响应里出现一次，接口永不回显；
--   4) 凭据状态机 ACTIVE/REVOKED：默认无重叠——轮换先吊销旧凭据再发新凭据，吊销立即生效；
--   5) 乐观锁使用 version 列手工 CAS（与 ai_model_endpoint 一致的约定）。

CREATE TABLE `ai_application`
(
    `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '应用编号',
    `app_code`    varchar(64)  NOT NULL COMMENT '应用标识（全局唯一，创建后不可修改）',
    `name`        varchar(128) NOT NULL COMMENT '应用名称',
    `description` varchar(512) DEFAULT '' COMMENT '应用说明',
    `origins`     varchar(2048) NOT NULL COMMENT '精确 Origin 列表（JSON 数组文本，已归一化）',
    `enabled`     bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否启用',
    `version`     int          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`     varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`     varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_application_app_code` (`app_code`, `deleted`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 应用';

CREATE TABLE `ai_application_credential`
(
    `id`             bigint      NOT NULL AUTO_INCREMENT COMMENT '凭据编号',
    `application_id` bigint      NOT NULL COMMENT '应用编号',
    `secret_digest`  char(64)    NOT NULL COMMENT '客户端秘密的 SHA-256 摘要（十六进制），不保存明文',
    `status`         varchar(16) NOT NULL COMMENT '状态（ACTIVE/REVOKED）',
    `revoked_time`   datetime    DEFAULT NULL COMMENT '吊销时间（吊销立即生效）',
    `creator`        varchar(64) DEFAULT '' COMMENT '创建者',
    `create_time`    datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`        varchar(64) DEFAULT '' COMMENT '更新者',
    `update_time`    datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        bit(1)      NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_application_credential_digest` (`secret_digest`, `deleted`),
    KEY `idx_ai_application_credential_app` (`application_id`, `status`),
    CONSTRAINT `fk_ai_application_credential` FOREIGN KEY (`application_id`) REFERENCES `ai_application` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 应用客户端凭据（只存摘要）';

-- AI 应用菜单与权限点（菜单 id 取 4010 段；与 AiApplicationController 的 @PreAuthorize 一一对应）
INSERT INTO `system_menu`
(`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
 `icon`, `component`, `component_name`, `status`, `visible`,
 `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (4010, 'AI 应用', 'ai:application:query', 2, 2, 4000, 'application',
        'ep:key', 'ai/application/index', 'AiApplication', 0, b'1', b'1', b'1',
        '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4011, '应用新增', 'ai:application:create', 3, 1, 4010, '', '', '', NULL,
        0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4012, '应用修改', 'ai:application:update', 3, 2, 4010, '', '', '', NULL,
        0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4013, '应用删除', 'ai:application:delete', 3, 3, 4010, '', '', '', NULL,
        0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4014, '凭据轮换', 'ai:application:rotate', 3, 4, 4010, '', '', '', NULL,
        0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4015, '凭据吊销', 'ai:application:revoke', 3, 5, 4010, '', '', '', NULL,
        0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');
