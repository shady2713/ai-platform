package com.basicframework.framework.ai.provider.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.ai.config.AiModelProperties;
import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelProbeKind;
import com.basicframework.framework.ai.core.model.ModelProbeResult;
import com.basicframework.framework.ai.core.model.ModelUsage;
import com.basicframework.framework.ai.core.model.media.SpeechSynthesisRequest;
import com.basicframework.framework.ai.core.model.media.SpeechSynthesisResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.audio.transcription.AudioTranscription;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.Speech;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.audio.tts.TextToSpeechResponse;
import org.springframework.ai.openai.OpenAiAudioSpeechOptions;
import org.springframework.ai.openai.api.OpenAiAudioApi;
import org.springframework.core.io.Resource;
import reactor.core.publisher.Flux;

/**
 * X04 语音通道（非实时 STT/TTS）：探测与运行期调用的可观测语义。
 *
 * <p>被钉住的语义：
 * <ol>
 *   <li><b>转写探测确实上传平台合成 WAV 夹具</b>：multipart 资源带稳定文件名与 RIFF/WAVE 头，
 *       不是纯文本调用、也不使用任何真实用户数据；</li>
 *   <li><b>空文本/空音频不算通过</b>：分别记 UNSUPPORTED + NO_TEXT_RETURNED / NO_AUDIO_RETURNED；</li>
 *   <li><b>未声明能力或未装配通道不发厂商调用</b>：计数为 0，返回稳定 UNSUPPORTED；</li>
 *   <li><b>运行期合成的产物 MIME 由平台按请求格式固定映射</b>（mp3/wav/opus），
 *       用量缺失记 UNKNOWN（不写 0），空产物按 MEDIA_OUTPUT_EMPTY 拒绝。</li>
 * </ol>
 */
class SpringAiSpeechTest {

    /** RIFF 头：RIFF....WAVE。 */
    private static final byte[] WAV_MAGIC = {'R', 'I', 'F', 'F'};

