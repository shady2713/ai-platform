package com.basicframework.module.ai.service.vision;

/**
 * 图片文件头解析（X02，包内实现细节）：只读文件头得到**真实像素尺寸**，不整图解码。
 *
 * <p>为什么不解码像素：解码一张"看起来很小"的图片可能展开成几十亿像素（解压炸弹），
 * 把一次识别变成一次内存耗尽。这里只在有界范围内扫描文件头（PNG 的 IHDR、JPEG 的 SOFn、
 * WebP 的 VP8/VP8L/VP8X 块），拿到宽高即停止；任何结构性异常都按"尺寸不可信"返回失败，
 * 由调用方按 {@code AI_MEDIA_REQUEST_INVALID} 拒绝。
 *
 * <p>扫描范围与迭代次数都有上限：损坏文件不能让解析过程变成无限循环。
 */
final class AiVisionImageHeader {

    /** 头部扫描的最大扫描字节数（超过即视为结构不可信）。 */
    private static final int MAX_SCAN_BYTES = 64 * 1024;

    /** JPEG 段扫描的最大段数。 */
    private static final int MAX_JPEG_SEGMENTS = 512;

    /** WebP 块扫描的最大块数。 */
    private static final int MAX_WEBP_CHUNKS = 64;

    private AiVisionImageHeader() {}

    /** 真实像素尺寸（像素）。 */
    record Size(int width, int height) {

        boolean positive() {
            return width > 0 && height > 0;
        }
    }

    /**
     * 读取文件头尺寸；结构不可解析时抛出 {@link IllegalArgumentException}（不含文件内容）。
     */
    static Size read(byte[] content, AiVisionImageFormat format) {
        if (content == null || content.length < 12) {
            throw new IllegalArgumentException("图片内容过短，无法解析文件头");
        }
        // 扫描范围有界：损坏文件不能让头部解析退化成全文扫描
        int limit = Math.min(content.length, MAX_SCAN_BYTES);
        Size size =
                switch (format) {
                    case PNG -> pngSize(content, limit);
                    case JPEG -> jpegSize(content, limit);
                    case WEBP -> webpSize(content, limit);
                };
        if (size == null || !size.positive()) {
            throw new IllegalArgumentException("图片文件头未给出有效尺寸");
        }
        return size;
    }

    /** PNG：签名之后的第一个块必须是 IHDR，宽高是其中的两个大端 32 位整数。 */
    private static Size pngSize(byte[] content, int limit) {
        if (limit < 24 || !matchesAscii(content, 12, "IHDR")) {
            return null;
        }
        return new Size(readInt32BigEndian(content, 16), readInt32BigEndian(content, 20));
    }

