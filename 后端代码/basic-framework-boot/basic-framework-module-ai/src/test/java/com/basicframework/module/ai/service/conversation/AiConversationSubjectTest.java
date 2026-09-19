package com.basicframework.module.ai.service.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.ai.domain.identity.AiSubjectType;
import org.junit.jupiter.api.Test;

/** O01 会话主体：归属比较只用应用 + 主体类型 + 外部用户标识，空值按空串归一。 */
class AiConversationSubjectTest {

    private final AiConversationSubject subject = new AiConversationSubject(5L, AiSubjectType.USER, "u-1001");

    @Test
    void matchesOnlyTheSameOwner() {
        assertThat(subject.sameAs(5L, "USER", "u-1001")).isTrue();
        assertThat(subject.sameAs(6L, "USER", "u-1001")).as("换应用不算本人").isFalse();
        assertThat(subject.sameAs(5L, "APP", "u-1001")).as("换主体类型不算本人").isFalse();
        assertThat(subject.sameAs(5L, "USER", "u-2002")).as("换外部用户不算本人").isFalse();
        assertThat(subject.sameAs(null, "USER", "u-1001")).isFalse();
        assertThat(subject.sameAs(5L, null, "u-1001")).isFalse();
    }

    @Test
    void normalizesMissingExternalUserIdToEmptyString() {
        AiConversationSubject app = new AiConversationSubject(5L, AiSubjectType.APP, null);

        assertThat(app.externalUserId()).isEmpty();
        assertThat(app.subjectTypeName()).isEqualTo("APP");
        assertThat(app.sameAs(5L, "APP", null)).isTrue();
        assertThat(app.sameAs(5L, "APP", "")).isTrue();
        assertThat(app.sameAs(5L, "USER", null)).isFalse();
    }
}
