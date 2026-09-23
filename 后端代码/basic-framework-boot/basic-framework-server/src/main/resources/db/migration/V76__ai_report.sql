-- R04：报表保存与版本存储。
-- 设计要点：
--   1) 报表（ai_report）是"可刷新定义 + 归属 + 模式"的聚合；版本（ai_report_version）是不可变快照：
--      每次保存/对话修改都新增版本，历史版本永不修改（AT-045 的可追溯性靠它）；
--   2) 模式（SNAPSHOT/REFRESHABLE）：快照版保存"数据截至时间"，可刷新版保存"原配置"，
--      刷新按当前用户权限重新执行（AT-048：失权后快照与刷新都按当前 ACL 处理）；
--   3) 归属（应用 + 主体类型 + 外部用户标识）与 A03 的 scope 指纹逐版本记录：
--      读取时若当前范围无法证明覆盖原范围（指纹不一致）则拒绝显示（第 4 步）；
--   4) 并发修改用乐观锁（version 列 + CAS），冲突返回 409（AT-046）。

CREATE TABLE `ai_report`
(
    `id`                   bigint       NOT NULL AUTO_INCREMENT COMMENT '报表编号',
    `code`                 varchar(64)  NOT NULL COMMENT '报表标识（创建后不可修改）',
    `name`                 varchar(128) NOT NULL COMMENT '报表名称',
    `description`          varchar(512) DEFAULT '' COMMENT '说明',
    `application_id`       bigint       NOT NULL COMMENT '所属应用编号（归属之一）',
    `subject_type`         varchar(16)  NOT NULL COMMENT '主体类型（APP/USER）',
    `external_user_id`     varchar(128) NOT NULL DEFAULT '' COMMENT '外部用户标识（私人报表的所有者）',
    `mode`                 varchar(16)  NOT NULL COMMENT '模式（SNAPSHOT 快照/REFRESHABLE 可刷新）',
    `service_id`           bigint       DEFAULT NULL COMMENT '来源服务编号（可空：手工创建的报表）',
    `release_id`           bigint       DEFAULT NULL COMMENT '来源服务发布版本编号（可空）',
    `theme_id`             varchar(40)  DEFAULT NULL COMMENT '主题标识（记录保存时的主题）',
    `theme_revision`       int          DEFAULT NULL COMMENT '主题修订号',
    `schema_version`       varchar(16)  NOT NULL DEFAULT '1.0' COMMENT 'ReportSpec 契约版本（旧版本加载时判定兼容性）',
    `latest_version_no`    int          NOT NULL DEFAULT 0 COMMENT '最新版本号（0 表示尚无版本）',
    `published_version_no` int          NOT NULL DEFAULT 0 COMMENT '当前生效版本号（0 表示未生效）',
    `version`              int          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`              varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`          datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`              varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`          datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`              bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_report_code` ((if(`deleted` = b'1', NULL, concat(`application_id`, ':', `code`)))),
    KEY `idx_ai_report_owner` (`application_id`, `subject_type`, `external_user_id`, `id`),
    CONSTRAINT `fk_ai_report_application` FOREIGN KEY (`application_id`) REFERENCES `ai_application` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 报表（可刷新定义 + 私有归属，R04）';

CREATE TABLE `ai_report_version`
(
    `id`                 bigint        NOT NULL AUTO_INCREMENT COMMENT '版本编号',
    `report_id`          bigint        NOT NULL COMMENT '报表编号',
    `version_no`         int           NOT NULL COMMENT '版本号（报表内递增，发布后不可变）',
    `mode`               varchar(16)   NOT NULL COMMENT '模式（SNAPSHOT/REFRESHABLE）',
    `spec_json`          mediumtext    NOT NULL COMMENT 'ReportSpec（已校验；长度受 VO 上限约束）',
    `data_json`          mediumtext    DEFAULT NULL COMMENT '快照数据（SNAPSHOT 模式；可刷新模式为空）',
    `sources_json`       text          DEFAULT NULL COMMENT '来源与资源依赖（datasetRef/queryRef/resultRef/行数/完整性）',
    `scope_refs_json`    text          DEFAULT NULL COMMENT '逐项资源依赖的 A03 范围指纹（读取时逐项复核）',
    `scope_fingerprint`  varchar(128)  NOT NULL COMMENT '保存时的授权范围指纹（读取时比对，不一致即拒绝显示）',
    `as_of`              datetime      DEFAULT NULL COMMENT '数据截至时间（快照模式）',
    `completeness`       varchar(16)   DEFAULT NULL COMMENT '数据完整性（COMPLETE/PARTIAL/FAILED）',
    `created_by_run`     varchar(128)  DEFAULT NULL COMMENT '来源运行标识（可追溯）',
    `version`            int           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`            varchar(64)   DEFAULT '' COMMENT '创建者',
    `create_time`        datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`            varchar(64)   DEFAULT '' COMMENT '更新者',
    `update_time`        datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`            bit(1)        NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_report_version_no` ((if(`deleted` = b'1', NULL, concat(`report_id`, ':', `version_no`)))),
    KEY `idx_ai_report_version_report` (`report_id`, `id`),
    CONSTRAINT `fk_ai_report_version_report` FOREIGN KEY (`report_id`) REFERENCES `ai_report` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 报表版本（不可变快照，R04）';
