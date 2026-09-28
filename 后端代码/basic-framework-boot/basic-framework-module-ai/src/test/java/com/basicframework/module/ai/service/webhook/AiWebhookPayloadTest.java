package com.basicframework.module.ai.service.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Webhook 投递正文（X10）：字段固定、无凭据无正文、只带状态与资源引用。
 *
 * <p>正文是接收端校验签名的输入，所以它必须**逐字节稳定**：同一事件重复构造的结果完全一致，
 * 且不含任何随请求变化的值（时间戳与尝试次数只在请求头里）。
 */
class AiWebhookPayloadTest {

    @Test
    void payloadContainsOnlyEventResourceAndStatus() {
        String payload = AiWebhookPayload.build(
                "RUN.SUCCEEDED", "run_abc123", "SUCCEEDED", LocalDateTime.of(2026, 9, 27, 10, 30, 5));

        assertThat(payload)
                .isEqualTo("{\"schemaVersion\":\"1.0\",\"eventType\":\"RUN.SUCCEEDED\",\"resourceType\":\"RUN\","
                        + "\"resourceKey\":\"run_abc123\",\"status\":\"SUCCEEDED\","
                        + "\"occurredAt\":\"2026-09-27T10:30:05\"}");
        // 不含地址、密钥、令牌、正文与提示词
        assertThat(payload)
                .doesNotContain("http")
                .doesNotContain("secret")
                .doesNotContain("token")
                .doesNotContain("prompt")
                .doesNotContain("@");
    }

    @Test
    void payloadIsByteStableAcrossBuilds() {
        LocalDateTime occurred = LocalDateTime.of(2026, 9, 27, 10, 30, 5);

        assertThat(AiWebhookPayload.build("RUN.FAILED", "run_x", "FAILED", occurred))
                .isEqualTo(AiWebhookPayload.build("RUN.FAILED", "run_x", "FAILED", occurred));
    }

    @Test
    void unknownOccurrenceTimeIsOmittedInsteadOfFakedAsNow() {
        String payload = AiWebhookPayload.build("RUN.CANCELLED", "run_x", "CANCELLED", null);

        // 序列化器按"不写空值"输出：缺失的发生时间不会被伪造成当前时间
        assertThat(payload).doesNotContain("occurredAt");
    }

    @Test
    void schemaVersionIsFrozen() {
        assertThat(AiWebhookPayload.SCHEMA_VERSION).isEqualTo("1.0");
        assertThat(AiWebhookEventTypes.supported()).hasSize(3);
        assertThat(List.of(AiWebhookPayload.SCHEMA_VERSION)).containsExactly("1.0");
    }
}
