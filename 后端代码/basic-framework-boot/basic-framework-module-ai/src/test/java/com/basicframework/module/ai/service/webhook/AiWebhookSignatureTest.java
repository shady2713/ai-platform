package com.basicframework.module.ai.service.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Webhook 签名协议（X10）：签名可验证、篡改与伪造被拒、时间戳越界被拒、密钥长度收窄。
 *
 * <p>这些断言语义上是**接收端样例的前置条件**：接收端按同一规范串与同一算法校验，
 * 因此这里覆盖的每条拒绝路径在集成测试里都用真实接收端再验一次。
 */
class AiWebhookSignatureTest {

    private static final String SECRET = "s3cret-signing-key-0123456789";

    private static final String DELIVERY_NO = "whd_0123456789abcdef0123456789abcdef";

    private static final String TIMESTAMP = "1790000000";

    private static final byte[] BODY =
            "{\"schemaVersion\":\"1.0\",\"eventType\":\"RUN.SUCCEEDED\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void signatureVerifiesWithTheSameSecretAndCanonicalForm() {
        String signature = AiWebhookSignature.sign(SECRET, TIMESTAMP, DELIVERY_NO, BODY);

        assertThat(signature).startsWith(AiWebhookSignature.SCHEME + "=");
        assertThat(AiWebhookSignature.verify(SECRET, TIMESTAMP, DELIVERY_NO, BODY, signature))
                .isTrue();
    }

    @Test
    void forgedOrTamperedSignatureIsRejected() {
        String signature = AiWebhookSignature.sign(SECRET, TIMESTAMP, DELIVERY_NO, BODY);

        // 换密钥、换正文、换编号、换时间戳、直接改签名，五种伪造都必须被拒
        assertThat(AiWebhookSignature.verify("another-signing-key-0123456789", TIMESTAMP, DELIVERY_NO, BODY, signature))
                .isFalse();
        assertThat(AiWebhookSignature.verify(
                        SECRET,
                        TIMESTAMP,
                        DELIVERY_NO,
                        "{\"status\":\"FAILED\"}".getBytes(StandardCharsets.UTF_8),
                        signature))
                .isFalse();
        assertThat(AiWebhookSignature.verify(SECRET, TIMESTAMP, "whd_ffffffff", BODY, signature))
                .isFalse();
        assertThat(AiWebhookSignature.verify(SECRET, "1790000001", DELIVERY_NO, BODY, signature))
                .isFalse();
        assertThat(AiWebhookSignature.verify(
                        SECRET, TIMESTAMP, DELIVERY_NO, BODY, signature.substring(0, signature.length() - 2) + "00"))
                .isFalse();
        assertThat(AiWebhookSignature.verify(SECRET, TIMESTAMP, DELIVERY_NO, BODY, null))
                .isFalse();
        assertThat(AiWebhookSignature.verify(null, TIMESTAMP, DELIVERY_NO, BODY, signature))
                .isFalse();
    }

    @Test
    void timestampOutsideToleranceIsRejected() {
        long now = 1_790_000_000L;

        assertThat(AiWebhookSignature.withinTolerance(now, now, AiWebhookSignature.DEFAULT_TOLERANCE))
                .isTrue();
        assertThat(AiWebhookSignature.withinTolerance(
                        now - (AiWebhookSignature.DEFAULT_TOLERANCE.toSeconds() - 1),
                        now,
                        AiWebhookSignature.DEFAULT_TOLERANCE))
                .isTrue();
        assertThat(AiWebhookSignature.withinTolerance(
                        now - (AiWebhookSignature.DEFAULT_TOLERANCE.toSeconds() + 1),
                        now,
                        AiWebhookSignature.DEFAULT_TOLERANCE))
                .isFalse();
        // 未来时间戳同样拒绝（双向窗口）
        assertThat(AiWebhookSignature.withinTolerance(now + 3600, now, null)).isFalse();
        assertThat(AiWebhookSignature.withinTolerance(now + 10, now, null)).isTrue();
        assertThat(AiWebhookSignature.withinTolerance(now, now, Duration.ofSeconds(1)))
                .isTrue();
    }

    @Test
    void canonicalFormAndDigestAreStable() {
        String canonical = AiWebhookSignature.canonical(TIMESTAMP, DELIVERY_NO, BODY);

        assertThat(canonical).isEqualTo(TIMESTAMP + "." + DELIVERY_NO + "." + AiWebhookSignature.sha256Hex(BODY));
        assertThat(AiWebhookSignature.sha256Hex(BODY)).hasSize(64).isLowerCase();
        assertThat(AiWebhookSignature.sha256Hex(BODY)).isEqualTo(AiWebhookSignature.sha256Hex(BODY));
        assertThat(AiWebhookSignature.sha256Hex(null)).isEqualTo(AiWebhookSignature.sha256Hex(new byte[0]));
    }

    @Test
    void secretLengthIsBounded() {
        assertThat(AiWebhookSignature.isAcceptableSecret(null)).isFalse();
        assertThat(AiWebhookSignature.isAcceptableSecret("short")).isFalse();
        assertThat(AiWebhookSignature.isAcceptableSecret("s".repeat(AiWebhookSignature.MIN_SECRET_LENGTH)))
                .isTrue();
        assertThat(AiWebhookSignature.isAcceptableSecret("s".repeat(AiWebhookSignature.MAX_SECRET_LENGTH)))
                .isTrue();
        assertThat(AiWebhookSignature.isAcceptableSecret("s".repeat(AiWebhookSignature.MAX_SECRET_LENGTH + 1)))
                .isFalse();
    }

    @Test
    void headerNamesAreFrozenProtocolSurface() {
        assertThat(AiWebhookSignature.HEADER_SIGNATURE).isEqualTo("X-AI-Webhook-Signature");
        assertThat(AiWebhookSignature.HEADER_TIMESTAMP).isEqualTo("X-AI-Webhook-Timestamp");
        assertThat(AiWebhookSignature.HEADER_DELIVERY_NO).isEqualTo("X-AI-Webhook-Id");
        assertThat(AiWebhookSignature.HEADER_EVENT).isEqualTo("X-AI-Webhook-Event");
        assertThat(AiWebhookSignature.HEADER_ATTEMPT).isEqualTo("X-AI-Webhook-Attempt");
        assertThat(AiWebhookSignature.HEADER_RESOURCE).isEqualTo("X-AI-Webhook-Resource");
        assertThat(AiWebhookSignature.ALGORITHM).isEqualTo("HmacSHA256");
    }
}
