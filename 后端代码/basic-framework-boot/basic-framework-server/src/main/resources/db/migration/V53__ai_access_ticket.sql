-- A04：访问票据（换票）。
-- 设计要点：
--   1) 票据 token 为 32 字节随机值，库中**只存 SHA-256 摘要**（唯一索引），明文只在换票响应出现一次；
--   2) 票据自带裁剪后的范围快照与范围指纹：调用方按票据范围过滤，范围收窄后指纹变化，历史产物需重新鉴权（A03）；
--   3) 票据短期有效（默认 10 分钟，可配置），到期即拒绝；撤销应用/主体后即使票据未过期也拒绝（校验时重新读应用与主体状态）；
--   4) 无跨请求缓存：每次校验都读当前状态，事务边界内签发的票据只有在提交后才可能被校验到。

CREATE TABLE `ai_access_ticket`
(
    `id`                bigint       NOT NULL AUTO_INCREMENT COMMENT '票据编号',
    `application_id`    bigint       NOT NULL COMMENT '应用编号',
    `subject_type`      varchar(16)  NOT NULL COMMENT '主体类型（APP/USER）',
    `external_user_id`  varchar(128) NOT NULL DEFAULT '' COMMENT '外部用户标识（APP 主体为空串）',
    `token_digest`      char(64)     NOT NULL COMMENT '票据 token 的 SHA-256 摘要（十六进制），不保存明文',
    `scope_snapshot`    varchar(4096) NOT NULL COMMENT '裁剪后的范围快照（JSON：组织与对象白名单 + 来源 + 版本）',
    `scope_fingerprint` char(64)     NOT NULL COMMENT '范围指纹（与 A03 判定一致；范围收窄后变化）',
    `authz_revision`    bigint       NOT NULL DEFAULT 1 COMMENT '签发时的授权版本',
    `expires_time`      datetime     NOT NULL COMMENT '到期时间（短期票据）',
    `status`            varchar(16)  NOT NULL COMMENT '状态（ACTIVE/REVOKED）',
    `version`           int          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`           varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`           varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_access_ticket_digest` (`token_digest`, `deleted`),
    KEY `idx_ai_access_ticket_subject` (`application_id`, `subject_type`, `external_user_id`),
    CONSTRAINT `fk_ai_access_ticket_application` FOREIGN KEY (`application_id`) REFERENCES `ai_application` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 访问票据（只存摘要）';
