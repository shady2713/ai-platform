package com.basicframework.module.ai.adapter.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.ai.core.model.EmbeddingRequest;
import com.basicframework.framework.ai.core.model.EmbeddingResponse;
import com.basicframework.framework.ai.core.model.ModelUsage;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.domain.policy.AiOutboundLevel;
import com.basicframework.module.ai.service.knowledge.indexing.AiKnowledgeEmbeddingClient;
import com.basicframework.module.ai.service.knowledge.indexing.AiKnowledgeEmbeddingException;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.AiModelInvocationResult;
import com.basicframework.module.ai.service.model.AiModelInvocationService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** K05 真实嵌入客户端：端点解析、批量校验、维度基线与外发等级（全部走 M04 的调用服务）。 */
class AiModelEmbeddingClientTest {

    private final AiModelEndpointService endpointService = mock(AiModelEndpointService.class);

    private final AiModelInvocationService invocationService = mock(AiModelInvocationService.class);

    private final AiModelEmbeddingClient client = new AiModelEmbeddingClient(endpointService, invocationService);

    private static AiModelEndpointDO endpoint(Long id, boolean enabled) {
        return new AiModelEndpointDO()
                .setId(id)
                .setName("text-embedding-3-small")
                .setEnabled(enabled);
    }

    private static EmbeddingResponse response(int count, int dimension) {
        List<float[]> vectors = java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> new float[dimension])
                .toList();
        return new EmbeddingResponse(vectors, new ModelUsage(1, 1, false), "text-embedding-3-small");
    }

    private static void assertReason(Throwable throwable, AiKnowledgeEmbeddingException.Reason expected) {
        assertThat(throwable).isInstanceOf(AiKnowledgeEmbeddingException.class);
        assertThat(((AiKnowledgeEmbeddingException) throwable).reason()).isEqualTo(expected);
    }

    @Test
    void resolvesEnabledEndpointAndReturnsBatchAfterDimensionCheck() {
        when(endpointService.getEndpointPage(any(), eq("text-embedding-3-small"), any()))
                .thenReturn(new PageResult<>(List.of(endpoint(9L, true)), 1L));
        when(invocationService.embed(eq(9L), any(EmbeddingRequest.class), eq(AiOutboundLevel.L2_INTERNAL)))
                .thenReturn(new AiModelInvocationResult<>(null, response(2, 1536)));

        AiKnowledgeEmbeddingClient.EmbeddingBatch batch = client.embed("text-embedding-3-small", List.of("第一段", "第二段"));

        assertThat(batch.dimension()).isEqualTo(1536);
        assertThat(batch.vectors()).hasSize(2);
        assertThat(batch.modelId()).isEqualTo("text-embedding-3-small");
        // 维度基线由模型中心校验（同维度不同 revision 也不允许混用，AT-029）
        verify(endpointService).assertEmbeddingDimensionUnchanged(9L, 1536);
    }

    @Test
    void missingOrDisabledEndpointFailsClosedWithoutCallingTheModel() {
        when(endpointService.getEndpointPage(any(), any(), any())).thenReturn(new PageResult<>(List.of(), 0L));
        assertThatThrownBy(() -> client.embed("unknown-model", List.of("文本")))
                .satisfies(throwable ->
                        assertReason(throwable, AiKnowledgeEmbeddingException.Reason.ENDPOINT_UNAVAILABLE));

        when(endpointService.getEndpointPage(any(), any(), any()))
                .thenReturn(new PageResult<>(List.of(endpoint(9L, false)), 1L));
        assertThatThrownBy(() -> client.embed("text-embedding-3-small", List.of("文本")))
                .satisfies(throwable ->
                        assertReason(throwable, AiKnowledgeEmbeddingException.Reason.ENDPOINT_UNAVAILABLE));
        verify(invocationService, never()).embed(any(), any(), any());
    }

    @Test
    void dimensionBaselineViolationIsReportedAsMismatch() {
        when(endpointService.getEndpointPage(any(), any(), any()))
                .thenReturn(new PageResult<>(List.of(endpoint(9L, true)), 1L));
        when(invocationService.embed(any(), any(), any()))
                .thenReturn(new AiModelInvocationResult<>(null, response(1, 1536)));
        org.mockito.Mockito.doThrow(new IllegalStateException("dimension-changed"))
                .when(endpointService)
                .assertEmbeddingDimensionUnchanged(9L, 1536);

        assertThatThrownBy(() -> client.embed("text-embedding-3-small", List.of("文本")))
                .satisfies(
                        throwable -> assertReason(throwable, AiKnowledgeEmbeddingException.Reason.DIMENSION_MISMATCH));
    }

    @Test
    void upstreamFailureAndInvalidResponseAreStable() {
        when(endpointService.getEndpointPage(any(), any(), any()))
                .thenReturn(new PageResult<>(List.of(endpoint(9L, true)), 1L));
        when(invocationService.embed(any(), any(), any())).thenThrow(new IllegalStateException("connect failed"));
        assertThatThrownBy(() -> client.embed("text-embedding-3-small", List.of("文本")))
                .satisfies(throwable -> assertReason(throwable, AiKnowledgeEmbeddingException.Reason.UPSTREAM_FAILED));

        // 重新打桩：前一次是 thenThrow，必须用 doReturn 形式（否则 when(...) 会真的抛）
        org.mockito.Mockito.doReturn(new AiModelInvocationResult<>(null, response(1, 1536)))
                .when(invocationService)
                .embed(any(), any(), any());
        assertThatThrownBy(() -> client.embed("text-embedding-3-small", List.of("第一段", "第二段")))
                .as("返回条数与请求不符")
                .satisfies(throwable -> assertReason(throwable, AiKnowledgeEmbeddingException.Reason.RESPONSE_INVALID));
        assertThatThrownBy(() -> client.embed("text-embedding-3-small", List.of()))
                .satisfies(throwable -> assertReason(throwable, AiKnowledgeEmbeddingException.Reason.RESPONSE_INVALID));
    }

    @Test
    void exceptionReasonCodesAreStableAndDoNotCarryText() {
        AiKnowledgeEmbeddingException failure = new AiKnowledgeEmbeddingException(
                AiKnowledgeEmbeddingException.Reason.DIMENSION_MISMATCH, "knowledge-base");
        assertThat(failure.reasonCode()).isEqualTo("embed-dimension_mismatch");
        assertThat(failure.getMessage()).isEqualTo("DIMENSION_MISMATCH:knowledge-base");
        assertThat(AiKnowledgeEmbeddingException.of(
                                AiKnowledgeEmbeddingException.Reason.UPSTREAM_FAILED, new IllegalStateException("x"))
                        .reasonCode())
                .isEqualTo("embed-upstream_failed");
        assertThat(new AiKnowledgeEmbeddingException(AiKnowledgeEmbeddingException.Reason.RESPONSE_INVALID, null)
                        .getMessage())
                .isEqualTo("RESPONSE_INVALID");
        assertThat(Duration.ofSeconds(1)).isNotNull();
    }
}
