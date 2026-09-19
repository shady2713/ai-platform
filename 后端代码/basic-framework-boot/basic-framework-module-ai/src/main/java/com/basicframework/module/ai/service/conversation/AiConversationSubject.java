package com.basicframework.module.ai.service.conversation;

import com.basicframework.module.ai.domain.identity.AiSubjectType;

/**
 * 会话主体（O01）：由服务端从 MEMBER 会话解析出的**可信身份**，不接受请求参数直接构造。
 *
 * <p>会话与消息的归属判定只用它：应用编号 + 主体类型 + 外部用户标识三者同时相等才算本人。
 */
public record AiConversationSubject(Long applicationId, AiSubjectType subjectType, String externalUserId) {

    public AiConversationSubject {
        externalUserId = externalUserId == null ? "" : externalUserId;
    }

    /** 是否与给定归属相同（本人）。 */
    public boolean sameAs(Long applicationId, String subjectType, String externalUserId) {
        return this.applicationId != null
                && this.applicationId.equals(applicationId)
                && subjectType != null
                && subjectType.equals(this.subjectType.name())
                && this.externalUserId.equals(externalUserId == null ? "" : externalUserId);
    }

    /** 主体类型名（写入归属列）。 */
    public String subjectTypeName() {
        return subjectType.name();
    }
}
