package com.basicframework.module.ai.service.speech;

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
import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelPort;
import com.basicframework.framework.ai.core.model.ModelRequest;
import com.basicframework.framework.ai.core.model.ModelResponse;
import com.basicframework.framework.ai.core.model.ModelUsage;
import com.basicframework.framework.ai.core.model.media.MediaArtifact;
import com.basicframework.framework.ai.core.model.media.SpeechSegment;
import com.basicframework.framework.ai.core.model.media.SpeechSynthesisRequest;
import com.basicframework.framework.ai.core.model.media.SpeechSynthesisResponse;
import com.basicframework.framework.ai.core.model.media.SpeechTranscriptionRequest;
import com.basicframework.framework.ai.core.model.media.SpeechTranscriptionResponse;
import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.adapter.model.AiMediaCapabilityGate;
import com.basicframework.module.ai.adapter.model.AiModelClientResolver;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaAssetDO;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaTaskDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointRevisionDO;
import com.basicframework.module.ai.dal.mysql.media.AiMediaAssetMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.file.AiFileService;
import com.basicframework.module.ai.service.file.dto.AiFileUploadResultDTO;
import com.basicframework.module.ai.service.media.dto.AiMediaStepOutcome;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskLeaseDTO;
import com.basicframework.module.ai.service.model.AiModelCapabilityProbeService;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.dto.AiModelProbeResultDTO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 语音执行器（X04）：真实准入闸门（端点启用 + 能力声明 + 探测确认）先于任何端口调用；
 * 转写执行期重读源音频（失权即失败）；产物必须是白名单格式的真实音频才落私有文件；
 * 结果与用量只落平台事实（未知用量记 UNKNOWN 且数值为空）。
 */
class AiSpeechStepExecutorTest {

    private static final Long ENDPOINT_ID = 9L;

    private static final Long TASK_ID = 601L;

    private static final String MODEL_REF = "whisper-1";

    private final AiModelEndpointService endpointService = mock(AiModelEndpointService.class);

    private final AiModelCapabilityProbeService probeService = mock(AiModelCapabilityProbeService.class);

    private final AiModelClientResolver clientResolver = mock(AiModelClientResolver.class);

    private final AiFileService fileService = mock(AiFileService.class);

    private final AiMediaAssetMapper assetMapper = mock(AiMediaAssetMapper.class);

    private final RecordingSpeechPort port = new RecordingSpeechPort();

    private final AiMediaCapabilityGate gate = new AiMediaCapabilityGate(endpointService, probeService, clientResolver);

    private final AiSpeechStepExecutor executor =
            new AiSpeechStepExecutor(gate, fileService, new AiSpeechAudioGuard(), assetMapper);

    private final AtomicInteger nextFileId = new AtomicInteger(2000);

    @BeforeEach
    void setUp() {
        when(endpointService.getEnabledEndpoint(ENDPOINT_ID))
                .thenReturn(new AiModelEndpointDO()
                        .setId(ENDPOINT_ID)
                        .setEnabled(true)
                        .setConfigRevision(3));
        when(endpointService.getRevisions(ENDPOINT_ID)).thenReturn(List.of(revision("SPEECH_TO_TEXT,TEXT_TO_SPEECH")));
        when(probeService.getLatestResults(ENDPOINT_ID))
                .thenReturn(List.of(probe("SPEECH_TO_TEXT", 3), probe("TEXT_TO_SPEECH", 3)));
        when(clientResolver.resolve(ENDPOINT_ID)).thenReturn(port);
        when(fileService.upload(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            Long fileId = (long) nextFileId.incrementAndGet();
            return new AiFileUploadResultDTO()
                    .setFileId(fileId)
                    .setBusinessType(invocation.getArgument(0, String.class))
                    .setBusinessKey(invocation.getArgument(1, String.class))
                    .setName(invocation.getArgument(2, String.class))
                    .setSize((long) invocation.getArgument(4, byte[].class).length);
        });
    }

    @Test
    void supportsOnlySpeechOperations() {
        assertThat(executor.supports(AiMediaTaskDO.OPERATION_TRANSCRIBE)).isTrue();
        assertThat(executor.supports(AiMediaTaskDO.OPERATION_SYNTHESIZE)).isTrue();
        assertThat(executor.supports(AiMediaTaskDO.OPERATION_GENERATE)).isFalse();
        assertThat(executor.supports(AiMediaTaskDO.OPERATION_EDIT)).isFalse();
    }

