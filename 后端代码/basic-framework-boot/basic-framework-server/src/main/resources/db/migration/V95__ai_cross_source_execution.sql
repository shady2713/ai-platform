-- Y04：有界跨源查询执行与统一结果（FR-23/FR-26/FR-38，V2 跨系统链第四环，AT-070/AT-071）。
-- 设计要点：
--   1) **重试幂等必须是机制而不是约定**：跨源执行天然会重试（来源超时、连接抖动），
--      而"重试时小心点别重复加"不是机制。本卡把去重落到两张表上：
--      ai_cross_source_execution 是一次执行的锚点（execution_key 唯一 + plan_hash 冲突检测），
--      ai_cross_source_execution_source 是**每来源一行**的贡献台账，
--      唯一键 (execution_id, role, deleted) 让"一个来源最多贡献一次"成为数据库不变量；
--      合计由"已计入的行"求和得到（status='COUNTED'），因此重试覆盖而不叠加；
--   2) **各源一致性时间点必须显式**：source_as_of 是**该来源自己的**数据时间点，
--      跨源结果只能解释为所有来源都成立的那个时刻，即各源 as_of 的最小值
--      （execution.consistency_as_of）；max_skew_millis 让"数据时间差"可观测，
--      偏移超限时受控结束而不是假装同一时刻；
--   3) **缺失不等于 0**：可选来源缺失写 missing_roles 且状态为 MISSING，
--      执行状态为 PARTIAL 而不是 SUCCEEDED——"没有回款记录"与"回款金额是 0"必须能区分；
--   4) **受控结束**：预算超限、来源截断、必需来源失败、时间点偏移、容量超限
--      都有独立 failure_code（稳定错误码），不静默截断也不把半截结果当成完整结果；
--   5) 超容量的数仓接口**拒绝或转登记**（status=REGISTERED），不默认引入分布式查询集群。
--
-- 生命周期：两张表都是执行事实与审计留痕（soft-delete，与 ai_run / ai_run_idempotency 同口径）。
-- 来源贡献行随执行记录软删除，不做物理清理（重试幂等依赖唯一键在软删除语义下依然生效：
-- deleted 位参与唯一键，因此同一 (execution_id, role) 删除后可以重新登记，但已计入金额不会复活）。

DROP TABLE IF EXISTS `ai_cross_source_execution_source`;

DROP TABLE IF EXISTS `ai_cross_source_execution`;

CREATE TABLE `ai_cross_source_execution` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '跨源执行编号',
  `execution_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '执行幂等键（调用方提供；同一键只对应一份计划与一次合计）',
  `metric_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '跨源指标口径标识',
  `semantics_revision` int NOT NULL COMMENT '口径版本号（显式钉住；换版本不改旧结果）',
  `plan_hash` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '计划指纹（同一执行键提交不同计划即冲突）',
  `mapping_revision` bigint NOT NULL COMMENT '参与关联的实体键映射版本（各来源必须一致，跨版本拒绝关联）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'RUNNING' COMMENT '状态（RUNNING 执行中/SUCCEEDED 完整/PARTIAL 仅可选来源缺失/FAILED 受控结束/REGISTERED 超容量转登记）',
  `total_amount` decimal(24,6) DEFAULT NULL COMMENT '跨源合计金额（由已计入来源行求和，不接受调用方直接写入）',
  `currency` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'NONE' COMMENT '结果币种（与口径一致；多币种无换算规则在 Y03 上游阻断）',
  `consistency_as_of` datetime DEFAULT NULL COMMENT '一致性时间点：各来源数据时间的最小值（唯一"所有来源都成立"的时刻）',
  `max_skew_millis` bigint NOT NULL DEFAULT '0' COMMENT '各来源数据时间点最大偏移（毫秒；让数据时间差可观测）',
  `missing_roles` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '缺失的来源角色（JSON 数组；显式缺失而不是按 0 补齐）',
  `total_bytes` bigint NOT NULL DEFAULT '0' COMMENT '中间结果总字节（预算用量证据）',
  `total_rows` int NOT NULL DEFAULT '0' COMMENT '中间结果总行数（预算用量证据）',
  `concurrent_peak` int NOT NULL DEFAULT '0' COMMENT '并发来源数峰值（预算用量证据）',
  `failure_code` int DEFAULT NULL COMMENT '受控结束的稳定失败编号（无失败为空）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_cross_source_execution_key` (`execution_key`, `deleted`),
  KEY `idx_ai_cross_source_execution_metric` (`metric_code`, `semantics_revision`, `id`),
  KEY `idx_ai_cross_source_execution_status` (`status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 跨源执行记录（重试幂等锚点与统一结果留痕，Y04）';

CREATE TABLE `ai_cross_source_execution_source` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '来源贡献编号',
  `execution_id` bigint NOT NULL COMMENT '跨源执行编号',
  `role` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '来源角色（与唯一键共同构成"每来源一行"）',
  `dataset_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '数据集标识',
  `dataset_version` int NOT NULL COMMENT '数据集版本号（显式钉住，不接受取当前版本）',
  `mapping_revision` bigint NOT NULL COMMENT '该来源钉住的实体键映射版本',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'COUNTED' COMMENT '状态（COUNTED 已计入/FAILED 必需来源失败/MISSING 可选来源缺失）',
  `amount` decimal(24,6) NOT NULL DEFAULT '0.000000' COMMENT '已计入金额（唯一一份；重试覆盖而不叠加）',
  `row_count` int NOT NULL DEFAULT '0' COMMENT '该来源返回的中间结果行数',
  `byte_size` bigint NOT NULL DEFAULT '0' COMMENT '该来源中间结果字节',
  `source_as_of` datetime DEFAULT NULL COMMENT '该来源自己的数据时间点（不是执行时刻，也不是其它来源的时间点）',
  `elapsed_millis` bigint NOT NULL DEFAULT '0' COMMENT '该来源耗时（毫秒）',
  `attempt_count` int NOT NULL DEFAULT '1' COMMENT '取数尝试次数（含重试；>1 说明该来源重试过）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本（重试覆盖用：并发重试只有一个赢家）',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_cross_source_execution_source_role` (`execution_id`, `role`, `deleted`),
  KEY `idx_ai_cross_source_execution_source_status` (`execution_id`, `status`),
  CONSTRAINT `fk_ai_cross_source_execution_source_execution` FOREIGN KEY (`execution_id`) REFERENCES `ai_cross_source_execution` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 跨源执行来源贡献（每来源一行的已计入台账，Y04）';
