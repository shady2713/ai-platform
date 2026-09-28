package com.basicframework.framework.ai.provider.springai;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

/**
 * 媒体探测用的平台内置合成夹具（X02）：只生成图片，不使用任何真实用户数据。
 *
 * <p>为什么在内存里现画而不是提交二进制夹具：探测夹具必须**可复现、可审计**——
 * 代码里画出来的图永远同尺寸同内容，仓库里也就不需要存二进制资源；
 * 两张图都很小（64x64 / 96x96），不会让一次探测变成一次大流量外发。
 *
 * <p>为什么不用字体绘制"文字"：容器/CI 里不保证有可用字体，靠字体画字会让探测结果依赖环境。
 * OCR 夹具因此画成固定行距的黑色条带（"文字行"的占位几何），探测判据按
 * {@code ModelProbeKind.IMAGE_OCR} 的冻结语义只要求**返回非空文本**，不要求逐字匹配。
 */
final class SyntheticMediaFixtures {

    /** 图片理解探测夹具：64x64，蓝底 + 白色方块（形状足够简单，任何视觉模型都能描述）。 */
    private static final int UNDERSTANDING_SIDE = 64;

    /** OCR 探测夹具：96x96 白底 + 三条黑色"文字行"条带。 */
    private static final int OCR_SIDE = 96;

    /** 转写探测夹具：8 kHz / 单声道 / 16 位 PCM 的 0.5 秒 440 Hz 低幅正弦（可复现的短音频）。 */
    private static final int SPEECH_SAMPLE_RATE = 8_000;

    private static final double SPEECH_SECONDS = 0.5;

    private static final double SPEECH_TONE_HZ = 440.0;

    /** 幅度取满量程的 20%：能听出音调又不削顶。 */
    private static final double SPEECH_AMPLITUDE = 0.2;

    private static byte[] understandingFixture;

    private static byte[] ocrFixture;

    private static byte[] speechFixture;

    private SyntheticMediaFixtures() {}

    /** 图片理解夹具（懒生成并缓存：同一进程内字节完全一致）。 */
    static synchronized byte[] understandingFixturePng() {
        if (understandingFixture == null) {
            BufferedImage image = new BufferedImage(UNDERSTANDING_SIDE, UNDERSTANDING_SIDE, BufferedImage.TYPE_INT_RGB);
            fill(image, 0x1F4FD8);
            drawRect(image, 16, 16, 32, 32, 0xFFFFFF);
            understandingFixture = toPng(image);
        }
        return understandingFixture.clone();
    }

    /** OCR 夹具（白底 + 三条黑色条带，模拟文字行）。 */
    static synchronized byte[] ocrFixturePng() {
        if (ocrFixture == null) {
            BufferedImage image = new BufferedImage(OCR_SIDE, OCR_SIDE, BufferedImage.TYPE_INT_RGB);
            fill(image, 0xFFFFFF);
            drawRect(image, 12, 20, 72, 10, 0x000000);
            drawRect(image, 12, 42, 60, 10, 0x000000);
            drawRect(image, 12, 64, 66, 10, 0x000000);
            ocrFixture = toPng(image);
        }
        return ocrFixture.clone();
    }

    /**
     * 转写探测夹具（0.5 秒 8 kHz 单声道 16 位 PCM WAV，440 Hz 低幅正弦）。
     *
     * <p>为什么要合成音频而不是提交二进制样本：探测夹具必须**可复现、可审计**，代码里算出来的
     * WAV 永远同采样率同内容，仓库里也就不需要存二进制资源；0.5 秒、约 8 KB，
     * 不会让一次探测变成一次大流量外发。
     *
     * <p>不承诺"合成夹具能被识别成文字"：{@code ModelProbeKind.SPEECH_TO_TEXT} 的冻结判据是
     * "短合成音频夹具返回非空文本"，端点对非语音内容返回空文本时结论记 UNSUPPORTED
     * （与 OCR 夹具同一套诚实语义），不因此推断端点不可用、也不改用文本能力凑结论。
     */
    static synchronized byte[] speechFixtureWav() {
        if (speechFixture == null) {
            speechFixture = toWav(speechSamples());
        }
        return speechFixture.clone();
    }

    /** 生成 PCM 采样（16 位小端，单声道）。 */
    private static byte[] speechSamples() {
        int sampleCount = (int) Math.round(SPEECH_SAMPLE_RATE * SPEECH_SECONDS);
        byte[] samples = new byte[sampleCount * 2];
        for (int index = 0; index < sampleCount; index++) {
            double seconds = (double) index / SPEECH_SAMPLE_RATE;
            short value = (short)
                    Math.round(Math.sin(2 * Math.PI * SPEECH_TONE_HZ * seconds) * SPEECH_AMPLITUDE * Short.MAX_VALUE);
            samples[index * 2] = (byte) (value & 0xFF);
            samples[index * 2 + 1] = (byte) ((value >> 8) & 0xFF);
        }
        return samples;
    }

    /** 44 字节标准 WAV 头 + PCM 数据（RIFF/WAVE/fmt/data）。 */
    private static byte[] toWav(byte[] samples) {
        int byteRate = SPEECH_SAMPLE_RATE * 2;
        int dataSize = samples.length;
        ByteBuffer buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(36 + dataSize);
        buffer.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(SPEECH_SAMPLE_RATE);
        buffer.putInt(byteRate);
        buffer.putShort((short) 2);
        buffer.putShort((short) 16);
        buffer.put("data".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(dataSize);
        buffer.put(samples);
        return buffer.array();
    }

    private static void fill(BufferedImage image, int rgb) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, rgb);
            }
        }
    }

    private static void drawRect(BufferedImage image, int x, int y, int width, int height, int rgb) {
        for (int row = y; row < y + height; row++) {
            for (int column = x; column < x + width; column++) {
                image.setRGB(column, row, rgb);
            }
        }
    }

    private static byte[] toPng(BufferedImage image) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException failure) {
            // 内存图片编码失败属于环境损坏：直接失败，不能降级为空夹具冒充探测
            throw new IllegalStateException("合成探测图片编码失败", failure);
        }
    }
}
