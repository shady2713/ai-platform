package com.basicframework.module.ai.service.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.ai.core.model.media.SpeechSynthesisRequest;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 语音参数守卫（X04）：格式/时长/文本/音色/语言的收窄规则全部来自 X01 冻结的取值集合，
 * 非法请求在任何 IO 与准入判定之前被拒绝。
 */
class AiSpeechParamsTest {

    private final AiSpeechParams params = new AiSpeechParams();

    @Test
    void platformLimitsMirrorTheFrozenX01ValueSets() {
        assertThat(AiSpeechParams.MAX_AUDIO_BYTES).isEqualTo(25L * 1024 * 1024);
        assertThat(AiSpeechParams.MAX_INPUT_DURATION_MILLIS).isEqualTo(300_000L);
        assertThat(AiSpeechParams.MAX_OUTPUT_DURATION_MILLIS).isEqualTo(1_200_000L);
        assertThat(AiSpeechParams.MAX_TEXT_LENGTH).isEqualTo(SpeechSynthesisRequest.MAX_TEXT_LENGTH);
        assertThat(AiSpeechParams.SUPPORTED_OUTPUT_FORMATS).isEqualTo(Set.of("mp3", "wav", "opus"));
        assertThat(AiSpeechParams.SUPPORTED_LANGUAGES).containsExactlyInAnyOrder("zh-CN", "zh-TW", "en-US", "ja-JP");
        assertThat(AiSpeechParams.MAX_REQUEST_KEY_LENGTH).isEqualTo(40);
    }

    @Test
    void requestKeyIsTrimmedAndBounded() {
        assertThat(params.requireRequestKey("  stt-1 ")).isEqualTo("stt-1");
        assertThrowsCode(() -> params.requireRequestKey("  "), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertThrowsCode(() -> params.requireRequestKey("k".repeat(41)), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
    }

    @Test
    void synthesisTextIsNotEmptyAndWithinPlatformLimit() {
        assertThat(params.requireText(" 你好，世界 ")).isEqualTo(" 你好，世界 ");
        assertThat(params.requireText("字".repeat(4096))).hasSize(4096);
        assertThrowsCode(() -> params.requireText(null), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertThrowsCode(() -> params.requireText("   "), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertThrowsCode(() -> params.requireText("字".repeat(4097)), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
    }

    @Test
    void outputFormatDefaultsToMp3AndRejectsUnknownValues() {
        assertThat(params.normalizeOutputFormat(null)).isEqualTo("mp3");
        assertThat(params.normalizeOutputFormat("  ")).isEqualTo("mp3");
        assertThat(params.normalizeOutputFormat(" WAV ")).isEqualTo("wav");
        assertThat(params.normalizeOutputFormat("Opus")).isEqualTo("opus");
        assertThrowsCode(() -> params.normalizeOutputFormat("aac"), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
    }

    @Test
    void voiceShapeFollowsTheFrozenClientPattern() {
        assertThat(params.normalizeVoice(null)).isNull();
        assertThat(params.normalizeVoice("  ")).isNull();
        assertThat(params.normalizeVoice(" Alloy ")).isEqualTo("Alloy");
        assertThat(params.normalizeVoice("zh-CN-female-1")).isEqualTo("zh-CN-female-1");
        assertThrowsCode(() -> params.normalizeVoice("1abc"), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertThrowsCode(() -> params.normalizeVoice("a"), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertThrowsCode(() -> params.normalizeVoice("a".repeat(65)), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
    }

    @Test
    void languageHintIsOptionalButMustBeInTheFrozenSet() {
        assertThat(params.normalizeLanguageHint(null)).isNull();
        assertThat(params.normalizeLanguageHint(" ")).isNull();
        assertThat(params.normalizeLanguageHint("zh-CN")).isEqualTo("zh-CN");
        assertThrowsCode(() -> params.normalizeLanguageHint("fr-FR"), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
    }

    @Test
    void audioDeclaredMetadataIsBoundedByPlatformLimits() {
        params.requireAudioDeclaredMetadata(9L, "audio/wav", 1024L, 8_000L);

        assertThrowsCode(
                () -> params.requireAudioDeclaredMetadata(null, "audio/wav", 1024L, 8_000L),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertThrowsCode(
                () -> params.requireAudioDeclaredMetadata(9L, "audio/wav", null, 8_000L),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertThrowsCode(
                () -> params.requireAudioDeclaredMetadata(9L, "video/mp4", 1024L, 8_000L),
                AiErrorCodeConstants.AI_MEDIA_INPUT_TYPE_UNSUPPORTED);
        assertThrowsCode(
                () -> params.requireAudioDeclaredMetadata(9L, "audio/wav", 25L * 1024 * 1024 + 1, 8_000L),
                AiErrorCodeConstants.AI_MEDIA_INPUT_TOO_LARGE);
        assertThrowsCode(
                () -> params.requireAudioDeclaredMetadata(9L, "audio/wav", 1024L, null),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertThrowsCode(
                () -> params.requireAudioDeclaredMetadata(9L, "audio/wav", 1024L, 0L),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertThrowsCode(
                () -> params.requireAudioDeclaredMetadata(9L, "audio/wav", 1024L, 300_001L),
                AiErrorCodeConstants.AI_MEDIA_INPUT_DURATION_EXCEEDED);
        // 边界值：300 秒整可以通过
        params.requireAudioDeclaredMetadata(9L, "audio/wav", 1024L, 300_000L);
    }

    private static void assertThrowsCode(Runnable call, com.basicframework.framework.common.exception.ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOf(ServiceException.class).satisfies(exception -> assertThat(
                        ((ServiceException) exception).getCode())
                .isEqualTo(code.getCode()));
    }
}
