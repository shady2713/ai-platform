package com.basicframework.module.ai.service.speech;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 音频格式白名单（X04）：五种封装的文件头判定与 TTS 输出格式映射。
 *
 * <p>白名单之外的 MIME 一律返回空（调用方按不支持拒绝）；映射之外不猜格式。
 */
class AiSpeechAudioFormatTest {

    @Test
    void mimeWhitelistMatchesTheFrozenClientContract() {
        assertThat(AiSpeechAudioFormat.fromMimeType("audio/mpeg")).contains(AiSpeechAudioFormat.MPEG);
        assertThat(AiSpeechAudioFormat.fromMimeType(" AUDIO/MP4 ")).contains(AiSpeechAudioFormat.MP4);
        assertThat(AiSpeechAudioFormat.fromMimeType("audio/ogg")).contains(AiSpeechAudioFormat.OGG);
        assertThat(AiSpeechAudioFormat.fromMimeType("audio/wav")).contains(AiSpeechAudioFormat.WAV);
        assertThat(AiSpeechAudioFormat.fromMimeType("audio/webm")).contains(AiSpeechAudioFormat.WEBM);
        assertThat(AiSpeechAudioFormat.fromMimeType("audio/flac")).isEmpty();
        assertThat(AiSpeechAudioFormat.fromMimeType("video/mp4")).isEmpty();
        assertThat(AiSpeechAudioFormat.fromMimeType(null)).isEmpty();
        assertThat(AiSpeechAudioFormat.fromMimeType(" ")).isEmpty();
    }

    @Test
    void outputFormatsMapToFixedMimeTypes() {
        assertThat(AiSpeechAudioFormat.fromOutputFormat("mp3")).contains(AiSpeechAudioFormat.MPEG);
        assertThat(AiSpeechAudioFormat.fromOutputFormat(" WAV ")).contains(AiSpeechAudioFormat.WAV);
        assertThat(AiSpeechAudioFormat.fromOutputFormat("opus"))
                .as("opus 以 Ogg 封装返回")
                .contains(AiSpeechAudioFormat.OGG);
        assertThat(AiSpeechAudioFormat.fromOutputFormat("aac")).isEmpty();
        assertThat(AiSpeechAudioFormat.fromOutputFormat(null)).isEmpty();
    }

    @Test
    void formatNamesAndMimeTypesAreStable() {
        assertThat(AiSpeechAudioFormat.MPEG.mimeType()).isEqualTo("audio/mpeg");
        assertThat(AiSpeechAudioFormat.MPEG.formatName()).isEqualTo("mp3");
        assertThat(AiSpeechAudioFormat.OGG.formatName()).isEqualTo("ogg");
        assertThat(AiSpeechAudioFormat.WAV.formatName()).isEqualTo("wav");
    }

    @Test
    void magicDetectionMatchesEachContainer() {
        assertThat(AiSpeechAudioFormat.WAV.matchesMagic(wav())).isTrue();
        assertThat(AiSpeechAudioFormat.MPEG.matchesMagic(id3())).isTrue();
        assertThat(AiSpeechAudioFormat.MPEG.matchesMagic(frameSync())).isTrue();
        assertThat(AiSpeechAudioFormat.OGG.matchesMagic(ogg())).isTrue();
        assertThat(AiSpeechAudioFormat.MP4.matchesMagic(mp4())).isTrue();
        assertThat(AiSpeechAudioFormat.WEBM.matchesMagic(webm())).isTrue();

        // 声明与内容不是同一种东西：交叉判定必须失败
        assertThat(AiSpeechAudioFormat.MPEG.matchesMagic(wav())).isFalse();
        assertThat(AiSpeechAudioFormat.WAV.matchesMagic(ogg())).isFalse();
        assertThat(AiSpeechAudioFormat.WEBM.matchesMagic(mp4())).isFalse();
    }

    @Test
    void magicDetectionRejectsShortNullAndForgedContent() {
        assertThat(AiSpeechAudioFormat.WAV.matchesMagic(null)).isFalse();
        assertThat(AiSpeechAudioFormat.WAV.matchesMagic(new byte[0])).isFalse();
        assertThat(AiSpeechAudioFormat.WAV.matchesMagic("RIFF".getBytes(StandardCharsets.US_ASCII)))
                .as("过短的文件不可能被判定为真实音频")
                .isFalse();
        // RIFF 容器但不是 WAVE（例如 AVI）：不得当作音频放行
        byte[] riffAvi = new byte[16];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, riffAvi, 0, 4);
        System.arraycopy("AVI ".getBytes(StandardCharsets.US_ASCII), 0, riffAvi, 8, 4);
        assertThat(AiSpeechAudioFormat.WAV.matchesMagic(riffAvi)).isFalse();
        assertThat(AiSpeechAudioFormat.WAV.matchesMagic(
                        "<html><body>not audio</body></html>".getBytes(StandardCharsets.UTF_8)))
                .isFalse();
    }

    @Test
    void fromMimeTypeNeverReturnsUnknownFormat() {
        for (AiSpeechAudioFormat format : AiSpeechAudioFormat.values()) {
            Optional<AiSpeechAudioFormat> parsed = AiSpeechAudioFormat.fromMimeType(format.mimeType());
            assertThat(parsed).contains(format);
        }
    }

    private static byte[] wav() {
        byte[] content = new byte[16];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, content, 0, 4);
        System.arraycopy("WAVE".getBytes(StandardCharsets.US_ASCII), 0, content, 8, 4);
        return content;
    }

    private static byte[] id3() {
        byte[] content = new byte[16];
        System.arraycopy("ID3".getBytes(StandardCharsets.US_ASCII), 0, content, 0, 3);
        return content;
    }

    private static byte[] frameSync() {
        byte[] content = new byte[16];
        content[0] = (byte) 0xFF;
        content[1] = (byte) 0xFB;
        return content;
    }

    private static byte[] ogg() {
        byte[] content = new byte[16];
        System.arraycopy("OggS".getBytes(StandardCharsets.US_ASCII), 0, content, 0, 4);
        return content;
    }

    private static byte[] mp4() {
        byte[] content = new byte[16];
        System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, content, 4, 4);
        return content;
    }

    private static byte[] webm() {
        byte[] content = new byte[16];
        content[0] = (byte) 0x1A;
        content[1] = (byte) 0x45;
        content[2] = (byte) 0xDF;
        content[3] = (byte) 0xA3;
        return content;
    }
}
