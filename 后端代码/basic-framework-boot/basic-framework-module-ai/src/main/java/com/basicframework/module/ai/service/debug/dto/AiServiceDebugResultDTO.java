package com.basicframework.module.ai.service.debug.dto;

import com.basicframework.module.ai.service.context.dto.AiContextSectionStatDTO;
import java.util.List;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 调试运行结果（S04）：阶段摘要 + 证据，不含提示词正文与隐藏推理。
 *
 * <p>{@link #output} 是模型返回给业务的可见结果（文本或结构化 JSON），
 * 平台不额外暴露模型内部的推理过程，也不回显拼装后的提示词。
 */
@Data
@Accessors(chain = true)
@ToString(exclude = {"estimatedTokens", "output", "inputTokens", "outputTokens"})
public class AiServiceDebugResultDTO {

    /** 解析到的发布版本编号 */
    private Long releaseId;

    /** 发布版本号 */
    private Integer releaseVersion;

    /** 发布内容摘要 */
    private String contentHash;

    /** 模型端点编号 */
    private Long modelEndpointId;

    /** 端点配置版本（模型修订号） */
    private Integer modelRevision;

    /** 测试主体类型 */
    private String testSubjectType;

    /** 测试主体标识 */
    private String testSubjectId;

    /** 逐条绑定的当前授权判定结果（绑定编号 -> 是否放行） */
    private List<String> authorizedBindings;

    /** 上下文分区统计（证据层） */
    private List<AiContextSectionStatDTO> sections;

    /** 输入 token 估算值 */
    private Integer estimatedTokens;

    /** 是否发生过裁剪 */
    private Boolean truncated;

    /** 阶段摘要 */
    private List<AiDebugStageDTO> stages;

    /** 输出文本（结构化输出时为紧凑 JSON） */
    private String output;

    /** 输出是否为结构化 JSON */
    private Boolean structured;

    /** 上游用量：输入 token（缺失为 null，不伪造 0） */
    private Integer inputTokens;

    /** 上游用量：输出 token（缺失为 null） */
    private Integer outputTokens;

    /** 本次调试总耗时（毫秒） */
    private Long durationMs;
}
