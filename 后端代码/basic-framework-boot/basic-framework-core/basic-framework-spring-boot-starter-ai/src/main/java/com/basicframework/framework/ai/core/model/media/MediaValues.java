package com.basicframework.framework.ai.core.model.media;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 媒体值对象的共享校验（X01，包内实现细节，不属于公开契约）。
 *
 * <p>只做平台层面的语法与形状校验：MIME 形状、摘要格式、尺寸语法、格式白名单。
 * "该端点是否接受某格式/上限多少" 由端点声明与准入矩阵判定，不在这里硬编码。
 */
final class MediaValues {

    private static final Pattern MIME_TYPE = Pattern.compile("^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+$");

    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    /** 平台硬上限：图片尺寸按 {@code 宽x高} 语法给出，两个方向都不能超过该值（端点可声明更窄范围）。 */
    static final int MAX_IMAGE_DIMENSION = 8192;

    static final String IMAGE_SIZE_PATTERN_MESSAGE = "图片尺寸必须形如 1024x1024 且每个方向在 1-8192 之间";

    private static final Pattern IMAGE_SIZE = Pattern.compile("^(\\d{1,5})x(\\d{1,5})$");

    private MediaValues() {}

    /** 校验并归一化 MIME 类型（去空白 + 小写）。 */
    static String requireMimeType(String mimeType, String field) {
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        String normalized = mimeType.trim().toLowerCase(Locale.ROOT);
        if (!MIME_TYPE.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + "不是合法的 MIME 类型：" + normalized);
        }
        return normalized;
    }

    /** 校验可选的 SHA-256 摘要：空值返回 null，非空必须是 64 位十六进制（归一化为小写）。 */
    static String requireSha256(String sha256, String field) {
        if (sha256 == null || sha256.isBlank()) {
            return null;
        }
        String normalized = sha256.trim().toLowerCase(Locale.ROOT);
        if (!SHA256_HEX.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + "必须是 64 位十六进制 SHA-256");
        }
        return normalized;
    }

    /** 计算内容摘要（小写 64 位十六进制）。 */
    static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException missingAlgorithm) {
            // JDK 必然提供 SHA-256：缺失属于环境损坏，直接失败而不是降级为无摘要
            throw new IllegalStateException("SHA-256 不可用", missingAlgorithm);
        }
    }

    /** 必填文本（不裁剪内容，只拒绝空白）。 */
    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value;
    }

    /** 可选文本：空白归一化为 null。 */
    static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 归一化可选的图片尺寸（语法与平台硬上限），非法直接拒绝。 */
    static String normalizeImageSize(String size) {
        if (size == null || size.isBlank()) {
            return null;
        }
        String normalized = size.trim().toLowerCase(Locale.ROOT);
        Matcher matcher = IMAGE_SIZE.matcher(normalized);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(IMAGE_SIZE_PATTERN_MESSAGE);
        }
        long width = Long.parseLong(matcher.group(1));
        long height = Long.parseLong(matcher.group(2));
        if (width < 1 || width > MAX_IMAGE_DIMENSION || height < 1 || height > MAX_IMAGE_DIMENSION) {
            throw new IllegalArgumentException(IMAGE_SIZE_PATTERN_MESSAGE);
        }
        return normalized;
    }

    /** 归一化输出格式：空值取平台默认值，取值必须在白名单内。 */
    static String normalizeOutputFormat(String format, String defaultFormat, Set<String> allowed, String field) {
        String normalized = format == null || format.isBlank()
                ? defaultFormat
                : format.trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException(field + "只支持 " + String.join("/", allowed) + "，实际：" + normalized);
        }
        return normalized;
    }

    /** 内容长度上限校验（按 UTF-16 码元），超限拒绝而不是截断。 */
    static String requireMaxLength(String value, int maxLength, String field) {
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + "长度不能超过 " + maxLength);
        }
        return value;
    }
}
