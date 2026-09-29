-- X11：报表受控分享与权限撤销（FR-05/FR-28/FR-29/FR-40）。
-- 设计要点（docs/contracts/ai/report-share-permissions.md）：
--   1) 报表可见权（分享凭据）与源数据读取权（接收者自己的 A03 授权）**分离**：分享凭据只回答
--      "谁能打开这份分享"，内容是否出库还要按接收者**当前**源权限逐项复核（scope 指纹不匹配即降级）；
--   2) 凭据只存 SHA-256 摘要（token_hash，唯一）：明文令牌 32 字节 SecureRandom → Base64URL，
--      仅在创建响应出现一次，绝不落库、不进日志；摘要不可反推，库泄露不等于凭据泄露；
--   3) 分享在创建时固定版本（version_no）：接收者看到的是签发时刻的版本，报表新增版本不改变分享内容；
--   4) 撤销（REVOKED）、到期（EXPIRED，读取时惰性物化，不设常驻扫描任务）、授予者主体停用
--      （grantor-unavailable）都让后续读取立即 404（防枚举：全部与"分享不存在"同语义）；
--   5) 每次读取（含拒绝）追加一条 ai_report_share_access 审计（结论 + 稳定原因码），只追加不更新。

DROP TABLE IF EXISTS `ai_report_share`;

CREATE TABLE `ai_report_share` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '分享编号',
  `token_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '分享凭据的 SHA-256 摘要（小写十六进制；明文令牌不落库）',
  `report_id` bigint NOT NULL COMMENT '报表编号（创建时校验所有者=授予者；复制 reportId 无效，读取只认令牌摘要）',
  `version_no` int NOT NULL COMMENT '分享时固定的版本号（报表新增版本不改变分享内容）',
  `application_id` bigint NOT NULL COMMENT '所属应用编号',
  `grantor_subject_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'USER' COMMENT '授予者主体类型（固定 USER：只有用户主体可分享）',
  `grantor_external_user_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '授予者外部用户标识（所有者；主体停用后读取立即拒绝）',
  `grantee_subject_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'USER' COMMENT '接收者主体类型（固定 USER）',
  `grantee_external_user_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '接收者外部用户标识（读取时必须等于当前主体，防凭据转借）',
  `grantee_display_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '接收者显示名（创建时快照，授予者界面展示接收范围用）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ACTIVE' COMMENT '状态（ACTIVE/REVOKED/EXPIRED；REVOKED 撤销、EXPIRED 到期惰性物化）',
  `expires_time` datetime DEFAULT NULL COMMENT '过期时间（空为长期有效；过期后读取拒绝并把状态物化为 EXPIRED）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本（撤销 CAS 使用）',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_report_share_token` (`token_hash`),
  KEY `idx_ai_report_share_grantor` (`application_id`, `grantor_subject_type`, `grantor_external_user_id`, `id`),
  KEY `idx_ai_report_share_grantee` (`application_id`, `report_id`, `grantee_subject_type`, `grantee_external_user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 报表受控分享（凭据摘要 + 固定版本 + 可撤销，X11）';

DROP TABLE IF EXISTS `ai_report_share_access`;

CREATE TABLE `ai_report_share_access` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '访问记录编号',
  `share_id` bigint DEFAULT NULL COMMENT '分享编号（凭据无法定位分享时为空）',
  `report_id` bigint DEFAULT NULL COMMENT '报表编号（同上；审计按 share_id 归组）',
  `application_id` bigint NOT NULL COMMENT '应用编号（有分享行时为分享所属应用，否则为访问主体所属应用）',
  `subject_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '访问者主体类型（来自服务端会话身份）',
  `external_user_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '访问者外部用户标识',
  `outcome` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '结论（GRANTED 读取被受理/DENIED 读取被拒绝；降级态是 GRANTED + content_authorized=0）',
  `reason_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '稳定原因码（subject-mismatch/revoked/expired/grantor-unavailable/scope-uncovered；完整成功为空）',
  `content_authorized` bit(1) NOT NULL DEFAULT b'0' COMMENT '本次是否真的出库了报表内容（降级态为 0：spec/data/asOf/completeness 均未返回）',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_ai_report_share_access_share` (`share_id`, `id`),
  KEY `idx_ai_report_share_access_subject` (`application_id`, `subject_type`, `external_user_id`, `id`),
  KEY `idx_ai_report_share_access_retention` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 报表分享访问审计（append-retention，每次读取一条，X11）';
