package com.basicframework.framework.web.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

class SensitiveDataSanitizerTest {

    @Test
    void sanitizers_handleNullEmptyAndAbsentDataWithoutInventingContent() {
        assertThat(SensitiveDataSanitizer.sanitizeMap(null)).isNull();
        assertThat(SensitiveDataSanitizer.sanitizeMap(Map.of())).isNull();
        assertThat(SensitiveDataSanitizer.sanitizeJson(null)).isNull();
        assertThat(SensitiveDataSanitizer.sanitizeJson("  ")).isNull();
        assertThat(SensitiveDataSanitizer.sanitizeResult(null)).isNull();

        String sanitized = SensitiveDataSanitizer.sanitizeResult(CommonResult.success(null), new String[] {null, ""});

        assertThat(JsonUtils.parseTree(sanitized).get("code").asInt()).isZero();
    }

    @Test
    void sanitizeJson_removesCredentialAndSensitivePiiKeysRecursively() {
        String json =
                """
                {
                  "username": "visible",
                  "email": "contact@example.com",
                  "MOBILE-PHONE": "13800138000",
                  "old_password": "${TEST_SECRET}",
                  "NEW-PASSWORD": "${TEST_SECRET}",
                  "captcha_verification": "captcha-proof-value",
                  "nested": {
                    "telephone": "021-12345678",
                    "apiSecret": "${TEST_SECRET}",
                    "ACCESS_KEY": "${TEST_SECRET}",
                    "response-key": "${TEST_SECRET}",
                    "idCard": "${TEST_SECRET}"
                  },
                  "items": [{"refreshToken": "${TEST_SECRET}", "name": "kept"}]
                }
                """;

        JsonNode sanitized = JsonUtils.parseTree(SensitiveDataSanitizer.sanitizeJson(json));

        assertThat(sanitized.get("username").asText()).isEqualTo("visible");
        assertThat(sanitized.has("email")).isFalse();
        assertThat(sanitized.has("MOBILE-PHONE")).isFalse();
        assertThat(sanitized.has("old_password")).isFalse();
        assertThat(sanitized.has("NEW-PASSWORD")).isFalse();
        assertThat(sanitized.has("captcha_verification")).isFalse();
        assertThat(sanitized.get("nested").isEmpty()).isTrue();
        assertThat(sanitized.at("/items/0/refreshToken").isMissingNode()).isTrue();
        assertThat(sanitized.at("/items/0/name").asText()).isEqualTo("kept");
    }

    @Test
    void sanitizeJson_removesAdditionalKeyUsingNormalizedMatch() {
        String sanitized = SensitiveDataSanitizer.sanitizeJson(
                "{\"security_pin\":\"${TEST_SECRET}\",\"name\":\"kept\"}", "securityPin");

        assertThat(sanitized).doesNotContain("security_pin", "${TEST_SECRET}").contains("name", "kept");
    }

    @Test
    void sanitizeMap_removesDefaultAndAdditionalKeysWithoutChangingInput() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("Api-Key", "${TEST_SECRET}");
        input.put("email", "contact@example.com");
        input.put("mobile-phone", "13800138000");
        input.put("captcha-verification", "captcha-proof-value");
        input.put("pin", "${TEST_SECRET}");
        input.put("name", "kept");

        String sanitized = SensitiveDataSanitizer.sanitizeMap(input, "PIN");

        assertThat(sanitized)
                .doesNotContain("${TEST_SECRET}", "contact@example.com", "13800138000", "captcha-proof-value")
                .contains("name", "kept");
        assertThat(input).containsKeys("Api-Key", "email", "mobile-phone", "captcha-verification", "pin", "name");
    }

    @Test
    void sanitizeJson_returnsPlaceholderForInvalidJson() {
        assertThat(SensitiveDataSanitizer.sanitizeJson("not-json"))
                .isEqualTo(SensitiveDataSanitizer.SANITIZE_FAILURE_PLACEHOLDER);
    }

    @Test
    void sanitizeResult_removesSensitiveDataButKeepsResponseMetadata() {
        CommonResult<Map<String, String>> result = CommonResult.success(Map.of(
                "accessToken", "${TEST_SECRET}",
                "email", "contact@example.com",
                "name", "kept"));

        JsonNode sanitized = JsonUtils.parseTree(SensitiveDataSanitizer.sanitizeResult(result));

        assertThat(sanitized.get("code").asInt()).isEqualTo(0);
        assertThat(sanitized.at("/data/accessToken").isMissingNode()).isTrue();
        assertThat(sanitized.at("/data/email").isMissingNode()).isTrue();
        assertThat(sanitized.at("/data/name").asText()).isEqualTo("kept");
    }

    @Test
    void sanitizeResult_returnsPlaceholderWhenSerializationFailsWithoutLeakingTheCause() {
        CommonResult<ExplodingPayload> result = CommonResult.success(new ExplodingPayload());

        assertThat(SensitiveDataSanitizer.sanitizeResult(result))
                .isEqualTo(SensitiveDataSanitizer.SANITIZE_FAILURE_PLACEHOLDER)
                .doesNotContain("private serialization detail");
    }

    @Test
    void sanitizeRequestPath_usesRouteTemplateAndDropsUnmappedPathValues() {
        MockHttpServletRequest mappedRequest = new MockHttpServletRequest("GET", "/files/contact@example.com");
        mappedRequest.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/files/{fileName}");

        assertThat(SensitiveDataSanitizer.sanitizeRequestPath(mappedRequest)).isEqualTo("/files/{fileName}");
        assertThat(SensitiveDataSanitizer.sanitizeRequestPath(
                        new MockHttpServletRequest("GET", "/files/contact@example.com")))
                .isEqualTo(SensitiveDataSanitizer.UNMAPPED_REQUEST_PATH)
                .doesNotContain("contact@example.com");
    }

    private static final class ExplodingPayload {

        public String getValue() {
            throw new IllegalStateException("private serialization detail");
        }
    }
}
