-- C04：主题版本存储与发布。
-- 设计要点（docs/ai-platform/05-data-security-contracts.md §2.5 ai_theme、11 §4.5、04 §8.1）：
--   1) 一行 = 一个**主题修订**（application_id + revision 唯一）；发布版本不可修改，改主题必须新建修订；
--   2) tokens_json 只接受**已校验 token**（色值格式、半径取值域、字体白名单），
--      布局选项走 layout_json 的受控枚举/数值——不接受任意 CSS、url() 或表达式，主题不成为注入面；
--   3) publication_state 三态：DRAFT 草稿 / PUBLISHED 当前生效 / SUPERSEDED 被更新的修订取代；
--      同一应用同时最多一个 PUBLISHED（服务层同事务内校验，见 AiThemeServiceImpl）；
--   4) 运行时覆盖（宿主传入）**不写库**，也不影响其他应用：有效主题 = 平台默认 → 应用已发布修订 → 允许字段的运行时覆盖；
--   5) 配置类表使用逻辑删除（docs/data-lifecycle.md 的 AI 表分类），发布修订的不可变性由服务层保证。

CREATE TABLE `ai_theme`
(
    `id`                   bigint      NOT NULL AUTO_INCREMENT COMMENT '主题修订编号',
    `public_id`            varchar(40) NOT NULL COMMENT '主题对外标识（thm_ 前缀的不透明字符串）',
    `application_id`       bigint      NOT NULL COMMENT '所属应用编号',
    `revision`             int         NOT NULL COMMENT '修订号（应用内递增，发布后不可修改）',
    `tokens_json`          text        NOT NULL COMMENT 'ThemeTokens v1（已校验：色值/半径/字体白名单/深浅色）',
    `layout_json`          text        NOT NULL COMMENT '布局与排版受控选项（fontScale/density/narrowBreakpoint/minSidebarWidth）',
    `tokens_fingerprint`   varchar(64) NOT NULL COMMENT 'tokens+layout 的摘要（同修订内容比对与审计；不含任何凭据）',
    `publication_state`    varchar(16) NOT NULL COMMENT '发布状态（DRAFT 草稿/PUBLISHED 当前生效/SUPERSEDED 已被取代）',
    `published_time`       datetime    DEFAULT NULL COMMENT '发布时间（首次发布时写入；回退会重新指向历史修订）',
    `version`              int         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`              varchar(64) DEFAULT '' COMMENT '创建者',
    `create_time`          datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`              varchar(64) DEFAULT '' COMMENT '更新者',
    `update_time`          datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`              bit(1)      NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_theme_public_id` ((if(`deleted` = b'1', NULL, `public_id`))),
    UNIQUE KEY `uk_ai_theme_revision` ((if(`deleted` = b'1', NULL, concat(`application_id`, ':', `revision`)))),
    -- 同一应用同时最多一个生效修订：由数据库唯一性承担并发保障（不是只在服务层比对）
    UNIQUE KEY `uk_ai_theme_published` ((if(`publication_state` = 'PUBLISHED', `application_id`, NULL))),
    KEY `idx_ai_theme_application` (`application_id`, `revision`),
    CONSTRAINT `fk_ai_theme_application` FOREIGN KEY (`application_id`) REFERENCES `ai_application` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 主题修订（控制面配置，C04）';

-- 主题管理菜单与权限点（4105-4107，挂在 4000 AI 中台下）。
-- 说明：页面（apps/web-ele 的 ai/theme）由 C09 交付；本卡先落地接口鉴权码与目录登记，
-- 与 V69（查询计划）同一口径：权限码是接口鉴权的唯一入口，页面到齐后即用。
INSERT INTO `system_menu`
(`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
 `icon`, `component`, `component_name`, `status`, `visible`,
 `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (4105, 'AI 主题', 'ai:theme:query', 2, 12, 4000, 'theme', 'ep:brush',
        'ai/theme/index', 'AiTheme', 0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1',
        CURRENT_TIMESTAMP, b'0'),
       (4106, '创建主题修订', 'ai:theme:create', 3, 1, 4105, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4107, '发布/回退主题修订', 'ai:theme:publish', 3, 2, 4105, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');
