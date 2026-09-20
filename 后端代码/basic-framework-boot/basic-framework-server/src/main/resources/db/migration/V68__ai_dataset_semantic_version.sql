-- D04：数据集语义版本管理。
-- 设计要点：
--   1) 数据集只描述"来源对象 + 语义定义"，语义定义是**不可变版本**（ai_dataset_version）：
--      字段/指标/维度/枚举/时间/单位/粒度与逐字段权限策略都进定义，版本发布后不得修改；
--   2) schemaHash 是定义内容的稳定哈希，用于去重、漂移判定与引用追踪；
--   3) 结构漂移（上游增删列或类型变化）把版本置为 DRIFTED（待验证），
--      发布前必须重新验证"定义里的每个列在上游仍存在且类型兼容"，验证结论落 source_schema_hash；
--   4) 旧报表按版本编号引用，版本行永不物理删除（软删除 + 引用保护），保证可追溯。

CREATE TABLE `ai_dataset`
(
    `id`                   bigint       NOT NULL AUTO_INCREMENT COMMENT '数据集编号',
    `code`                 varchar(64)  NOT NULL COMMENT '数据集标识（全局唯一且创建后不可修改）',
    `name`                 varchar(128) NOT NULL COMMENT '数据集名称',
    `description`          varchar(512) DEFAULT '' COMMENT '说明',
    `connector_id`         bigint       NOT NULL COMMENT '连接器编号（只读数据来源）',
    `source_object`        varchar(129) NOT NULL COMMENT '来源对象（schema.table，必须在该连接器授权白名单内）',
    `status`               varchar(16)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态（ENABLED/DISABLED）',
    `latest_version_no`    int          NOT NULL DEFAULT 0 COMMENT '最新语义版本号（0 表示尚无版本）',
    `published_version_no` int          NOT NULL DEFAULT 0 COMMENT '最近发布的语义版本号（0 表示未发布）',
    `version`              int          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`              varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`          datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`              varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`          datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`              bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_dataset_code` ((if(`deleted` = b'1', NULL, `code`))),
    KEY `idx_ai_dataset_connector` (`connector_id`, `status`, `id`),
    CONSTRAINT `fk_ai_dataset_connector` FOREIGN KEY (`connector_id`) REFERENCES `ai_connector` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 语义数据集（来源对象 + 语义版本，D04）';

CREATE TABLE `ai_dataset_version`
(
    `id`                  bigint        NOT NULL AUTO_INCREMENT COMMENT '版本编号',
    `dataset_id`          bigint        NOT NULL COMMENT '数据集编号',
    `version_no`          int           NOT NULL COMMENT '语义版本号（数据集内递增，发布后不可变）',
    `status`              varchar(16)   NOT NULL DEFAULT 'DRAFT' COMMENT '状态（DRAFT/PUBLISHED）',
    `definition_json`     varchar(8000) NOT NULL COMMENT '语义定义（粒度/时间/字段/指标/维度/单位/枚举/权限策略）',
    `schema_hash`         char(64)      NOT NULL COMMENT '定义内容哈希（去重、漂移判定与引用追踪）',
    `source_schema_hash`  char(64)      DEFAULT NULL COMMENT '验证通过时的上游结构哈希（漂移基线）',
    `verification_status` varchar(24)   NOT NULL DEFAULT 'UNVERIFIED' COMMENT '验证状态（UNVERIFIED/VERIFIED/DRIFTED）',
    `drift_json`          varchar(2000) DEFAULT NULL COMMENT '最近一次漂移结论（新增/缺失/类型变化列）',
    `verified_at`         datetime      DEFAULT NULL COMMENT '最近一次验证时间',
    `published_at`        datetime      DEFAULT NULL COMMENT '发布时间',
    `version`             int           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`             varchar(64)   DEFAULT '' COMMENT '创建者',
    `create_time`         datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`             varchar(64)   DEFAULT '' COMMENT '更新者',
    `update_time`         datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`             bit(1)        NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_dataset_version_no` ((if(`deleted` = b'1', NULL, concat(`dataset_id`, ':', `version_no`)))),
    KEY `idx_ai_dataset_version` (`dataset_id`, `status`, `id`),
    CONSTRAINT `fk_ai_dataset_version_dataset` FOREIGN KEY (`dataset_id`) REFERENCES `ai_dataset` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 语义数据集版本（不可变语义快照，D04）';

-- AI 数据集菜单与权限点（4060-4066，挂在 4000 AI 中台下）
INSERT INTO `system_menu`
(`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
 `icon`, `component`, `component_name`, `status`, `visible`,
 `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (4060, 'AI 数据集', 'ai:dataset:query', 2, 7, 4000, 'dataset', 'ep:data-analysis',
        'ai/dataset/index', 'AiDataset', 0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1',
        CURRENT_TIMESTAMP, b'0'),
       (4061, '数据集新增', 'ai:dataset:create', 3, 1, 4060, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4062, '数据集修改', 'ai:dataset:update', 3, 2, 4060, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4063, '数据集删除', 'ai:dataset:delete', 3, 3, 4060, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4064, '创建语义版本', 'ai:dataset:version:create', 3, 4, 4060, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4065, '验证语义版本', 'ai:dataset:version:verify', 3, 5, 4060, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4066, '发布语义版本', 'ai:dataset:version:publish', 3, 6, 4060, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');
