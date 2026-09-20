-- D02：声明式 HTTP 连接器与 OpenAPI 导入。
-- 设计要点：
--   1) 导入只接受**限定范围**：OpenAPI 文档的 paths 中的 GET/POST 操作，禁止脚本、禁止自动抓取外部 $ref，
--      导入结果是 DRAFT（草稿），必须显式发布后才可执行；
--   2) 操作是**声明式**的：方法、路径模板、参数映射、响应提取与分页终止规则都落库，
--      执行时不允许模型或调用方替换 header 与 URL——只允许按声明填入参数值；
--   3) 分页有界：页数上限固定，命中重复游标（或重复页）立即停止，达到页数上限时结论为 PARTIAL（不是成功全量）；
--   4) 请求只发往连接器固定的 Origin：解析出的 URL 必须与连接器 baseUrl 同源（SSRF 防线），
--      并统一经平台受控出站客户端（默认拒绝一切目标）。

CREATE TABLE `ai_connector_operation`
(
    `id`               bigint        NOT NULL AUTO_INCREMENT COMMENT '操作编号',
    `connector_id`     bigint        NOT NULL COMMENT '连接器编号',
    `operation_key`    varchar(128)  NOT NULL COMMENT '操作标识（OpenAPI operationId 或 method+path 派生）',
    `http_method`      varchar(8)    NOT NULL COMMENT 'HTTP 方法（GET/POST）',
    `path_template`    varchar(256)  NOT NULL COMMENT '路径模板（以 / 开头，占位符形如 {id}）',
    `summary`          varchar(256)  DEFAULT '' COMMENT '操作说明',
    `parameter_json`   varchar(2000) NOT NULL COMMENT '参数声明（名称/位置/是否必填/类型；不含脚本）',
    `response_json`    varchar(1000) NOT NULL COMMENT '响应提取规则（JSON 指针列表，限定深度与条数）',
    `pagination_json`  varchar(1000) NOT NULL COMMENT '分页规则（NONE/PAGE/CURSOR + 参数名 + 页数上限 + 游标字段）',
    `status`           varchar(16)   NOT NULL DEFAULT 'DRAFT' COMMENT '状态（DRAFT/PUBLISHED）',
    `version`          int           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`          varchar(64)   DEFAULT '' COMMENT '创建者',
    `create_time`      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`          varchar(64)   DEFAULT '' COMMENT '更新者',
    `update_time`      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          bit(1)        NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_connector_operation_key` ((if(`deleted` = b'1', NULL, concat(`connector_id`, ':', `operation_key`)))),
    KEY `idx_ai_connector_operation` (`connector_id`, `status`, `id`),
    CONSTRAINT `fk_ai_connector_operation_connector` FOREIGN KEY (`connector_id`) REFERENCES `ai_connector` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 连接器操作（声明式导入草稿与发布，D02）';

-- 导入与发布权限点（4055/4056，挂在 4050 连接器菜单下）
INSERT INTO `system_menu`
(`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
 `icon`, `component`, `component_name`, `status`, `visible`,
 `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (4055, '导入接口', 'ai:connector:import', 3, 5, 4050, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4056, '发布接口', 'ai:connector:operation', 3, 6, 4050, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');
