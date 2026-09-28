-- X03：媒体生成/编辑持久任务（图片生成与编辑；X04 复用同一任务模型承接 STT/TTS）。
-- 说明：
--   1) 任务是**事实**：受理即落库，状态只按 QUEUED → RUNNING → SUCCEEDED/FAILED/CANCELLED 迁移，
--      并发领取用租约栅栏（owner + claimed_epoch）与状态 CAS，晚到的执行结果不能覆盖终态；
--   2) 幂等键由调用方提供：同一主体+应用的同一 request_key 只受理一次，重复提交返回既有任务（不重复生成）；
--   3) 产物只落平台私有文件编号（ai_media_asset.file_id），**不保存任何上游临时地址**；
--      用量只记上游真实计数（REPORTED）或 UNKNOWN，缺失不写 0；
--   4) 输入只留引用与声明级元数据（源图/源音频的文件编号、MIME、字节数、摘要），不留正文与地址。

DROP TABLE IF EXISTS `ai_media_task`;

CREATE TABLE `ai_media_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '媒体任务编号',
  `request_key` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '幂等键（调用方提供，主体内唯一）',
  `application_id` bigint NOT NULL COMMENT '应用编号',
  `subject_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '主体类型（APP/USER）',
  `external_user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '可信外部用户标识',
  `media_kind` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '媒体种类（IMAGE/AUDIO）',
  `operation` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作（GENERATE/EDIT/TRANSCRIBE/SYNTHESIZE）',
  `capability` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '能力（与 ModelCapability 同名）',
  `endpoint_id` bigint NOT NULL COMMENT '受理时固定的模型端点编号',
  `endpoint_config_revision` int NOT NULL COMMENT '受理时固定的端点配置版本',
  `model_ref` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '受理时固定的模型标识（非秘密配置）',
  `input_text` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '文本输入（生成提示词/编辑指令/合成文本；不含凭据）',
  `source_file_id` bigint DEFAULT NULL COMMENT '源文件编号（编辑底图/待转写音频；生成类为空）',
  `source_mime` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '源文件声明 MIME',
  `source_size_bytes` bigint DEFAULT NULL COMMENT '源文件声明字节数',
  `source_sha256` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '源文件声明摘要',
  `target_size` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '目标尺寸（宽x高；为空表示由端点默认值决定）',
  `output_count` int NOT NULL DEFAULT '1' COMMENT '请求产物数量（有界）',
  `output_format` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'png' COMMENT '请求输出格式',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'QUEUED' COMMENT '状态（QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED）',
  `result_count` int NOT NULL DEFAULT '0' COMMENT '已落库产物数量',
  `failure_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '失败原因（稳定错误码，不含上游正文）',
  `attempt_count` int NOT NULL DEFAULT '0' COMMENT '已尝试次数',
  `max_attempts` int NOT NULL DEFAULT '3' COMMENT '最大尝试次数',
  `next_attempt_time` datetime DEFAULT NULL COMMENT '下次可领取时间',
  `lease_owner` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '租约持有者（worker 标识）',
  `lease_expires_time` datetime DEFAULT NULL COMMENT '租约到期时间',
  `heartbeat_time` datetime DEFAULT NULL COMMENT '最近心跳时间',
  `claimed_epoch` int NOT NULL DEFAULT '0' COMMENT '领取纪元（栅栏：每次领取 +1）',
  `usage_unit` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '计量单位（IMAGE/AUDIO_MILLISECOND/TOKEN/CHARACTER；未知为空）',
  `usage_quantity` bigint DEFAULT NULL COMMENT '计量数值（未知为空，不写 0）',
  `usage_source` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'UNKNOWN' COMMENT '计量来源（REPORTED/ESTIMATED/UNKNOWN）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_media_task_request` (`application_id`, `subject_type`, `external_user_id`, `request_key`, `deleted`),
  KEY `idx_ai_media_task_claim` (`status`, `next_attempt_time`, `id`),
  KEY `idx_ai_media_task_lease` (`status`, `lease_expires_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 媒体任务（受理即固定端点与配置版本，X03）';

DROP TABLE IF EXISTS `ai_media_asset`;

CREATE TABLE `ai_media_asset` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '媒体产物编号',
  `task_id` bigint NOT NULL COMMENT '媒体任务编号',
  `ordinal` int NOT NULL COMMENT '产物序号（同一任务内从 1 开始，决定展示顺序）',
  `file_id` bigint NOT NULL COMMENT '平台私有文件编号（唯一可读取入口，不存在上游地址）',
  `mime_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '产物 MIME（白名单内的取值）',
  `size_bytes` bigint NOT NULL COMMENT '产物字节数',
  `sha256` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '产物内容摘要（服务端计算）',
  `width` int DEFAULT NULL COMMENT '图片宽度（非图片或未知为空，不写 0）',
  `height` int DEFAULT NULL COMMENT '图片高度（非图片或未知为空，不写 0）',
  `duration_millis` bigint DEFAULT NULL COMMENT '音频时长（毫秒；非音频或未知为空）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_media_asset_ordinal` (`task_id`, `ordinal`, `deleted`),
  KEY `idx_ai_media_asset_task` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 媒体产物（只存平台私有文件编号与服务端摘要，X03）';

-- 媒体任务消费者 Job（常驻领取：先恢复过期租约，再领取待处理任务）
-- 编号 38：28-34/36/37 已被既有迁移占用（33 是 V64 的保留清理任务），复用会造成主键冲突、迁移整体失败。
INSERT INTO `infra_job`
(`id`, `name`, `status`, `handler_name`, `handler_param`, `cron_expression`,
 `retry_count`, `retry_interval`, `monitor_timeout`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (38, 'AI 媒体任务 Job', 1, 'aiMediaTaskJob', '', '*/30 * * * * ?', 0, 0, 0, '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');