-- M01：AI 模型端点持久化。
-- 设计要点：
--   1) 非秘密配置（model_id/能力）生成不可变 revision，历史保存在 ai_model_endpoint_revision；
--   2) 凭据只保存在端点行上（CredentialCipher 密文），轮换只递增 credential_revision，历史版本不含秘密；
--   3) provider/base_url 一旦被发布服务引用（referenced=1）不可原地修改，地址迁移必须新建端点；
--   4) 乐观锁使用 version 列手工 CAS（框架未启用 MyBatis-Plus 乐观锁插件）。

CREATE TABLE `ai_model_endpoint`
(
    `id`                    bigint       NOT NULL AUTO_INCREMENT COMMENT '端点编号',
    `name`                  varchar(128) NOT NULL COMMENT '端点名称',
    `provider`              varchar(32)  NOT NULL COMMENT '提供方标识（如 openai_compatible）',
    `base_url`              varchar(512) NOT NULL COMMENT '基础地址（https）',
    `config_revision`       int          NOT NULL DEFAULT 1 COMMENT '当前非秘密配置版本',
    `credential_revision`   int          NOT NULL DEFAULT 0 COMMENT '凭据版本（0 表示未配置；轮换只递增该值）',
    `credential_ciphertext` varchar(2048) DEFAULT NULL COMMENT '凭据密文（AES-GCM，含版本前缀）',
    `enabled`               bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否启用',
    `referenced`            bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否被发布服务引用（引用后 provider/base_url 不可原地修改）',
    `version`               int          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`               varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`           datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`               varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`           datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`               bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_model_endpoint_name` (`name`, `deleted`),
    KEY `idx_ai_model_endpoint_provider` (`provider`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 模型端点';

CREATE TABLE `ai_model_endpoint_revision`
(
    `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '版本编号',
    `endpoint_id` bigint       NOT NULL COMMENT '端点编号',
    `revision`    int          NOT NULL COMMENT '非秘密配置版本（从 1 递增，写入后不可变）',
    `model_id`    varchar(128) NOT NULL COMMENT '模型标识',
    `capabilities` varchar(128) NOT NULL COMMENT '能力集合（逗号分隔：TEXT,EMBEDDING）',
    `creator`     varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`     varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_endpoint_revision` (`endpoint_id`, `revision`),
    CONSTRAINT `fk_ai_model_endpoint_revision` FOREIGN KEY (`endpoint_id`) REFERENCES `ai_model_endpoint` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 模型端点配置版本（不可变）';

-- AI 中台菜单与权限点：权限码与 AiModelEndpointController 的 @PreAuthorize 一一对应。
-- 菜单 id 取 4000 段（现存菜单最大 3004，2026-09 核对）；type：1 目录 / 2 菜单 / 3 按钮。
INSERT INTO `system_menu`
(`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
 `icon`, `component`, `component_name`, `status`, `visible`,
 `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (4000, 'AI 中台', '', 1, 40, 0, '/ai', 'ep:cpu', '', NULL,
        0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4001, '模型端点', 'ai:model-endpoint:query', 2, 1, 4000, 'model-endpoint',
        'ep:connection', 'ai/model-endpoint/index', 'AiModelEndpoint', 0, b'1', b'1', b'1',
        '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4002, '端点新增', 'ai:model-endpoint:create', 3, 1, 4001, '', '', '', NULL,
        0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4003, '端点修改', 'ai:model-endpoint:update', 3, 2, 4001, '', '', '', NULL,
        0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4004, '端点删除', 'ai:model-endpoint:delete', 3, 3, 4001, '', '', '', NULL,
        0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');
