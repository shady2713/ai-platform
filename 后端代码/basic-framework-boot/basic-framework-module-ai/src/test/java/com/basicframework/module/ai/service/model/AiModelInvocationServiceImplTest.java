package com.basicframework.module.ai.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.basicframework.framework.ai.core.model.EmbeddingResponse;
import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelPort;
import com.basicframework.framework.ai.core.model.ModelRequest;
import com.basicframework.framework.ai.core.model.ModelResponse;
import com.basicframework.framework.ai.core.model.ModelUsage;
import com.basicframework.framework.ai.core.model.StructuredModelResult;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.adapter.model.AiModelClientResolver;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.domain.policy.AiOutboundLevel;
import com.basicframework.module.ai.domain.policy.AiOutboundPolicy;
import com.basicframework.module.ai.domain.policy.AiOutboundPolicyProperties;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.usage.AiModelInvocationRecord;
import com.basicframework.module.ai.service.usage.AiModelUsageRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * M05 调用编排：策略拒绝时零网络交互、上游失败不切换端点、计量记录覆盖成功与失败。
 */
class AiModelInvocationServiceImplTest {

    private AiModelEndpointService endpointService;

    private AiModelClientResolver clientResolver;

    private AiOutboundPolicy outboundPolicy;

    private AiOutboundPolicyProperties properties;

    private AiModelUsageRecorder recorder;

