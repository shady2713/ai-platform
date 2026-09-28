package com.basicframework.framework.ai.core.model.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.ai.core.model.ModelUsage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 语音能力契约（X01，非实时 STT/TTS）：分段字幕、文本长度上限与音频产物不变量。
 */
class SpeechMediaContractTest {

    private static final MediaFileRef AUDIO = MediaFileRef.of(7L, "audio/wav", 4096L);

    private static final MediaArtifact AUDIO_ARTIFACT =
            new MediaArtifact("audio/mpeg", "mp3".getBytes(StandardCharsets.UTF_8), null, null, null, 1500L);

    @Test
    void segmentValidatesTimeline() {
        SpeechSegment segment = new SpeechSegment("你好", 0L, 1200L);

        assertThat(segment.durationMillis()).isEqualTo(1200L);
        assertThatThrownBy(() -> new SpeechSegment(" ", 0L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("分段文本");
        assertThatThrownBy(() -> new SpeechSegment("你好", -1L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("开始时间");
        assertThatThrownBy(() -> new SpeechSegment("你好", 100L, 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("结束时间");
    }

    @Test
    void transcriptionRequestNormalizesLanguageHint() {
        assertThat(SpeechTranscriptionRequest.of("m", AUDIO).languageHint()).isNull();
        SpeechTranscriptionRequest request = new SpeechTranscriptionRequest("m", AUDIO, "zh", Duration.ofMinutes(5));

        assertThat(request.audio()).isEqualTo(AUDIO);
        assertThat(request.languageHint()).isEqualTo("zh");
        assertThat(request.timeout()).isEqualTo(Duration.ofMinutes(5));

        assertThatThrownBy(() -> SpeechTranscriptionRequest.of("m", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("音频引用");
    }

    @Test
    void transcriptionResponseRequiresTextAndCopesWithOptionalSegments() {
        SpeechTranscriptionResponse onlyText = SpeechTranscriptionResponse.of("你好", null, "whisper-1");

        assertThat(onlyText.segments()).isEmpty();
        assertThat(onlyText.usage()).isEqualTo(ModelUsage.UNKNOWN);
        assertThat(onlyText.modelId()).isEqualTo("whisper-1");

        List<SpeechSegment> mutable = new ArrayList<>();
        mutable.add(new SpeechSegment("你好", 0L, 900L));
        SpeechTranscriptionResponse withSegments =
                new SpeechTranscriptionResponse("你好", mutable, ModelUsage.of(3, 2), "whisper-1");
        mutable.clear();

        assertThat(withSegments.segments()).hasSize(1);
        assertThat(withSegments.usage().totalTokens()).isEqualTo(5);

        assertThatThrownBy(() -> SpeechTranscriptionResponse.of("  ", null, "m"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("转写文本");
    }

    @Test
    void synthesisRequestAppliesDefaultsAndLengthLimit() {
        SpeechSynthesisRequest request = SpeechSynthesisRequest.of("m", "欢迎使用中台");

        assertThat(request.voice()).isNull();
        assertThat(request.outputFormat()).isEqualTo("mp3");
        assertThat(request.timeout()).isNull();
        assertThat(new SpeechSynthesisRequest("m", "你好", " ", " WAV ", null).voice())
                .isNull();
        assertThat(new SpeechSynthesisRequest("m", "你好", "Alloy", " WAV ", null).outputFormat())
                .isEqualTo("wav");
        assertThat(new SpeechSynthesisRequest("m", "你好", "Alloy", " WAV ", null).voice())
                .isEqualTo("Alloy");

        assertThatThrownBy(() -> SpeechSynthesisRequest.of("m", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("合成文本");
        String tooLong = "字".repeat(SpeechSynthesisRequest.MAX_TEXT_LENGTH + 1);
        assertThatThrownBy(() -> SpeechSynthesisRequest.of("m", tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("长度");
        assertThatThrownBy(() -> new SpeechSynthesisRequest("m", "你好", null, "aac", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("输出格式");
    }

    @Test
    void synthesisResponseRequiresAudioAndNormalizesUsage() {
        SpeechSynthesisResponse response = new SpeechSynthesisResponse(AUDIO_ARTIFACT, null, "tts-1");

        assertThat(response.audio().mimeType()).isEqualTo("audio/mpeg");
        assertThat(response.usage()).isEqualTo(ModelUsage.UNKNOWN);

        assertThatThrownBy(() -> new SpeechSynthesisResponse(null, null, "tts-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("音频产物");
    }
}
