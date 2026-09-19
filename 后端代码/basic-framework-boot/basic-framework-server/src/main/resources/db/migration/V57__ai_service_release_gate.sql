-- S02：发布预检查与不可变发布版本。
-- 设计要点：
--   1) ai_service.eval_threshold 是草稿上的发布门槛（0-100），创建候选时冻结到
--      ai_service_release.eval_threshold，并计入内容摘要：改门槛必须新建候选并重新评测；
--   2) ai_service_release_evaluation 记录评测结论：结论绑定**内容摘要 + 端点配置版本**，
--      passed 由平台按冻结门槛判定（不由调用方提交），借旧报告或改门槛发布都会在发布预检查被拒绝；
--   3) 发布版本内容列（提示词/Schema/能力/端点/端点配置版本/门槛）写入后不可修改，
--      状态列只允许 CANDIDATE -> ACTIVE -> RETIRED 单向流转。

ALTER TABLE `ai_service`
    ADD COLUMN `eval_threshold` int NOT NULL DEFAULT 0 COMMENT '发布要求的评测得分门槛（0-100）' AFTER `run_subject_type`;

ALTER TABLE `ai_service_release`
    ADD COLUMN `eval_threshold` int NOT NULL DEFAULT 0 COMMENT '发布时冻结的评测得分门槛（0-100）' AFTER `required_capabilities`;

CREATE TABLE `ai_service_release_evaluation`
(
    `id`                       bigint       NOT NULL AUTO_INCREMENT COMMENT '评测记录编号',
    `service_id`               bigint       NOT NULL COMMENT '服务编号（冗余自发布版本，便于按服务追溯）',
    `release_id`               bigint       NOT NULL COMMENT '发布版本编号',
    `content_hash`             char(64)     NOT NULL COMMENT '被评测内容摘要：与发布版本内容摘要一致才是有效证据',
    `model_endpoint_id`        bigint       NOT NULL COMMENT '评测所用的模型端点编号',
    `endpoint_config_revision` int          NOT NULL COMMENT '评测所用的端点配置版本',
    `score`                    int          NOT NULL COMMENT '评测得分（0-100）',
    `threshold`                int          NOT NULL COMMENT '评测时冻结的门槛（得分 >= 门槛 才判定通过）',
    `passed`                   bit(1)       NOT NULL COMMENT '是否通过（平台按门槛判定，调用方不能直接提交结论）',
    `case_count`               int          NOT NULL COMMENT '评测用例数（至少 1）',
    `notes`                    varchar(512) DEFAULT '' COMMENT '备注（不得写入提示词、响应正文或凭据）',
    `version`                  int          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`                  varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`              datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`                  varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`              datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`                  bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    KEY `idx_ai_service_eval_release` (`release_id`, `id`),
    KEY `idx_ai_service_eval_service` (`service_id`, `id`),
    CONSTRAINT `fk_ai_service_eval_release` FOREIGN KEY (`release_id`) REFERENCES `ai_service_release` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_ai_service_eval_service` FOREIGN KEY (`service_id`) REFERENCES `ai_service` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 服务发布评测（绑定内容摘要与端点配置版本，S02）';

-- AI 服务发布菜单（菜单 id 取 4036-4038，挂在 4030 服务菜单下）
INSERT INTO `system_menu`
(`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
 `icon`, `component`, `component_name`, `status`, `visible`,
 `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (4036, '创建发布候选', 'ai:service:release', 3, 6, 4030, '', '', '', NULL,
        0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4037, '切换发布版本', 'ai:service:activate', 3, 7, 4030, '', '', '', NULL,
        0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4038, '记录评测结果', 'ai:service:evaluate', 3, 8, 4030, '', '', '', NULL,
        0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');