    @Test
    void deniedAdmissionFailsBeforeAnyPortCall() {
        // 探测结论的配置版本落后（改配置后未重探）：媒体调用必须在任何网络请求之前被拒绝
        when(probeService.getLatestResults(ENDPOINT_ID))
                .thenReturn(List.of(probe("SPEECH_TO_TEXT", 2), probe("TEXT_TO_SPEECH", 2)));

        assertCode(
                () -> executor.execute(lease(), transcribeTask(wav(64))),
                AiErrorCodeConstants.AI_MODEL_CAPABILITY_NOT_ENABLED);

        assertThat(port.transcribeCalls.get()).as("准入失败必须零外发").isZero();
        assertThat(port.synthesizeCalls.get()).isZero();
    }

    @Test
    void transcribeStoresTranscriptAsPrivateTextFileWithHonestUsage() {
        byte[] content = wav(64);
        when(fileService.read(88L)).thenReturn(content);
        port.transcription = new SpeechTranscriptionResponse(
                "你好，这是转写全文", List.of(new SpeechSegment("你好", 0L, 500L)), ModelUsage.of(30, 40), MODEL_REF);

        AiMediaStepOutcome outcome = executor.execute(lease(), transcribeTask(content));

        assertThat(outcome.getStatus()).isEqualTo(AiMediaTaskDO.STATUS_SUCCEEDED);
        assertThat(outcome.getResultCount()).isEqualTo(1);
        assertThat(outcome.getUsageUnit()).isEqualTo(AiSpeechStepExecutor.USAGE_UNIT_TOKEN);
        assertThat(outcome.getUsageQuantity()).isEqualTo(70L);
        assertThat(outcome.getUsageSource()).isEqualTo(AiMediaTaskDO.USAGE_SOURCE_REPORTED);
        assertThat(port.transcribeCalls.get()).isEqualTo(1);

        // 端口收到的是执行期重读过的私有引用（不是受理时的声明副本）
        SpeechTranscriptionRequest request = port.lastTranscriptionRequest;
        assertThat(request.modelId()).isEqualTo(MODEL_REF);
        assertThat(request.audio().fileId()).isEqualTo(88L);
        assertThat(request.audio().mimeType()).isEqualTo("audio/wav");
        assertThat(request.audio().sizeBytes()).isEqualTo((long) content.length);
        assertThat(request.audio().sha256()).isEqualTo(sha256(content));
        assertThat(request.languageHint()).isEqualTo("zh-CN");

        // 转写全文落平台私有文件（text/plain，UTF-8）
        ArgumentCaptor<byte[]> uploaded = ArgumentCaptor.forClass(byte[].class);
        verify(fileService)
                .upload(
                        eq("ai_media_task"),
                        eq("601"),
                        eq("ai-transcript-601.txt"),
                        eq("text/plain"),
                        uploaded.capture());
        assertThat(uploaded.getValue()).isEqualTo("你好，这是转写全文".getBytes(StandardCharsets.UTF_8));

        ArgumentCaptor<AiMediaAssetDO> asset = ArgumentCaptor.forClass(AiMediaAssetDO.class);
        verify(assetMapper).insert(asset.capture());
        assertThat(asset.getValue().getTaskId()).isEqualTo(TASK_ID);
        assertThat(asset.getValue().getOrdinal()).isEqualTo(1);
        assertThat(asset.getValue().getMimeType()).isEqualTo("text/plain");
        assertThat(asset.getValue().getSizeBytes()).isEqualTo(uploaded.getValue().length);
        assertThat(asset.getValue().getSha256()).isEqualTo(sha256(uploaded.getValue()));
        assertThat(asset.getValue().getDurationMillis()).as("文本结果没有时长").isNull();
    }

    @Test
    void transcribeRejectsRevokedSourceAtExecutionTime() {
        when(fileService.read(88L)).thenThrow(new ServiceException(AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND));

        assertCode(
                () -> executor.execute(lease(), transcribeTask(wav(64))), AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND);

        assertThat(port.transcribeCalls.get()).as("受理时能读、执行时失权：不得外发").isZero();
        verify(fileService, never()).upload(any(), any(), any(), any(), any());
        verify(assetMapper, never()).insert(any(AiMediaAssetDO.class));
    }

