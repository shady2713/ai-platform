package com.basicframework.module.ai.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelPort;
import com.basicframework.framework.ai.core.model.ModelProbeKind;
import com.basicframework.framework.ai.core.model.ModelProbeResult;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.adapter.model.AiModelClientResolver;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointRevisionDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelProbeDO;
import com.basicframework.module.ai.dal.mysql.model.AiModelProbeMapper;
import com.basicframework.module.ai.service.model.dto.AiModelCapabilityOverviewDTO;
import com.basicframework.module.ai.service.model.dto.AiModelProbeResultDTO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * M04 能力探测服务：六类探测真实执行并落库、判定失败不被启用状态掩盖、
 * 可发布范围取声明与确认的交集。
 */
class AiModelCapabilityProbeServiceImplTest {

    private AiModelEndpointService endpointService;

    private AiModelClientResolver clientResolver;

    private AiModelProbeMapper probeMapper;

    private AiModelCapabilityProbeServiceImpl service;

    @BeforeEach
    void setUp() {
        endpointService = mock(AiModelEndpointService.class);
        clientResolver = mock(AiModelClientResolver.class);
        probeMapper = mock(AiModelProbeMapper.class);
        service = new AiModelCapabilityProbeServiceImpl(endpointService, clientResolver, probeMapper);
    }

    private static AiModelEndpointDO endpoint() {
        return new AiModelEndpointDO().setId(7L).setConfigRevision(2).setCredentialRevision(1);
    }

    private static AiModelEndpointRevisionDO revision(String capabilities) {
        return new AiModelEndpointRevisionDO()
                .setEndpointId(7L)
                .setRevision(2)
                .setModelId("gpt-4o-mini")
                .setCapabilities(capabilities);
    }

    private void givenEndpoint(String declaredCapabilities) {
        when(endpointService.getEndpoint(7L)).thenReturn(endpoint());
        when(endpointService.getRevisions(7L)).thenReturn(List.of(revision(declaredCapabilities)));
    }

    @Test
    void probesEveryKindAndPersistsStableConclusions() {
        givenEndpoint("TEXT,TEXT_STREAM,STRUCTURED_OUTPUT");
        ModelPort port = mock(ModelPort.class);
        when(clientResolver.resolveForProbe(7L)).thenReturn(port);
        when(port.probe(any(ModelProbeKind.class))).thenAnswer(invocation -> {
            ModelProbeKind kind = invocation.getArgument(0);
            return ModelProbeResult.supported(kind, kind == ModelProbeKind.EMBEDDING ? 1536 : null, 42L);
        });

        List<AiModelProbeResultDTO> results = service.probeAll(7L);

        assertThat(results).hasSize(6);
        assertThat(results)
                .extracting(AiModelProbeResultDTO::getProbeKind)
                .containsExactly(
                        "CONNECTIVITY", "TEXT", "TEXT_STREAM", "STRUCTURED_OUTPUT", "TOOL_CALLING", "EMBEDDING");
        assertThat(results).allSatisfy(result -> {
            assertThat(result.getStatus()).isEqualTo("SUPPORTED");
            assertThat(result.getDetailCode()).isNull();
            assertThat(result.getLatencyMs()).isEqualTo(42);
            assertThat(result.getConfigRevision()).isEqualTo(2);
        });
        verify(probeMapper, times(6)).insert(any(AiModelProbeDO.class));
    }

    @Test
    void resolutionFailureIsRecordedAsFailedForEveryKindInsteadOfBeingSkipped() {
        givenEndpoint("TEXT");
        when(clientResolver.resolveForProbe(7L))
                .thenThrow(new ModelException(ModelException.Reason.CREDENTIAL_UNAVAILABLE, "端点未配置凭据"));

        List<AiModelProbeResultDTO> results = service.probeAll(7L);

        assertThat(results).hasSize(6);
        assertThat(results).allSatisfy(result -> {
            assertThat(result.getStatus()).isEqualTo("FAILED");
            assertThat(result.getDetailCode()).isEqualTo("CREDENTIAL_UNAVAILABLE");
        });
        verify(probeMapper, times(6)).insert(any(AiModelProbeDO.class));
    }

    @Test
    void publishableScopeIsIntersectionOfDeclaredAndProbed() {
        givenEndpoint("TEXT,TEXT_STREAM,EMBEDDING");
        // 最近结论：文本与嵌入确认，流式失败
        when(probeMapper.selectLatestPerKind(7L))
                .thenReturn(List.of(
                        probe("TEXT", "SUPPORTED", null, null),
                        probe("TEXT_STREAM", "FAILED", "TIMEOUT", null),
                        probe("EMBEDDING", "SUPPORTED", null, 1536)));

        AiModelCapabilityOverviewDTO overview = service.getCapabilityOverview(7L);

        assertThat(overview.getDeclared()).containsExactly("TEXT", "TEXT_STREAM", "EMBEDDING");
        assertThat(overview.getSupported()).containsExactlyInAnyOrder("TEXT", "EMBEDDING");
        assertThat(overview.getPublishable()).as("未确认的能力不得进入发布范围").containsExactly("TEXT", "EMBEDDING");
    }

    @Test
    void latestResultsOnlyExposeStoredConclusions() {
        givenEndpoint("TEXT");
        when(probeMapper.selectLatestPerKind(7L)).thenReturn(List.of(probe("TEXT", "SUPPORTED", null, null)));

        List<AiModelProbeResultDTO> results = service.getLatestResults(7L);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.getProbeKind()).isEqualTo("TEXT");
            assertThat(result.getStatus()).isEqualTo("SUPPORTED");
        });
        verify(clientResolver, never()).resolveForProbe(any());
    }

    @Test
    void probingUnknownEndpointFailsBeforeAnyUpstreamCall() {
        when(endpointService.getEndpoint(404L))
                .thenThrow(new ServiceException(
                        com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MODEL_ENDPOINT_NOT_FOUND));

        assertThatThrownBy(() -> service.probeAll(404L)).isInstanceOf(ServiceException.class);
        verify(clientResolver, never()).resolveForProbe(any());
        verify(probeMapper, never()).insert(any(AiModelProbeDO.class));
    }

    @Test
    void persistedRecordsNeverCarryPromptsOrVendorBodies() {
        givenEndpoint("TEXT");
        ModelPort port = mock(ModelPort.class);
        when(clientResolver.resolveForProbe(7L)).thenReturn(port);
        when(port.probe(any(ModelProbeKind.class)))
                .thenReturn(ModelProbeResult.failed(ModelProbeKind.TEXT, ModelException.Reason.TIMEOUT, 15L));
        ArgumentCaptor<AiModelProbeDO> captor = ArgumentCaptor.forClass(AiModelProbeDO.class);

        service.probeAll(7L);

        verify(probeMapper, times(6)).insert(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(record -> {
            assertThat(record.getDetailCode()).isEqualTo("TIMEOUT");
            assertThat(record.getLatencyMs()).isEqualTo(15);
            assertThat(record.toString()).doesNotContain("sk-");
        });
    }

    private static AiModelProbeDO probe(String kind, String status, String detailCode, Integer dimension) {
        AiModelProbeDO record = new AiModelProbeDO();
        record.setProbeKind(kind);
        record.setStatus(status);
        record.setDetailCode(detailCode);
        record.setEmbeddingDimension(dimension);
        record.setLatencyMs(10);
        record.setConfigRevision(2);
        return record;
    }
}
