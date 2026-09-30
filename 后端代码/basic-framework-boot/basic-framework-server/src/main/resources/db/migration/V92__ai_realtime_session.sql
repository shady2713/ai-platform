-- X05：实时语音会话与打断恢复（FR-37）。
-- 设计要点（决策记录 docs/adr/0052-realtime-voice-protocol-and-capability-verification.md）：
--   1) 实时能力**不按供应商推断**：每个（端点, 配置版本, 协议）的声明与真实探测确认分开记录
--      （ai_realtime_endpoint_capability），未确认的组合不可受理会话；
--   2) 会话受理即固定端点/配置版本/凭据版本/协议/音频格式/绝对到期时间与短期票据摘要
--      （ai_realtime_session）：票据只存 SHA-256 摘要，明文只在受理/续票响应出现一次；
--   3) 背压有界：输入音频按字节计账（buffered_bytes + input_bytes_total），
--      条件更新（buffered_bytes + 帧 <= input_capacity_bytes）超限即按稳定原因结束会话；
--   4) 打断用回合栅栏（turn_no），晚到的旧回合事件被丢弃并计数（dropped_stale_frames）；
--   5) 工具调用按（会话, 回合, 调用标识）唯一并带状态（ai_realtime_tool_call）：
--      重连不重复执行（条件更新只允许 PROPOSED 转 EXECUTING）；
--   6) 过期与切用户关闭会话（在读取/使用时惰性判定，不新增常驻扫描任务）。

DROP TABLE IF EXISTS `ai_realtime_session`;

