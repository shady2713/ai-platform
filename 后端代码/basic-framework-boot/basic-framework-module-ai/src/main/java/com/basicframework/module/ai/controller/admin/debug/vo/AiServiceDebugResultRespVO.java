package com.basicframework.module.ai.controller.admin.debug.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 调试运行结果（协议层 VO）：阶段摘要、分区统计、用量与可见输出。
 *
 * <p>不回显拼装后的提示词，也不返回模型隐藏推理：调试展示的是"做了什么、花了多少、输出了什么"。
 */
@Schema(description = "管理后台 - AI 服务调试运行结果")
@Data
@Accessors(chain = true)
@ToString(exclude = {"estimatedTokens", "output", "inputTokens", "outputTokens"})
public class AiServiceDebugResultRespVO {

    @Schema(description = "解析到的发布版本编号")
    private Long releaseId;

    @Schema(description = "发布版本号")
    private Integer releaseVersion;

    @Schema(description = "发布内容摘要")
    private String contentHash;

    @Schema(description = "模型端点编号")
    private Long modelEndpointId;

    @Schema(description = "端点配置版本（模型修订号）")
    private Integer modelRevision;

    @Schema(description = "测试主体类型")
    private String testSubjectType;

    @Schema(description = "测试主体标识")
    private String testSubjectId;

    @Schema(description = "逐条绑定的当前授权判定结果（类型:标识）")
    private List<String> authorizedBindings;

    @Schema(description = "上下文分区统计")
    private List<AiContextSectionStatRespVO> sections;

    @Schema(description = "输入 token 估算值")
    private Integer estimatedTokens;

    @Schema(description = "是否发生过裁剪")
    private Boolean truncated;

    @Schema(description = "阶段摘要")
    private List<AiDebugStageRespVO> stages;

    @Schema(description = "输出文本（结构化输出时为紧凑 JSON）")
    private String output;

    @Schema(description = "输出是否为结构化 JSON")
    private Boolean structured;

    @Schema(description = "上游用量：输入 token（缺失为 null，不伪造 0）")
    private Integer inputTokens;

    @Schema(description = "上游用量：输出 token（缺失为 null）")
    private Integer outputTokens;

    @Schema(description = "本次调试总耗时（毫秒）")
    private Long durationMs;
}
