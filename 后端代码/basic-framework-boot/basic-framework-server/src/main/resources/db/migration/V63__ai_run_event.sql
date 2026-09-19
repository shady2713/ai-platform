-- O05：SSE 事件、重放与取消。
-- 设计要点：
--   1) 事件序号（seq）由**运行行**并发安全分配：`UPDATE ai_run SET event_seq = event_seq + 1` 在行锁内
--      完成，再回读得到本次序号；同一运行的事件序号严格递增、无空洞重排；
--   2) 事件与运行状态在同一事务内提交：订阅者看到的"状态变化"与"事件"永远一致，
--      不会出现"事件说成功、库里还是 RUNNING"的错位；
--   3) 重放按 seq 推进（afterSeq），窗口外的请求返回明确错误并引导读取运行快照，
--      绝不用"新 POST 偷偷重跑"来掩盖丢失的事件；
--   4) 心跳是 SSE 注释（`:` 行），不写入事件表、不推进 seq：连接保活不等于运行有进展；
--   5) 事件正文只保存结果块（受控结构），提示词与模型输入正文不入事件表。

ALTER TABLE `ai_run`
    ADD COLUMN `event_seq` int NOT NULL DEFAULT 0 COMMENT '已分配的事件序号（行锁内递增，O05）' AFTER `step_count`;

CREATE TABLE `ai_run_event`
(
    `id`          bigint      NOT NULL AUTO_INCREMENT COMMENT '事件编号',
    `run_id`      bigint      NOT NULL COMMENT '运行编号',
    `seq`         int         NOT NULL COMMENT '运行内事件序号（从 1 递增，由运行行分配）',
    `status`      varchar(24) NOT NULL COMMENT '事件状态（QUEUED/RUNNING/WAITING_INPUT/WAITING_CONFIRMATION/SUCCEEDED/FAILED/CANCELLED）',
    `block_type`  varchar(32) DEFAULT NULL COMMENT '结果块类型（受控结构；无块时为空）',
    `block_json`  text COMMENT '结果块正文（受控结构；不含提示词与模型输入正文）',
    `schema_version` varchar(8) NOT NULL DEFAULT '1.0' COMMENT '事件契约版本（RunEvent v1）',
    `version`     int         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`     varchar(64) DEFAULT '' COMMENT '创建者',
    `create_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`     varchar(64) DEFAULT '' COMMENT '更新者',
    `update_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     bit(1)      NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_run_event_seq` (`run_id`, `seq`, `deleted`),
    KEY `idx_ai_run_event_created` (`create_time`, `id`),
    CONSTRAINT `fk_ai_run_event_run` FOREIGN KEY (`run_id`) REFERENCES `ai_run` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 运行事件（SSE 事件源，seq 由运行行分配，O05）';