    @Test
    void transcribeWithMissingSourceFileIdIsRejectedBeforeOutbound() {
        assertCode(
                () -> executor.execute(lease(), transcribeTask(wav(64)).setSourceFileId(null)),
                AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND);
        assertThat(port.transcribeCalls.get()).isZero();
    }

    @Test
    void synthesizeStoresVerifiedAudioWithDurationAndReportedUsage() {
        port.synthesis = new SpeechSynthesisResponse(
                new MediaArtifact("audio/mpeg", id3(256), null, null, null, 1_500L), ModelUsage.of(10, 20), "tts-1");

        AiMediaStepOutcome outcome = executor.execute(lease(), synthesizeTask());

        assertThat(outcome.getStatus()).isEqualTo(AiMediaTaskDO.STATUS_SUCCEEDED);
        assertThat(outcome.getUsageUnit()).isEqualTo(AiSpeechStepExecutor.USAGE_UNIT_TOKEN);
        assertThat(outcome.getUsageQuantity()).isEqualTo(30L);
        assertThat(port.synthesizeCalls.get()).isEqualTo(1);

        SpeechSynthesisRequest request = port.lastSynthesisRequest;
        assertThat(request.text()).isEqualTo("欢迎使用中台");
        assertThat(request.voice()).isEqualTo("Alloy");
        assertThat(request.outputFormat()).isEqualTo("mp3");

        ArgumentCaptor<AiMediaAssetDO> asset = ArgumentCaptor.forClass(AiMediaAssetDO.class);
        verify(assetMapper).insert(asset.capture());
        AiMediaAssetDO stored = asset.getValue();
        assertThat(stored.getTaskId()).isEqualTo(TASK_ID);
        assertThat(stored.getMimeType()).isEqualTo("audio/mpeg");
        assertThat(stored.getSizeBytes()).isEqualTo(id3(256).length);
        assertThat(stored.getSha256()).isEqualTo(sha256(id3(256)));
        assertThat(stored.getDurationMillis()).as("端口给出的真实时长如实落库").isEqualTo(1_500L);
        assertThat(stored.getWidth()).isNull();
        assertThat(stored.getHeight()).isNull();
    }

    @Test
    void synthesizeWithNonAudioArtifactFailsWithoutStoringAnything() {
        byte[] html = "<html><body>vendor error page</body></html>".getBytes(StandardCharsets.UTF_8);
        port.synthesis = new SpeechSynthesisResponse(
                new MediaArtifact("audio/mpeg", html, null, null, null, null), ModelUsage.UNKNOWN, "tts-1");

        assertCode(() -> executor.execute(lease(), synthesizeTask()), AiErrorCodeConstants.AI_MEDIA_OUTPUT_INVALID);

        verify(fileService, never()).upload(any(), any(), any(), any(), any());
        verify(assetMapper, never()).insert(any(AiMediaAssetDO.class));
    }

    @Test
    void synthesizeWithOverlongArtifactIsRejectedWithDurationCode() {
        port.synthesis = new SpeechSynthesisResponse(
                new MediaArtifact("audio/mpeg", id3(64), null, null, null, 1_200_001L), ModelUsage.UNKNOWN, "tts-1");

        assertCode(
                () -> executor.execute(lease(), synthesizeTask()),
                AiErrorCodeConstants.AI_MEDIA_OUTPUT_DURATION_EXCEEDED);

        verify(fileService, never()).upload(any(), any(), any(), any(), any());
        verify(assetMapper, never()).insert(any(AiMediaAssetDO.class));
    }

    @Test
    void synthesizeWithUnknownUsageRecordsUnknownWithoutFabricatingZero() {
        port.synthesis = new SpeechSynthesisResponse(
                new MediaArtifact("audio/wav", wav(128), null, null, null, null), ModelUsage.UNKNOWN, "tts-1");

        AiMediaStepOutcome outcome = executor.execute(lease(), synthesizeTask().setOutputFormat("wav"));

        assertThat(outcome.getUsageSource()).isEqualTo(AiMediaTaskDO.USAGE_SOURCE_UNKNOWN);
        assertThat(outcome.getUsageQuantity()).as("上游缺失不得用 0 冒充真实计量").isNull();
        assertThat(outcome.getUsageUnit()).isNull();
    }

