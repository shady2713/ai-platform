-- A07：AI 业务对象与 infra 私有文件的绑定（业务 ACL）。
-- 设计要点：
--   1) 一个文件可以被多个业务对象引用（唯一键含 business_type + business_key）：
--      删除某条引用只解除该绑定，只有"没有其他有效引用"时才真正删除文件——共享引用未释放不能误删；
--   2) 绑定记录上传主体（应用 + 主体类型 + 外部用户标识）：删除只允许所有者执行
--      （管理权限 canManageFiles 不参与业务判定，也不传入授权上下文）；
--   3) 读取授权每次都按当前状态判定（不缓存）：绑定被撤销、授权被回收、主体被停用后立即拒绝；
--   4) 未知业务类型在服务层与 infra 注册表两处都会拒绝（fail-closed）。

CREATE TABLE `ai_file_binding`
(
    `id`               bigint       NOT NULL AUTO_INCREMENT COMMENT '绑定编号',
    `file_id`          bigint       NOT NULL COMMENT 'infra 文件编号（逻辑引用，不建物理外键）',
    `business_type`    varchar(32)  NOT NULL COMMENT 'AI 业务类型（ai_report/ai_knowledge_document/ai_chat_session）',
    `business_key`     varchar(128) NOT NULL COMMENT '业务对象标识（报表键/知识库键/会话键）',
    `application_id`   bigint       NOT NULL COMMENT '上传主体所属应用（跨应用隔离）',
    `subject_type`     varchar(16)  NOT NULL COMMENT '上传主体类型（APP/USER）',
    `external_user_id` varchar(128) NOT NULL DEFAULT '' COMMENT '上传主体外部用户标识（所有者）',
    `status`           varchar(16)  NOT NULL COMMENT '状态（ACTIVE/RELEASED）',
    `version`          int          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`          varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`          varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_file_binding` (`file_id`, `business_type`, `business_key`, `deleted`),
    KEY `idx_ai_file_binding_business` (`business_type`, `business_key`, `status`),
    KEY `idx_ai_file_binding_file` (`file_id`, `status`),
    KEY `idx_ai_file_binding_subject` (`application_id`, `subject_type`, `external_user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 业务文件绑定（业务 ACL）';
