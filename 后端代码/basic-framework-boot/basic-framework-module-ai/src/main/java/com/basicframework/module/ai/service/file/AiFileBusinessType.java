package com.basicframework.module.ai.service.file;

import com.basicframework.module.ai.domain.policy.AiResourceType;
import java.util.Locale;
import java.util.Optional;

/**
 * AI 业务文件类型（A07）：把文件绑定到业务对象，并声明该类型的归属校验方式。
 *
 * <p>三种类型的归属判定不同：
 * <ul>
 *   <li>{@link #REPORT}：按 A03 授权目录判定（资源类型 REPORT，资源标识 = 报表键）；</li>
 *   <li>{@link #KNOWLEDGE_DOCUMENT}：按 A03 授权目录判定（资源类型 KNOWLEDGE_BASE，资源标识 = 知识库键）；</li>
 *   <li>{@link #CHAT_SESSION}：会话附件只属于上传主体本人（暂无授权目录语义）。</li>
 * </ul>
 * 未知类型一律拒绝：注册表（infra）与服务层都会失败，不做任何回退。
 */
public enum AiFileBusinessType {

    /** 报表附件。 */
    REPORT("ai_report", AiResourceType.REPORT),

    /** 知识文档。 */
    KNOWLEDGE_DOCUMENT("ai_knowledge_document", AiResourceType.KNOWLEDGE_BASE),

    /** 会话附件（归属上传主体本人）。 */
    CHAT_SESSION("ai_chat_session", null);

    private final String code;

    private final AiResourceType governedResourceType;

    AiFileBusinessType(String code, AiResourceType governedResourceType) {
        this.code = code;
        this.governedResourceType = governedResourceType;
    }

    /** 业务类型标识（与 infra 注册表、绑定表存储一致）。 */
    public String code() {
        return code;
    }

    /** 走授权目录判定时使用的资源类型；为空表示"仅所有者可访问"。 */
    public Optional<AiResourceType> governedResourceType() {
        return Optional.ofNullable(governedResourceType);
    }

    /** 解析业务类型；未知类型返回空（调用方按拒绝处理）。 */
    public static Optional<AiFileBusinessType> parse(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        for (AiFileBusinessType type : values()) {
            if (type.code.equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
