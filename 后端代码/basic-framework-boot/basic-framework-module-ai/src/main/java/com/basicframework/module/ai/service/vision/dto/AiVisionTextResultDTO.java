package com.basicframework.module.ai.service.vision.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 图片理解结果（X02）：只有受控文本 + 私有文件引用，没有上游地址与供应商字段。
 *
 * <p>空文本不属于成功：上游"成功"但没有文本时按 `AI_MEDIA_OUTPUT_EMPTY` 拒绝（X01 的媒体输出语义），
 * 不返回空结果让调用方以为识别成功。
 */
@Data
@Accessors(chain = true)
public class AiVisionTextResultDTO {

    /** 结果引用的私有图片编号（**只有标识，没有地址**） */
    private Long fileId;

    /** 模型返回的文本（非空） */
    private String text;

    /** 用量（缺失记 UNKNOWN） */
    private AiVisionUsageDTO usage;

    /** 结束原因（上游稳定映射，可为空） */
    private String finishReason;
}
