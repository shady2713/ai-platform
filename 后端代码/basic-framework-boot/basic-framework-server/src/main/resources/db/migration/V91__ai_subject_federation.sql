-- Y01：多系统授权发现与范围选择（FR-04/13/20/21/38，V2 跨系统链头）。
-- 设计要点（决策记录 docs/adr/0051-cross-system-subject-federation.md）：
--   1) 业务系统 = 接入应用（app_code 是稳定且不可修改的系统标识）。app_id 只是应用边界，
--      不是跨企业 tenant_id（FR-38），因此两个应用的相同 external_user_id **不构成**同一身份；
--   2) 跨系统身份只能由**显式登记 + 独立审批**的联邦映射建立：同一 operator 不得批准自己
--      提交的映射（requested_by <> approved_by 由服务层校验），只有 APPROVED 行参与发现；
--   3) 发现（授权目录）是只读事实：主体 ACTIVE + 范围解析非空 + 至少一条 ACTIVE 授权才出现；
--      无权系统**完全不出现**（不返回"存在但无权"），撤销后下一次读取立即消失（无缓存）；
--   4) 映射行保留状态迁移历史（PENDING → APPROVED → REVOKED），撤销后重新登记走同一行的
--      新事实，不物理删除（soft-delete）。

DROP TABLE IF EXISTS `ai_subject_federation`;

CREATE TABLE `ai_subject_federation` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '联邦映射编号',
  `source_application_id` bigint NOT NULL COMMENT '来源系统（应用）编号：当前会话主体所属应用',
  `source_subject_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'USER' COMMENT '来源主体类型（USER/APP）',
  `source_external_user_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '来源主体外部用户标识（APP 主体为空串）',
  `target_application_id` bigint NOT NULL COMMENT '目标系统（应用）编号：被联邦的另一个业务系统接入',
  `target_subject_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'USER' COMMENT '目标主体类型（USER/APP）',
  `target_external_user_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '目标主体外部用户标识（只接受服务端登记事实，绝不按同名推断）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT '状态（PENDING 待独立审批/APPROVED 已批准生效/REVOKED 已撤销）',
  `requested_by` bigint NOT NULL COMMENT '提交人（后台操作员编号）',
  `requested_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
  `approved_by` bigint DEFAULT NULL COMMENT '批准人（必须与提交人不同：独立审批）',
  `approved_time` datetime DEFAULT NULL COMMENT '批准时间',
  `approval_note` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审批说明（≤200 字符，服务层校验）',
  `revision` bigint NOT NULL DEFAULT '1' COMMENT '映射版本（提交=1，批准/撤销递增；范围选择指纹据此判定映射事实是否变化）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本（批准/撤销 CAS）',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_subject_federation_identity` (`source_application_id`, `source_subject_type`, `source_external_user_id`, `target_application_id`, `target_subject_type`, `target_external_user_id`),
  KEY `idx_ai_subject_federation_source` (`source_application_id`, `source_subject_type`, `source_external_user_id`, `status`, `id`),
  KEY `idx_ai_subject_federation_target` (`target_application_id`, `target_subject_type`, `target_external_user_id`, `status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 跨系统主体联邦映射（显式登记 + 独立审批，Y01）';

-- 跨系统联邦权限点（菜单 id 继续使用 4010 段；与 AiApplicationDiscoveryController 的 @PreAuthorize 一一对应）：
--   提交/批准/撤销联邦映射是**独立审批**动作，因此不沿用 ai:application:update，
--   单独授予（拥有应用修改权不等于拥有跨系统身份映射权）。
INSERT INTO `system_menu`
(`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
 `icon`, `component`, `component_name`, `status`, `visible`,
 `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (4016, '跨系统主体联邦', 'ai:application:federation', 3, 6, 4010, '', '', '', NULL,
        0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');
