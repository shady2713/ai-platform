package com.basicframework.module.ai.service.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * 受控音频校验（X04）：声明与内容的一致性、字节/时长上限与摘要核对；
 * 不合格一律拒绝（不落半段音频、不"尽力转码"），拒绝路径零外发。
 */
class AiSpeechAudioGuardTest {

    private final AiSpeechAudioGuard guard = new AiSpeechAudioGuard();

    @Test
    void verifyInputAcceptsConsistentDeclarationAndReturnsVerifiedFacts() {
        byte[] content = wav(64);

        AiSpeechAudioVerified verified = guard.verifyInput("audio/wav", content.length, sha256(content), content);

        assertThat(verified.format()).isEqualTo(AiSpeechAudioFormat.WAV);
        assertThat(verified.sizeBytes()).isEqualTo(content.length);
        assertThat(verified.sha256()).isEqualTo(sha256(content));
        assertThat(verified.durationMillis()).as("输入侧不做解码：时长是未知而不是 0").isNull();
    }

    @Test
    void verifyInputAcceptsMissingDeclaredSha256() {
        byte[] content = wav(64);

        AiSpeechAudioVerified verified = guard.verifyInput("audio/wav", content.length, null, content);

        assertThat(verified.sha256()).isEqualTo(sha256(content));
    }

    @Test
    void verifyInputRejectsForgedOrUnknownDeclarations() {
        byte[] content = wav(64);

        assertCode(
                () -> guard.verifyInput("video/mp4", content.length, null, content),
                AiErrorCodeConstants.AI_MEDIA_INPUT_TYPE_UNSUPPORTED);
        // 声明字节数与真实内容不符：不能把"声明的大小"当作准入依据
        assertCode(
                () -> guard.verifyInput("audio/wav", content.length + 1, null, content),
                AiErrorCodeConstants.AI_MEDIA_INPUT_TYPE_UNSUPPORTED);
        // 声明是 WAV 实际是 HTML（伪装音频）
        byte[] html = "<html><body>vendor error page</body></html>".getBytes(StandardCharsets.UTF_8);
        assertCode(
                () -> guard.verifyInput("audio/wav", html.length, null, html),
                AiErrorCodeConstants.AI_MEDIA_INPUT_TYPE_UNSUPPORTED);
        assertCode(
                () -> guard.verifyInput("audio/wav", 0L, null, new byte[0]),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(
                () -> guard.verifyInput("audio/wav", 16L, null, null), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(
                () -> guard.verifyInput("audio/wav", 999_999L, null, content),
                AiErrorCodeConstants.AI_MEDIA_INPUT_TYPE_UNSUPPORTED);
    }

    @Test
    void verifyInputRejectsShaMismatchAndMalformedSha() {
        byte[] content = wav(64);

        assertCode(
                () -> guard.verifyInput("audio/wav", content.length, sha256(new byte[] {1, 2, 3}), content),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(
                () -> guard.verifyInput("audio/wav", content.length, "not-a-sha", content),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
    }

    @Test
    void verifyOutputAcceptsRealAudioWithinLimitsAndKeepsReportedDuration() {
        byte[] content = id3(128);

        AiSpeechAudioVerified verified = guard.verifyOutput("audio/mpeg", content, 1_500L, "mp3");

        assertThat(verified.format()).isEqualTo(AiSpeechAudioFormat.MPEG);
        assertThat(verified.sizeBytes()).isEqualTo(content.length);
        assertThat(verified.durationMillis()).isEqualTo(1_500L);
        assertThat(verified.sha256()).isEqualTo(sha256(content));
    }

    @Test
    void verifyOutputKeepsUnknownDurationAsUnknown() {
        AiSpeechAudioVerified verified = guard.verifyOutput("audio/ogg", ogg(64), null, "opus");

        assertThat(verified.format()).isEqualTo(AiSpeechAudioFormat.OGG);
        assertThat(verified.durationMillis()).as("上游没给时长：未知，不写 0").isNull();
    }

    @Test
    void verifyOutputRejectsEmptyForgedAndMismatchedArtifacts() {
        assertCode(
                () -> guard.verifyOutput("audio/mpeg", new byte[0], null, "mp3"),
                AiErrorCodeConstants.AI_MEDIA_OUTPUT_EMPTY);
        assertCode(
                () -> guard.verifyOutput("audio/mpeg", null, null, "mp3"), AiErrorCodeConstants.AI_MEDIA_OUTPUT_EMPTY);
        // 上游声称 mp3 但字节是 HTML
        byte[] html = "<html><body>vendor error page</body></html>".getBytes(StandardCharsets.UTF_8);
        assertCode(
                () -> guard.verifyOutput("audio/mpeg", html, null, "mp3"),
                AiErrorCodeConstants.AI_MEDIA_OUTPUT_INVALID);
        // 上游返回的 MIME 与请求格式不一致（请求 wav 却给 mp3）
        assertCode(
                () -> guard.verifyOutput("audio/mpeg", id3(64), null, "wav"),
                AiErrorCodeConstants.AI_MEDIA_OUTPUT_INVALID);
        assertCode(() -> guard.verifyOutput(null, id3(64), null, "mp3"), AiErrorCodeConstants.AI_MEDIA_OUTPUT_INVALID);
        // 请求格式不在白名单（受理侧已收窄，这里是兜底）
        assertCode(
                () -> guard.verifyOutput("audio/x-aac", id3(64), null, "aac"),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
    }

    @Test
    void verifyOutputRejectsArtifactOverDurationLimit() {
        assertCode(
                () -> guard.verifyOutput("audio/mpeg", id3(64), 1_200_001L, "mp3"),
                AiErrorCodeConstants.AI_MEDIA_OUTPUT_DURATION_EXCEEDED);
        // 边界值：20 分钟整可以通过
        AiSpeechAudioVerified verified = guard.verifyOutput("audio/mpeg", id3(64), 1_200_000L, "mp3");
        assertThat(verified.durationMillis()).isEqualTo(1_200_000L);
    }

    @Test
    void normalizeDeclaredSha256RejectsMalformedValues() {
        assertThat(guard.normalizeDeclaredSha256(null)).isEmpty();
        assertThat(guard.normalizeDeclaredSha256("  ")).isEmpty();
        String upper = sha256(wav(8)).toUpperCase(java.util.Locale.ROOT);
        assertThat(guard.normalizeDeclaredSha256(upper)).contains(sha256(wav(8)));
        assertCode(() -> guard.normalizeDeclaredSha256("zz"), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
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

    private static byte[] ogg(int padding) {
        byte[] content = new byte[12 + padding];
        System.arraycopy("OggS".getBytes(StandardCharsets.US_ASCII), 0, content, 0, 4);
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
