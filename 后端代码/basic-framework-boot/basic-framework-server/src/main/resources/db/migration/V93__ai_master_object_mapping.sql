-- Y02：跨系统业务对象与主数据映射（FR-22/FR-38，V2 跨系统链第二环，AT-070）。
-- 设计要点（决策记录 docs/adr/0053-master-data-entity-mapping-versions.md）：
--   1) 业务对象是**企业统一对象**（ai_master_object），它与各业务系统里的标识之间只能靠
--      **显式登记的源键**（ai_master_object_mapping）关联：匹配方式是人工登记或可信主数据导入，
--      绝不按名称/同名推断——ai_master_object_mapping.source_name 只用于展示，不参与任何判定；
--   2) 映射**版本化**：一个对象的映射按版本组织（ai_master_object_revision）。草稿（DRAFT）可编辑，
--      发布（PUBLISHED）后不可变：版本内容指纹（mapping_fingerprint）冻结，旧报表/旧产物按受理时
--      的版本编号解释，换版本不改旧结果；发布需要**独立审核**（published_by <> created_by）；
--   3) **冲突与过期一律阻断**：同一（对象, 系统, 实体类型, 时间段）出现多条生效源键（一对多），
--      或同一（系统, 实体类型, 源键）在重叠时间段内属于多个对象（多对一），发布与判定都拒绝；
--      有效期不覆盖判定时刻的行不参与判定，且"全部过期"与"从未登记"用不同错误码表达，不静默取一个；
--   4) 判定路径是**只读事实**：每次读取重新算生效集合、重算版本指纹并比对，不缓存、无定时任务；
--      对主体的目录发现复用 Y01 的可访问系统集合（无权系统不出现，拒绝不可区分）。
--
-- 生命周期：ai_master_object / ai_master_object_revision 是配置面事实（soft-delete，与 ai_dataset/
-- ai_dataset_version 同口径）；ai_master_object_mapping 是**版本内容行**，只属于草稿，删除即物理删除
-- （hard-delete）——发布后的行随版本冻结，永不删除也不可改。

DROP TABLE IF EXISTS `ai_master_object_mapping`;

DROP TABLE IF EXISTS `ai_master_object_revision`;

DROP TABLE IF EXISTS `ai_master_object`;

CREATE TABLE `ai_master_object` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '企业统一对象编号',
  `object_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '统一对象标识（稳定且不可修改，跨系统映射的锚点）',
  `object_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '对象名称（仅展示，绝不参与实体判定）',
  `object_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '对象类型（CUSTOMER/SUPPLIER/PRODUCT/EMPLOYEE/ORGANIZATION/OTHER）',
  `description` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '说明（不承载判定语义）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ACTIVE' COMMENT '状态（ACTIVE 可用/DISABLED 停用：停用后判定阻断）',
  `current_revision` bigint NOT NULL DEFAULT '0' COMMENT '当前已发布的映射版本（0=尚无已发布版本；判定必须显式指定版本，不回退到最新）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_master_object_code` (`object_code`, `deleted`),
  KEY `idx_ai_master_object_status` (`status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 企业统一对象（跨系统主数据映射锚点，Y02）';

CREATE TABLE `ai_master_object_revision` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '映射版本编号',
  `master_object_id` bigint NOT NULL COMMENT '统一对象编号',
  `revision_no` bigint NOT NULL COMMENT '映射版本号（对象内递增；发布后不可变，旧报表按受理时的版本解释）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT' COMMENT '状态（DRAFT 草稿可编辑/PUBLISHED 已发布不可变）',
  `valid_from` datetime NOT NULL COMMENT '版本有效期起点（含）',
  `valid_to` datetime DEFAULT NULL COMMENT '版本有效期终点（不含；NULL=长期有效；过期即阻断判定）',
  `entry_count` int NOT NULL DEFAULT '0' COMMENT '发布时冻结的映射条目数（读取预算据此有界）',
  `mapping_fingerprint` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '发布时冻结的内容指纹（读取时重算比对，不符即阻断）',
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
  UNIQUE KEY `uk_ai_master_object_revision_no` (`master_object_id`, `revision_no`, `deleted`),
  KEY `idx_ai_master_object_revision_status` (`master_object_id`, `status`, `revision_no`),
  CONSTRAINT `fk_ai_master_object_revision_object` FOREIGN KEY (`master_object_id`) REFERENCES `ai_master_object` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 主数据映射版本（草稿可编辑、发布后不可变的映射快照，Y02）';

CREATE TABLE `ai_master_object_mapping` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '源键映射编号',
  `master_object_id` bigint NOT NULL COMMENT '统一对象编号',
  `revision` bigint NOT NULL COMMENT '所属映射版本（草稿期可增删；发布后随版本冻结）',
  `application_id` bigint NOT NULL COMMENT '来源系统（接入应用）编号',
  `entity_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '该系统中的实体类型（如 customer/order）',
  `source_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '该系统中的业务主键（显式登记事实，绝不按名称/同名推断）',
  `source_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '展示名（只展示，不参与判定：同名不同实体不合并）',
  `match_method` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '匹配方式（MANUAL 人工登记/TRUSTED_FEED 可信主数据导入）',
  `valid_from` datetime NOT NULL COMMENT '源键有效期起点（含）',
  `valid_to` datetime DEFAULT NULL COMMENT '源键有效期终点（不含；NULL=长期有效）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本（草稿期删除 CAS）',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_master_object_mapping_entry` (`master_object_id`, `revision`, `application_id`, `entity_type`, `source_key`),
  KEY `idx_ai_master_object_mapping_revision` (`master_object_id`, `revision`),
  KEY `idx_ai_master_object_mapping_source` (`application_id`, `entity_type`, `source_key`, `revision`),
  CONSTRAINT `fk_ai_master_object_mapping_object` FOREIGN KEY (`master_object_id`) REFERENCES `ai_master_object` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 主数据源键映射（显式登记的跨系统标识对应，Y02）';

-- 主数据映射权限点（菜单 id 继续使用 AI 段：4118 页面 / 4119 维护按钮）：
--   查看（ai:semantic:query）只读映射事实与目录发现；维护（ai:semantic:manage）才能登记对象、
--   编辑草稿版本与发布——发布是审核动作，且发布人必须不同于草稿创建人（服务层校验）。
INSERT INTO `system_menu`
(`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
 `icon`, `component`, `component_name`, `status`, `visible`,
 `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (4118, '主数据映射', 'ai:semantic:query', 2, 17, 4000, 'semantic', 'ep:connection',
        'ai/semantic/index', 'AiSemantic', 0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1',
        CURRENT_TIMESTAMP, b'0');

INSERT INTO `system_menu`
(`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
 `icon`, `component`, `component_name`, `status`, `visible`,
 `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (4119, '主数据映射维护', 'ai:semantic:manage', 3, 18, 4118, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');
