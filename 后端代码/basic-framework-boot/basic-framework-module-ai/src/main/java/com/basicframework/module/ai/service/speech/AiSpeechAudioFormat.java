package com.basicframework.module.ai.service.speech;

import java.util.Locale;
import java.util.Optional;

/**
 * 音频输入/输出格式白名单（X04）：非实时 STT/TTS 的接收集合。
 *
 * <p>取值与 X01 冻结的客户端契约 `packages/ai-contracts/src/multimodal.ts` 的
 * {@code AUDIO_MIME_TYPES} 逐项一致（audio/mpeg、audio/mp4、audio/ogg、audio/wav、audio/webm）：
 * 只接受这五种封装，**不接受任意容器**；扩展名与请求里的 MIME 都可以伪造，
 * 只有文件头不可伪造，因此 {@link #matchesMagic(byte[])} 是"声明与内容是否一致"的唯一判据
 * （与图片侧 {@code AiVisionImageFormat} 同一套做法）。
 *
 * <p>TTS 输出格式（mp3/wav/opus，见 {@code SpeechSynthesisRequest.OUTPUT_FORMATS}）与这里的
 * MIME 白名单是两套词汇，映射固定在 {@link #fromOutputFormat(String)}：opus 按标准做法以
 * Ogg 封装（audio/ogg）返回，其余一一对应；映射之外不猜格式。
 */
public enum AiSpeechAudioFormat {

    /** MP3（ID3v2 标签或 MPEG 帧同步）。 */
    MPEG("audio/mpeg", "mp3"),

    /** MP4/M4A（ISO BMFF：偏移 4 起为 ftyp）。 */
    MP4("audio/mp4", "mp4"),

    /** Ogg（含 Ogg Opus）。 */
    OGG("audio/ogg", "ogg"),

    /** WAV（RIFF 容器 + WAVE 标识）。 */
    WAV("audio/wav", "wav"),

    /** WebM（EBML 头）。 */
    WEBM("audio/webm", "webm");

    /** 音频文件头至少要有这么多字节才可能被判定（真实文件远大于此，过短一律视为伪装）。 */
    private static final int MIN_HEADER_BYTES = 12;

    /** MPEG 帧同步的高 3 位（0xE0）。 */
    private static final int MPEG_FRAME_SYNC_MASK = 0xE0;

    private static final byte[] EBML_MAGIC = {(byte) 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3};

    private final String mimeType;

    private final String formatName;

    AiSpeechAudioFormat(String mimeType, String formatName) {
        this.mimeType = mimeType;
        this.formatName = formatName;
    }

    /** 平台 MIME 类型（小写，与前端契约一致）。 */
    public String mimeType() {
        return mimeType;
    }

    /** 格式短名（用于文件命名与错误归因，不带点）。 */
    public String formatName() {
        return formatName;
    }

    /** 按 MIME 解析格式；空白与大小写差异归一化，未知类型返回空。 */
    public static Optional<AiSpeechAudioFormat> fromMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return Optional.empty();
        }
        String normalized = mimeType.trim().toLowerCase(Locale.ROOT);
        for (AiSpeechAudioFormat format : values()) {
            if (format.mimeType.equals(normalized)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }

    /**
     * TTS 输出格式到产物 MIME 的固定映射；映射之外的格式返回空（调用方按请求不合规拒绝）。
     *
     * <p>为什么在这里而不是让适配器上报 MIME：产物格式由**平台受理的请求参数**决定，
     * 上游返回的字节必须与请求的格式一致，平台再按 MIME 复核文件头；让上游自报 MIME
     * 会把"声明什么就是什么"的口子开回来。
     */
    public static Optional<AiSpeechAudioFormat> fromOutputFormat(String outputFormat) {
        if (outputFormat == null || outputFormat.isBlank()) {
            return Optional.empty();
        }
        String normalized = outputFormat.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "mp3" -> Optional.of(MPEG);
            case "wav" -> Optional.of(WAV);
            // opus 按标准封装在 Ogg 容器里返回（audio/ogg），不是裸 Opus 流
            case "opus" -> Optional.of(OGG);
            default -> Optional.empty();
        };
    }

    /** 文件头是否与该格式一致（空内容或过短返回 false）。 */
    public boolean matchesMagic(byte[] content) {
        if (content == null || content.length < MIN_HEADER_BYTES) {
            return false;
        }
        return switch (this) {
            case MPEG -> matchesAscii(content, 0, "ID3") || isMpegFrameSync(content);
            case MP4 -> matchesAscii(content, 4, "ftyp");
            case OGG -> matchesAscii(content, 0, "OggS");
            case WAV -> matchesAscii(content, 0, "RIFF") && matchesAscii(content, 8, "WAVE");
            case WEBM -> matches(content, EBML_MAGIC, 0);
        };
    }

    /** MPEG 帧同步：0xFF 后跟 111xxxxx。 */
    private static boolean isMpegFrameSync(byte[] content) {
        return (content[0] & 0xFF) == 0xFF && (content[1] & MPEG_FRAME_SYNC_MASK) == MPEG_FRAME_SYNC_MASK;
    }

    private static boolean matches(byte[] content, byte[] magic, int offset) {
        if (content.length < offset + magic.length) {
            return false;
        }
        for (int index = 0; index < magic.length; index++) {
            if (content[offset + index] != magic[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesAscii(byte[] content, int offset, String expected) {
        if (content.length < offset + expected.length()) {
            return false;
        }
        for (int index = 0; index < expected.length(); index++) {
            if ((content[offset + index] & 0xFF) != expected.charAt(index)) {
                return false;
            }
        }
        return true;
    }
}
