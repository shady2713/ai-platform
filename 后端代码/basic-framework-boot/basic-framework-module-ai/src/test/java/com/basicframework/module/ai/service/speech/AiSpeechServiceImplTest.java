package com.basicframework.module.ai.service.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaTaskDO;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.file.AiFileService;
import com.basicframework.module.ai.service.media.AiMediaTaskService;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskResultDTO;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskSubmitDTO;
import com.basicframework.module.ai.service.speech.dto.AiSpeechSynthesizeDTO;
import com.basicframework.module.ai.service.speech.dto.AiSpeechTranscribeDTO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 语音受理服务（X04）：参数先收窄、音频先核验（A07 + 内容）再受理；
 * 受理行只固定服务端核验过的事实，不把调用方声明原样转存。
 */
class AiSpeechServiceImplTest {

    private static final Long ENDPOINT_ID = 7L;

    private final AiMediaTaskService taskService = mock(AiMediaTaskService.class);

    private final AiFileService fileService = mock(AiFileService.class);

    private final AiSpeechServiceImpl service =
            new AiSpeechServiceImpl(new AiSpeechParams(), new AiSpeechAudioGuard(), taskService, fileService);

    @BeforeEach
    void setUp() {
        when(taskService.submit(any())).thenReturn(new AiMediaTaskResultDTO().setId(512L));
    }

    @Test
    void transcribeVerifiesAudioAndSubmitsVerifiedFacts() {
        byte[] content = wav(128);
        when(fileService.read(88L)).thenReturn(content);

        AiMediaTaskResultDTO result = service.transcribe(new AiSpeechTranscribeDTO()
                .setRequestKey(" stt-1 ")
                .setEndpointId(ENDPOINT_ID)
                .setSourceFileId(88L)
                .setSourceMime("audio/wav")
                .setSourceSizeBytes((long) content.length)
                .setSourceSha256(sha256(content))
                .setSourceDurationMillis(8_000L)
                .setLanguageHint("zh-CN"));

        assertThat(result.getId()).isEqualTo(512L);
        ArgumentCaptor<AiMediaTaskSubmitDTO> captor = ArgumentCaptor.forClass(AiMediaTaskSubmitDTO.class);
        verify(taskService).submit(captor.capture());
        AiMediaTaskSubmitDTO submitted = captor.getValue();
        assertThat(submitted.getRequestKey()).isEqualTo("stt-1");
        assertThat(submitted.getMediaKind()).isEqualTo(AiMediaTaskDO.KIND_AUDIO);
        assertThat(submitted.getOperation()).isEqualTo(AiMediaTaskDO.OPERATION_TRANSCRIBE);
        assertThat(submitted.getCapability()).isEqualTo(ModelCapability.SPEECH_TO_TEXT.name());
        assertThat(submitted.getEndpointId()).isEqualTo(ENDPOINT_ID);
        assertThat(submitted.getLanguageHint()).isEqualTo("zh-CN");
        assertThat(submitted.getSourceFileId()).isEqualTo(88L);
        assertThat(submitted.getSourceMime()).isEqualTo("audio/wav");
        assertThat(submitted.getSourceSizeBytes()).isEqualTo((long) content.length);
        assertThat(submitted.getSourceSha256()).as("受理行只写服务端核验过的摘要").isEqualTo(sha256(content));
        assertThat(submitted.getOutputCount()).isEqualTo(1);
        assertThat(submitted.getOutputFormat()).as("转写没有输出格式").isNull();
    }

    @Test
    void transcribeRecomputesShaWhenCallerOmitsIt() {
        byte[] content = wav(64);
        when(fileService.read(88L)).thenReturn(content);

        service.transcribe(transcribeRequest(content).setSourceSha256(null));

        ArgumentCaptor<AiMediaTaskSubmitDTO> captor = ArgumentCaptor.forClass(AiMediaTaskSubmitDTO.class);
        verify(taskService).submit(captor.capture());
        assertThat(captor.getValue().getSourceSha256()).isEqualTo(sha256(content));
    }

    @Test
    void transcribeRejectsUnreadableSourceWithoutSubmitting() {
        when(fileService.read(88L)).thenThrow(new ServiceException(AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND));

        assertCode(() -> service.transcribe(transcribeRequest(wav(64))), AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND);
        verifyNoInteractions(taskService);
    }

