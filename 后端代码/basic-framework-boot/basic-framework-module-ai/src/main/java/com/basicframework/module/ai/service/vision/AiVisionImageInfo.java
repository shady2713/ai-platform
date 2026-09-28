package com.basicframework.module.ai.service.vision;

/**
 * 已核验的图片输入（X02）：全部字段都由服务端从**真实内容**得出，不采信调用方声明。
 *
 * <p>它同时是外发给模型端口的引用构造依据（{@code MediaFileRef} 的 fileId/mime/size/sha256），
 * 因此"声明与内容不一致"必须在构造本对象之前就被拒绝。
 *
 * @param fileId    平台私有文件编号（正数）
 * @param format    核验后的图片格式
 * @param sizeBytes 真实字节数
 * @param width     真实宽度（像素，含文件头解析结果）
 * @param height    真实高度（像素）
 * @param sha256    内容摘要（小写 64 位十六进制，外发前完整性核对用）
 */
record AiVisionImageInfo(
        Long fileId, AiVisionImageFormat format, long sizeBytes, int width, int height, String sha256) {

    AiVisionImageInfo {
        if (fileId == null || fileId <= 0) {
            throw new IllegalArgumentException("媒体文件编号必须为正数");
        }
    }

    /** 该图片是否都由平台硬上限覆盖（长边与总像素都在允许范围内）。 */
    boolean withinDimensionLimits() {
        return width >= 1
                && height >= 1
                && width <= AiVisionLimits.MAX_IMAGE_DIMENSION
                && height <= AiVisionLimits.MAX_IMAGE_DIMENSION;
    }
}
