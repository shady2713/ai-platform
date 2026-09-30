-- Y03：跨源指标口径与关联粒度校验（FR-22/FR-23/FR-38，V2 跨系统链第三环，AT-034/AT-070）。
-- 设计要点：
--   1) 跨源相加是否成立**不是模型能猜的事**，因此口径（币种/单位/时区/时间窗口/主键粒度/聚合顺序）
--      必须先被显式登记成可版本化的配置面事实：ai_metric_semantics 是锚点，
--      ai_metric_semantics_revision 是不可变版本（发布时冻结 definition_fingerprint）。
--      没有登记口径 = 没有任何"这些数可以相加"的依据，聚合路径只能拒绝；
--   2) 换版本不改旧结果：判定必须显式给出 metricCode + revisionNo + asOf，
--      每次读取重算指纹与冻结值比对（与 Y02 的 mapping_fingerprint 同一手法）；
--   3) **扇出即重复计算**：多对多关联会让同一事实被算两次，因此每个来源都必须在口径里
--      声明自己的主键粒度（definition_json.sources[].primaryKey），并按该粒度先聚合再关联。
--      这不是"提示模型注意别重复"，而是没有粒度声明就无法通过聚合校验；
--   4) 币种不同且无换算规则 → 拒绝求和（100 USD + 100 CNY = 200 是最危险的"看起来对"）；
--   5) 缺口（某来源没有数据）不得按 0 静默补齐：只有口径里显式标记 optional 的来源可以按缺省继续，
--      其余必须澄清——"没有回款记录"和"回款金额是 0"在报表上必须能区分。
--
-- 生命周期：ai_metric_semantics / ai_metric_semantics_revision 是配置面事实（soft-delete，
-- 与 ai_dataset / ai_dataset_version / Y02 的 ai_master_object 同口径）。本卡不引入内容行表
-- （来源声明整体冻结在版本的 definition_json 里），因此没有需要 hard-delete 的表。

DROP TABLE IF EXISTS `ai_metric_semantics_revision`;

DROP TABLE IF EXISTS `ai_metric_semantics`;

CREATE TABLE `ai_metric_semantics` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '跨源指标口径编号',
  `metric_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '口径标识（稳定且不可修改，跨源聚合的锚点）',
  `metric_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '口径名称（仅展示）',
  `description` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '说明（不承载判定语义）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ACTIVE' COMMENT '状态（ACTIVE 可用/DISABLED 停用：停用后跨源聚合阻断）',
  `current_revision` bigint NOT NULL DEFAULT '0' COMMENT '当前已发布的口径版本（0=尚无；聚合必须显式指定版本，不回退到最新）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_metric_semantics_code` (`metric_code`, `deleted`),
  KEY `idx_ai_metric_semantics_status` (`status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 跨源指标口径（跨源聚合的登记锚点，Y03）';

CREATE TABLE `ai_metric_semantics_revision` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '口径版本编号',
  `metric_semantics_id` bigint NOT NULL COMMENT '口径编号',
  `revision_no` bigint NOT NULL COMMENT '口径版本号（口径内递增；发布后不可变，旧报表按受理时的版本解释）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT' COMMENT '状态（DRAFT 草稿可编辑/PUBLISHED 已发布不可变）',
  `definition_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '口径定义（币种/单位/时区/时间窗口/来源声明与主键粒度/聚合顺序/换算规则）',
  `definition_fingerprint` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '发布时冻结的内容指纹（读取时重算比对，不符即阻断）',
  `valid_from` datetime NOT NULL COMMENT '版本有效期起点（含）',
  `valid_to` datetime DEFAULT NULL COMMENT '版本有效期终点（不含；NULL=长期有效；过期即阻断）',
  `created_by` bigint NOT NULL COMMENT '草稿创建人（发布人必须不同：独立审核）',
  `published_by` bigint DEFAULT NULL COMMENT '发布人（必须与草稿创建人不同）',
  `published_time` datetime DEFAULT NULL COMMENT '发布时间',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_metric_semantics_revision_no` (`metric_semantics_id`, `revision_no`, `deleted`),
  KEY `idx_ai_metric_semantics_revision_status` (`metric_semantics_id`, `status`, `revision_no`),
  CONSTRAINT `fk_ai_metric_semantics_revision_semantics` FOREIGN KEY (`metric_semantics_id`) REFERENCES `ai_metric_semantics` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 跨源指标口径版本（发布后不可变、指纹冻结的口径快照，Y03）';
