package com.basicframework.framework.ai.provider.springai;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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

    private static byte[] understandingFixture;

    private static byte[] ocrFixture;

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
