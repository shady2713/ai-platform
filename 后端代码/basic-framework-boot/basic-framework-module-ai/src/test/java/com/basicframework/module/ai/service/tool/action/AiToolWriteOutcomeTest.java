package com.basicframework.module.ai.service.tool.action;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.exception.util.ServiceExceptionUtil;
import com.basicframework.module.ai.adapter.connector.http.dto.AiConnectorExecutionResultDTO;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import org.junit.jupiter.api.Test;

/**
 * X06 写调用结果判定（AT-019）：只有"证明请求没发出/上游明确拒绝"才是 FAILED，
 * 超时、连接中断、5xx、响应不可用一律 UNKNOWN（不得盲目重放）。
 */
class AiToolWriteOutcomeTest {

    private static AiConnectorExecutionResultDTO failed(String detailCode) {
        return new AiConnectorExecutionResultDTO().setStatus("FAILED").setDetailCode(detailCode);
    }

    @Test
    void upstreamSuccessIsApplied() {
        assertThat(AiToolWriteOutcome.classifyResult(new AiConnectorExecutionResultDTO()
                                .setStatus("COMPLETE")
                                .setItemCount(1))
                        .verdict())
                .isEqualTo(AiToolWriteOutcome.Verdict.APPLIED);
        assertThat(AiToolWriteOutcome.classifyResult(new AiConnectorExecutionResultDTO()
                                .setStatus("PARTIAL")
                                .setItemCount(3))
                        .verdict())
                .isEqualTo(AiToolWriteOutcome.Verdict.APPLIED);
        assertThat(AiToolWriteOutcome.classifyResult(new AiConnectorExecutionResultDTO()
                                .setStatus("COMPLETE")
                                .setItemCount(1))
                        .reasonCode())
                .isEqualTo("COMPLETE");
    }

    @Test
    void onlyProvableRejectionsAreFailed() {
        assertThat(AiToolWriteOutcome.classifyResult(failed("HTTP_400")).verdict())
                .isEqualTo(AiToolWriteOutcome.Verdict.REJECTED);
        assertThat(AiToolWriteOutcome.classifyResult(failed("HTTP_409")).verdict())
                .isEqualTo(AiToolWriteOutcome.Verdict.REJECTED);
        assertThat(AiToolWriteOutcome.classifyResult(failed("HTTP_499")).verdict())
                .isEqualTo(AiToolWriteOutcome.Verdict.REJECTED);
        assertThat(AiToolWriteOutcome.classifyResult(failed("TARGET_NOT_ALLOWED"))
                        .verdict())
                .as("出站目标未允许：请求没有发出")
                .isEqualTo(AiToolWriteOutcome.Verdict.REJECTED);
        assertThat(AiToolWriteOutcome.classifyResult(failed("PRIVATE_TARGET_DENIED"))
                        .verdict())
                .isEqualTo(AiToolWriteOutcome.Verdict.REJECTED);
        assertThat(AiToolWriteOutcome.classifyResult(failed("INVALID_REQUEST")).verdict())
                .isEqualTo(AiToolWriteOutcome.Verdict.REJECTED);
        assertThat(AiToolWriteOutcome.classifyResult(failed("AI_DISABLED")).verdict())
                .as("没有受控出站客户端：请求从未发出")
                .isEqualTo(AiToolWriteOutcome.Verdict.REJECTED);
    }

    @Test
    void ambiguousOutcomesAreUnknown() {
        for (String detail : java.util.Arrays.asList(
                "TIMEOUT", "CONNECT_FAILED", "RESPONSE_TOO_LARGE", "HTTP_500", "HTTP_503", "HTTP_302", null)) {
            AiToolWriteOutcome.Classification classification = AiToolWriteOutcome.classifyResult(failed(detail));
            assertThat(classification.verdict())
                    .as("detailCode=%s 必须记为未定，而不是失败", detail)
                    .isEqualTo(AiToolWriteOutcome.Verdict.UNKNOWN);
            assertThat(classification.settled()).isFalse();
        }
        assertThat(AiToolWriteOutcome.classifyResult(null).verdict())
                .as("没有上游结果对象：未定")
                .isEqualTo(AiToolWriteOutcome.Verdict.UNKNOWN);
        assertThat(AiToolWriteOutcome.classifyResult(failed("HTTP_abc")).verdict())
                .isEqualTo(AiToolWriteOutcome.Verdict.UNKNOWN);
        assertThat(AiToolWriteOutcome.classifyResult(failed("")).verdict())
                .isEqualTo(AiToolWriteOutcome.Verdict.UNKNOWN);
    }

    @Test
    void failuresAreFailedOnlyWhenProvablyNotSent() {
        ServiceException connectorDisabled = ServiceExceptionUtil.exception(AiErrorCodeConstants.AI_CONNECTOR_DISABLED);
        assertThat(AiToolWriteOutcome.classifyFailure(connectorDisabled).verdict())
                .isEqualTo(AiToolWriteOutcome.Verdict.REJECTED);
        assertThat(AiToolWriteOutcome.classifyFailure(connectorDisabled).reasonCode())
                .isEqualTo(String.valueOf(AiErrorCodeConstants.AI_CONNECTOR_DISABLED.getCode()));

        // 未知异常（如读取中断）不能猜成失败
        AiToolWriteOutcome.Classification unknown =
                AiToolWriteOutcome.classifyFailure(new IllegalStateException("读取响应失败"));
        assertThat(unknown.verdict()).isEqualTo(AiToolWriteOutcome.Verdict.UNKNOWN);
        assertThat(unknown.reasonCode()).isEqualTo(AiToolWriteOutcome.REASON_UNCONFIRMED);
        assertThat(unknown.rejected()).isFalse();
    }
}
