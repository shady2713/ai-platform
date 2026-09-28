package com.basicframework.module.ai.service.image.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 图片生成请求（服务层 DTO，X03）：受控参数 + 幂等键。 */
@Data
@Accessors(chain = true)
public class AiImageGenerateDTO {

    /** 幂等键（调用方提供；重复提交返回既有任务，不重复生成） */
    private String requestKey;

    /** 模型端点编号（能力必须已声明且探测确认） */
    private Long endpointId;

    /** 生成提示词 */
    private String prompt;

    /** 目标尺寸（宽x高；为空表示由端点默认值决定） */
    private String size;

    /** 生成张数（为空按 1 张） */
    private Integer count;

    /** 输出格式（为空按 png） */
    private String outputFormat;
}
