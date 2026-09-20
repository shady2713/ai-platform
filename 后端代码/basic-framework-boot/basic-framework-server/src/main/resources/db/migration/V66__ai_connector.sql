-- D01：连接器配置与秘密管理。
-- 设计要点：
--   1) 连接器配置是**声明式结构化字段**（HTTP：baseUrl/method/healthPath/authType；MYSQL：host/port/database/username/sslMode），
--      不接受整段连接串：连接串参数（allowLoadLocalInfile、autoDeserialize 等）因此无法从外部注入；
--   2) 秘密（HTTP Bearer/Basic 凭据、MySQL 密码）经 CredentialCipher 加密后只存密文，响应只回"是否已配置"，
--      轮换只递增 credential_revision，历史版本不含秘密；
--   3) 探测（连接测试）走平台统一出站策略：HTTP 经 ExternalHttpClient（默认拒绝一切目标），
--      MySQL 只允许连接声明式字段拼出的地址；探测结论只记录稳定原因码，不记录凭据、主机或异常正文；
--   4) 引用保护：被数据集/工具引用的连接器不能删除（referenced 标记 + 引用检查端口）。

CREATE TABLE `ai_connector`
(
    `id`                    bigint        NOT NULL AUTO_INCREMENT COMMENT '连接器编号',
    `code`                  varchar(64)   NOT NULL COMMENT '连接器标识（全局唯一，创建后不可修改）',
    `name`                  varchar(128)  NOT NULL COMMENT '连接器名称',
    `connector_type`        varchar(16)   NOT NULL COMMENT '类型（HTTP/MYSQL）',
    `status`                varchar(16)   NOT NULL DEFAULT 'ENABLED' COMMENT '状态（ENABLED/DISABLED）',
    `config_json`           varchar(2000) NOT NULL COMMENT '声明式配置（结构化字段，不含秘密）',
    `credential_ciphertext` varchar(1024) DEFAULT NULL COMMENT '秘密密文（AES-GCM，含版本前缀；查询接口永不返回）',
    `credential_revision`   int           NOT NULL DEFAULT 0 COMMENT '秘密版本（0 表示未配置）',
    `referenced`            bit(1)        NOT NULL DEFAULT b'0' COMMENT '是否被数据集/工具引用（引用后不可删除）',
    `version`               int           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`               varchar(64)   DEFAULT '' COMMENT '创建者',
    `create_time`           datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`               varchar(64)   DEFAULT '' COMMENT '更新者',
    `update_time`           datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`               bit(1)        NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    -- 唯一键只约束"未删除行"（MySQL 8 函数索引 + 唯一索引允许多个 NULL）：
    -- 已删除行映射为 NULL，因此同一 code 可以被反复创建与删除，不会与历史已删除行冲突
    UNIQUE KEY `uk_ai_connector_code` ((if(`deleted` = b'1', NULL, `code`))),
    KEY `idx_ai_connector_type` (`connector_type`, `status`, `id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 连接器配置（声明式字段 + 加密秘密，D01）';

CREATE TABLE `ai_connector_probe`
(
    `id`           bigint      NOT NULL AUTO_INCREMENT COMMENT '探测记录编号',
    `connector_id` bigint      NOT NULL COMMENT '连接器编号',
    `probe_kind`   varchar(32) NOT NULL COMMENT '探测类型（HTTP_CONNECTIVITY/MYSQL_CONNECTIVITY）',
    `status`       varchar(16) NOT NULL COMMENT '结论（SUPPORTED/FAILED）',
    `detail_code`  varchar(64) DEFAULT NULL COMMENT '失败原因码（稳定词表；成功为空）',
    `latency_ms`   int         NOT NULL DEFAULT 0 COMMENT '耗时（毫秒）',
    `version`      int         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`      varchar(64) DEFAULT '' COMMENT '创建者',
    `create_time`  datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`      varchar(64) DEFAULT '' COMMENT '更新者',
    `update_time`  datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`      bit(1)      NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    KEY `idx_ai_connector_probe` (`connector_id`, `id`),
    CONSTRAINT `fk_ai_connector_probe_connector` FOREIGN KEY (`connector_id`) REFERENCES `ai_connector` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 连接器探测结论（只记稳定原因码，D01）';

-- AI 连接器菜单与权限点（4050-4054，挂在 4000 AI 中台下）
INSERT INTO `system_menu`
(`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
 `icon`, `component`, `component_name`, `status`, `visible`,
 `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (4050, 'AI 连接器', 'ai:connector:query', 2, 6, 4000, 'connector', 'ep:connection',
        'ai/connector/index', 'AiConnector', 0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1',
        CURRENT_TIMESTAMP, b'0'),
       (4051, '连接器新增', 'ai:connector:create', 3, 1, 4050, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4052, '连接器修改', 'ai:connector:update', 3, 2, 4050, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4053, '连接器删除', 'ai:connector:delete', 3, 3, 4050, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4054, '连接测试', 'ai:connector:probe', 3, 4, 4050, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');
