package com.basicframework.framework.ai.core.model.media;

import com.basicframework.framework.ai.core.model.ModelUsage;
import java.util.List;

/**
 * 图片产物响应（X01 冻结）：图片生成与图片编辑的共用输出。
 *
 * <p>产物必须非空：上游"成功"但没有图片属于协议异常，适配器应抛
 * {@code MEDIA_OUTPUT_EMPTY}，不得返回空列表让调用方以为成功。
 *
 * @param images       图片产物（至少 1 个，按上游返回顺序）
 * @param usage        用量；缺失时为 {@link ModelUsage#UNKNOWN}（不伪造 0）
 * @param modelId      实际使用的模型标识
 * @param finishReason 结束原因（厂商原始值的稳定映射，可为空）
 */
public record ImageResult(List<MediaArtifact> images, ModelUsage usage, String modelId, String finishReason) {

    public ImageResult {
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("图片产物不能为空");
        }
        images = List.copyOf(images);
        usage = usage == null ? ModelUsage.UNKNOWN : usage;
    }

    /** 单张产物结果的常用构造。 */
    public static ImageResult of(MediaArtifact image, ModelUsage usage, String modelId) {
        return new ImageResult(List.of(image), usage, modelId, null);
    }
}
