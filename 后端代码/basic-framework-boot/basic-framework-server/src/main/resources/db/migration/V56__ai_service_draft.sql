-- S01：AI 服务草稿与资源绑定。
-- 设计要点：
--   1) ai_service 持有**可编辑草稿**（模型端点、提示词、输入/输出 Schema、所需能力、运行主体类型），
--      乐观锁 version 保证并发编辑冲突返回 409；draft_revision 每次配置变更递增，供发布时固定；
--   2) ai_service_release 保存**不可变发布版本**（S02 写入），发布时把模型端点当前配置版本冻结，
--      避免"改了端点配置悄悄改变已发布服务"；
--   3) ai_service_resource 是服务与资源的绑定：release_id 为空表示草稿绑定，
--      发布时把草稿绑定复制/固化到该 release（S02）；绑定必须通过归属校验（见服务层）。

CREATE TABLE `ai_service`
(
    `id`                    bigint       NOT NULL AUTO_INCREMENT COMMENT '服务编号',
    `app_id`                bigint       NOT NULL COMMENT '所属应用编号（服务归属与绑定越权校验的基准）',
    `code`                  varchar(64)  NOT NULL COMMENT '服务标识（应用内唯一）',
    `name`                  varchar(128) NOT NULL COMMENT '服务名称',
    `description`           varchar(512) DEFAULT '' COMMENT '服务说明',
    `status`                varchar(16)  NOT NULL COMMENT '状态（DRAFT/READY/ARCHIVED）',
    `model_endpoint_id`     bigint       NOT NULL COMMENT '模型端点编号',
    `prompt_template`       text         NOT NULL COMMENT '提示词模板',
    `input_schema`          text         NOT NULL COMMENT '输入 JSON Schema（平台校验后注入）',
    `output_schema`         text         DEFAULT NULL COMMENT '输出 JSON Schema（结构化输出时必填）',
    `required_capabilities` varchar(128) NOT NULL COMMENT '所需能力（逗号分隔：TEXT,STRUCTURED_OUTPUT）',
    `run_subject_type`      varchar(16)  NOT NULL COMMENT '运行主体类型（APP/USER）',
    `draft_revision`        int          NOT NULL DEFAULT 1 COMMENT '草稿修订号：配置变更递增',
    `version`               int          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`               varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`           datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`               varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`           datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`               bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_service_code` (`app_id`, `code`, `deleted`),
    KEY `idx_ai_service_endpoint` (`model_endpoint_id`),
    CONSTRAINT `fk_ai_service_app` FOREIGN KEY (`app_id`) REFERENCES `ai_application` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 服务（草稿）';

CREATE TABLE `ai_service_release`
(
    `id`                    bigint      NOT NULL AUTO_INCREMENT COMMENT '发布版本编号',
    `service_id`            bigint      NOT NULL COMMENT '服务编号',
    `release_version`       int         NOT NULL COMMENT '发布版本号（从 1 递增，写入后不可变）',
    `model_endpoint_id`     bigint      NOT NULL COMMENT '发布时固定的模型端点编号',
    `endpoint_config_revision` int      NOT NULL COMMENT '发布时固定的端点配置版本',
    `prompt_template`       text        NOT NULL COMMENT '发布时固定的提示词模板',
    `input_schema`          text        NOT NULL COMMENT '发布时固定的输入 Schema',
    `output_schema`         text        DEFAULT NULL COMMENT '发布时固定的输出 Schema',
    `required_capabilities` varchar(128) NOT NULL COMMENT '发布时固定的能力集合',
    `content_hash`          char(64)    NOT NULL COMMENT '发布内容摘要（评测与回退的稳定标识）',
    `status`                varchar(16) NOT NULL COMMENT '状态（CANDIDATE/ACTIVE/RETIRED）',
    `version`               int         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`               varchar(64) DEFAULT '' COMMENT '创建者',
    `create_time`           datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`               varchar(64) DEFAULT '' COMMENT '更新者',
    `update_time`           datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`               bit(1)      NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_service_release_version` (`service_id`, `release_version`, `deleted`),
    CONSTRAINT `fk_ai_service_release_service` FOREIGN KEY (`service_id`) REFERENCES `ai_service` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 服务发布版本（不可变，S02）';

CREATE TABLE `ai_service_resource`
(
    `id`             bigint       NOT NULL AUTO_INCREMENT COMMENT '绑定编号',
    `service_id`     bigint       NOT NULL COMMENT '服务编号',
    `release_id`     bigint       DEFAULT NULL COMMENT '发布版本编号（空表示草稿绑定）',
    `resource_type`  varchar(32)  NOT NULL COMMENT '资源类型（REPORT/KNOWLEDGE_BASE/FILE/TOOL/DATASET）',
    `resource_key`   varchar(128) NOT NULL COMMENT '资源标识',
    `actions`        varchar(128) NOT NULL COMMENT '需要的动作（逗号分隔：READ,EXECUTE,EXPORT）',
    `status`         varchar(16)  NOT NULL COMMENT '状态（ACTIVE/RELEASED）',
    `version`        int          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`        varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`        varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_service_resource` (`service_id`, `resource_type`, `resource_key`, `release_id`, `deleted`),
    KEY `idx_ai_service_resource_draft` (`service_id`, `release_id`, `status`),
    CONSTRAINT `fk_ai_service_resource_service` FOREIGN KEY (`service_id`) REFERENCES `ai_service` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 服务资源绑定（草稿/发布版本）';

-- AI 服务菜单与权限点（菜单 id 取 4030 段）
INSERT INTO `system_menu`
(`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
 `icon`, `component`, `component_name`, `status`, `visible`,
 `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (4030, 'AI 服务', 'ai:service:query', 2, 4, 4000, 'service',
        'ep:document', 'ai/service/index', 'AiService', 0, b'1', b'1', b'1',
        '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4031, '服务新增', 'ai:service:create', 3, 1, 4030, '', '', '', NULL,
        0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4032, '服务修改', 'ai:service:update', 3, 2, 4030, '', '', '', NULL,
        0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4033, '服务删除', 'ai:service:delete', 3, 3, 4030, '', '', '', NULL,
        0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4034, '资源绑定', 'ai:service:bind', 3, 4, 4030, '', '', '', NULL,
        0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4035, '标记可发布', 'ai:service:publish', 3, 5, 4030, '', '', '', NULL,
        0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');
