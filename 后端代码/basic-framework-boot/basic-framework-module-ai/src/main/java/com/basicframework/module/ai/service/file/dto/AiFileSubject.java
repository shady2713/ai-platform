package com.basicframework.module.ai.service.file.dto;

import com.basicframework.module.ai.domain.identity.AiSubjectType;

/**
 * AI 文件主体（A07）：由服务端从 MEMBER 会话/票据解析出的**可信身份**，不接受请求参数直接构造。
 */
public record AiFileSubject(Long applicationId, AiSubjectType subjectType, String externalUserId, Long ticketId) {

    /** 是否为主体本人：用于"仅所有者可访问"的归属判定。 */
    public boolean sameAs(AiFileBindingOwner owner) {
        return owner != null
                && applicationId != null
                && applicationId.equals(owner.applicationId())
                && subjectType != null
                && subjectType.name().equals(owner.subjectType())
                && externalUserId != null
                && externalUserId.equals(owner.externalUserId());
    }

    /** 绑定记录的所有者视图。 */
    public record AiFileBindingOwner(Long applicationId, String subjectType, String externalUserId) {}
}
