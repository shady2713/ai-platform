-- Q02：用量账本与并发配额占位。
-- 设计要点（docs/ai-platform/05-data-security-contracts.md §2.5、AT-059/060）：
--   1) 账本按**一次真实上游调用**一条记录，`invocation_id` 唯一：重复写入同一调用不重复累计，
--      重试若真的再次请求模型则使用**新的 invocation_id**（自然另记一条）；
--   2) 计量来源必须如实标注：上游报告的用量 REPORTED、平台估算 ESTIMATED、两者都没有 UNKNOWN
--      （绝不把未知写成 0，AT-060）；
--   3) 并发配额用**带租约的占位**：占位有到期时间、可续租、终态释放；进程崩溃后租约到期即可恢复，
--      不会永久占位（AT-059）；
--   4) 两张表都只含计量元数据：不含提示词、响应正文、端点密钥（密钥不进入任何统计标签）。
-- 生命周期：追加保留（append-retention）——只追加、按保留任务分批物理清理，不做逻辑删除。

CREATE TABLE `ai_usage_ledger`
(
    `id`             bigint      NOT NULL AUTO_INCREMENT COMMENT '记录编号',
    `invocation_id`  varchar(64) NOT NULL COMMENT '调用标识（一次真实上游调用一个，重复写入去重）',
    `run_id`         bigint      DEFAULT NULL COMMENT '运行编号（与 task 二选一）',
    `task_id`        bigint      DEFAULT NULL COMMENT '任务编号（与 run 二选一）',
    `application_id` bigint      NOT NULL COMMENT '应用编号（按应用聚合的维度）',
    `service_id`     bigint      DEFAULT NULL COMMENT '服务编号（按服务聚合的维度）',
    `subject_ref`    varchar(128) DEFAULT NULL COMMENT '主体标识（应用编号 + 主体摘要；不含身份信息）',
    `model_ref`      varchar(128) NOT NULL COMMENT '模型标识（非秘密配置）',
    `model_revision` int          DEFAULT NULL COMMENT '模型配置修订号',
    `endpoint_ref`   varchar(64)  DEFAULT NULL COMMENT '端点引用（**编号/别名**，不是地址或密钥）',
    `input_tokens`   bigint       DEFAULT NULL COMMENT '输入 token（未知为空，不写 0）',
    `output_tokens`  bigint       DEFAULT NULL COMMENT '输出 token（未知为空，不写 0）',
    `usage_source`   varchar(16)  NOT NULL COMMENT '计量来源（REPORTED 上游报告/ESTIMATED 平台估算/UNKNOWN 未知）',
    `duration_ms`    int          DEFAULT NULL COMMENT '上游耗时（毫秒）',
    `status`         varchar(16)  NOT NULL COMMENT '调用结果（SUCCEEDED/FAILED/CANCELLED）',
    `occurred_at`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间（聚合维度）',
    `creator`        varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`        varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_usage_ledger_invocation` (`invocation_id`),
    KEY `idx_ai_usage_ledger_app_time` (`application_id`, `occurred_at`),
    KEY `idx_ai_usage_ledger_service_time` (`service_id`, `occurred_at`),
    KEY `idx_ai_usage_ledger_run` (`run_id`),
    KEY `idx_ai_usage_ledger_task` (`task_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 用量账本（一次上游调用一条，Q02）';

CREATE TABLE `ai_quota_lease`
(
    `id`            bigint      NOT NULL AUTO_INCREMENT COMMENT '占位编号',
    `lease_key`     varchar(128) NOT NULL COMMENT '占位键（应用:服务:调用标识，唯一）',
    `application_id` bigint     NOT NULL COMMENT '应用编号（并发限额的维度）',
    `service_id`    bigint      DEFAULT NULL COMMENT '服务编号（可空：按应用限额）',
    `invocation_id` varchar(64) NOT NULL COMMENT '调用标识（与账本同口径；重复占位不叠加）',
    `holder_ref`    varchar(128) DEFAULT NULL COMMENT '持有者（进程/实例标识，便于排查残留占位）',
    `state`         varchar(16) NOT NULL COMMENT '状态（ACTIVE 持有/RELEASED 已释放）',
    `lease_until`   datetime    NOT NULL COMMENT '租约到期时间（到期即可被清理并回收配额）',
    `version`       int         NOT NULL DEFAULT 0 COMMENT '乐观锁版本（续租/释放用 CAS）',
    `creator`       varchar(64) DEFAULT '' COMMENT '创建者',
    `create_time`   datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`       varchar(64) DEFAULT '' COMMENT '更新者',
    `update_time`   datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_quota_lease_key` (`lease_key`),
    KEY `idx_ai_quota_lease_active` (`application_id`, `state`, `lease_until`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 并发配额占位（带租约，Q02）';

-- 用量监控菜单与权限点（4115，挂在 4000 AI 中台下，页面由 Q03 交付）
INSERT INTO `system_menu`
(`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
 `icon`, `component`, `component_name`, `status`, `visible`,
 `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (4115, '用量与限额', 'ai:usage:query', 2, 14, 4000, 'usage', 'ep:data-line',
        'ai/usage/index', 'AiUsage', 0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1',
        CURRENT_TIMESTAMP, b'0');
