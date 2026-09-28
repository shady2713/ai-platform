package com.basicframework.module.ai.service.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * 受控图片校验（X02）：格式白名单、声明与内容一致、真实像素上限、体积上限。
 *
 * <p>用例覆盖卡片的失败分支："伪装图片/超大像素拒绝"；每条拒绝都断言稳定错误码，
 * 因为这些码是调用方能依赖的唯一原因说明（响应里没有上游报文与文件内容）。
 */
class AiVisionImageGuardTest {

    private final AiVisionImageGuard guard = new AiVisionImageGuard();

    @Test
    void acceptsPngJpegAndWebpWithinPlatformLimits() {
        assertVerified(verify(png(64, 48)), AiVisionImageFormat.PNG, 64, 48);
        assertVerified(
                guard.verify(1L, "IMAGE/JPEG", jpeg(32, 24).length, null, jpeg(32, 24)),
                AiVisionImageFormat.JPEG,
                32,
                24);
        assertVerified(
                guard.verify(1L, "image/webp", webpVp8x(400, 300).length, null, webpVp8x(400, 300)),
                AiVisionImageFormat.WEBP,
                400,
                300);
    }

    @Test
    void readsRealDimensionsFromWebpHeaderVariants() {
        assertVerified(verifyWebp(webpVp8l(120, 90)), AiVisionImageFormat.WEBP, 120, 90);
        assertVerified(verifyWebp(webpVp8(320, 240)), AiVisionImageFormat.WEBP, 320, 240);
    }

    @Test
    void rejectsUnsupportedDeclaredMimeType() {
        assertCode(
                () -> guard.requireDeclaredMetadata(1L, "image/svg+xml", 100L),
                AiErrorCodeConstants.AI_MEDIA_INPUT_TYPE_UNSUPPORTED);
        assertCode(
                () -> guard.requireDeclaredMetadata(1L, "application/pdf", 100L),
                AiErrorCodeConstants.AI_MEDIA_INPUT_TYPE_UNSUPPORTED);
    }

    @Test
    void rejectsForgedContentWhoseMagicDoesNotMatchDeclaredMime() {
        byte[] notAnImage = "PK\u0003\u0004 压缩包冒充图片".getBytes(StandardCharsets.UTF_8);
        assertCode(
                () -> guard.verify(1L, "image/png", notAnImage.length, null, notAnImage),
                AiErrorCodeConstants.AI_MEDIA_INPUT_TYPE_UNSUPPORTED);
    }

    @Test
    void rejectsDeclaredSizeThatDoesNotMatchRealContent() {
        byte[] content = png(16, 16);
        assertCode(
                () -> guard.verify(1L, "image/png", content.length + 1, null, content),
                AiErrorCodeConstants.AI_MEDIA_INPUT_TYPE_UNSUPPORTED);
    }

