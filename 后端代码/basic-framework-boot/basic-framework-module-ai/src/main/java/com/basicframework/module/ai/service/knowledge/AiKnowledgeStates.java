package com.basicframework.module.ai.service.knowledge;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_BASE_CONFIG_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_SOURCE_KEY_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_KNOWLEDGE_VERSION_STATE_INVALID;

import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentVersionDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeIndexGenerationDO;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 知识库状态与取值约束（K02）：白名单 + 状态机，全部是纯函数（可单测、可复用）。
 *
 * <p>为什么集中在一处：状态词表同时被迁移注释、服务层、控制器与文档引用；
 * 分散写会导致"库里出现未知状态"这类只能靠人工发现的问题。未知取值一律**拒绝**，
 * 不做默认回退（回退会把拼错的 status 变成"可用"）。
 *
 * <p>状态机（允许的转移）：
 * <pre>
 *   文档：PENDING → PARSING → INDEXING → READY / FAILED；READY/FAILED → DELETING
 *   版本：INDEXING → READY / FAILED；READY → SUPERSEDED（新版本可用时）
 *   索引代：BUILDING → ACTIVE / FAILED；ACTIVE → RETIRED
 * </pre>
 */
public final class AiKnowledgeStates {

    /** 知识库可见性白名单。 */
    public static final Set<String> VISIBILITIES =
            Set.of(AiKnowledgeBaseDO.VISIBILITY_SHARED, AiKnowledgeBaseDO.VISIBILITY_APPLICATION);

    /** 知识库状态白名单。 */
    public static final Set<String> BASE_STATUSES =
            Set.of(AiKnowledgeBaseDO.STATUS_ENABLED, AiKnowledgeBaseDO.STATUS_DISABLED);

    /** 文档状态白名单。 */
    public static final Set<String> DOCUMENT_STATUSES = Set.of(
            AiKnowledgeDocumentDO.STATUS_PENDING,
            AiKnowledgeDocumentDO.STATUS_PARSING,
            AiKnowledgeDocumentDO.STATUS_INDEXING,
            AiKnowledgeDocumentDO.STATUS_READY,
            AiKnowledgeDocumentDO.STATUS_FAILED,
            AiKnowledgeDocumentDO.STATUS_DELETING);

    /** 文档来源类型白名单。 */
    public static final Set<String> SOURCE_TYPES =
            Set.of(AiKnowledgeDocumentDO.SOURCE_UPLOAD, AiKnowledgeDocumentDO.SOURCE_API_SYNC);

    /** 版本状态白名单。 */
    public static final Set<String> VERSION_STATUSES = Set.of(
            AiKnowledgeDocumentVersionDO.STATUS_INDEXING,
            AiKnowledgeDocumentVersionDO.STATUS_READY,
            AiKnowledgeDocumentVersionDO.STATUS_FAILED,
            AiKnowledgeDocumentVersionDO.STATUS_SUPERSEDED);

    /** 索引代状态白名单。 */
    public static final Set<String> GENERATION_STATUSES = Set.of(
            AiKnowledgeIndexGenerationDO.STATUS_BUILDING,
            AiKnowledgeIndexGenerationDO.STATUS_ACTIVE,
            AiKnowledgeIndexGenerationDO.STATUS_RETIRED,
            AiKnowledgeIndexGenerationDO.STATUS_FAILED);

    /** 知识库标识：与数据集/工具一致的小写标识模式。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]{2,63}$");

    /**
     * sourceKey：不透明幂等键（外部系统标识或上传标识），允许 `a.b:c/d-e` 这类形态。
     * 只用于等值比较与存储，不参与任何路径解析，因此允许斜杠是安全的；空白与引号一律拒绝。
     */
    private static final Pattern SOURCE_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$");

    /** 嵌入模型标识：与模型端点命名一致的标识字符。 */
    private static final Pattern MODEL_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$");

    /** 内容指纹：sha256 hex。 */
    private static final Pattern HASH_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    /** 嵌入维度上限（K01：维度是索引物理约束，超上限的声明一定是配置错误）。 */
    public static final int MAX_DIMENSION = 8_192;

    /** 保留策略上限（天）。 */
    public static final int MAX_RETENTION_DAYS = 3_650;

    private AiKnowledgeStates() {}

    /** 校验知识库标识。 */
    public static String requireCode(String code) {
        String normalized = code == null ? null : code.trim().toLowerCase(Locale.ROOT);
        if (normalized == null || !CODE_PATTERN.matcher(normalized).matches()) {
            throw exception(AI_KNOWLEDGE_BASE_CONFIG_INVALID);
        }
        return normalized;
    }

    /** 校验可见性。 */
    public static String requireVisibility(String visibility) {
        return requireIn(VISIBILITIES, visibility, AI_KNOWLEDGE_BASE_CONFIG_INVALID);
    }

    /** 校验知识库状态。 */
    public static String requireBaseStatus(String status) {
        return requireIn(BASE_STATUSES, status, AI_KNOWLEDGE_BASE_CONFIG_INVALID);
    }

    /** 校验文档状态。 */
    public static String requireDocumentStatus(String status) {
        return requireIn(DOCUMENT_STATUSES, status, AI_KNOWLEDGE_VERSION_STATE_INVALID);
    }

    /** 校验版本状态。 */
    public static String requireVersionStatus(String status) {
        return requireIn(VERSION_STATUSES, status, AI_KNOWLEDGE_VERSION_STATE_INVALID);
    }

