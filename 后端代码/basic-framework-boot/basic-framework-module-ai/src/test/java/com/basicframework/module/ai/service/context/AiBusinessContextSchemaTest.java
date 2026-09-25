package com.basicframework.module.ai.service.context;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONTEXT_SCHEMA_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

/** C08 业务上下文协议：逐键形状校验、身份字段进不来、键序稳定。 */
class AiBusinessContextSchemaTest {

    private static void assertRejected(String raw) {
        assertThatThrownBy(() -> AiBusinessContextSchema.normalize(raw))
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(AI_CONTEXT_SCHEMA_INVALID.getCode()));
    }

    @Test
    void acceptsRegisteredFieldsAndNormalizesKeyOrder() {
        String normalized = AiBusinessContextSchema.normalize(
                "{\"timezone\":\"Asia/Shanghai\",\"objectId\":\"order-1\",\"page\":\"crm/order\","
                        + "\"objectType\":\"order\",\"locale\":\"zh-CN\","
                        + "\"filters\":{\"status\":\"PAID\",\"amount\":[1,2]}}");

        assertThat(normalized)
                .isEqualTo("{\"page\":\"crm/order\",\"objectType\":\"order\",\"objectId\":\"order-1\","
                        + "\"filters\":{\"status\":\"PAID\",\"amount\":[1,2]},\"locale\":\"zh-CN\","
                        + "\"timezone\":\"Asia/Shanghai\"}");
        // 键序稳定：同一份内容重复校验结果一致（运行摘要/幂等键依赖它）
        assertThat(AiBusinessContextSchema.normalize(normalized)).isEqualTo(normalized);
    }

    @Test
    void emptyContextIsAllowed() {
        assertThat(AiBusinessContextSchema.normalize(null)).isNull();
        assertThat(AiBusinessContextSchema.normalize("  ")).isNull();
    }

    @Test
    void identityAndScopeFieldsAreRejectedBecauseTheyAreNotRegistered() {
        for (String raw : new String[] {
            "{\"appCode\":\"crm-portal\"}",
            "{\"subjectId\":\"alice\"}",
            "{\"externalUserId\":\"alice\"}",
            "{\"scope\":[\"org:10\"]}",
            "{\"applicationId\":1}",
            "{\"deleted\":false}"
        }) {
            assertRejected(raw);
        }
    }

    @Test
    void malformedOrOversizedPayloadsAreRejected() {
        assertRejected("not-json");
        assertRejected("[1,2]");
        assertRejected("null");
        assertRejected("{\"page\":\"" + "a".repeat(129) + "\"}");
        assertRejected("{\"page\":\"" + "a".repeat(4000) + "\"}");
    }

    @Test
    void perKeyShapesAreValidated() {
        // page：受限字符集
        assertRejected("{\"page\":\"<script>alert(1)</script>\"}");
        assertRejected("{\"page\":\"\"}");
        assertRejected("{\"page\":12}");
        // objectType：小写标识符
        assertRejected("{\"objectType\":\"Order\"}");
        assertRejected("{\"objectType\":\"order;drop\"}");
        // objectId：不透明字符串（不做编号解析）
        assertThat(AiBusinessContextSchema.normalize("{\"objectId\":\"ord_ABC-123\"}"))
                .isEqualTo("{\"objectId\":\"ord_ABC-123\"}");
        assertRejected("{\"objectId\":123}");
        assertRejected("{\"objectId\":\"\"}");
        // locale / timezone
        assertRejected("{\"locale\":\"chinese\"}");
        assertRejected("{\"timezone\":\"Mars/Olympus\"}");
        assertThat(AiBusinessContextSchema.normalize("{\"timezone\":\"UTC\"}")).isEqualTo("{\"timezone\":\"UTC\"}");
        assertThat(AiBusinessContextSchema.normalize("{\"timezone\":\"+08:00\"}"))
                .isEqualTo("{\"timezone\":\"+08:00\"}");
    }

    @Test
    void filtersAcceptScalarsAndScalarArraysOnly() {
        assertThat(AiBusinessContextSchema.normalize("{\"filters\":{\"ok\":true,\"n\":3}}"))
                .isEqualTo("{\"filters\":{\"ok\":true,\"n\":3}}");
        // 嵌套对象不是筛选条件
        assertRejected("{\"filters\":{\"a\":{\"b\":1}}}");
        assertRejected("{\"filters\":{\"A\":1}}");
        assertRejected("{\"filters\":[1,2]}");
        assertRejected("{\"filters\":{\"a\":\"" + "x".repeat(257) + "\"}}");
    }

    @Test
    void registeredKeysMatchDesignContract() {
        assertThat(AiBusinessContextSchema.REGISTERED_KEYS)
                .containsExactly("page", "objectType", "objectId", "filters", "locale", "timezone");
        assertThat(AiBusinessContextSchema.isRegisteredKey("appCode")).isFalse();
        assertThat(AiBusinessContextSchema.isRegisteredKey(null)).isFalse();
    }
}
