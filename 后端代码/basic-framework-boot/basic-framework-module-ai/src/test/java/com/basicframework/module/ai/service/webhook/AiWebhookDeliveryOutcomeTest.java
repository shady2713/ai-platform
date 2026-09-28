package com.basicframework.module.ai.service.webhook;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WEBHOOK_DELIVERY_EXHAUSTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WEBHOOK_TARGET_DISABLED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WEBHOOK_TARGET_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookDeliveryAttemptDO;
import org.junit.jupiter.api.Test;

/**
 * 投递结论与失败词表（X10）：结论唯一、可重试与确定失败不混、词表与错误码目录一致。
 *
 * <p>失败码是落库事实，词表必须封闭且与 {@code AiErrorCodeConstants} 的平台码一致——
 * 这里的断言让"改了错误码忘了改词表"在单测阶段就红。
 */
class AiWebhookDeliveryOutcomeTest {

    @Test
    void deliveredOutcomeCarriesStatusAndTimestamp() {
        AiWebhookDeliveryOutcome outcome = AiWebhookDeliveryOutcome.delivered(200, 1_790_000_000L, 12L);

        assertThat(outcome.isDelivered()).isTrue();
        assertThat(outcome.isRetryable()).isFalse();
        assertThat(outcome.isPermanent()).isFalse();
        assertThat(outcome.errorCode()).isNull();
        assertThat(outcome.httpStatus()).isEqualTo(200);
        assertThat(outcome.signatureTimestamp()).isEqualTo(1_790_000_000L);
        assertThat(outcome.durationMs()).isEqualTo(12L);
    }

    @Test
    void retryableAndPermanentOutcomesAreDistinct() {
        AiWebhookDeliveryOutcome retryable =
                AiWebhookDeliveryOutcome.retryable(AiWebhookFailureCodes.TIMEOUT, null, 7L, 3L);
        AiWebhookDeliveryOutcome permanent =
                AiWebhookDeliveryOutcome.permanent(AiWebhookFailureCodes.REDIRECT_NOT_FOLLOWED, 302, 7L, 3L);

        assertThat(retryable.isRetryable()).isTrue();
        assertThat(retryable.isPermanent()).isFalse();
        assertThat(retryable.isDelivered()).isFalse();
        assertThat(permanent.isPermanent()).isTrue();
        assertThat(permanent.isRetryable()).isFalse();
        assertThat(permanent.errorCode()).isEqualTo("redirect-not-followed");
        assertThat(permanent.httpStatus()).isEqualTo(302);
    }

    @Test
    void attemptOutcomeVocabularyMatchesTheAttemptRecord() {
        assertThat(AiWebhookDeliveryOutcome.OUTCOME_DELIVERED).isEqualTo(AiWebhookDeliveryAttemptDO.OUTCOME_DELIVERED);
        assertThat(AiWebhookDeliveryOutcome.OUTCOME_RETRYABLE).isEqualTo(AiWebhookDeliveryAttemptDO.OUTCOME_RETRYABLE);
        assertThat(AiWebhookDeliveryOutcome.OUTCOME_PERMANENT).isEqualTo(AiWebhookDeliveryAttemptDO.OUTCOME_PERMANENT);
        assertThat(AiWebhookDeliveryOutcome.delivered(200, 1L, 0L).attemptOutcome())
                .isEqualTo(AiWebhookDeliveryAttemptDO.OUTCOME_DELIVERED);
    }

    @Test
    void platformFailureCodesMirrorTheRegisteredErrorCodes() {
        assertThat(AiWebhookFailureCodes.TARGET_NOT_FOUND)
                .isEqualTo(AiWebhookFailureCodes.platform(AI_WEBHOOK_TARGET_NOT_FOUND))
                .isEqualTo("1_003_011_000");
        assertThat(AiWebhookFailureCodes.TARGET_DISABLED)
                .isEqualTo(AiWebhookFailureCodes.platform(AI_WEBHOOK_TARGET_DISABLED))
                .isEqualTo("1_003_011_001");
        assertThat(AiWebhookFailureCodes.DELIVERY_EXHAUSTED)
                .isEqualTo(AiWebhookFailureCodes.platform(AI_WEBHOOK_DELIVERY_EXHAUSTED))
                .isEqualTo("1_003_011_006");
    }

    @Test
    void transportFailureCodesAreTheClosedVocabulary() {
        assertThat(AiWebhookFailureCodes.TARGET_NOT_ALLOWED).isEqualTo("target-not-allowed");
        assertThat(AiWebhookFailureCodes.PRIVATE_TARGET_DENIED).isEqualTo("private-target-denied");
        assertThat(AiWebhookFailureCodes.REDIRECT_NOT_FOLLOWED).isEqualTo("redirect-not-followed");
        assertThat(AiWebhookFailureCodes.HTTP_CLIENT_ERROR).isEqualTo("http-client-error");
        assertThat(AiWebhookFailureCodes.HTTP_SERVER_ERROR).isEqualTo("http-server-error");
        assertThat(AiWebhookFailureCodes.HTTP_RATE_LIMITED).isEqualTo("http-rate-limited");
        assertThat(AiWebhookFailureCodes.TIMEOUT).isEqualTo("timeout");
        assertThat(AiWebhookFailureCodes.CONNECT_FAILED).isEqualTo("connect-failed");
        assertThat(AiWebhookFailureCodes.INTERNAL_ERROR).isEqualTo("internal-error");
        assertThat(AiWebhookFailureCodes.REQUEST_INVALID).isEqualTo("request-invalid");
        assertThat(AiWebhookFailureCodes.RESPONSE_TOO_LARGE).isEqualTo("response-too-large");
        assertThat(AiWebhookFailureCodes.SIGNING_KEY_UNAVAILABLE).isEqualTo("signing-key-unavailable");
    }

    @Test
    void platformFormattingFallsBackForNonStandardCodeWidth() {
        assertThat(AiWebhookFailureCodes.platform(
                        new com.basicframework.framework.common.exception.ErrorCode(1_003_001_000, "任意")))
                .isEqualTo("1_003_001_000");
        assertThat(AiWebhookFailureCodes.platform(
                        new com.basicframework.framework.common.exception.ErrorCode(123, "非标准宽度")))
                .isEqualTo("123");
    }
}
