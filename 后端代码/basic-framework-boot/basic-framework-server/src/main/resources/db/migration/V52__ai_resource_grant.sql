-- A03：资源授权（grant 目录 + 授权 revision）。
-- 设计要点：
--   1) grant 目录以 (application_id, subject_type, external_user_id, resource_type, resource_key) 为唯一键：
--      同一资源标识在不同类型/不同主体下互不串权；
--   2) actions 是逗号分隔的**动作白名单**（READ/EXECUTE/EXPORT），授权判定必须命中白名单，未列入即拒绝；
--   3) authz_revision 在应用停用、主体撤销、授权变更时递增；判定不使用跨请求缓存，
--      旧 revision 的判定结果不得复用（防"撤销后仍按旧授权放行"）；
--   4) 乐观锁使用 version 列手工 CAS。

CREATE TABLE `ai_resource_grant`
(
    `id`               bigint       NOT NULL AUTO_INCREMENT COMMENT '授权编号',
    `application_id`   bigint       NOT NULL COMMENT '应用编号',
    `subject_type`     varchar(16)  NOT NULL COMMENT '主体类型（APP/USER）',
    `external_user_id` varchar(128) NOT NULL DEFAULT '' COMMENT '外部用户标识（APP 主体为空串）',
    `resource_type`    varchar(32)  NOT NULL COMMENT '资源类型（REPORT/KNOWLEDGE_BASE/FILE/TOOL/DATASET）',
    `resource_key`     varchar(128) NOT NULL COMMENT '资源标识（同类型内唯一；不同类型同 ID 不串权）',
    `actions`          varchar(128) NOT NULL COMMENT '动作白名单（逗号分隔：READ,EXECUTE,EXPORT）',
    `status`           varchar(16)  NOT NULL COMMENT '状态（ACTIVE/REVOKED）',
    `authz_revision`   bigint       NOT NULL DEFAULT 1 COMMENT '授权版本：授权或撤销时递增',
    `version`          int          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`          varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`          varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_resource_grant` (`application_id`, `subject_type`, `external_user_id`, `resource_type`, `resource_key`, `deleted`),
    KEY `idx_ai_resource_grant_lookup` (`application_id`, `subject_type`, `external_user_id`, `resource_type`),
    CONSTRAINT `fk_ai_resource_grant_application` FOREIGN KEY (`application_id`) REFERENCES `ai_application` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 资源授权目录';

-- 资源授权菜单与权限点（菜单 id 取 4020 段）
INSERT INTO `system_menu`
(`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
 `icon`, `component`, `component_name`, `status`, `visible`,
 `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (4020, '资源授权', 'ai:grant:query', 2, 3, 4000, 'grant',
        'ep:lock', 'ai/grant/index', 'AiResourceGrant', 0, b'1', b'1', b'1',
        '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4021, '授权新增', 'ai:grant:create', 3, 1, 4020, '', '', '', NULL,
        0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4022, '授权修改', 'ai:grant:update', 3, 2, 4020, '', '', '', NULL,
        0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4023, '授权撤销', 'ai:grant:revoke', 3, 3, 4020, '', '', '', NULL,
        0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');
