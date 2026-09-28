package com.basicframework.module.ai.service.vision;

import java.util.Locale;
import java.util.Optional;

/**
 * 图片输入格式白名单（X02）：PNG / JPEG / WebP。
 *
 * <p>与前端契约 `IMAGE_MIME_TYPES` 逐项一致。**刻意不含 SVG**（可执行/可外链，属于文档而非位图）
 * 与动图；白名单之外的声明一律拒绝，不做"尽力而为"的转码。
 *
 * <p>{@link #matchesMagic(byte[])} 是"声明与内容是否一致"的唯一判据：扩展名与请求里的 MIME 都可以伪造，
 * 只有文件头不可伪造。魔数不符时按 {@code AI_MEDIA_INPUT_TYPE_UNSUPPORTED} 拒绝。
 */
public enum AiVisionImageFormat {

    /** PNG（8 字节签名）。 */
    PNG("image/png", "png"),

    /** JPEG（SOI + 起始标识）。 */
    JPEG("image/jpeg", "jpeg"),

    /** WebP（RIFF 容器 + WEBP 标识）。 */
    WEBP("image/webp", "webp");

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private final String mimeType;

    private final String formatName;

    AiVisionImageFormat(String mimeType, String formatName) {
        this.mimeType = mimeType;
        this.formatName = formatName;
    }

    /** 平台 MIME 类型（小写，与前端契约一致）。 */
    public String mimeType() {
        return mimeType;
    }

    /** 格式短名（用于图片头解析与错误归因，不带点）。 */
    public String formatName() {
        return formatName;
    }

    /** 按 MIME 解析格式；空白与大小写差异归一化，未知类型返回空。 */
    public static Optional<AiVisionImageFormat> fromMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return Optional.empty();
        }
        String normalized = mimeType.trim().toLowerCase(Locale.ROOT);
        for (AiVisionImageFormat format : values()) {
            if (format.mimeType.equals(normalized)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }

    /** 文件头是否与该格式一致（空内容返回 false）。 */
    public boolean matchesMagic(byte[] content) {
        if (content == null || content.length < 12) {
            return false;
        }
        return switch (this) {
            case PNG -> matches(content, PNG_MAGIC, 0);
            case JPEG -> (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xD8 && (content[2] & 0xFF) == 0xFF;
            case WEBP -> matchesAscii(content, 0, "RIFF") && matchesAscii(content, 8, "WEBP");
        };
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
