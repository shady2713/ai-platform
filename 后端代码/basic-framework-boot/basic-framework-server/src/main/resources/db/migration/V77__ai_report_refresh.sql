-- R06：报表刷新尝试与结果切换。
-- 设计要点：
--   1) 可刷新报表（REFRESHABLE）按**当前权限**重新执行固定查询版本：每次刷新落一条尝试记录
--      （ai_report_refresh），成功时的绑定数据存在尝试记录上（版本行沿用 R04 的口径：可刷新版本不存数据）；
--   2) 原子切换：先按当前范围校验来源可执行性并执行查询，全部成功后才用 R04 的乐观锁新增版本
--      （published_version_no 随之切换）；任一步失败都不新增版本，旧结果保持可读（AT-047）；
--   3) 失败也留痕：失败尝试记录稳定原因码与尝试时间，界面据此显示"上次刷新时间 + 失败原因"；
--   4) 读取旧结果同样按当前 ACL：尝试记录带逐项范围指纹，读取时逐项 reauthorizeHistorical 复核（AT-048）。

CREATE TABLE `ai_report_refresh`
(
    `id`                bigint       NOT NULL AUTO_INCREMENT COMMENT '刷新尝试编号',
    `report_id`         bigint       NOT NULL COMMENT '报表编号',
    `base_version_no`   int          NOT NULL COMMENT '本次刷新基于的版本号',
    `result_version_no` int          DEFAULT NULL COMMENT '本次刷新产生的版本号（OK 时存在）',
    `status`            varchar(16)  NOT NULL COMMENT '结果（OK 已刷新 / UNCHANGED 数据未变化 / FAILED 失败）',
    `reason`            varchar(128) DEFAULT NULL COMMENT '稳定原因码（失败为平台错误码；未变化为 UNCHANGED）',
    `as_of`             datetime     NOT NULL COMMENT '尝试时间（成功时即本次数据的截至时间）',
    `completeness`      varchar(16)  DEFAULT NULL COMMENT '数据完整性（COMPLETE/PARTIAL）',
    `data_json`         mediumtext   DEFAULT NULL COMMENT '成功刷新时的绑定数据（读取时按当前 ACL 复核）',
    `sources_json`      text         DEFAULT NULL COMMENT '本次刷新引用的资源依赖（A03 词表）',
    `scope_refs_json`   text         DEFAULT NULL COMMENT '逐项资源依赖的 A03 范围指纹（读取旧结果时复核）',
    `scope_fingerprint` varchar(128) NOT NULL COMMENT '本次刷新的授权范围指纹（读取旧结果时比对）',
    `version`           int          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`           varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`           varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    KEY `idx_ai_report_refresh_report` (`report_id`, `id`),
    KEY `idx_ai_report_refresh_status` (`status`, `as_of`),
    CONSTRAINT `fk_ai_report_refresh_report` FOREIGN KEY (`report_id`) REFERENCES `ai_report` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 报表刷新尝试（结果原子切换 + 失败留痕，R06）';

-- 刷新 Job：按尝试记录的节奏扫描"到期未刷新"的可刷新报表（handler 名与 bean 名一致）。
INSERT INTO `infra_job`
(`id`, `name`, `status`, `handler_name`, `handler_param`, `cron_expression`,
 `retry_count`, `retry_interval`, `monitor_timeout`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (37, 'AI 报表刷新 Job', 1, 'aiReportRefreshJob', '', '0 0/30 * * * ?', 0, 0, 0, '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');
