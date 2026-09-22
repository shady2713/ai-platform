-- K02：知识库与文档版本数据模型。
-- 设计要点：
--   1) 五个对象一条链：知识库（ai_knowledge_base）→ 文档（ai_knowledge_document）→
--      文档版本（ai_knowledge_document_version）→ 切片（ai_knowledge_chunk）→ 索引代
--      （ai_knowledge_index_generation）；文档只声明 sourceKey，版本承载"某次入库的私有文件"；
--   2) sourceKey 幂等：同一知识库内 (knowledge_base_id, source_key) 在存活行中唯一——
--      重复入库同指纹复用、不同指纹生成新版本（不新建文档）；
--   3) 版本发布后不可修改：status=READY/SUPERSEDED 的版本行禁止再改（只能新建版本），
--      索引成功后才把 document.active_version_no 切到新版本（失败保留旧可用版本，AT-024）；
--   4) 嵌入模型与维度落在知识库上：维度是索引的物理约束（K01 结论），
--      索引代切换时维度必须与知识库声明一致，禁止混用不同模型的向量（AT-029）；
--   5) 生命周期：知识库/文档/文档版本为软删除（历史可追溯、引用可保护）；
--      切片与索引代是派生数据，物理清理（K07 负责回收，见 docs/data-lifecycle.md）。

