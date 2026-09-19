package com.basicframework.module.ai.service.context.dto;

import com.basicframework.module.ai.domain.runtime.AiContextSection;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 分区统计（S04）：调试与运行只展示这一层"阶段摘要与证据"，不展示分区正文。
 *
 * <p>{@code sanitized=true} 表示该分区里出现了伪造的分区标记（例如知识片段试图冒充平台政策），
 * 已被中和；这是可观测的安全证据，必须随结果返回。
 */
@Data
@Accessors(chain = true)
@ToString(exclude = {"estimatedTokens"})
public class AiContextSectionStatDTO {

    /** 分区 */
    private AiContextSection section;

    /** 实际纳入的条目数（单条分区为 1 或 0） */
    private int includedCount;

    /** 因预算或条数上限被丢弃的条目数 */
    private int droppedCount;

    /** 该分区估算占用的 token 数 */
    private int estimatedTokens;

    /** 是否发生了裁剪（丢弃条目） */
    private boolean truncated;

    /** 是否中和过伪造的分区标记 */
    private boolean sanitized;
}