    @Test
    void transcribeRejectsDeclaredMetadataMismatchAndMalformedParametersBeforeSubmit() {
        byte[] content = wav(64);
        when(fileService.read(88L)).thenReturn(content);

        // 声明字节数与真实内容不一致
        assertCode(
                () -> service.transcribe(transcribeRequest(content).setSourceSizeBytes((long) content.length + 1)),
                AiErrorCodeConstants.AI_MEDIA_INPUT_TYPE_UNSUPPORTED);
        // 声明 MIME 不在白名单（参数守卫在任何 IO 之前拒绝）
        assertCode(
                () -> service.transcribe(transcribeRequest(content).setSourceMime("audio/flac")),
                AiErrorCodeConstants.AI_MEDIA_INPUT_TYPE_UNSUPPORTED);
        // 时长超过单段上限
        assertCode(
                () -> service.transcribe(transcribeRequest(content).setSourceDurationMillis(300_001L)),
                AiErrorCodeConstants.AI_MEDIA_INPUT_DURATION_EXCEEDED);
        // 语言不在冻结集合
        assertCode(
                () -> service.transcribe(transcribeRequest(content).setLanguageHint("fr-FR")),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        // 伪装 MIME 的 HTML 字节
        byte[] html = "<html><body>not audio</body></html>".getBytes(StandardCharsets.UTF_8);
        when(fileService.read(99L)).thenReturn(html);
        assertCode(
                () -> service.transcribe(new AiSpeechTranscribeDTO()
                        .setRequestKey("stt-2")
                        .setEndpointId(ENDPOINT_ID)
                        .setSourceFileId(99L)
                        .setSourceMime("audio/wav")
                        .setSourceSizeBytes((long) html.length)
                        .setSourceDurationMillis(1_000L)),
                AiErrorCodeConstants.AI_MEDIA_INPUT_TYPE_UNSUPPORTED);

        verifyNoInteractions(taskService);
    }

    @Test
    void transcribeRejectsNullRequest() {
        assertThatThrownBy(() -> service.transcribe(null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(taskService);
    }

    @Test
    void synthesizeSubmitsNarrowedParamsAndDefaultsFormatToMp3() {
        AiMediaTaskResultDTO result = service.synthesize(new AiSpeechSynthesizeDTO()
                .setRequestKey("tts-1")
                .setEndpointId(ENDPOINT_ID)
                .setText("欢迎使用中台")
                .setVoice("Alloy")
                .setOutputFormat(null));

        assertThat(result.getId()).isEqualTo(512L);
        ArgumentCaptor<AiMediaTaskSubmitDTO> captor = ArgumentCaptor.forClass(AiMediaTaskSubmitDTO.class);
        verify(taskService).submit(captor.capture());
        AiMediaTaskSubmitDTO submitted = captor.getValue();
        assertThat(submitted.getMediaKind()).isEqualTo(AiMediaTaskDO.KIND_AUDIO);
        assertThat(submitted.getOperation()).isEqualTo(AiMediaTaskDO.OPERATION_SYNTHESIZE);
        assertThat(submitted.getCapability()).isEqualTo(ModelCapability.TEXT_TO_SPEECH.name());
        assertThat(submitted.getInputText()).isEqualTo("欢迎使用中台");
        assertThat(submitted.getVoice()).isEqualTo("Alloy");
        assertThat(submitted.getOutputFormat()).isEqualTo("mp3");
        assertThat(submitted.getOutputCount()).isEqualTo(1);
        assertThat(submitted.getSourceFileId()).as("合成没有源文件").isNull();
        verifyNoInteractions(fileService);
    }

    @Test
    void synthesizeRejectsInvalidTextVoiceAndFormatWithoutSubmitting() {
        AiSpeechSynthesizeDTO base = new AiSpeechSynthesizeDTO()
                .setRequestKey("tts-2")
                .setEndpointId(ENDPOINT_ID)
                .setText("你好")
                .setVoice("Alloy")
                .setOutputFormat("wav");

        assertCode(
                () -> service.synthesize(base.setOutputFormat("aac")), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(
                () -> service.synthesize(base.setOutputFormat("wav").setVoice("1bad")),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(
                () -> service.synthesize(base.setVoice("Alloy").setText("   ")),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertThatThrownBy(() -> service.synthesize(null)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(taskService, fileService);
    }

    private AiSpeechTranscribeDTO transcribeRequest(byte[] content) {
        return new AiSpeechTranscribeDTO()
                .setRequestKey("stt-1")
                .setEndpointId(ENDPOINT_ID)
                .setSourceFileId(88L)
                .setSourceMime("audio/wav")
                .setSourceSizeBytes((long) content.length)
                .setSourceSha256(sha256(content))
                .setSourceDurationMillis(8_000L);
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

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException missingAlgorithm) {
            throw new IllegalStateException("SHA-256 不可用", missingAlgorithm);
        }
    }
}
