package com.basicframework.module.ai.service.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelUsage;
import org.junit.jupiter.api.Test;

/**
 * M05 计量记录：UNKNOWN 与 ESTIMATED 的区别、失败只留稳定原因、调用标识每次唯一。
 */
class AiModelInvocationMeterTest {

    @Test
    void vendorUsageIsRecordedAsMeasured() {
        AiModelInvocationRecord record = AiModelInvocationMeter.succeeded(
                "inv-1", 9L, 2, 3, "TEXT", ModelUsage.of(12, 5), 88L, false, 4, "输出正文不参与计量");

        assertThat(record.succeeded()).isTrue();
        assertThat(record.getPromptTokens()).isEqualTo(12);
        assertThat(record.getCompletionTokens()).isEqualTo(5);
        assertThat(record.isEstimated()).isFalse();
        assertThat(record.usageKnown()).isTrue();
        assertThat(record.getLatencyMs()).isEqualTo(88L);
        assertThat(record.getCapability()).isEqualTo("TEXT");
        assertThat(record.getCredentialRevision()).isEqualTo(3);
    }

    @Test
    void missingUsageStaysUnknownAndIsNeverWrittenAsZero() {
        AiModelInvocationRecord record = AiModelInvocationMeter.succeeded(
                "inv-2", 9L, 2, 3, "TEXT", ModelUsage.UNKNOWN, 5L, false, 4, "some output");

        assertThat(record.usageKnown()).isFalse();
        assertThat(record.getPromptTokens()).isNull();
        assertThat(record.getCompletionTokens()).isNull();
        assertThat(record.isEstimated()).isFalse();
    }

    @Test
    void estimationOnlyWhenEnabledAndAlwaysMarkedEstimated() {
        AiModelInvocationRecord estimated = AiModelInvocationMeter.succeeded(
                "inv-3", 9L, 2, 3, "TEXT", ModelUsage.UNKNOWN, 5L, true, 4, "123456789");

        assertThat(estimated.isEstimated()).as("估算值必须标记，不能冒充真实计量").isTrue();
        assertThat(estimated.getCompletionTokens()).isEqualTo(3);
        assertThat(estimated.usageKnown()).isTrue();

        // 上游给了真实值时不会被估算覆盖
        AiModelInvocationRecord measured = AiModelInvocationMeter.succeeded(
                "inv-4", 9L, 2, 3, "TEXT", ModelUsage.of(7, 1), 5L, true, 4, "123456789");
        assertThat(measured.isEstimated()).isFalse();
        assertThat(measured.getPromptTokens()).isEqualTo(7);
    }

    @Test
    void estimationRoundsUpAndNeverInventsTokensForEmptyOutput() {
        assertThat(AiModelInvocationMeter.estimateTokens(null, 4)).isZero();
        assertThat(AiModelInvocationMeter.estimateTokens("", 4)).isZero();
        assertThat(AiModelInvocationMeter.estimateTokens("abc", 4)).isEqualTo(1);
        assertThat(AiModelInvocationMeter.estimateTokens("abcde", 4)).isEqualTo(2);
        assertThat(AiModelInvocationMeter.estimateTokens("abc", 0)).isEqualTo(3);
    }

    @Test
    void failureRecordKeepsStableReasonOnly() {
        AiModelInvocationRecord record = AiModelInvocationMeter.failed(
                "inv-5", 9L, 2, 3, "STRUCTURED_OUTPUT", ModelException.Reason.INVALID_STRUCTURED_OUTPUT, 42L);

        assertThat(record.succeeded()).isFalse();
        assertThat(record.getStatus()).isEqualTo(AiModelInvocationMeter.STATUS_FAILED);
        assertThat(record.getErrorReason()).isEqualTo("INVALID_STRUCTURED_OUTPUT");
        assertThat(record.getLatencyMs()).isEqualTo(42L);
        assertThat(record.usageKnown()).isFalse();
        assertThat(record.toString()).doesNotContain("credentialRevision=3");
    }

    @Test
    void invocationIdIsUniquePerCall() {
        String first = AiModelInvocationMeter.newInvocationId();
        String second = AiModelInvocationMeter.newInvocationId();

        assertThat(first).isNotBlank().isNotEqualTo(second);
    }
}