    /** JPEG：扫描段直到 SOFn（0xC0-0xCF，排除 DHT/JPG/DAC），取其中的高/宽。 */
    private static Size jpegSize(byte[] content, int limit) {
        int index = 2;
        for (int segment = 0; segment < MAX_JPEG_SEGMENTS && index + 3 < limit; segment++) {
            if ((content[index] & 0xFF) != 0xFF) {
                return null;
            }
            int marker = content[index + 1] & 0xFF;
            if (marker == 0xFF) {
                // 填充字节：继续扫描下一个
                index++;
                continue;
            }
            if (isStartOfFrame(marker)) {
                if (index + 9 >= limit) {
                    return null;
                }
                int height = readInt16BigEndian(content, index + 5);
                int width = readInt16BigEndian(content, index + 7);
                return new Size(width, height);
            }
            if (marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD9) || marker == 0x01) {
                // 无长度字段的标记
                index += 2;
                continue;
            }
            int segmentLength = readInt16BigEndian(content, index + 2);
            if (segmentLength < 2) {
                return null;
            }
            index += 2 + segmentLength;
        }
        return null;
    }

    /** WebP：RIFF 容器内按块扫描，VP8X/VP8L/VP8 三种块各有一套尺寸编码。 */
    private static Size webpSize(byte[] content, int limit) {
        int index = 12;
        for (int chunk = 0; chunk < MAX_WEBP_CHUNKS && index + 8 <= limit; chunk++) {
            String fourCc = asciiAt(content, index, 4);
            int payloadSize = readInt32LittleEndian(content, index + 4);
            int payloadStart = index + 8;
            if (payloadSize < 0 || payloadStart + payloadSize > content.length) {
                return null;
            }
            Size size =
                    switch (fourCc) {
                        case "VP8X" -> vp8xSize(content, payloadStart, payloadSize);
                        case "VP8L" -> vp8lSize(content, payloadStart, payloadSize);
                        case "VP8 " -> vp8Size(content, payloadStart, payloadSize);
                        default -> null;
                    };
            if (size != null) {
                return size;
            }
            // 块长度为奇数时补 1 字节对齐
            index = payloadStart + payloadSize + (payloadSize % 2);
        }
        return null;
    }

    /** VP8X（扩展格式）：24 位小端的"画布宽-1/高-1"。 */
    private static Size vp8xSize(byte[] content, int start, int payloadSize) {
        if (payloadSize < 10) {
            return null;
        }
        int width = 1 + readInt24LittleEndian(content, start + 4);
        int height = 1 + readInt24LittleEndian(content, start + 7);
        return new Size(width, height);
    }

    /** VP8L（无损）：14 位宽-1 + 14 位高-1，紧跟在 0x2F 签名之后。 */
    private static Size vp8lSize(byte[] content, int start, int payloadSize) {
        if (payloadSize < 5 || (content[start] & 0xFF) != 0x2F) {
            return null;
        }
        long bits = (readInt32LittleEndian(content, start + 1) & 0xFFFFFFFFL) >>> 0;
        int width = 1 + (int) (bits & 0x3FFF);
        int height = 1 + (int) ((bits >> 14) & 0x3FFF);
        return new Size(width, height);
    }

    /** VP8（有损）：3 字节帧标签 + 起始码 0x9D 0x01 0x2A，其后是 14 位宽/高。 */
    private static Size vp8Size(byte[] content, int start, int payloadSize) {
        if (payloadSize < 10
                || (content[start + 3] & 0xFF) != 0x9D
                || (content[start + 4] & 0xFF) != 0x01
                || (content[start + 5] & 0xFF) != 0x2A) {
            return null;
        }
        int width = readInt16LittleEndian(content, start + 6) & 0x3FFF;
        int height = readInt16LittleEndian(content, start + 8) & 0x3FFF;
        return new Size(width, height);
    }

    /** JPEG SOFn 标记（不含 DHT/JPG/DAC 这三个非 SOF 标记）。 */
    private static boolean isStartOfFrame(int marker) {
        return marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
    }

    private static int readInt32BigEndian(byte[] content, int offset) {
        return ((content[offset] & 0xFF) << 24)
                | ((content[offset + 1] & 0xFF) << 16)
                | ((content[offset + 2] & 0xFF) << 8)
                | (content[offset + 3] & 0xFF);
    }

    private static int readInt32LittleEndian(byte[] content, int offset) {
        return ((content[offset + 3] & 0xFF) << 24)
                | ((content[offset + 2] & 0xFF) << 16)
                | ((content[offset + 1] & 0xFF) << 8)
                | (content[offset] & 0xFF);
    }

    private static int readInt24LittleEndian(byte[] content, int offset) {
        return ((content[offset + 2] & 0xFF) << 16) | ((content[offset + 1] & 0xFF) << 8) | (content[offset] & 0xFF);
    }

    private static int readInt16BigEndian(byte[] content, int offset) {
        return ((content[offset] & 0xFF) << 8) | (content[offset + 1] & 0xFF);
    }

    private static int readInt16LittleEndian(byte[] content, int offset) {
        return ((content[offset + 1] & 0xFF) << 8) | (content[offset] & 0xFF);
    }

    private static boolean matchesAscii(byte[] content, int offset, String expected) {
        return expected.equals(asciiAt(content, offset, expected.length()));
    }

    private static String asciiAt(byte[] content, int offset, int length) {
        if (content.length < offset + length) {
            return "";
        }
        StringBuilder builder = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            builder.append((char) (content[offset + index] & 0xFF));
        }
        return builder.toString();
    }
}
