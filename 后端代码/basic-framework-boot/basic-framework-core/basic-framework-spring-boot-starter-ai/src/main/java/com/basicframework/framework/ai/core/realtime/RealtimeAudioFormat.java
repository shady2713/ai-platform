package com.basicframework.framework.ai.core.realtime;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 实时会话音频格式（X05 冻结）：受理时固定的音频参数，非法组合直接拒绝（不截断、不取默认值）。
 *
 * <p>取值是**平台词汇**，不是任何供应商的默认值：MIME 只接受三种封装（裸 PCM、Ogg、WebM），
 * 采样率/声道/帧长命中冻结集合。压缩封装的单帧字节上限无法从参数推导，取平台硬上限
 * {@link #MAX_COMPRESSED_FRAME_BYTES}（64 KiB）：超过即拒绝，避免"帧长参数合规但单帧无限大"。
 *
 * <p>规范形式（{@link #canonicalForm()}）是持久化与协议里出现的唯一形状：例如
 * {@code audio/pcm@16000:1:20}。会话受理时把规范形式写入会话行，之后解析回来做逐帧核验——
 * 客户端不能在中途改格式（改格式必须新建会话）。
 *
 * @param mimeType    封装类型（audio/pcm、audio/ogg、audio/webm；小写）
 * @param sampleRateHz 采样率（8000/16000/24000/48000）
 * @param channels    声道数（1/2）
 * @param frameMillis 单帧时长（10/20/40/60 毫秒）
 */
public record RealtimeAudioFormat(String mimeType, int sampleRateHz, int channels, int frameMillis) {

    /** 裸 PCM（16 位小端、交错声道）：单帧字节数与参数一一对应，是唯一可推导上限的封装。 */
    public static final String MIME_PCM = "audio/pcm";

    /** Ogg 封装（含 Ogg Opus）。 */
    public static final String MIME_OGG = "audio/ogg";

    /** WebM 封装（含 WebM Opus）。 */
    public static final String MIME_WEBM = "audio/webm";

    /** 允许的封装集合（与 X04 非实时音频白名单的差异：实时不接受 mpeg/mp4 这类容器）。 */
    public static final Set<String> MIME_TYPES = Set.of(MIME_PCM, MIME_OGG, MIME_WEBM);

    /** 允许的采样率。 */
    public static final Set<Integer> SAMPLE_RATES = Set.of(8000, 16000, 24000, 48000);

    /** 允许的声道数。 */
    public static final Set<Integer> CHANNELS = Set.of(1, 2);

    /** 允许的帧长（毫秒）。 */
    public static final Set<Integer> FRAME_MILLIS = Set.of(10, 20, 40, 60);

    /** 压缩封装（非 PCM）的单帧字节硬上限：无法从参数推导，取 64 KiB。 */
    public static final int MAX_COMPRESSED_FRAME_BYTES = 64 * 1024;

    /** PCM 每个采样的字节数（16 位）。 */
    private static final int PCM_BYTES_PER_SAMPLE = 2;

    public RealtimeAudioFormat {
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("实时音频封装不能为空");
        }
        mimeType = mimeType.trim().toLowerCase(Locale.ROOT);
        if (!MIME_TYPES.contains(mimeType)) {
            throw new IllegalArgumentException("实时音频封装不在允许集合内：" + mimeType);
        }
        if (!SAMPLE_RATES.contains(sampleRateHz)) {
            throw new IllegalArgumentException("实时音频采样率不在允许集合内：" + sampleRateHz);
        }
        if (!CHANNELS.contains(channels)) {
            throw new IllegalArgumentException("实时音频声道数不在允许集合内：" + channels);
        }
        if (!FRAME_MILLIS.contains(frameMillis)) {
            throw new IllegalArgumentException("实时音频帧长不在允许集合内：" + frameMillis);
        }
    }

    /** 是否裸 PCM（单帧上限可由参数推导）。 */
    public boolean pcm() {
        return MIME_PCM.equals(mimeType);
    }

    /**
     * 单帧字节上限：PCM 按 采样率 × 声道 × 2 字节 × 帧长 推导；压缩封装取平台硬上限。
     *
     * <p>这是**上限**而不是期望值：更小的帧允许（例如 20ms 帧被拆成两个 10ms 发送），更大的帧拒绝。
     */
    public int maxFrameBytes() {
        if (!pcm()) {
            return MAX_COMPRESSED_FRAME_BYTES;
        }
        return sampleRateHz * channels * PCM_BYTES_PER_SAMPLE * frameMillis / 1000;
    }

    /** 规范形式（持久化与协议里出现的唯一形状）。 */
    public String canonicalForm() {
        return mimeType + "@" + sampleRateHz + ":" + channels + ":" + frameMillis;
    }

    /** 解析规范形式；形状或取值不合法返回空（调用方按"会话事实损坏"拒绝，不猜默认值）。 */
    public static Optional<RealtimeAudioFormat> parse(String canonicalForm) {
        if (canonicalForm == null || canonicalForm.isBlank()) {
            return Optional.empty();
        }
        String value = canonicalForm.trim();
        int at = value.indexOf('@');
        if (at <= 0 || at == value.length() - 1) {
            return Optional.empty();
        }
        String mime = value.substring(0, at);
        String[] parts = value.substring(at + 1).split(":");
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            return Optional.of(new RealtimeAudioFormat(
                    mime,
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim())));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return canonicalForm();
    }
}