    @Test
    void taskWithMismatchedCapabilityIsRejectedBeforeAdmission() {
        AiMediaTaskDO task = synthesizeTask().setCapability(ModelCapability.SPEECH_TO_TEXT.name());

        assertCode(() -> executor.execute(lease(), task), AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND);

        assertThat(port.synthesizeCalls.get()).isZero();
    }

    private static AiMediaTaskDO transcribeTask(byte[] content) {
        return new AiMediaTaskDO()
                .setId(TASK_ID)
                .setOperation(AiMediaTaskDO.OPERATION_TRANSCRIBE)
                .setCapability(ModelCapability.SPEECH_TO_TEXT.name())
                .setEndpointId(ENDPOINT_ID)
                .setModelRef(MODEL_REF)
                .setSourceFileId(88L)
                .setSourceMime("audio/wav")
                .setSourceSizeBytes((long) content.length)
                .setSourceSha256(sha256(content))
                .setLanguageHint("zh-CN")
                .setOutputCount(1);
    }

    private static AiMediaTaskDO synthesizeTask() {
        return new AiMediaTaskDO()
                .setId(TASK_ID)
                .setOperation(AiMediaTaskDO.OPERATION_SYNTHESIZE)
                .setCapability(ModelCapability.TEXT_TO_SPEECH.name())
                .setEndpointId(ENDPOINT_ID)
                .setModelRef("tts-1")
                .setInputText("欢迎使用中台")
                .setVoice("Alloy")
                .setOutputFormat("mp3")
                .setOutputCount(1);
    }

    private static AiMediaTaskLeaseDTO lease() {
        return new AiMediaTaskLeaseDTO().setTaskId(TASK_ID).setOwner("worker").setEpoch(1);
    }

    private static AiModelEndpointRevisionDO revision(String capabilities) {
        return new AiModelEndpointRevisionDO().setCapabilities(capabilities);
    }

    private static AiModelProbeResultDTO probe(String probeKind, int configRevision) {
        return new AiModelProbeResultDTO()
                .setProbeKind(probeKind)
                .setStatus("SUPPORTED")
                .setConfigRevision(configRevision);
    }

    private static void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOf(ServiceException.class).satisfies(exception -> assertThat(
                        ((ServiceException) exception).getCode())
                .isEqualTo(code.getCode()));
    }

    private static byte[] wav(int padding) {
        byte[] content = new byte[12 + padding];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, content, 0, 4);
        System.arraycopy("WAVE".getBytes(StandardCharsets.US_ASCII), 0, content, 8, 4);
        return content;
    }

    private static byte[] id3(int padding) {
        byte[] content = new byte[12 + padding];
        System.arraycopy("ID3".getBytes(StandardCharsets.US_ASCII), 0, content, 0, 3);
        return content;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException missingAlgorithm) {
            throw new IllegalStateException("SHA-256 不可用", missingAlgorithm);
        }
    }

    /** 记录型语音端口替身（非 Mockito 打桩）：零外发与引用形状都是真实行为断言。 */
    private static final class RecordingSpeechPort implements ModelPort {

        private final AtomicInteger transcribeCalls = new AtomicInteger();

        private final AtomicInteger synthesizeCalls = new AtomicInteger();

        private volatile SpeechTranscriptionResponse transcription;

        private volatile SpeechSynthesisResponse synthesis;

        private volatile SpeechTranscriptionRequest lastTranscriptionRequest;

        private volatile SpeechSynthesisRequest lastSynthesisRequest;

        @Override
        public Set<ModelCapability> capabilities() {
            return Set.of(ModelCapability.SPEECH_TO_TEXT, ModelCapability.TEXT_TO_SPEECH);
        }

        @Override
        public ModelResponse generate(ModelRequest request) {
            throw new ModelException(ModelException.Reason.CAPABILITY_UNSUPPORTED, "文本调用不应发生");
        }

        @Override
        public EmbeddingResponse embed(EmbeddingRequest request) {
            throw new ModelException(ModelException.Reason.CAPABILITY_UNSUPPORTED, "嵌入调用不应发生");
        }

        @Override
        public SpeechTranscriptionResponse transcribeSpeech(SpeechTranscriptionRequest request) {
            transcribeCalls.incrementAndGet();
            lastTranscriptionRequest = request;
            return transcription;
        }

        @Override
        public SpeechSynthesisResponse synthesizeSpeech(SpeechSynthesisRequest request) {
            synthesizeCalls.incrementAndGet();
            lastSynthesisRequest = request;
            return synthesis;
        }
    }
}
