-- X10：受控异步结果 Webhook（运行终态结果的有界投递）。
-- 设计要点（FR-08/FR-11/FR-40）：
--   1) 目标（ai_webhook_target）是**控制面配置**：投递地址 + 事件白名单 + HMAC 签名密钥。
--      密钥只存 CredentialCipher 密文（AAD 绑定目标编号，密文不可挪到别的目标行），轮换只递增
--      secret_revision；接口与日志永不回显明文。停用（DISABLED）即停发：入队与发送前都要求目标启用；
--   2) 投递（ai_webhook_delivery）是**事实**：同一目标 + 同一事件 + 同一资源最多一条投递
--      （uk_ai_webhook_delivery_event），投递失败**只影响投递行**，绝不回写运行状态、绝不重跑 run；
--      状态只按 PENDING → RUNNING → SUCCEEDED/FAILED 迁移，并发领取用租约栅栏（owner + claimed_epoch），
--      晚到的结果命中 0 行、不能覆盖终态；
--   3) 每次完成的尝试单独留痕（ai_webhook_delivery_attempt，append-retention）：结论、HTTP 状态、
--      稳定原因码、签名时间戳与耗时；表内不含目标密钥、投递正文的原文之外的任何凭据。
--      投递正文（payload_json）只含事件类型、资源引用与运行状态，不含提示词/响应正文/凭据；
--   4) 出站只走受控边界（GuardedExternalHttpClient）：重定向不跟随（3xx 按确定失败收尾）、
--      私网/未授权目标在发送前被拒（零请求），超时与 5xx 才进入有界退避重试。

DROP TABLE IF EXISTS `ai_webhook_target`;

