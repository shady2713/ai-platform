package com.basicframework.module.ai.service.vision.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 图片理解请求（X02）：显式端点 + 受控图片引用 + 理解指令。 */
@Data
@Accessors(chain = true)
public class AiVisionUnderstandRequestDTO {

    /** 模型端点编号（调用方显式指定，不做候选遍历） */
    private Long endpointId;

    /** 受控图片引用 */
    private AiVisionImageRefDTO image;

    /** 理解指令（非空，最长 4000 字符） */
    private String instruction;

    /** 单次调用超时（毫秒）；为空表示使用端点默认值 */
    private Long timeoutMillis;
}
