-- M04：模型能力探测结果与嵌入维度记录。
-- 设计要点：
--   1) 探测结果每次一行（append 语义 + 逻辑删除），记录真实调用的结论、稳定明细码、耗时与嵌入维度；
--      不写提示词、响应正文或凭据：失败只落 ModelException.Reason 名称；
--   2) 嵌入维度记录在端点行上（首次成功嵌入时写入，改变即拒绝写既有索引，见 AI_MODEL_EMBEDDING_DIMENSION_CHANGED）；
--   3) 探测记录带 config_revision / credential_revision，配置或凭据变化后旧结论可对比；
--   4) 不建物理外键：探测记录是历史事实，端点删除后仍需保留（与 revision 表的强约束语义不同）。

ALTER TABLE `ai_model_endpoint`
    ADD COLUMN `embedding_dimension` int DEFAULT NULL COMMENT '已记录的嵌入维度（首次成功嵌入时写入；改变即拒绝写入既有索引）' AFTER `referenced`;

CREATE TABLE `ai_model_probe`
(
    `id`                  bigint      NOT NULL AUTO_INCREMENT COMMENT '探测记录编号',
    `endpoint_id`         bigint      NOT NULL COMMENT '端点编号（逻辑引用，不建物理外键）',
    `config_revision`     int         NOT NULL COMMENT '探测时的配置版本',
    `credential_revision` int         NOT NULL COMMENT '探测时的凭据版本（只记录版本号，不含凭据）',
    `probe_kind`          varchar(32) NOT NULL COMMENT '探测类型（CONNECTIVITY/TEXT/TEXT_STREAM/STRUCTURED_OUTPUT/TOOL_CALLING/EMBEDDING）',
    `status`              varchar(16) NOT NULL COMMENT '结论状态（SUPPORTED/UNSUPPORTED/FAILED）',
    `detail_code`         varchar(64) DEFAULT NULL COMMENT '稳定明细码：失败原因名或不支持原因；成功为空',
    `embedding_dimension` int         DEFAULT NULL COMMENT '嵌入探测观测到的向量维度',
    `latency_ms`          int         NOT NULL DEFAULT 0 COMMENT '真实调用耗时（毫秒）',
    `creator`             varchar(64) DEFAULT '' COMMENT '创建者',
    `create_time`         datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`             varchar(64) DEFAULT '' COMMENT '更新者',
    `update_time`         datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`             bit(1)      NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    KEY `idx_ai_model_probe_endpoint_kind` (`endpoint_id`, `probe_kind`, `id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 模型能力探测结果';

-- 探测权限点：与 AiModelCapabilityProbeController 的 @PreAuthorize 一一对应（菜单 id 取 4000 段，V48 已占用 4000-4004）。
INSERT INTO `system_menu`
(`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
 `icon`, `component`, `component_name`, `status`, `visible`,
 `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (4005, '端点能力探测', 'ai:model-endpoint:probe', 3, 4, 4001, '',
        '', '', NULL, 0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');
