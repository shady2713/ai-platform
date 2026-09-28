package com.basicframework.module.ai.service.vision;

/**
 * 已核验的图片产物（X03）：上游返回的字节经服务端判定为白名单格式的真实位图。
 *
 * <p>与输入的 {@link AiVisionImageInfo} 分开：产物没有"调用方声明"要核对，也没有平台文件编号，
 * 只有服务端从内容解析出的事实（格式、字节数、宽高、摘要），用于落库与展示。
 *
 * @param format    核验后的图片格式
 * @param sizeBytes 真实字节数
 * @param width     真实宽度（像素）
 * @param height    真实高度（像素）
 * @param sha256    内容摘要（小写 64 位十六进制）
 */
public record AiVisionImageOutput(AiVisionImageFormat format, long sizeBytes, int width, int height, String sha256) {

    /** 是否在平台像素上限内（长边不超过上限）。 */
    public boolean withinDimensionLimits() {
        return width >= 1
                && height >= 1
                && width <= AiVisionLimits.MAX_IMAGE_DIMENSION
                && height <= AiVisionLimits.MAX_IMAGE_DIMENSION;
    }
}