    @Test
    void rejectsOversizedPixelsBeforeHandingImageToModel() {
        // 9000 像素宽超过平台单边上限 8192：拒绝而不是缩放/裁剪
        byte[] tooWide = png(9000, 10);
        assertCode(() -> verify(tooWide), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        // WebP 的尺寸来自文件头，超大画布同样拒绝（不需要真的造出大图）
        assertCode(() -> verifyWebp(webpVp8x(20000, 10)), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
    }

    @Test
    void rejectsOversizedBytesWithStableCode() {
        long oversize = AiVisionLimits.MAX_IMAGE_BYTES + 1;
        assertCode(
                () -> guard.requireDeclaredMetadata(1L, "image/png", oversize),
                AiErrorCodeConstants.AI_MEDIA_INPUT_TOO_LARGE);
        byte[] content = new byte[(int) oversize];
        System.arraycopy(png(8, 8), 0, content, 0, 8);
        assertCode(
                () -> guard.verify(1L, "image/png", oversize, null, content),
                AiErrorCodeConstants.AI_MEDIA_INPUT_TOO_LARGE);
    }

    @Test
    void rejectsCorruptHeaderAndDigestMismatch() {
        byte[] truncated = new byte[16];
        System.arraycopy(png(8, 8), 0, truncated, 0, 8);
        assertCode(() -> verify(truncated), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        byte[] content = png(16, 16);
        assertCode(
                () -> guard.verify(1L, "image/png", content.length, "0".repeat(64), content),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(() -> guard.normalizeDeclaredSha256("not-a-digest"), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
    }

    @Test
    void rejectsEmptyOrIllegalDeclaredMetadata() {
        assertCode(
                () -> guard.requireDeclaredMetadata(null, "image/png", 1L),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(
                () -> guard.requireDeclaredMetadata(0L, "image/png", 1L),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(
                () -> guard.requireDeclaredMetadata(1L, "image/png", 0L),
                AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
        assertCode(() -> verify(new byte[0]), AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID);
    }

    @Test
    void computedDigestIsAlwaysReturnedForOutboundIntegrityCheck() {
        byte[] content = png(24, 24);
        String digest = AiVisionImageGuard.sha256Hex(content);

        // 声明摘要正确时通过，并回填服务端计算的摘要（外发前的完整性依据）
        assertThat(guard.verify(1L, "image/png", content.length, digest, content)
                        .sha256())
                .isEqualTo(digest);
        // 声明摘要大小写归一化后仍一致
        assertThat(guard.verify(1L, "image/png", content.length, digest.toUpperCase(), content)
                        .sha256())
                .isEqualTo(digest);
    }

    private AiVisionImageInfo verify(byte[] content) {
        return guard.verify(1L, "image/png", content.length, null, content);
    }

    private AiVisionImageInfo verifyWebp(byte[] content) {
        return guard.verify(1L, "image/webp", content.length, null, content);
    }

    private static void assertVerified(AiVisionImageInfo info, AiVisionImageFormat format, int width, int height) {
        assertThat(info.format()).isEqualTo(format);
        assertThat(info.width()).isEqualTo(width);
        assertThat(info.height()).isEqualTo(height);
        assertThat(info.withinDimensionLimits()).isTrue();
    }

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(expected.getCode());
    }

    private static byte[] png(int width, int height) {
        return encode(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png");
    }

    private static byte[] jpeg(int width, int height) {
        return encode(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpeg");
    }

    private static byte[] encode(BufferedImage image, String format) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, format, output);
            return output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("测试图片编码失败", failure);
        }
    }

    /** 手工构造 VP8X 头：RIFF/WEBP + VP8X 块（24 位小端"画布尺寸-1"）。 */
    private static byte[] webpVp8x(int width, int height) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        writeInt32LittleEndian(output, 10 + 8);
        output.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
        output.writeBytes("VP8X".getBytes(StandardCharsets.US_ASCII));
        writeInt32LittleEndian(output, 10);
        output.writeBytes(new byte[4]);
        writeInt24LittleEndian(output, width - 1);
        writeInt24LittleEndian(output, height - 1);
        return output.toByteArray();
    }

    /** 手工构造 VP8L 头：0x2F 签名 + 14 位宽-1 + 14 位高-1。 */
    private static byte[] webpVp8l(int width, int height) {
        int bits = ((height - 1) << 14) | (width - 1);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        writeInt32LittleEndian(output, 5 + 8);
        output.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
        output.writeBytes("VP8L".getBytes(StandardCharsets.US_ASCII));
        writeInt32LittleEndian(output, 5);
        output.write(0x2F);
        writeInt32LittleEndian(output, bits);
        return output.toByteArray();
    }

    /** 手工构造 VP8（有损）头：3 字节帧标签 + 起始码 + 14 位宽/高。 */
    private static byte[] webpVp8(int width, int height) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        writeInt32LittleEndian(output, 10 + 8);
        output.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
        output.writeBytes("VP8 ".getBytes(StandardCharsets.US_ASCII));
        writeInt32LittleEndian(output, 10);
        output.writeBytes(new byte[3]);
        output.write(0x9D);
        output.write(0x01);
        output.write(0x2A);
        writeInt16LittleEndian(output, width & 0x3FFF);
        writeInt16LittleEndian(output, height & 0x3FFF);
        return output.toByteArray();
    }

    private static void writeInt32LittleEndian(ByteArrayOutputStream output, int value) {
        output.writeBytes(ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(value)
                .array());
    }

    private static void writeInt24LittleEndian(ByteArrayOutputStream output, int value) {
        output.write(value & 0xFF);
        output.write((value >> 8) & 0xFF);
        output.write((value >> 16) & 0xFF);
    }

    private static void writeInt16LittleEndian(ByteArrayOutputStream output, int value) {
        output.write(value & 0xFF);
        output.write((value >> 8) & 0xFF);
    }

    // ---------- X03 复用入口：产物核验（没有调用方声明可核对，只按内容判定） ----------

    @Test
    void outputVerificationAcceptsRealRasterAndReportsServerSideFacts() {
        byte[] content = png(120, 90);

        AiVisionImageOutput verified = guard.verifyOutput("image/png", content);

        assertThat(verified.format()).isEqualTo(AiVisionImageFormat.PNG);
        assertThat(verified.sizeBytes()).isEqualTo(content.length);
        assertThat(verified.width()).isEqualTo(120);
        assertThat(verified.height()).isEqualTo(90);
        assertThat(verified.sha256()).isEqualTo(AiVisionImageGuard.sha256Hex(content));
        assertThat(verified.withinDimensionLimits()).isTrue();
    }

    @Test
    void outputVerificationRejectsUnsupportedDeclaredMimeAndForgedBytes() {
        byte[] content = png(32, 32);
        assertCode(() -> guard.verifyOutput("image/svg+xml", content), AiErrorCodeConstants.AI_MEDIA_OUTPUT_INVALID);
        assertCode(
                () -> guard.verifyOutput("image/png", "<html>不是图片</html>".getBytes(StandardCharsets.UTF_8)),
                AiErrorCodeConstants.AI_MEDIA_OUTPUT_INVALID);
    }

    @Test
    void outputVerificationRejectsEmptyBody() {
        assertCode(() -> guard.verifyOutput("image/png", new byte[0]), AiErrorCodeConstants.AI_MEDIA_OUTPUT_EMPTY);
        assertCode(() -> guard.verifyOutput("image/png", null), AiErrorCodeConstants.AI_MEDIA_OUTPUT_EMPTY);
    }

    @Test
    void outputVerificationRejectsOversizedBytesAndBrokenHeader() {
        long oversize = AiVisionLimits.MAX_IMAGE_BYTES + 1;
        byte[] tooBig = new byte[(int) oversize];
        System.arraycopy(png(8, 8), 0, tooBig, 0, 8);
        assertCode(() -> guard.verifyOutput("image/png", tooBig), AiErrorCodeConstants.AI_MEDIA_OUTPUT_INVALID);

        // 魔数对但头被截断：解析失败必须整笔拒绝，不能"尽力而为"地落库
        byte[] truncated = new byte[16];
        System.arraycopy(png(8, 8), 0, truncated, 0, 8);
        assertCode(() -> guard.verifyOutput("image/png", truncated), AiErrorCodeConstants.AI_MEDIA_OUTPUT_INVALID);
    }

    @Test
    void outputVerificationRejectsPixelsBeyondPlatformLimit() {
        assertCode(() -> guard.verifyOutput("image/png", png(9000, 10)), AiErrorCodeConstants.AI_MEDIA_OUTPUT_INVALID);
    }
}