    private AiModelInvocationServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        endpointService = mock(AiModelEndpointService.class);
        clientResolver = mock(AiModelClientResolver.class);
        outboundPolicy = mock(AiOutboundPolicy.class);
        properties = new AiOutboundPolicyProperties();
        recorder = mock(AiModelUsageRecorder.class);
        ObjectProvider<AiModelUsageRecorder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(recorder);
        service =
                new AiModelInvocationServiceImpl(endpointService, clientResolver, outboundPolicy, properties, provider);
    }

    private static AiModelEndpointDO endpoint() {
        return new AiModelEndpointDO().setId(9L).setConfigRevision(2).setCredentialRevision(3);
    }

    @Test
    void blockedResourcePerformsNoResolutionAndNoUpstreamCall() {
        org.mockito.Mockito.doThrow(new ServiceException(AiErrorCodeConstants.AI_MODEL_OUTBOUND_BLOCKED))
                .when(outboundPolicy)
                .assertAllowed(eq(9L), eq(AiOutboundLevel.L4_SECRET));

        assertThatThrownBy(() ->
                        service.generate(9L, ModelRequest.of("gpt-4o-mini", "含个人信息的内容"), AiOutboundLevel.L4_SECRET))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_MODEL_OUTBOUND_BLOCKED.getCode());

        verifyNoInteractions(endpointService);
        verifyNoInteractions(clientResolver);
        verifyNoInteractions(recorder);
    }

    @Test
    void successfulCallIsMeteredWithMeasuredUsage() {
        when(endpointService.getEndpoint(9L)).thenReturn(endpoint());
        ModelPort port = mock(ModelPort.class);
        when(clientResolver.resolve(9L)).thenReturn(port);
        when(port.generate(any()))
                .thenReturn(new ModelResponse("pong", ModelUsage.of(11, 2), null, "gpt-4o-mini", "stop"));

        AiModelInvocationResult<ModelResponse> result =
                service.generate(9L, ModelRequest.of("gpt-4o-mini", "ping"), AiOutboundLevel.L2_INTERNAL);

        assertThat(result.output().text()).isEqualTo("pong");
        AiModelInvocationRecord record = result.record();
        assertThat(record.succeeded()).isTrue();
        assertThat(record.getCapability()).isEqualTo(ModelCapability.TEXT.name());
        assertThat(record.getEndpointId()).isEqualTo(9L);
        assertThat(record.getConfigRevision()).isEqualTo(2);
        assertThat(record.getPromptTokens()).isEqualTo(11);
        assertThat(record.isEstimated()).isFalse();
        assertThat(record.getInvocationId()).isNotBlank();

        ArgumentCaptor<AiModelInvocationRecord> captor = ArgumentCaptor.forClass(AiModelInvocationRecord.class);
        verify(recorder).record(captor.capture());
        assertThat(captor.getValue().getInvocationId()).isEqualTo(record.getInvocationId());
        verify(outboundPolicy).assertAllowed(9L, AiOutboundLevel.L2_INTERNAL);
    }

    @Test
    void upstreamFailureIsMeteredAndNeverSwitchesEndpoint() {
        when(endpointService.getEndpoint(9L)).thenReturn(endpoint());
        ModelPort port = mock(ModelPort.class);
        when(clientResolver.resolve(9L)).thenReturn(port);
        when(port.generate(any())).thenThrow(new ModelException(ModelException.Reason.TIMEOUT, "模型调用超时"));

        assertThatThrownBy(
                        () -> service.generate(9L, ModelRequest.of("gpt-4o-mini", "ping"), AiOutboundLevel.L2_INTERNAL))
                .isInstanceOf(ModelException.class)
                .satisfies(exception ->
                        assertThat(((ModelException) exception).getReason()).isEqualTo(ModelException.Reason.TIMEOUT));

        ArgumentCaptor<AiModelInvocationRecord> captor = ArgumentCaptor.forClass(AiModelInvocationRecord.class);
        verify(recorder).record(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(captor.getValue().getErrorReason()).isEqualTo("TIMEOUT");
        // 只解析调用方指定的端点：没有遍历候选端点，也就没有隐式失败转移
        verify(clientResolver).resolve(9L);
        verify(clientResolver, never()).resolveForProbe(anyLong());
    }

    @Test
    void meteringWorksWhenNoRecorderIsWiredYet() {
        when(endpointService.getEndpoint(9L)).thenReturn(endpoint());
        ModelPort port = mock(ModelPort.class);
        when(clientResolver.resolve(9L)).thenReturn(port);
        when(port.generate(any()))
                .thenReturn(new ModelResponse("pong", ModelUsage.UNKNOWN, null, "gpt-4o-mini", "stop"));
        ObjectProvider<AiModelUsageRecorder> emptyProvider = mock(ObjectProvider.class);
        AiModelInvocationServiceImpl withoutRecorder = new AiModelInvocationServiceImpl(
                endpointService, clientResolver, outboundPolicy, properties, emptyProvider);

        AiModelInvocationResult<ModelResponse> result =
                withoutRecorder.generate(9L, ModelRequest.of("gpt-4o-mini", "ping"), AiOutboundLevel.L1_PUBLIC);

        assertThat(result.record().getInvocationId()).isNotBlank();
        assertThat(result.record().usageKnown()).as("上游未给用量时记录保持 UNKNOWN").isFalse();
        verifyNoInteractions(recorder);
    }

    @Test
    void structuredAndEmbeddingPathsAreMeteredWithTheirCapabilities() {
        when(endpointService.getEndpoint(9L)).thenReturn(endpoint());
        ModelPort port = mock(ModelPort.class);
        when(clientResolver.resolve(9L)).thenReturn(port);
        when(port.generateStructured(any()))
                .thenReturn(new StructuredModelResult(
                        "{\"answer\":\"ok\"}", null, ModelUsage.of(5, 2), "stop", "gpt-4o-mini"));
        when(port.embed(any()))
                .thenReturn(new EmbeddingResponse(
                        java.util.List.of(new float[] {1f, 2f}), ModelUsage.of(3, 0), "text-embedding-3-small"));

        AiModelInvocationResult<com.basicframework.framework.ai.core.model.StructuredModelResult> structured =
                service.generateStructured(
                        9L,
                        com.basicframework.framework.ai.core.model.StructuredModelRequest.of(
                                "gpt-4o-mini", "问题", "{\"type\":\"object\"}"),
                        AiOutboundLevel.L2_INTERNAL);
        AiModelInvocationResult<com.basicframework.framework.ai.core.model.EmbeddingResponse> embedded = service.embed(
                9L,
                com.basicframework.framework.ai.core.model.EmbeddingRequest.of(
                        "text-embedding-3-small", java.util.List.of("一段")),
                AiOutboundLevel.L2_INTERNAL);

        assertThat(structured.record().getCapability()).isEqualTo(ModelCapability.STRUCTURED_OUTPUT.name());
        assertThat(structured.record().getPromptTokens()).isEqualTo(5);
        assertThat(embedded.record().getCapability()).isEqualTo(ModelCapability.EMBEDDING.name());
        assertThat(embedded.record().getCompletionTokens()).isZero();
        assertThat(embedded.output().dimensions()).isEqualTo(2);
    }

    @Test
    void structuredAndEmbeddingFailuresAreMeteredWithStableReasons() {
        when(endpointService.getEndpoint(9L)).thenReturn(endpoint());
        ModelPort port = mock(ModelPort.class);
        when(clientResolver.resolve(9L)).thenReturn(port);
        when(port.generateStructured(any()))
                .thenThrow(new ModelException(ModelException.Reason.INVALID_STRUCTURED_OUTPUT, "畸形输出"));
        when(port.embed(any())).thenThrow(new ModelException(ModelException.Reason.BATCH_TOO_LARGE, "批次超限"));

        assertThatThrownBy(() -> service.generateStructured(
                        9L,
                        com.basicframework.framework.ai.core.model.StructuredModelRequest.of(
                                "gpt-4o-mini", "问题", "{\"type\":\"object\"}"),
                        AiOutboundLevel.L1_PUBLIC))
                .isInstanceOf(ModelException.class);
        assertThatThrownBy(() -> service.embed(
                        9L,
                        com.basicframework.framework.ai.core.model.EmbeddingRequest.of("m", java.util.List.of("一段")),
                        AiOutboundLevel.L1_PUBLIC))
                .isInstanceOf(ModelException.class);

        ArgumentCaptor<AiModelInvocationRecord> captor = ArgumentCaptor.forClass(AiModelInvocationRecord.class);
        verify(recorder, org.mockito.Mockito.times(2)).record(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(AiModelInvocationRecord::getErrorReason)
                .containsExactly("INVALID_STRUCTURED_OUTPUT", "BATCH_TOO_LARGE");
    }

    @Test
    void estimationIsAppliedWhenConfigured() {
        properties.getMetering().setEstimateWhenMissing(true);
        properties.getMetering().setCharsPerToken(4);
        when(endpointService.getEndpoint(9L)).thenReturn(endpoint());
        ModelPort port = mock(ModelPort.class);
        when(clientResolver.resolve(9L)).thenReturn(port);
        when(port.generate(any()))
                .thenReturn(new ModelResponse("123456789", ModelUsage.UNKNOWN, null, "gpt-4o-mini", "stop"));

        AiModelInvocationRecord record = service.generate(
                        9L, ModelRequest.of("gpt-4o-mini", "ping"), AiOutboundLevel.L1_PUBLIC)
                .record();

        assertThat(record.isEstimated()).isTrue();
        assertThat(record.getCompletionTokens()).isEqualTo(3);
    }
}
