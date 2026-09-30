package com.basicframework.module.ai.service.realtime;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_ADAPTER_UNAVAILABLE_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_AUDIO_FORMAT_UNSUPPORTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_ENDPOINT_CONFIG_CHANGED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_PROTOCOL_UNVERIFIED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.ai.core.model.ModelEndpointSnapshot;
import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.realtime.RealtimeAdapter;
import com.basicframework.framework.ai.core.realtime.RealtimeAudioFormat;
import com.basicframework.framework.ai.core.realtime.RealtimeCapability;
import com.basicframework.framework.ai.core.realtime.RealtimeCapabilityReport;
import com.basicframework.framework.ai.core.realtime.RealtimeProtocol;
import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.core.crypto.CredentialCipher;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointRevisionDO;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeEndpointCapabilityDO;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeSessionDO;
import com.basicframework.module.ai.dal.mysql.realtime.AiRealtimeEndpointCapabilityMapper;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 实时端点能力验证（X05 单测）：没有适配器即拒绝、未确认不发布、失败结论在冷却窗口内不重复外发、
 * 重连只接受受理时固定的配置/凭据版本。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiRealtimeEndpointVerifierTest {

    private static final Long ENDPOINT_ID = 9L;

    private static final RealtimeAudioFormat PCM_20MS =
            new RealtimeAudioFormat(RealtimeAudioFormat.MIME_PCM, 16000, 1, 20);

    @Mock
    private AiModelEndpointService endpointService;

    @Mock
    private AiRealtimeAdapterRegistry adapters;

    @Mock
    private AiRealtimeEndpointCapabilityMapper capabilityMapper;

    @Mock
    private CredentialCipher credentialCipher;

    @Mock
    private RealtimeAdapter adapter;

    @Spy
    private AiRealtimeParams params = new AiRealtimeParams();

    @InjectMocks
    private AiRealtimeEndpointVerifier verifier;

    @Test
    void verifiedEndpointToStringNeverLeaksCredentials() {
        ModelEndpointSnapshot snapshot = new ModelEndpointSnapshot(
                9L, 3, 2, "local-double", "https://example.invalid", "realtime-1", Set.of(), "sk-secret-value");
        AiRealtimeVerifiedEndpoint verified = new AiRealtimeVerifiedEndpoint(
                9L, 3, 2, "realtime-1", RealtimeProtocol.WEBSOCKET, PCM_20MS, snapshot, adapter);

        assertThat(verified.toString())
                .contains("endpointId=9")
                .contains("apiKey=***")
                .doesNotContain("sk-secret-value");
    }

    @Test
    void missingAdapterIsRejectedBeforeAnyProbe() {
        when(endpointService.getEnabledEndpoint(ENDPOINT_ID)).thenReturn(endpoint());
        when(adapters.find(RealtimeProtocol.WEBSOCKET)).thenReturn(Optional.empty());

        assertCode(
                () -> verifier.verify(ENDPOINT_ID, RealtimeProtocol.WEBSOCKET, PCM_20MS),
                AI_REALTIME_ADAPTER_UNAVAILABLE_CONFLICT);
        verify(capabilityMapper, never())
                .upsert(any(), any(), any(), any(), any(), any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void formatOutsideAdapterSupportIsRejectedBeforeProbe() {
        when(endpointService.getEnabledEndpoint(ENDPOINT_ID)).thenReturn(endpoint());
        when(adapters.find(RealtimeProtocol.WEBSOCKET)).thenReturn(Optional.of(adapter));
        when(adapter.supportedAudioFormats()).thenReturn(Set.of());

        assertCode(
                () -> verifier.verify(ENDPOINT_ID, RealtimeProtocol.WEBSOCKET, PCM_20MS),
                AI_REALTIME_AUDIO_FORMAT_UNSUPPORTED);
    }

    @Test
    void firstVerificationRunsRealProbeAndPersistsNarrowedReport() {
        when(endpointService.getEnabledEndpoint(ENDPOINT_ID)).thenReturn(endpoint());
        when(endpointService.getRevisions(ENDPOINT_ID)).thenReturn(List.of(revision()));
        when(adapters.find(RealtimeProtocol.WEBSOCKET)).thenReturn(Optional.of(adapter));
        when(adapter.supportedAudioFormats()).thenReturn(Set.of(PCM_20MS));
        when(adapter.probe(any()))
                .thenReturn(RealtimeCapabilityReport.verified(
                        RealtimeProtocol.WEBSOCKET, 3, RealtimeCapability.BASELINE, RealtimeCapability.BASELINE, 11L));
        when(credentialCipher.decrypt(any(), any())).thenReturn("***");
        AiRealtimeEndpointCapabilityDO ledger = verifiedLedger();
        when(capabilityMapper.selectOneByVersion(ENDPOINT_ID, 3, "WEBSOCKET")).thenReturn(null, ledger);

        AiRealtimeVerifiedEndpoint verified = verifier.verify(ENDPOINT_ID, RealtimeProtocol.WEBSOCKET, PCM_20MS);

        assertThat(verified.endpointId()).isEqualTo(ENDPOINT_ID);
        assertThat(verified.configRevision()).isEqualTo(3);
        assertThat(verified.credentialRevision()).isEqualTo(2);
        assertThat(verified.modelRef()).isEqualTo("realtime-1");
        assertThat(verified.protocol()).isEqualTo(RealtimeProtocol.WEBSOCKET);
        verify(capabilityMapper)
                .upsert(
                        eq(ENDPOINT_ID),
                        eq(3),
                        eq("WEBSOCKET"),
                        eq("VERIFIED"),
                        any(),
                        any(),
                        any(),
                        eq("audio/pcm@16000:1:20"),
                        anyLong(),
                        any(),
                        any());
    }

    @Test
    void failedConclusionInsideCooldownIsNotReProbed() {
        when(endpointService.getEnabledEndpoint(ENDPOINT_ID)).thenReturn(endpoint());
        when(adapters.find(RealtimeProtocol.WEBSOCKET)).thenReturn(Optional.of(adapter));
        when(adapter.supportedAudioFormats()).thenReturn(Set.of(PCM_20MS));
        when(capabilityMapper.selectOneByVersion(ENDPOINT_ID, 3, "WEBSOCKET"))
                .thenReturn(new AiRealtimeEndpointCapabilityDO()
                        .setEndpointId(ENDPOINT_ID)
                        .setConfigRevision(3)
                        .setProtocol("WEBSOCKET")
                        .setStatus(AiRealtimeEndpointCapabilityDO.STATUS_FAILED)
                        .setDetailCode("UPSTREAM_TIMEOUT")
                        .setProbedTime(LocalDateTime.now().minusSeconds(5))
                        .setAudioFormats("audio/pcm@16000:1:20"));

        assertCode(
                () -> verifier.verify(ENDPOINT_ID, RealtimeProtocol.WEBSOCKET, PCM_20MS),
                AI_REALTIME_PROTOCOL_UNVERIFIED_CONFLICT);
        verify(adapter, never()).probe(any());
    }

    @Test
    void failedConclusionOutsideCooldownIsReProbed() {
        when(endpointService.getEnabledEndpoint(ENDPOINT_ID)).thenReturn(endpoint());
        when(endpointService.getRevisions(ENDPOINT_ID)).thenReturn(List.of(revision()));
        when(adapters.find(RealtimeProtocol.WEBSOCKET)).thenReturn(Optional.of(adapter));
        when(adapter.supportedAudioFormats()).thenReturn(Set.of(PCM_20MS));
        when(capabilityMapper.selectOneByVersion(ENDPOINT_ID, 3, "WEBSOCKET"))
                .thenReturn(staleFailedLedger(), verifiedLedger());
        when(adapter.probe(any()))
                .thenReturn(RealtimeCapabilityReport.verified(
                        RealtimeProtocol.WEBSOCKET, 3, RealtimeCapability.BASELINE, RealtimeCapability.BASELINE, 7L));
        when(credentialCipher.decrypt(any(), any())).thenReturn("***");

        assertThat(verifier.verify(ENDPOINT_ID, RealtimeProtocol.WEBSOCKET, PCM_20MS)
                        .configRevision())
                .isEqualTo(3);
        verify(adapter).probe(any());
    }

    @Test
    void probeFailureIsRecordedAsFailedConclusionWithoutUpstreamPayload() {
        when(endpointService.getEnabledEndpoint(ENDPOINT_ID)).thenReturn(endpoint());
        when(adapters.find(RealtimeProtocol.WEBSOCKET)).thenReturn(Optional.of(adapter));
        when(adapter.supportedAudioFormats()).thenReturn(Set.of(PCM_20MS));
        when(endpointService.getRevisions(ENDPOINT_ID)).thenReturn(List.of(revision()));
        doThrow(new ModelException(ModelException.Reason.UPSTREAM_FAILED, "upstream says no"))
                .when(adapter)
                .probe(any());
        when(capabilityMapper.selectOneByVersion(ENDPOINT_ID, 3, "WEBSOCKET"))
                .thenReturn(null, failedLedger("UPSTREAM_FAILED"));

        assertCode(
                () -> verifier.verify(ENDPOINT_ID, RealtimeProtocol.WEBSOCKET, PCM_20MS),
                AI_REALTIME_PROTOCOL_UNVERIFIED_CONFLICT);
        verify(capabilityMapper)
                .upsert(
                        eq(ENDPOINT_ID),
                        eq(3),
                        eq("WEBSOCKET"),
                        eq("FAILED"),
                        eq("UPSTREAM_FAILED"),
                        any(),
                        any(),
                        any(),
                        anyLong(),
                        any(),
                        any());
    }

    @Test
    void verifiedLedgerWithoutRequestedFormatIsRejected() {
        when(endpointService.getEnabledEndpoint(ENDPOINT_ID)).thenReturn(endpoint());
        when(adapters.find(RealtimeProtocol.WEBSOCKET)).thenReturn(Optional.of(adapter));
        when(adapter.supportedAudioFormats()).thenReturn(Set.of(PCM_20MS));
        when(capabilityMapper.selectOneByVersion(ENDPOINT_ID, 3, "WEBSOCKET"))
                .thenReturn(verifiedLedger().setAudioFormats("audio/ogg@16000:1:20"));

        assertCode(
                () -> verifier.verify(ENDPOINT_ID, RealtimeProtocol.WEBSOCKET, PCM_20MS),
                AI_REALTIME_AUDIO_FORMAT_UNSUPPORTED);
    }

    @Test
    void pinnedVerificationRejectsChangedConfigurationAndCorruptFacts() {
        AiRealtimeSessionDO session = session().setEndpointConfigRevision(2);
        when(endpointService.getEnabledEndpoint(ENDPOINT_ID)).thenReturn(endpoint());

        assertCode(() -> verifier.verifyPinned(session), AI_REALTIME_ENDPOINT_CONFIG_CHANGED_CONFLICT);

        AiRealtimeSessionDO corrupt = session().setProtocol("RTSP");
        assertCode(() -> verifier.verifyPinned(corrupt), AI_STATE_CONFLICT);
    }

    @Test
    void pinnedVerificationAcceptsUnchangedConfiguration() {
        when(endpointService.getEnabledEndpoint(ENDPOINT_ID)).thenReturn(endpoint());
        when(endpointService.getRevisions(ENDPOINT_ID)).thenReturn(List.of(revision()));
        when(adapters.find(RealtimeProtocol.WEBSOCKET)).thenReturn(Optional.of(adapter));
        when(adapter.supportedAudioFormats()).thenReturn(Set.of(PCM_20MS));
        when(capabilityMapper.selectOneByVersion(ENDPOINT_ID, 3, "WEBSOCKET")).thenReturn(verifiedLedger());
        when(credentialCipher.decrypt(any(), any())).thenReturn("***");

        assertThat(verifier.verifyPinned(session()).protocol()).isEqualTo(RealtimeProtocol.WEBSOCKET);
    }

    private static AiModelEndpointDO endpoint() {
        AiModelEndpointDO endpoint = new AiModelEndpointDO();
        endpoint.setId(ENDPOINT_ID);
        endpoint.setConfigRevision(3);
        endpoint.setCredentialRevision(2);
        endpoint.setCredentialCiphertext("ciphertext");
        endpoint.setProvider("local-double");
        endpoint.setBaseUrl("https://example.invalid");
        return endpoint;
    }

    private static AiModelEndpointRevisionDO revision() {
        AiModelEndpointRevisionDO revision = new AiModelEndpointRevisionDO();
        revision.setModelId("realtime-1");
        return revision;
    }

    private static AiRealtimeEndpointCapabilityDO verifiedLedger() {
        return new AiRealtimeEndpointCapabilityDO()
                .setEndpointId(ENDPOINT_ID)
                .setConfigRevision(3)
                .setProtocol("WEBSOCKET")
                .setStatus(AiRealtimeEndpointCapabilityDO.STATUS_VERIFIED)
                .setConfirmedCapabilities("SESSION_NEGOTIATION,AUDIO_INPUT_STREAM,AUDIO_OUTPUT_STREAM")
                .setAudioFormats("audio/pcm@16000:1:20")
                .setProbedTime(LocalDateTime.now());
    }

    private static AiRealtimeEndpointCapabilityDO staleFailedLedger() {
        return failedLedger("UPSTREAM_TIMEOUT")
                .setProbedTime(LocalDateTime.now().minusMinutes(5));
    }

    private static AiRealtimeEndpointCapabilityDO failedLedger(String detailCode) {
        return new AiRealtimeEndpointCapabilityDO()
                .setEndpointId(ENDPOINT_ID)
                .setConfigRevision(3)
                .setProtocol("WEBSOCKET")
                .setStatus(AiRealtimeEndpointCapabilityDO.STATUS_FAILED)
                .setDetailCode(detailCode)
                .setProbedTime(LocalDateTime.now());
    }

    private static AiRealtimeSessionDO session() {
        return new AiRealtimeSessionDO()
                .setId(42L)
                .setEndpointId(ENDPOINT_ID)
                .setEndpointConfigRevision(3)
                .setEndpointCredentialRevision(2)
                .setProtocol("WEBSOCKET")
                .setAudioFormat("audio/pcm@16000:1:20")
                .setStatus(AiRealtimeSessionDO.STATUS_OPEN);
    }

    private static void assertCode(ThrowingOperation operation, ErrorCode expected) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(expected.getCode()));
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run();
    }
}
