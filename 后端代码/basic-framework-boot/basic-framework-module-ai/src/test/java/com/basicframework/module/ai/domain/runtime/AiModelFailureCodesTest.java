package com.basicframework.module.ai.domain.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import org.junit.jupiter.api.Test;

/** S04 模型失败映射：原因到平台错误码的稳定对应，缺省不猜测上游细节。 */
class AiModelFailureCodesTest {

    @Test
    void mapsPlatformAttributableReasonsToSpecificCodes() {
        assertThat(AiModelFailureCodes.of(ModelException.Reason.ENDPOINT_NOT_FOUND)
                        .getCode())
                .isEqualTo(AiErrorCodeConstants.AI_MODEL_ENDPOINT_NOT_FOUND.getCode());
        assertThat(AiModelFailureCodes.of(ModelException.Reason.ENDPOINT_DISABLED)
                        .getCode())
                .isEqualTo(AiErrorCodeConstants.AI_MODEL_ENDPOINT_DISABLED.getCode());
        assertThat(AiModelFailureCodes.of(ModelException.Reason.CAPABILITY_UNSUPPORTED)
                        .getCode())
                .isEqualTo(AiErrorCodeConstants.AI_MODEL_CAPABILITY_UNSUPPORTED.getCode());
        assertThat(AiModelFailureCodes.of(ModelException.Reason.TARGET_NOT_ALLOWED)
                        .getCode())
                .isEqualTo(AiErrorCodeConstants.AI_MODEL_OUTBOUND_BLOCKED.getCode());
        assertThat(AiModelFailureCodes.of(ModelException.Reason.RATE_LIMITED).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_QUOTA_EXCEEDED.getCode());
    }

    @Test
    void convergesEverythingElseToTheGenericModelFailure() {
        for (ModelException.Reason reason : new ModelException.Reason[] {
            ModelException.Reason.AI_DISABLED,
            ModelException.Reason.CREDENTIAL_UNAVAILABLE,
            ModelException.Reason.TIMEOUT,
            ModelException.Reason.UPSTREAM_FAILED,
            ModelException.Reason.UPSTREAM_REJECTED,
            ModelException.Reason.OUTPUT_LIMIT_EXCEEDED,
            ModelException.Reason.INVALID_STRUCTURED_INPUT,
            ModelException.Reason.INVALID_STRUCTURED_OUTPUT,
            ModelException.Reason.BATCH_TOO_LARGE,
            ModelException.Reason.EMBEDDING_DIMENSION_MISMATCH
        }) {
            assertThat(AiModelFailureCodes.of(reason).getCode())
                    .as("原因 %s 收敛为通用模型调用失败", reason)
                    .isEqualTo(AiErrorCodeConstants.AI_MODEL_CALL_FAILED.getCode());
        }
        assertThat(AiModelFailureCodes.of(null).getCode())
                .as("未知原因同样收敛，不猜测归因")
                .isEqualTo(AiErrorCodeConstants.AI_MODEL_CALL_FAILED.getCode());
    }

    @Test
    void convertsToPlatformExceptionWithoutUpstreamBody() {
        ServiceException converted = AiModelFailureCodes.toServiceException(
                new ModelException(ModelException.Reason.UPSTREAM_FAILED, "上游返回了敏感正文"));

        assertThat(converted.getCode()).isEqualTo(AiErrorCodeConstants.AI_MODEL_CALL_FAILED.getCode());
        assertThat(converted.getMessage()).as("异常正文只用平台文案，不带上游报文").doesNotContain("敏感正文");
    }
}
