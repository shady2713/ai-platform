-- A02：外部主体（APP/USER）与范围版本。
-- 设计要点：
--   1) 主体唯一键 = (application_id, subject_type, external_user_id)：同一 externalUserId 在不同应用下互不冲突；
--      APP 主体的 external_user_id 存空串，保证唯一键同样成立；
--   2) 中台不保存外部密码/令牌，只保存可信 externalUserId 与业务侧的范围来源（scope_source）与范围版本（scope_version）；
--      业务侧撤销通过 status=DISABLED 同步到中台，中台只做读取与判定；
--   3) 不存 roles/deptIds：范围由可信解析器（SubjectScopeResolver）在请求期解析，客户端提交的任何范围字段都不生效；
--   4) 乐观锁使用 version 列手工 CAS。

CREATE TABLE `ai_subject`
(
    `id`               bigint      NOT NULL AUTO_INCREMENT COMMENT '主体编号',
    `application_id`   bigint      NOT NULL COMMENT '所属应用编号',
    `subject_type`     varchar(16) NOT NULL COMMENT '主体类型（APP/USER）',
    `external_user_id` varchar(128) NOT NULL DEFAULT '' COMMENT '可信外部用户标识（APP 主体为空串）',
    `display_name`     varchar(128) DEFAULT '' COMMENT '外部主体显示名（仅用于管理端展示）',
    `status`           varchar(16) NOT NULL COMMENT '状态（ACTIVE/DISABLED）；业务侧撤销同步为 DISABLED',
    `scope_source`     varchar(64) NOT NULL COMMENT '外部授权来源标识（业务系统/授权服务名）',
    `scope_version`    bigint      NOT NULL DEFAULT 1 COMMENT '当前范围版本；范围变化必须递增',
    `version`          int         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`          varchar(64) DEFAULT '' COMMENT '创建者',
    `create_time`      datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`          varchar(64) DEFAULT '' COMMENT '更新者',
    `update_time`      datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          bit(1)      NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_subject_identity` (`application_id`, `subject_type`, `external_user_id`, `deleted`),
    KEY `idx_ai_subject_external_user` (`external_user_id`),
    CONSTRAINT `fk_ai_subject_application` FOREIGN KEY (`application_id`) REFERENCES `ai_application` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 外部主体（APP/USER）';
