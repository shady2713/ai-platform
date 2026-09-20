package com.basicframework.module.ai.service.query.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.StructuredModelRequest;
import com.basicframework.framework.ai.core.model.StructuredModelResult;
import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointRevisionDO;
import com.basicframework.module.ai.domain.policy.AiOutboundLevel;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.AiModelInvocationResult;
import com.basicframework.module.ai.service.model.AiModelInvocationService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** D05 模型适配器：当前模型版本解析、结构化调用参数、空输出与上游失败的稳定收敛。 */
class AiQueryPlanModelAdapterTest {

    private static final Long ENDPOINT_ID = 51L;

    private final AiModelEndpointService endpointService = mock(AiModelEndpointService.class);

    private final AiModelInvocationService invocationService = mock(AiModelInvocationService.class);

    private final AiQueryPlanModelAdapter adapter = new AiQueryPlanModelAdapter(endpointService, invocationService);

    private static void assertCode(Throwable throwable, ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @BeforeEach
    void setUp() {
        when(endpointService.getRevisions(ENDPOINT_ID))
                .thenReturn(List.of(
                        new AiModelEndpointRevisionDO().setModelId("qwen-plus").setCapabilities("STRUCTURED_OUTPUT")));
    }

    @Test
    void callsStructuredOutputWithCurrentModelAndInternalLevel() {
        when(invocationService.generateStructured(anyLong(), any(), any()))
                .thenReturn(new AiModelInvocationResult<>(
                        null, new StructuredModelResult("{\"kind\":\"PLAN\"}", null, null, "stop", "qwen-plus")));

        assertThat(adapter.propose(ENDPOINT_ID, "prompt", "{}")).isEqualTo("{\"kind\":\"PLAN\"}");

        ArgumentCaptor<StructuredModelRequest> request = ArgumentCaptor.forClass(StructuredModelRequest.class);
        ArgumentCaptor<AiOutboundLevel> level = ArgumentCaptor.forClass(AiOutboundLevel.class);
        org.mockito.Mockito.verify(invocationService)
                .generateStructured(org.mockito.ArgumentMatchers.eq(ENDPOINT_ID), request.capture(), level.capture());
        assertThat(request.getValue().modelId()).as("使用端点当前修订的模型标识").isEqualTo("qwen-plus");
        assertThat(request.getValue().prompt()).isEqualTo("prompt");
        assertThat(request.getValue().timeout()).isNotNull();
        assertThat(level.getValue()).as("数据集摘要按内部控制面元数据外发").isEqualTo(AiOutboundLevel.L2_INTERNAL);
    }

    @Test
    void mapsEmptyOutputAndModelFailureToStableCodes() {
        when(invocationService.generateStructured(anyLong(), any(), any()))
                .thenReturn(new AiModelInvocationResult<>(
                        null, new StructuredModelResult("  ", null, null, null, "qwen-plus")));
        assertThatThrownBy(() -> adapter.propose(ENDPOINT_ID, "prompt", "{}"))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_QUERY_MODEL_OUTPUT_INVALID));

        when(invocationService.generateStructured(anyLong(), any(), any()))
                .thenThrow(new ModelException(ModelException.Reason.UPSTREAM_FAILED, "上游失败"));
        assertThatThrownBy(() -> adapter.propose(ENDPOINT_ID, "prompt", "{}")).isInstanceOf(ServiceException.class);

        assertThatThrownBy(() -> adapter.propose(null, "prompt", "{}"))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND));
    }

    @Test
    void refusesEndpointWithoutRevision() {
        when(endpointService.getRevisions(ENDPOINT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> adapter.propose(ENDPOINT_ID, "prompt", "{}"))
                .satisfies(throwable -> assertCode(throwable, AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND));
    }
}