    @Test
    void transcriptionProbeUploadsSyntheticWavWithStableFileName() {
        RecordingTranscriptionModel model = new RecordingTranscriptionModel("你好，这是探测");
        SpringAiModelClient client = clientWith(model, null, ModelCapability.SPEECH_TO_TEXT);

        ModelProbeResult probe = client.probe(ModelProbeKind.SPEECH_TO_TEXT);

        assertThat(probe.isSupported()).isTrue();
        assertThat(probe.detailCode()).isNull();
        assertThat(model.prompts).hasSize(1);
        byte[] uploaded = model.capturedBytes.get(0);
        assertThat(uploaded).as("探测上传的是平台合成 WAV 夹具").startsWith(WAV_MAGIC);
        assertThat(new String(uploaded, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WAVE");
        assertThat(uploaded.length).as("0.5 秒 8kHz 单声道 16 位 PCM + 44 字节头").isEqualTo(44 + 8_000);
        assertThat(model.capturedFileNames.get(0)).as("multipart 必须有扩展名匹配的文件名").isEqualTo("platform-probe.wav");
    }

    @Test
    void transcriptionProbeWithBlankTextIsUnsupported() {
        RecordingTranscriptionModel model = new RecordingTranscriptionModel("   ");
        SpringAiModelClient client = clientWith(model, null, ModelCapability.SPEECH_TO_TEXT);

        ModelProbeResult probe = client.probe(ModelProbeKind.SPEECH_TO_TEXT);

        assertThat(probe.status()).isEqualTo(ModelProbeResult.Status.UNSUPPORTED);
        assertThat(probe.detailCode()).isEqualTo(ModelProbeResult.CODE_NO_TEXT_RETURNED);
        assertThat(model.prompts).as("探测只调用一次转写").hasSize(1);
    }

    @Test
    void transcriptionProbeWithoutWiredModelIsAdapterNotImplementedWithoutCall() {
        AtomicInteger vendorCalls = new AtomicInteger();
        SpringAiModelClient client =
                new SpringAiModelClient(VendorChatResponses.snapshot(ModelCapability.SPEECH_TO_TEXT), prompt -> {
                    vendorCalls.incrementAndGet();
                    return VendorChatResponses.text("不应发生");
                });

        ModelProbeResult probe = client.probe(ModelProbeKind.SPEECH_TO_TEXT);

        assertThat(probe.status()).isEqualTo(ModelProbeResult.Status.UNSUPPORTED);
        assertThat(probe.detailCode()).isEqualTo(ModelProbeResult.CODE_ADAPTER_NOT_IMPLEMENTED);
        assertThat(vendorCalls.get()).as("未装配转写通道不得发起聊天调用").isZero();
    }

    @Test
    void transcriptionProbeWithoutDeclaredCapabilityIsUnsupportedWithoutCall() {
        RecordingTranscriptionModel model = new RecordingTranscriptionModel("不应发生");
        SpringAiModelClient client = clientWith(model, null, ModelCapability.TEXT);

        ModelProbeResult probe = client.probe(ModelProbeKind.SPEECH_TO_TEXT);

        assertThat(probe.status()).isEqualTo(ModelProbeResult.Status.UNSUPPORTED);
        assertThat(probe.detailCode()).isEqualTo(ModelProbeResult.CODE_CAPABILITY_NOT_DECLARED);
        assertThat(model.prompts).isEmpty();
    }

    @Test
    void synthesisProbeUsesMinimalTextAndPassesOnNonEmptyAudio() {
        RecordingSpeechModel model = new RecordingSpeechModel(new byte[] {1, 2, 3});
        SpringAiModelClient client = clientWith(null, model, ModelCapability.TEXT_TO_SPEECH);

        ModelProbeResult probe = client.probe(ModelProbeKind.TEXT_TO_SPEECH);

        assertThat(probe.isSupported()).isTrue();
        OpenAiAudioSpeechOptions options =
                (OpenAiAudioSpeechOptions) model.prompts.get(0).getOptions();
        assertThat(options.getInput()).isEqualTo("ping");
        assertThat(options.getModel()).isEqualTo("gpt-4o-mini");
        assertThat(options.getResponseFormat())
                .as("探测默认按 mp3 请求")
                .isEqualTo(OpenAiAudioApi.SpeechRequest.AudioResponseFormat.MP3);
    }

    @Test
    void synthesisProbeWithEmptyAudioIsUnsupported() {
        RecordingSpeechModel model = new RecordingSpeechModel(new byte[0]);
        SpringAiModelClient client = clientWith(null, model, ModelCapability.TEXT_TO_SPEECH);

        ModelProbeResult probe = client.probe(ModelProbeKind.TEXT_TO_SPEECH);

        assertThat(probe.status()).isEqualTo(ModelProbeResult.Status.UNSUPPORTED);
        assertThat(probe.detailCode()).isEqualTo(ModelProbeResult.CODE_NO_AUDIO_RETURNED);
    }

    @Test
    void synthesisProbeWithoutWiredModelIsAdapterNotImplementedWithoutCall() {
        SpringAiModelClient client =
                new SpringAiModelClient(VendorChatResponses.snapshot(ModelCapability.TEXT_TO_SPEECH), prompt -> null);

        ModelProbeResult probe = client.probe(ModelProbeKind.TEXT_TO_SPEECH);

        assertThat(probe.status()).isEqualTo(ModelProbeResult.Status.UNSUPPORTED);
        assertThat(probe.detailCode()).isEqualTo(ModelProbeResult.CODE_ADAPTER_NOT_IMPLEMENTED);
    }

    @Test
    void speechProbesReportUpstreamFailureAsStableReasonWithoutLeakingBody() {
        RecordingTranscriptionModel model = new RecordingTranscriptionModel("不应发生");
        model.failure = new IllegalStateException("vendor body sk-must-not-leak");
        SpringAiModelClient client = clientWith(model, null, ModelCapability.SPEECH_TO_TEXT);

        ModelProbeResult probe = client.probe(ModelProbeKind.SPEECH_TO_TEXT);

        assertThat(probe.status()).isEqualTo(ModelProbeResult.Status.FAILED);
        assertThat(probe.detailCode()).isEqualTo("UPSTREAM_FAILED");
        assertThat(probe.toString()).doesNotContain("sk-must-not-leak");
    }

    @Test
    void synthesizeSpeechReturnsArtifactWithPlatformMimeAndUnknownUsage() {
        RecordingSpeechModel model = new RecordingSpeechModel("mp3-bytes".getBytes(StandardCharsets.UTF_8));
        SpringAiModelClient client = clientWith(null, model, ModelCapability.TEXT_TO_SPEECH);

        SpeechSynthesisResponse response =
                client.synthesizeSpeech(new SpeechSynthesisRequest("gpt-4o-mini", "欢迎使用中台", "Alloy", "mp3", null));

        assertThat(response.audio().mimeType()).isEqualTo("audio/mpeg");
        assertThat(response.audio().content()).isEqualTo("mp3-bytes".getBytes(StandardCharsets.UTF_8));
        assertThat(response.audio().durationMillis()).as("上游未给时长：未知，不写 0").isNull();
        assertThat(response.usage()).as("厂商 TTS 没有平台可用计量：UNKNOWN").isEqualTo(ModelUsage.UNKNOWN);
        assertThat(response.modelId()).isEqualTo("gpt-4o-mini");
        OpenAiAudioSpeechOptions options =
                (OpenAiAudioSpeechOptions) model.prompts.get(0).getOptions();
        assertThat(options.getInput()).isEqualTo("欢迎使用中台");
        assertThat(options.getVoice()).isEqualTo("Alloy");
        assertThat(options.getResponseFormat()).isEqualTo(OpenAiAudioApi.SpeechRequest.AudioResponseFormat.MP3);
    }

    @Test
    void synthesizeSpeechMapsWavAndOpusToWhitelistedMimeTypes() {
        RecordingSpeechModel model = new RecordingSpeechModel(new byte[] {9, 9, 9});
        SpringAiModelClient client = clientWith(null, model, ModelCapability.TEXT_TO_SPEECH);

        assertThat(client.synthesizeSpeech(new SpeechSynthesisRequest("m", "你好", null, "wav", null))
                        .audio()
                        .mimeType())
                .isEqualTo("audio/wav");
        assertThat(client.synthesizeSpeech(new SpeechSynthesisRequest("m", "你好", null, "opus", null))
                        .audio()
                        .mimeType())
                .as("opus 以 Ogg 封装返回（音频 MIME 白名单里的 audio/ogg）")
                .isEqualTo("audio/ogg");
    }

    @Test
    void unsupportedSpeechFormatIsRejectedAsInputInvalid() {
        // 请求 record 已在构造期拒绝白名单外的格式；这里钉住适配器自己的兜底映射：
        // 映射之外的格式不得被"尽力"猜成某个厂商格式发出
        assertThatThrownBy(() -> SpringAiModelClient.speechFormatOf("aac"))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.MEDIA_INPUT_INVALID));
        assertThatThrownBy(() -> SpringAiModelClient.speechFormatOf(null))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.MEDIA_INPUT_INVALID));
        assertThat(SpringAiModelClient.speechFormatOf(" WAV ").mimeType()).isEqualTo("audio/wav");
    }

    @Test
    void synthesizeSpeechWithEmptyUpstreamAudioIsRejected() {
        RecordingSpeechModel model = new RecordingSpeechModel(null);
        SpringAiModelClient client = clientWith(null, model, ModelCapability.TEXT_TO_SPEECH);

        assertThatThrownBy(() -> client.synthesizeSpeech(new SpeechSynthesisRequest("m", "你好", null, "mp3", null)))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.MEDIA_OUTPUT_EMPTY));
    }

    @Test
    void synthesizeSpeechWithoutDeclaredCapabilityDoesNotCallVendor() {
        RecordingSpeechModel model = new RecordingSpeechModel(new byte[] {1});
        SpringAiModelClient client = clientWith(null, model, ModelCapability.TEXT);

        assertThatThrownBy(() -> client.synthesizeSpeech(SpeechSynthesisRequest.of("m", "你好")))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.CAPABILITY_UNSUPPORTED));
        assertThat(model.prompts).isEmpty();
    }

    @Test
    void synthesizeSpeechWithoutWiredModelIsRejected() {
        SpringAiModelClient client =
                new SpringAiModelClient(VendorChatResponses.snapshot(ModelCapability.TEXT_TO_SPEECH), prompt -> null);

        assertThatThrownBy(() -> client.synthesizeSpeech(SpeechSynthesisRequest.of("m", "你好")))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.CAPABILITY_UNSUPPORTED));
    }

    @Test
    void closedClientRejectsSynthesis() {
        RecordingSpeechModel model = new RecordingSpeechModel(new byte[] {1});
        SpringAiModelClient client = clientWith(null, model, ModelCapability.TEXT_TO_SPEECH);
        client.close();

        assertThatThrownBy(() -> client.synthesizeSpeech(SpeechSynthesisRequest.of("m", "你好")))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.UPSTREAM_FAILED));
        assertThat(model.prompts).isEmpty();
    }

    @Test
    void speechVendorFailureOnProbeIsReportedAsStableFailureWithoutLeakingBody() {
        RecordingSpeechModel model = new RecordingSpeechModel(new byte[] {1});
        model.failure = new IllegalArgumentException("vendor body sk-must-not-leak");
        SpringAiModelClient client = clientWith(null, model, ModelCapability.TEXT_TO_SPEECH);

        ModelProbeResult probe = client.probe(ModelProbeKind.TEXT_TO_SPEECH);

        assertThat(probe.status()).isEqualTo(ModelProbeResult.Status.FAILED);
        assertThat(probe.detailCode()).isEqualTo("UPSTREAM_FAILED");
        assertThat(probe.toString()).doesNotContain("sk-must-not-leak");
    }

    @Test
    void speechVendorFailureAtRuntimeIsReportedAsStableFailureWithoutLeakingBody() {
        RecordingSpeechModel model = new RecordingSpeechModel(new byte[] {1});
        model.failure = new IllegalStateException("vendor body sk-must-not-leak");
        SpringAiModelClient client = clientWith(null, model, ModelCapability.TEXT_TO_SPEECH);

        assertThatThrownBy(() -> client.synthesizeSpeech(SpeechSynthesisRequest.of("m", "你好")))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> {
                    assertThat(((ModelException) exception).getReason())
                            .isEqualTo(ModelException.Reason.UPSTREAM_FAILED);
                    assertThat(exception.getMessage()).doesNotContain("sk-must-not-leak");
                });
    }

    @Test
    void transcriptionProbeTreatsMissingVendorResponseAsNoText() {
        RecordingTranscriptionModel model = new RecordingTranscriptionModel("不应发生");
        model.nullResponse = true;
        SpringAiModelClient client = clientWith(model, null, ModelCapability.SPEECH_TO_TEXT);

        ModelProbeResult probe = client.probe(ModelProbeKind.SPEECH_TO_TEXT);

        assertThat(probe.status()).isEqualTo(ModelProbeResult.Status.UNSUPPORTED);
        assertThat(probe.detailCode()).isEqualTo(ModelProbeResult.CODE_NO_TEXT_RETURNED);
    }

    private static SpringAiModelClient clientWith(
            TranscriptionModel transcriptionModel, TextToSpeechModel speechModel, ModelCapability... capabilities) {
        return new SpringAiModelClient(
                VendorChatResponses.snapshot(capabilities),
                prompt -> VendorChatResponses.text("不应发生"),
                null,
                transcriptionModel,
                speechModel,
                new AiModelProperties());
    }

    /** 记录型转写替身：捕获上传字节与文件名，可切换失败与返回文本。 */
    private static final class RecordingTranscriptionModel implements TranscriptionModel {

        private final String text;

        private final List<AudioTranscriptionPrompt> prompts = new ArrayList<>();

        private final List<byte[]> capturedBytes = new ArrayList<>();

        private final List<String> capturedFileNames = new ArrayList<>();

        private RuntimeException failure;

        /** 上游没有返回任何响应（协议异常）的开关。 */
        private boolean nullResponse;

        RecordingTranscriptionModel(String text) {
            this.text = text;
        }

        @Override
        public AudioTranscriptionResponse call(AudioTranscriptionPrompt prompt) {
            prompts.add(prompt);
            if (failure != null) {
                throw failure;
            }
            if (nullResponse) {
                return null;
            }
            Resource resource = prompt.getInstructions();
            try {
                capturedBytes.add(resource.getInputStream().readAllBytes());
            } catch (Exception exception) {
                throw new IllegalStateException("探测夹具读取失败", exception);
            }
            capturedFileNames.add(resource.getFilename());
            return new AudioTranscriptionResponse(new AudioTranscription(text));
        }
    }

    /** 记录型合成替身：捕获请求，可切换失败与返回字节（null 表示上游没给响应）。 */
    private static final class RecordingSpeechModel implements TextToSpeechModel {

        private final byte[] audio;

        private final List<TextToSpeechPrompt> prompts = new ArrayList<>();

        private RuntimeException failure;

        RecordingSpeechModel(byte[] audio) {
            this.audio = audio;
        }

        @Override
        public TextToSpeechResponse call(TextToSpeechPrompt prompt) {
            prompts.add(prompt);
            if (failure != null) {
                throw failure;
            }
            return audio == null ? null : new TextToSpeechResponse(List.of(new Speech(audio)));
        }

        @Override
        public Flux<TextToSpeechResponse> stream(TextToSpeechPrompt prompt) {
            return Flux.empty();
        }
    }
}
