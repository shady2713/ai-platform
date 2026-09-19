-- O03：任务领取、租约心跳与可恢复调度。
-- 设计要点：
--   1) 领取是 CAS：任务行带 claimed_epoch（领取代次），worker 先读候选再以"期望代次"做条件更新，
--      同一时刻只有一个 worker 能把 QUEUED 改成 RUNNING 并拿到有效租约；
--   2) 续租与落库都携带 (lease_owner, claimed_epoch) 栅栏：租约过期后被别人重新领取时代次递增，
--      迟到的旧 worker 既不能续租也不能覆盖新 worker 的结果；
--   3) 租约过期即恢复：恢复任务扫描 lease_expires_time < NOW() 的行，
--      未达重试上限的回到 QUEUED 并进入重试等待，达到上限的置 FAILED（不做无限重试）；
--   4) 外部调用在短事务之外进行：领取、心跳、落库各自是独立短事务，网络调用不持有任何行锁。

ALTER TABLE `ai_run_task`
    ADD COLUMN `lease_owner` varchar(128) DEFAULT NULL COMMENT '租约持有者（worker 标识）' AFTER `payload_digest`,
    ADD COLUMN `lease_expires_time` datetime DEFAULT NULL COMMENT '租约到期时间' AFTER `lease_owner`,
    ADD COLUMN `heartbeat_time` datetime DEFAULT NULL COMMENT '最近一次心跳时间' AFTER `lease_expires_time`,
    ADD COLUMN `claimed_epoch` int NOT NULL DEFAULT 0 COMMENT '领取代次（续租与落库的栅栏）' AFTER `heartbeat_time`,
    ADD COLUMN `max_attempts` int NOT NULL DEFAULT 3 COMMENT '最大尝试次数（达到后置 FAILED，不再重试）' AFTER `claimed_epoch`,
    ADD COLUMN `last_error_code` varchar(64) DEFAULT NULL COMMENT '最近一次失败原因码（稳定词表）' AFTER `max_attempts`;

CREATE INDEX `idx_ai_run_task_lease` ON `ai_run_task` (`status`, `lease_expires_time`);

-- AI 任务恢复 Job（每分钟扫描过期租约；handler 名与 bean 名一致）
INSERT INTO `infra_job`
(`id`, `name`, `status`, `handler_name`, `handler_param`, `cron_expression`,
 `retry_count`, `retry_interval`, `monitor_timeout`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (32, 'AI 任务恢复 Job', 1, 'aiTaskRecoveryJob', '', '0 * * * * ?', 0, 0, 0, '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');