    /** 校验索引代状态。 */
    public static String requireGenerationStatus(String status) {
        return requireIn(GENERATION_STATUSES, status, AI_KNOWLEDGE_VERSION_STATE_INVALID);
    }

    /** 校验来源类型（缺省按上传）。 */
    public static String requireSourceType(String sourceType) {
        return requireIn(
                SOURCE_TYPES,
                sourceType == null ? AiKnowledgeDocumentDO.SOURCE_UPLOAD : sourceType,
                AI_KNOWLEDGE_BASE_CONFIG_INVALID);
    }

    /** 校验 sourceKey（幂等键）。 */
    public static String requireSourceKey(String sourceKey) {
        if (sourceKey == null || !SOURCE_KEY_PATTERN.matcher(sourceKey.trim()).matches()) {
            throw exception(AI_KNOWLEDGE_SOURCE_KEY_INVALID);
        }
        return sourceKey.trim();
    }

    /** 校验嵌入模型标识。 */
    public static String requireEmbeddingModel(String model) {
        if (model == null || !MODEL_PATTERN.matcher(model.trim()).matches()) {
            throw exception(AI_KNOWLEDGE_BASE_CONFIG_INVALID);
        }
        return model.trim();
    }

    /** 校验嵌入维度。 */
    public static int requireDimension(Integer dimension) {
        if (dimension == null || dimension <= 0 || dimension > MAX_DIMENSION) {
            throw exception(AI_KNOWLEDGE_BASE_CONFIG_INVALID);
        }
        return dimension;
    }

    /** 校验保留策略。 */
    public static int requireRetentionDays(Integer retentionDays) {
        int days = retentionDays == null ? 365 : retentionDays;
        if (days <= 0 || days > MAX_RETENTION_DAYS) {
            throw exception(AI_KNOWLEDGE_BASE_CONFIG_INVALID);
        }
        return days;
    }

    /** 校验内容指纹。 */
    public static String requireContentHash(String contentHash) {
        String normalized = contentHash == null ? null : contentHash.trim().toLowerCase(Locale.ROOT);
        if (normalized == null || !HASH_PATTERN.matcher(normalized).matches()) {
            throw exception(AI_KNOWLEDGE_SOURCE_KEY_INVALID);
        }
        return normalized;
    }

    /** 校验向量点标识（K01 的确定性 UUID）。 */
    public static String requireVectorId(String vectorId) {
        if (vectorId == null || vectorId.isBlank() || vectorId.length() > 64) {
            throw exception(AI_KNOWLEDGE_SOURCE_KEY_INVALID);
        }
        return vectorId.trim();
    }

    /** 文档状态机：允许的转移。 */
    public static boolean documentTransitionAllowed(String from, String to) {
        return switch (String.valueOf(from)) {
            case AiKnowledgeDocumentDO.STATUS_PENDING ->
                List.of(
                                AiKnowledgeDocumentDO.STATUS_PARSING,
                                AiKnowledgeDocumentDO.STATUS_INDEXING,
                                AiKnowledgeDocumentDO.STATUS_FAILED,
                                AiKnowledgeDocumentDO.STATUS_DELETING)
                        .contains(to);
            case AiKnowledgeDocumentDO.STATUS_PARSING ->
                List.of(
                                AiKnowledgeDocumentDO.STATUS_INDEXING,
                                AiKnowledgeDocumentDO.STATUS_FAILED,
                                AiKnowledgeDocumentDO.STATUS_DELETING)
                        .contains(to);
            case AiKnowledgeDocumentDO.STATUS_INDEXING ->
                List.of(
                                AiKnowledgeDocumentDO.STATUS_READY,
                                AiKnowledgeDocumentDO.STATUS_FAILED,
                                AiKnowledgeDocumentDO.STATUS_DELETING)
                        .contains(to);
            case AiKnowledgeDocumentDO.STATUS_READY ->
                List.of(AiKnowledgeDocumentDO.STATUS_INDEXING, AiKnowledgeDocumentDO.STATUS_DELETING)
                        .contains(to);
            case AiKnowledgeDocumentDO.STATUS_FAILED ->
                List.of(AiKnowledgeDocumentDO.STATUS_INDEXING, AiKnowledgeDocumentDO.STATUS_DELETING)
                        .contains(to);
            case AiKnowledgeDocumentDO.STATUS_DELETING -> false;
            default -> false;
        };
    }

    /** 索引代状态机：允许的转移。 */
    public static boolean generationTransitionAllowed(String from, String to) {
        return switch (String.valueOf(from)) {
            case AiKnowledgeIndexGenerationDO.STATUS_BUILDING ->
                List.of(AiKnowledgeIndexGenerationDO.STATUS_ACTIVE, AiKnowledgeIndexGenerationDO.STATUS_FAILED)
                        .contains(to);
            case AiKnowledgeIndexGenerationDO.STATUS_ACTIVE -> AiKnowledgeIndexGenerationDO.STATUS_RETIRED.equals(to);
            default -> false;
        };
    }

    private static String requireIn(
            Set<String> allowed, String value, com.basicframework.framework.common.exception.ErrorCode code) {
        String normalized = value == null ? null : value.trim().toUpperCase(Locale.ROOT);
        if (normalized == null || !allowed.contains(normalized)) {
            throw exception(code);
        }
        return normalized;
    }
}
