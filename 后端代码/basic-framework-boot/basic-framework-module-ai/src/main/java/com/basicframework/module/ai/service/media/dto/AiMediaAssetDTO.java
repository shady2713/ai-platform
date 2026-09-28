package com.basicframework.module.ai.service.media.dto;

import com.basicframework.module.ai.dal.dataobject.media.AiMediaAssetDO;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 媒体产物视图（服务层 DTO，X03）：平台私有文件编号 + 服务端核验过的事实。
 *
 * <p>没有地址字段：读取产物一律走私有文件端点（业务 ACL 判定），不使用任何上游临时 URL。
 */
@Data
@Accessors(chain = true)
public class AiMediaAssetDTO {

    /** 平台私有文件编号 */
    private Long fileId;

    /** 产物序号（同一任务内从 1 开始） */
    private Integer ordinal;

    /** 产物 MIME（白名单内的取值） */
    private String mimeType;

    /** 产物字节数 */
    private Long sizeBytes;

    /** 产物内容摘要（服务端计算） */
    private String sha256;

    /** 图片宽度（非图片或未知为空） */
    private Integer width;

    /** 图片高度（非图片或未知为空） */
    private Integer height;

    /** 音频时长（毫秒；未知为空） */
    private Long durationMillis;

    /** 由产物行构造视图。 */
    public static AiMediaAssetDTO from(AiMediaAssetDO asset) {
        return new AiMediaAssetDTO()
                .setFileId(asset.getFileId())
                .setOrdinal(asset.getOrdinal())
                .setMimeType(asset.getMimeType())
                .setSizeBytes(asset.getSizeBytes())
                .setSha256(asset.getSha256())
                .setWidth(asset.getWidth())
                .setHeight(asset.getHeight())
                .setDurationMillis(asset.getDurationMillis());
    }
}