CREATE TABLE `ai_realtime_session` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '实时会话编号',
  `session_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '会话业务键（平台生成，用于适配器幂等与排障，不含凭据）',
  `request_key` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '受理幂等键（调用方提供，主体内唯一）',
  `application_id` bigint NOT NULL COMMENT '应用编号（归属之一）',
  `subject_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '主体类型（APP/USER）',
  `external_user_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '可信外部用户标识（归属之一）',
  `endpoint_id` bigint NOT NULL COMMENT '受理时固定的模型端点编号',
  `endpoint_config_revision` int NOT NULL COMMENT '受理时固定的端点配置版本',
  `endpoint_credential_revision` int NOT NULL COMMENT '受理时固定的端点凭据版本',
  `model_ref` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '受理时固定的模型标识（非秘密配置）',
  `protocol` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '协议（WEBSOCKET/WEBRTC；受理时固定，重连只能沿用）',
  `audio_format` varchar(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '音频格式规范形式（如 audio/pcm@16000:1:20）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'OPEN' COMMENT '状态（OPEN/DETACHED/CLOSED）',
  `close_reason` varchar(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '结束原因稳定码（client-closed/session-expired/audio-backpressure-exceeded 等）',
  `turn_no` bigint NOT NULL DEFAULT '0' COMMENT '当前回合号（打断时 +1；晚到的旧回合事件按过期丢弃）',
  `dropped_stale_frames` int NOT NULL DEFAULT '0' COMMENT '因回合过期被丢弃的帧/事件累计数（不得静默丢弃的证明）',
  `input_capacity_bytes` bigint NOT NULL COMMENT '输入音频有界缓冲上限（受理时固定）',
  `buffered_bytes` bigint NOT NULL DEFAULT '0' COMMENT '当前占用字节（有界；超限即结束会话）',
  `input_bytes_total` bigint NOT NULL DEFAULT '0' COMMENT '累计接受输入字节（用量与排障）',
  `muted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否已关麦（关麦期间上行音频一律拒绝）',
  `resume_attempts` int NOT NULL DEFAULT '0' COMMENT '已用重连次数（有界）',
  `resume_deadline` datetime NOT NULL COMMENT '重连时限（断线后超过即关闭；正常态等于会话到期时间）',
  `ticket_digest` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '短期票据的 SHA-256 摘要（明文只在受理/续票响应出现一次）',
  `ticket_revision` int NOT NULL DEFAULT '1' COMMENT '票据代次（每次续票 +1；旧票据立即失效）',
  `ticket_expires_time` datetime NOT NULL COMMENT '票据到期时间（不晚于会话绝对到期时间）',
  `expires_time` datetime NOT NULL COMMENT '会话绝对到期时间（受理时固定，不续期）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_realtime_session_key` (`session_key`),
  UNIQUE KEY `uk_ai_realtime_session_request` (`application_id`, `subject_type`, `external_user_id`, `request_key`, `deleted`),
  KEY `idx_ai_realtime_session_subject` (`application_id`, `subject_type`, `external_user_id`, `status`, `expires_time`),
  KEY `idx_ai_realtime_session_endpoint` (`endpoint_id`, `protocol`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 实时语音会话（受理即固定端点与协议，X05）';

DROP TABLE IF EXISTS `ai_realtime_event`;

CREATE TABLE `ai_realtime_event` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '会话事件编号',
  `session_id` bigint NOT NULL COMMENT '会话编号',
  `event_type` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事件类型（TRANSCRIPT/AUDIO/TOOL_CALL/STALE_DROPPED/REATTACHED/CLOSED）',
  `turn_no` bigint NOT NULL COMMENT '事件所属回合',
  `event_seq` bigint NOT NULL DEFAULT '0' COMMENT '回合内事件序号（0 表示与序号无关的会话级事件）',
  `text_content` varchar(4000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '转写文本（不含提示词与上游报文）',
  `byte_count` int NOT NULL DEFAULT '0' COMMENT '音频字节数（仅 AUDIO 事件有值）',
  `detail_code` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '稳定明细（结束原因码/工具调用标识/丢弃原因；不含上游正文）',
  `dedup_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事件去重键（重放不产生重复行）',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_realtime_event_dedup` (`session_id`, `dedup_key`),
  KEY `idx_ai_realtime_event_session` (`session_id`, `turn_no`, `id`),
  CONSTRAINT `fk_ai_realtime_event_session` FOREIGN KEY (`session_id`) REFERENCES `ai_realtime_session` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 实时会话事件（只追加；转写与音频留痕，X05）';

DROP TABLE IF EXISTS `ai_realtime_tool_call`;

CREATE TABLE `ai_realtime_tool_call` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '会话内工具调用编号',
  `session_id` bigint NOT NULL COMMENT '会话编号',
  `turn_no` bigint NOT NULL COMMENT '提出该调用的回合',
  `call_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '上游工具调用标识',
  `tool_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '工具标识',
  `tool_version_id` bigint DEFAULT NULL COMMENT '判定时的已发布工具版本（拒绝时为执行的判定版本或空）',
  `policy` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '判定时的政策（执行判定前为空；只执行 AUTO 读工具）',
  `arguments_json` varchar(4000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '{}' COMMENT '冻结的参数（执行时按已发布版本重新校验；不回显上游正文）',
  `arguments_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '参数规范化哈希（稳定性口径）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PROPOSED' COMMENT '状态（PROPOSED/EXECUTING/EXECUTED/REJECTED/FAILED）',
  `result_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '执行结论稳定码（不含上游正文）',
  `executed_time` datetime DEFAULT NULL COMMENT '执行完成时间',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本（状态机 CAS）',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_realtime_tool_call` (`session_id`, `turn_no`, `call_id`, `deleted`),
  KEY `idx_ai_realtime_tool_call_session` (`session_id`, `status`, `id`),
  CONSTRAINT `fk_ai_realtime_tool_call_session` FOREIGN KEY (`session_id`) REFERENCES `ai_realtime_session` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 实时会话工具调用（唯一键去重，重连不重复执行，X05）';

DROP TABLE IF EXISTS `ai_realtime_endpoint_capability`;

CREATE TABLE `ai_realtime_endpoint_capability` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '验证结论编号',
  `endpoint_id` bigint NOT NULL COMMENT '模型端点编号',
  `config_revision` int NOT NULL COMMENT '被验证的端点配置版本（配置变更即失效）',
  `protocol` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '被验证的协议（WEBSOCKET/WEBRTC）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '结论（VERIFIED/UNSUPPORTED/FAILED）',
  `detail_code` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '稳定明细码（缺失能力名/失败原因名；不含上游报文）',
  `declared_capabilities` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '适配器声明的能力（逗号分隔）',
  `confirmed_capabilities` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '真实探测确认的能力（逗号分隔）',
  `audio_formats` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '验证通过的音频格式规范形式（逗号分隔）',
  `latency_millis` bigint NOT NULL DEFAULT '0' COMMENT '真实探测耗时（毫秒）',
  `probed_time` datetime NOT NULL COMMENT '探测时间（失败结论在冷却窗口内不重复外发）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_realtime_endpoint_capability` (`endpoint_id`, `config_revision`, `protocol`, `deleted`),
  KEY `idx_ai_realtime_endpoint_capability_protocol` (`protocol`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 实时端点能力验证台账（声明 + 真实探测确认，X05）';
