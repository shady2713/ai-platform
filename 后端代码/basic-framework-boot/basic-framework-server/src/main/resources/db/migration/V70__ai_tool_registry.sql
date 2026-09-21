-- D08：工具注册与执行政策。
-- 设计要点：
--   1) 工具是**版本化**的：政策（AUTO/CONFIRM/DENY）、输入/输出 schema 与来源绑定都属于版本快照，
--      发布后不可修改——政策不能"悄悄"从 DENY 变成 AUTO；
--   2) 首期只允许发布**读工具**（READ）：写操作能力先不开放（宁可没有，也不要一个能改数据的通道）；
--   3) 政策默认 DENY：新工具/新版本在显式改成 AUTO/CONFIRM 之前不可执行；
--   4) 执行时把模型 tool-call 映射到**已发布版本**并重新校验参数（未声明参数一律拒绝），
--      请求头/URL/方法/政策都不来自调用方。

CREATE TABLE `ai_tool`
(
    `id`                bigint       NOT NULL AUTO_INCREMENT COMMENT '工具编号',
    `code`              varchar(64)  NOT NULL COMMENT '工具标识（全局唯一且创建后不可修改）',
    `name`              varchar(128) NOT NULL COMMENT '工具名称',
    `description`       varchar(512) DEFAULT '' COMMENT '说明（供模型理解用途）',
    `connector_id`      bigint       NOT NULL COMMENT '连接器编号（工具来源）',
    `status`            varchar(16)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态（ENABLED/DISABLED）',
    `latest_version_no` int          NOT NULL DEFAULT 0 COMMENT '最新版本号（0 表示尚无版本）',
    `version`           int          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`           varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`           varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_tool_code` ((if(`deleted` = b'1', NULL, `code`))),
    KEY `idx_ai_tool_connector` (`connector_id`, `status`, `id`),
    CONSTRAINT `fk_ai_tool_connector` FOREIGN KEY (`connector_id`) REFERENCES `ai_connector` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 工具注册（版本化执行政策的载体，D08）';

CREATE TABLE `ai_tool_version`
(
    `id`                 bigint        NOT NULL AUTO_INCREMENT COMMENT '版本编号',
    `tool_id`            bigint        NOT NULL COMMENT '工具编号',
    `version_no`         int           NOT NULL COMMENT '版本号（工具内递增，发布后不可变）',
    `status`             varchar(16)   NOT NULL DEFAULT 'DRAFT' COMMENT '状态（DRAFT/PUBLISHED）',
    `tool_type`          varchar(16)   NOT NULL DEFAULT 'READ' COMMENT '类型（READ/WRITE）；首期只允许发布 READ',
    `policy`             varchar(16)   NOT NULL DEFAULT 'DENY' COMMENT '执行政策（AUTO/CONFIRM/DENY），默认 DENY',
    `source_kind`        varchar(24)   NOT NULL COMMENT '来源类型（HTTP_OPERATION）',
    `source_ref`         varchar(128)  NOT NULL COMMENT '来源标识（operationKey）',
    `input_schema_json`  varchar(4000) NOT NULL COMMENT '输入 schema（声明参数名/类型/必填）',
    `output_schema_json` varchar(4000) NOT NULL COMMENT '输出 schema（结果列声明，供结果归一与展示）',
    `schema_hash`        char(64)      NOT NULL COMMENT '版本内容哈希（政策+schema+来源）',
    `published_at`       datetime      DEFAULT NULL COMMENT '发布时间',
    `version`            int           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`            varchar(64)   DEFAULT '' COMMENT '创建者',
    `create_time`        datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`            varchar(64)   DEFAULT '' COMMENT '更新者',
    `update_time`        datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`            bit(1)        NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_tool_version_no` ((if(`deleted` = b'1', NULL, concat(`tool_id`, ':', `version_no`)))),
    KEY `idx_ai_tool_version` (`tool_id`, `status`, `id`),
    CONSTRAINT `fk_ai_tool_version_tool` FOREIGN KEY (`tool_id`) REFERENCES `ai_tool` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 工具版本（政策与输入输出 schema 的不可变快照，D08）';

-- AI 工具菜单与权限点（4080-4084，挂在 4000 AI 中台下）
INSERT INTO `system_menu`
(`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
 `icon`, `component`, `component_name`, `status`, `visible`,
 `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (4080, 'AI 工具', 'ai:tool:query', 2, 9, 4000, 'tool', 'ep:tools',
        'ai/tool/index', 'AiTool', 0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1',
        CURRENT_TIMESTAMP, b'0'),
       (4081, '工具新增', 'ai:tool:create', 3, 1, 4080, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4082, '工具修改', 'ai:tool:update', 3, 2, 4080, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4083, '工具删除', 'ai:tool:delete', 3, 3, 4080, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4084, '工具版本管理', 'ai:tool:version', 3, 4, 4080, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');
