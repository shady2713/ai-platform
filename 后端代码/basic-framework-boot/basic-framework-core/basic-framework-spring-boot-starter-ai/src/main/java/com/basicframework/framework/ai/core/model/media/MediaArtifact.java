package com.basicframework.framework.ai.core.model.media;

/**
 * 媒体输出产物（X01 冻结）：平台自有的字节载体，不暴露厂商类型与临时下载地址。
 *
 * <p>产物由端口实现返回后，调用方必须**先落平台私有文件**再交付或引用（FR-35：生成结果仍是私有文件）；
 * 本类型不持久化、不携带公网 URL，也不代表已通过业务权限。
 *
 * @param mimeType       产物 MIME 类型（去空白并小写归一化）
 * @param content        产物字节（构造与读取都做防御性复制）
 * @param sha256         产物摘要（小写 64 位十六进制）；构造时未给出则按内容计算，给出则必须与内容一致
 * @param width          图片宽度（像素）；非图片或上游未提供时为空
 * @param height         图片高度（像素）；非图片或上游未提供时为空
 * @param durationMillis 音频时长（毫秒）；非音频或上游未提供时为空
 */
public record MediaArtifact(
        String mimeType, byte[] content, String sha256, Integer width, Integer height, Long durationMillis) {

    public MediaArtifact {
        mimeType = MediaValues.requireMimeType(mimeType, "产物 MIME 类型");
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("媒体产物内容不能为空");
        }
        content = content.clone();
        String declared = MediaValues.requireSha256(sha256, "产物摘要");
        String computed = MediaValues.sha256Hex(content);
        if (declared != null && !declared.equals(computed)) {
            throw new IllegalArgumentException("媒体产物摘要与内容不一致");
        }
        sha256 = computed;
        requirePositive(width, "图片宽度");
        requirePositive(height, "图片高度");
        if (durationMillis != null && durationMillis <= 0) {
            throw new IllegalArgumentException("音频时长必须为正数");
        }
    }

    /** 读取产物字节（防御性复制：调用方修改返回值不影响本对象）。 */
    @Override
    public byte[] content() {
        return content.clone();
    }

    /** 产物字节数。 */
    public int sizeBytes() {
        return content.length;
    }

    private static void requirePositive(Integer value, String field) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(field + "必须为正数");
        }
    }
}