CREATE TABLE `ai_webhook_target` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '投递目标编号',
  `application_id` bigint NOT NULL COMMENT '应用编号（只投递该应用下运行的终态结果）',
  `code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '目标标识（应用内唯一，创建后不可修改）',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '目标名称',
  `target_url` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '投递地址（http/https；实际可否出站由受控出站边界的允许清单与私网策略决定）',
  `event_types` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事件白名单（JSON 数组文本，取值见 AiWebhookEventTypes）',
  `secret_ciphertext` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'HMAC 签名密钥（CredentialCipher 密文，AAD 绑定目标编号；永不回显）',
  `secret_revision` int NOT NULL DEFAULT '0' COMMENT '签名密钥版本（轮换递增；0 表示未配置）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ENABLED' COMMENT '状态（ENABLED/DISABLED；DISABLED 不再产生投递且人工重投被拒）',
  `max_attempts` int NOT NULL DEFAULT '3' COMMENT '单次投递的最大尝试次数（有界重试，1-10）',
  `enqueue_watermark` datetime NOT NULL DEFAULT '1970-01-01 00:00:00' COMMENT '补漏扫描水位（已覆盖的终态运行更新时间上界；只由投递 Job 单调推进，不参与乐观锁）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_webhook_target_code` (`application_id`, `code`, `deleted`),
  KEY `idx_ai_webhook_target_subscribe` (`application_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 受控结果 Webhook 目标（配置面，X10）';

DROP TABLE IF EXISTS `ai_webhook_delivery`;

CREATE TABLE `ai_webhook_delivery` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '投递编号（行主键）',
  `delivery_no` varchar(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '投递编号（对外唯一，重试不变；接收端据此去重）',
  `target_id` bigint NOT NULL COMMENT '投递目标编号',
  `application_id` bigint NOT NULL COMMENT '应用编号（入队时快照，便于按应用过滤）',
  `event_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事件类型（RUN.SUCCEEDED/RUN.FAILED/RUN.CANCELLED）',
  `resource_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'RUN' COMMENT '资源类型（当前只有 RUN）',
  `resource_id` bigint NOT NULL COMMENT '资源编号（运行编号）',
  `resource_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '资源业务键（run_ 前缀；接收端据此定位运行）',
  `occurred_time` datetime NOT NULL COMMENT '事件发生时间（运行终态写入时间，入队时快照）',
  `payload_json` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '投递正文（规范化 JSON：事件类型 + 资源引用 + 状态；不含提示词/响应正文/凭据；重试复用同一份）',
  `payload_digest` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '正文摘要（sha-256 十六进制，签名覆盖它）',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT '状态（PENDING/RUNNING/SUCCEEDED/FAILED）',
  `attempt_count` int NOT NULL DEFAULT '0' COMMENT '已尝试次数（领取时递增，重试预算据此收敛）',
  `max_attempts` int NOT NULL DEFAULT '3' COMMENT '最大尝试次数（入队时从目标快照，之后改目标不改变在途投递的预算）',
  `next_attempt_time` datetime DEFAULT NULL COMMENT '下次可领取时间（退避后）',
  `lease_owner` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '租约持有者（worker 标识）',
  `lease_expires_time` datetime DEFAULT NULL COMMENT '租约到期时间',
  `heartbeat_time` datetime DEFAULT NULL COMMENT '最近一次心跳时间',
  `claimed_epoch` int NOT NULL DEFAULT '0' COMMENT '领取纪元（栅栏：每次领取 +1）',
  `last_error_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最近一次尝试的稳定原因码（不含上游正文）',
  `failure_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '终态失败码（重试预算耗尽或确定失败原因；成功为空）',
  `first_attempt_time` datetime DEFAULT NULL COMMENT '首次尝试时间',
  `delivered_time` datetime DEFAULT NULL COMMENT '投递成功时间',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_webhook_delivery_event` (`target_id`, `event_type`, `resource_type`, `resource_id`, `deleted`),
  UNIQUE KEY `uk_ai_webhook_delivery_no` (`delivery_no`),
  KEY `idx_ai_webhook_delivery_claim` (`status`, `next_attempt_time`, `id`),
  KEY `idx_ai_webhook_delivery_lease` (`status`, `lease_expires_time`),
  KEY `idx_ai_webhook_delivery_resource` (`resource_type`, `resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 受控结果 Webhook 投递（事实行 + 租约栅栏，X10）';

DROP TABLE IF EXISTS `ai_webhook_delivery_attempt`;

CREATE TABLE `ai_webhook_delivery_attempt` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '尝试编号',
  `delivery_id` bigint NOT NULL COMMENT '投递编号',
  `attempt_no` int NOT NULL COMMENT '第几次尝试（从 1 开始，与投递行 attempt_count 对应）',
  `outcome` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '结论（DELIVERED 已送达/RETRYABLE 可重试/PERMANENT 确定失败）',
  `error_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '稳定原因码（送达为空）',
  `http_status` int DEFAULT NULL COMMENT 'HTTP 状态码（未发出请求时为空）',
  `signature_timestamp` bigint DEFAULT NULL COMMENT '签名时间戳（epoch 秒；接收端据此判定过期）',
  `duration_ms` bigint NOT NULL DEFAULT '0' COMMENT '本次尝试耗时（毫秒）',
  `started_time` datetime NOT NULL COMMENT '本次尝试开始时间',
  `finished_time` datetime NOT NULL COMMENT '本次尝试结束时间',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '创建者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT '更新者',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_webhook_attempt_no` (`delivery_id`, `attempt_no`),
  KEY `idx_ai_webhook_attempt_delivery` (`delivery_id`, `id`),
  KEY `idx_ai_webhook_attempt_retention` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI Webhook 投递尝试留痕（append-retention，X10）';

-- 补漏扫描的访问路径：入队按「应用 + 更新时间窗口」有界读取，避免每 10 秒把应用的终态运行整表扫一遍。
-- （扫描谓词再也用不到 JSON_CONTAINS：事件白名单在 Java 侧推导成 status IN (...)，可走索引。）
CREATE INDEX `idx_ai_run_webhook_scan` ON `ai_run` (`application_id`, `update_time`);

-- Webhook 投递 Job：入队终态运行 → 领取 → 投递 → 落结论（handler 名与 bean 名一致）。
INSERT INTO `infra_job`
(`id`, `name`, `status`, `handler_name`, `handler_param`, `cron_expression`,
 `retry_count`, `retry_interval`, `monitor_timeout`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (39, 'AI Webhook 投递 Job', 1, 'aiWebhookDeliveryJob', '', '*/10 * * * * ?', 0, 0, 0, '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');

-- 控制面菜单：Webhook 目标维护与投递管理（挂在 AI 中台目录 4000 下）。
INSERT INTO `system_menu`
(`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
 `icon`, `component`, `component_name`, `status`, `visible`,
 `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (4124, 'Webhook 投递', 'ai:webhook:query', 2, 17, 4000, 'webhook', 'ep:promotion',
        'ai/open-platform/webhook/index', 'AiWebhookDelivery', 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4125, '目标维护', 'ai:webhook:manage', 3, 1, 4124, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4126, '密钥轮换', 'ai:webhook:rotate', 3, 2, 4124, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4127, '目标删除', 'ai:webhook:delete', 3, 3, 4124, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4128, '人工重投', 'ai:webhook:redeliver', 3, 4, 4124, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');
