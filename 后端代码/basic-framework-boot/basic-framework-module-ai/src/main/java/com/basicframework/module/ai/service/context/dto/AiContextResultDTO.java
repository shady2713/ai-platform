package com.basicframework.module.ai.service.context.dto;

import java.util.List;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 上下文构建结果（S04）。
 *
 * <p>{@link #prompt} 是交给模型调用的输入正文：它只在服务层传递，
 * **不进日志、不进响应、不进 {@code toString()}**（数据分级规则要求 L3/L4 正文禁入日志）。
 * 对外的可观测信息是 {@link #sections} 里的分区统计与预算数字。
 */
@Data
@Accessors(chain = true)
@ToString(exclude = {"prompt", "estimatedTokens", "maxTokens"})
public class AiContextResultDTO {

    /** 拼装后的提示词正文（服务层内部使用） */
    private String prompt;

    /** 分区统计（顺序与拼装顺序一致） */
    private List<AiContextSectionStatDTO> sections;

    /** 实际占用的 token 估算值 */
    private int estimatedTokens;

    /** 本次使用的输入 token 预算 */
    private int maxTokens;

    /** 是否发生过裁剪 */
    private boolean truncated;
}
