-- O01：会话与消息存储。
-- 设计要点：
--   1) 会话归属由**服务端会话身份**（应用 + 主体类型 + 外部用户标识）决定，客户端不能自报归属；
--      越权访问与不存在同语义（404），防止用编号枚举他人会话；
--   2) 会话绑定服务与发布版本：首个 run 解析到 releaseId 后固定，后续消息沿用该版本（S03 的固定值语义）；
--      业务上下文按宿主传入的已注册字段保存，是否合规在运行时由上下文构造器（S04）判定；
--   3) 消息内容属于受控业务数据（L3）：只按主体过滤读取，不进日志；sequence_no 在会话内递增，
--      保证按序号分页稳定（不会因为并发写入而重复或跳过）；
--   4) 删除先关闭访问：status 置 DELETED 且消息行逻辑删除，读取立即 404；正文的物理清理与保留期
--      由 O06 的清理任务按批次处理（先关闭访问、后清理数据）。

CREATE TABLE `ai_conversation`
(
    `id`               bigint       NOT NULL AUTO_INCREMENT COMMENT '会话编号',
    `application_id`   bigint       NOT NULL COMMENT '应用编号',
    `subject_type`     varchar(16)  NOT NULL COMMENT '主体类型（APP/USER）',
    `external_user_id` varchar(64)  NOT NULL DEFAULT '' COMMENT '可信外部用户标识（APP 主体为空串）',
    `conversation_key` varchar(40)  NOT NULL COMMENT '会话业务键（conv_ 前缀，应用+主体内唯一）',
    `title`            varchar(128) NOT NULL DEFAULT '' COMMENT '会话标题',
    `service_id`       bigint       DEFAULT NULL COMMENT '绑定的 AI 服务编号',
    `release_id`       bigint       DEFAULT NULL COMMENT '固定的发布版本编号（首个 run 解析后写入）',
    `business_context` varchar(4000) NOT NULL DEFAULT '{}' COMMENT '业务上下文（已注册字段的 JSON 对象文本）',
    `message_count`    int          NOT NULL DEFAULT 0 COMMENT '消息条数（逻辑删除后递减）',
    `last_message_time` datetime    DEFAULT NULL COMMENT '最后一条消息时间',
    `status`           varchar(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态（ACTIVE/DELETED）',
    `version`          int          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`          varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`          varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_conversation_key` (`application_id`, `subject_type`, `external_user_id`, `conversation_key`, `deleted`),
    KEY `idx_ai_conversation_subject` (`application_id`, `subject_type`, `external_user_id`, `id`),
    KEY `idx_ai_conversation_service` (`service_id`, `id`),
    CONSTRAINT `fk_ai_conversation_app` FOREIGN KEY (`application_id`) REFERENCES `ai_application` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_ai_conversation_service` FOREIGN KEY (`service_id`) REFERENCES `ai_service` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_ai_conversation_release` FOREIGN KEY (`release_id`) REFERENCES `ai_service_release` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 会话（归属由服务端身份决定，绑定服务与发布版本，O01）';

CREATE TABLE `ai_conversation_message`
(
    `id`               bigint       NOT NULL AUTO_INCREMENT COMMENT '消息编号',
    `conversation_id`  bigint       NOT NULL COMMENT '会话编号',
    `application_id`   bigint       NOT NULL COMMENT '应用编号（冗余自会话，用于主体过滤）',
    `subject_type`     varchar(16)  NOT NULL COMMENT '主体类型（冗余自会话，用于主体过滤）',
    `external_user_id` varchar(64)  NOT NULL DEFAULT '' COMMENT '外部用户标识（冗余自会话，用于主体过滤）',
    `sequence_no`      int          NOT NULL COMMENT '会话内序号（从 1 递增，分页稳定）',
    `role`             varchar(16)  NOT NULL COMMENT '角色（user/assistant/system）',
    `content`          text         NOT NULL COMMENT '消息正文（受控业务数据，不进日志）',
    `content_hash`     char(64)     NOT NULL COMMENT '正文 SHA-256（审计与去重；摘要不等于正文）',
    `source_run_id`    bigint       DEFAULT NULL COMMENT '产生该消息的运行编号（O02 起写入）',
    `status`           varchar(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态（ACTIVE/DELETED）',
    `version`          int          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`          varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`          varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_conversation_message_seq` (`conversation_id`, `sequence_no`, `deleted`),
    KEY `idx_ai_conversation_message_subject` (`application_id`, `subject_type`, `external_user_id`, `conversation_id`, `id`),
    CONSTRAINT `fk_ai_conversation_message_conversation` FOREIGN KEY (`conversation_id`) REFERENCES `ai_conversation` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 会话消息（受控业务数据，按主体过滤，O01）';