CREATE TABLE `ai_knowledge_base`
(
    `id`                   bigint       NOT NULL AUTO_INCREMENT COMMENT '知识库编号',
    `code`                 varchar(64)  NOT NULL COMMENT '知识库标识（全局唯一且创建后不可修改）',
    `name`                 varchar(128) NOT NULL COMMENT '知识库名称',
    `description`          varchar(512) DEFAULT '' COMMENT '说明',
    `visibility`           varchar(16)  NOT NULL DEFAULT 'APPLICATION' COMMENT '可见性（SHARED 共享/APPLICATION 应用专用）',
    `owner_application_id` bigint       DEFAULT NULL COMMENT '所属应用编号（应用专用知识库必填，共享知识库为空）',
    `manager_user_id`      bigint       DEFAULT NULL COMMENT '管理者用户编号（仅用于展示与联系，鉴权一律走权限码）',
    `embedding_model`      varchar(64)  NOT NULL COMMENT '嵌入模型标识（K05 按它解析模型端点）',
    `embedding_dimension`  int          NOT NULL COMMENT '嵌入维度（索引物理约束，禁止与索引代不一致）',
    `active_generation_no` int          NOT NULL DEFAULT 0 COMMENT '当前生效的索引代（0 表示尚无可用索引）',
    `retention_days`       int          NOT NULL DEFAULT 365 COMMENT '保留策略（天；到期由 K07 清理）',
    `status`               varchar(16)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态（ENABLED/DISABLED）',
    `version`              int          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`              varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`          datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`              varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`          datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`              bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_knowledge_base_code` ((if(`deleted` = b'1', NULL, `code`))),
    KEY `idx_ai_knowledge_base_application` (`owner_application_id`, `status`, `id`),
    CONSTRAINT `fk_ai_knowledge_base_application` FOREIGN KEY (`owner_application_id`) REFERENCES `ai_application` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 知识库（共享/应用专用 + 嵌入模型与维度，K02）';

CREATE TABLE `ai_knowledge_document`
(
    `id`                bigint       NOT NULL AUTO_INCREMENT COMMENT '文档编号',
    `knowledge_base_id` bigint       NOT NULL COMMENT '知识库编号',
    `source_key`        varchar(128) NOT NULL COMMENT '来源幂等键（库内唯一；外部同步键或上传文件名）',
    `title`             varchar(256) NOT NULL COMMENT '文档标题',
    `source_type`       varchar(16)  NOT NULL DEFAULT 'UPLOAD' COMMENT '来源类型（UPLOAD 上传/API_SYNC 受授权同步）',
    `source_ref`        varchar(512) DEFAULT NULL COMMENT '来源位置（同步来源的受控标识，不抓取第三方站点）',
    `status`            varchar(16)  NOT NULL DEFAULT 'PENDING' COMMENT '状态（PENDING/PARSING/INDEXING/READY/FAILED/DELETING）',
    `active_version_no` int          NOT NULL DEFAULT 0 COMMENT '当前可用版本号（0 表示尚无可用版本；索引成功后才切换）',
    `latest_version_no` int          NOT NULL DEFAULT 0 COMMENT '最新版本号（0 表示尚无版本）',
    `failure_reason`    varchar(128) DEFAULT NULL COMMENT '最近失败原因（脱敏稳定原因码，不含正文）',
    `parse_note`        varchar(128) DEFAULT NULL COMMENT '解析提示（例如扫描件需要 OCR，K04 写入）',
    `version`           int          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`           varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`           varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_knowledge_document_source` ((if(`deleted` = b'1', NULL, concat(`knowledge_base_id`, ':', `source_key`)))),
    KEY `idx_ai_knowledge_document_base` (`knowledge_base_id`, `status`, `id`),
    CONSTRAINT `fk_ai_knowledge_document_base` FOREIGN KEY (`knowledge_base_id`) REFERENCES `ai_knowledge_base` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 知识文档（sourceKey 幂等 + active 版本指针，K02）';

CREATE TABLE `ai_knowledge_document_version`
(
    `id`                bigint       NOT NULL AUTO_INCREMENT COMMENT '版本编号',
    `document_id`       bigint       NOT NULL COMMENT '文档编号',
    `knowledge_base_id` bigint       NOT NULL COMMENT '知识库编号（冗余，供检索过滤与清理直接命中）',
    `version_no`        int          NOT NULL COMMENT '版本号（文档内递增，READY 后不可变）',
    `file_id`           bigint       NOT NULL COMMENT '私有文件编号（A07 业务文件：ai_knowledge_document + 知识库标识）',
    `content_hash`      char(64)     NOT NULL COMMENT '文件指纹（sha256 hex；同指纹重复入库不产生新版本）',
    `source_ref`        varchar(512) DEFAULT NULL COMMENT '来源位置（片段引用可追溯到该版本的来源）',
    `status`            varchar(16)  NOT NULL DEFAULT 'INDEXING' COMMENT '状态（INDEXING/READY/FAILED/SUPERSEDED）',
    `index_generation`  int          DEFAULT NULL COMMENT '切片所属索引代（READY 时必填）',
    `chunk_count`       int          NOT NULL DEFAULT 0 COMMENT '切片数（索引成功后写入）',
    `failure_reason`    varchar(128) DEFAULT NULL COMMENT '失败原因（脱敏稳定原因码）',
    `ready_at`          datetime     DEFAULT NULL COMMENT '可用时间（索引成功）',
    `version`           int          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`           varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`           varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_knowledge_version_no` ((if(`deleted` = b'1', NULL, concat(`document_id`, ':', `version_no`)))),
    KEY `idx_ai_knowledge_version_document` (`document_id`, `status`, `id`),
    KEY `idx_ai_knowledge_version_base` (`knowledge_base_id`, `index_generation`),
    CONSTRAINT `fk_ai_knowledge_version_document` FOREIGN KEY (`document_id`) REFERENCES `ai_knowledge_document` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_ai_knowledge_version_base` FOREIGN KEY (`knowledge_base_id`) REFERENCES `ai_knowledge_base` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 知识文档版本（私有文件绑定 + 发布后不可变，K02）';

CREATE TABLE `ai_knowledge_chunk`
(
    `id`                  bigint       NOT NULL AUTO_INCREMENT COMMENT '切片编号',
    `document_version_id` bigint       NOT NULL COMMENT '文档版本编号',
    `knowledge_base_id`   bigint       NOT NULL COMMENT '知识库编号（检索过滤与清理直接命中）',
    `chunk_index`         int          NOT NULL COMMENT '切片序号（版本内从 0 递增，决定引用顺序）',
    `content_hash`        char(64)     NOT NULL COMMENT '切片正文哈希（幂等与去重；正文存向量服务载荷）',
    `text_length`         int          NOT NULL COMMENT '正文长度（字符数，用于上下文预算）',
    `token_count`         int          NOT NULL DEFAULT 0 COMMENT '估算 token 数（预算与截断）',
    `vector_id`           varchar(64)  NOT NULL COMMENT '向量点标识（确定性 UUID，K01 映射）',
    `index_generation`    int          NOT NULL COMMENT '所属索引代',
    `location_ref`        varchar(128) DEFAULT NULL COMMENT '来源位置（页码/章节等，引用可核验）',
    `creator`             varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`             varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_knowledge_chunk_index` (`document_version_id`, `chunk_index`),
    KEY `idx_ai_knowledge_chunk_base` (`knowledge_base_id`, `index_generation`),
    CONSTRAINT `fk_ai_knowledge_chunk_version` FOREIGN KEY (`document_version_id`) REFERENCES `ai_knowledge_document_version` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_ai_knowledge_chunk_base` FOREIGN KEY (`knowledge_base_id`) REFERENCES `ai_knowledge_base` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 知识切片（派生数据，物理清理，K02）';

CREATE TABLE `ai_knowledge_index_generation`
(
    `id`                bigint       NOT NULL AUTO_INCREMENT COMMENT '索引代编号',
    `knowledge_base_id` bigint       NOT NULL COMMENT '知识库编号',
    `generation_no`     int          NOT NULL COMMENT '索引代序号（库内递增）',
    `embedding_model`   varchar(64)  NOT NULL COMMENT '嵌入模型标识（换模型必须换代，禁止混用向量，AT-029）',
    `dimension`         int          NOT NULL COMMENT '向量维度（必须与知识库声明一致）',
    `collection_name`   varchar(128) NOT NULL COMMENT '向量集合名（物理索引名）',
    `status`            varchar(16)  NOT NULL DEFAULT 'BUILDING' COMMENT '状态（BUILDING/ACTIVE/RETIRED/FAILED）',
    `chunk_count`       int          NOT NULL DEFAULT 0 COMMENT '本代切片数（激活时统计）',
    `document_count`    int          NOT NULL DEFAULT 0 COMMENT '本代文档数（激活时统计）',
    `failure_reason`    varchar(128) DEFAULT NULL COMMENT '失败原因（脱敏稳定原因码）',
    `activated_at`      datetime     DEFAULT NULL COMMENT '激活时间',
    `retired_at`        datetime     DEFAULT NULL COMMENT '退役时间',
    `version`           int          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `creator`           varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`           varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_ai_knowledge_generation_no` (`knowledge_base_id`, `generation_no`),
    KEY `idx_ai_knowledge_generation_status` (`knowledge_base_id`, `status`, `id`),
    CONSTRAINT `fk_ai_knowledge_generation_base` FOREIGN KEY (`knowledge_base_id`) REFERENCES `ai_knowledge_base` (`id`) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC COMMENT ='AI 知识索引代（版本化索引，K02）';

-- AI 知识库菜单与权限点（4090-4095，挂在 4000 AI 中台下）
INSERT INTO `system_menu`
(`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
 `icon`, `component`, `component_name`, `status`, `visible`,
 `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (4090, 'AI 知识库', 'ai:knowledge:query', 2, 10, 4000, 'knowledge', 'ep:notebook',
        'ai/knowledge/index', 'AiKnowledge', 0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1',
        CURRENT_TIMESTAMP, b'0'),
       (4091, '知识库新增', 'ai:knowledge:create', 3, 1, 4090, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4092, '知识库修改', 'ai:knowledge:update', 3, 2, 4090, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4093, '知识库删除', 'ai:knowledge:delete', 3, 3, 4090, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4094, '文档入库', 'ai:knowledge:ingest', 3, 4, 4090, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
       (4095, '文档版本管理', 'ai:knowledge:version', 3, 5, 4090, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');
