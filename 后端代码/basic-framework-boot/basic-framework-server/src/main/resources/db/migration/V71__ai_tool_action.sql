-- D09：工具确认（action）与分析步骤调度。
-- 设计要点：
--   1) 需要确认的工具调用落成 **action**：参数在创建时被冻结（存参数规范化 JSON + 参数哈希），
--      确认时必须携带同一参数哈希与 challenge——改参数/换用户/过期一律不执行（AT-020/021）；
--      主体归属与 ai_conversation/ai_run 同一模型（应用 + 主体类型 + 外部用户标识）；
--   2) 确认是 CAS 状态机：PENDING → CONFIRMED → EXECUTED 只走一次，
--      重复确认与重复执行都被 CAS 挡住（不产生第二次副作用）；
--   3) 分析步骤按运行计数：ai_run.step_count 记录已用步数，步骤调度在取消/终态后拒绝继续（AT-016）。

CREATE TABLE `ai_tool_action`
(
    `id`               bigint        NOT NULL AUTO_INCREMENT COMMENT '动作编号',
    `run_id`           bigint        NOT NULL COMMENT '运行编号（动作属于某次运行）',
    `tool_id`          bigint        NOT NULL COMMENT '工具编号',
    `tool_version_id`  bigint        NOT NULL COMMENT '工具版本编号（政策与来源来自该快照）',
    `application_id`   bigint        NOT NULL COMMENT '所属应用编号（主体归属之一）',
    `subject_type`     varchar(16)   NOT NULL COMMENT '主体类型（APP/USER）',
    `external_user_id` varchar(128)  NOT NULL DEFAULT '' COMMENT '外部用户标识（确认必须由同一主体完成）',
    `policy`           varchar(16)   NOT NULL COMMENT '创建时的执行政策（确认时要求仍为 CONFIRM）',
    `arguments_hash`   char(64)      NOT NULL COMMENT '参数规范化哈希（确认时比对，改参数即拒绝）',
    `arguments_json`   varchar(4000) NOT NULL COMMENT '冻结的参数（确认后按原参数执行，不回显）',
    `challenge`        char(32)      NOT NULL COMMENT '一次性确认挑战（与动作绑定，确认时校验）',
    `status`           varchar(16)   NOT NULL DEFAULT 'PENDING' COMMENT '状态（PENDING/CONFIRMED/EXECUTED/CANCELLED/EXPIRED/FAILED）',
    `expires_at`       datetime      NOT NULL COMMENT '过期时间（过期后不可确认/执行）',
    `decided_at`       datetime      DEFAULT NULL COMMENT '确认/拒绝时间',
    `executed_at`      datetime      DEFAULT NULL COMMENT '执行时间',
    `result_code`      varchar(64)   DEFAULT NULL COMMENT '执行结论（稳定原因码，不含上游正文）',
    `version`          int           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`          varchar(64)   DEFAULT '' COMMENT '创建者',
    `create_time`      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`          varchar(64)   DEFAULT '' COMMENT '更新者',
    `update_time`      datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          bit(1)        NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    KEY `idx_ai_tool_action_run` (`run_id`, `status`, `id`),
    KEY `idx_ai_tool_action_subject` (`application_id`, `subject_type`, `external_user_id`, `id`),
    CONSTRAINT `fk_ai_tool_action_run` FOREIGN KEY (`run_id`) REFERENCES `ai_run` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_ai_tool_action_tool` FOREIGN KEY (`tool_id`) REFERENCES `ai_tool` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 工具动作（参数冻结 + 到期挑战 + 确认状态机，D09）';

-- 说明：运行步数复用 O04 在 V60 已建的 ai_run.step_count（本卡不重复建列，只做原子占用与预算判定）。
