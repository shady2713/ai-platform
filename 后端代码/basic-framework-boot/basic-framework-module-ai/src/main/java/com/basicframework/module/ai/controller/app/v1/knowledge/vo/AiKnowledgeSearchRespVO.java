package com.basicframework.module.ai.controller.app.v1.knowledge.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/** 知识检索结论（协议层 VO）：命中引用 + 过滤观测 + 是否无证据。 */
@Schema(description = "AI 应用端 - 知识检索结论")
@Data
@Accessors(chain = true)
public class AiKnowledgeSearchRespVO {

    @Schema(description = "命中引用（按相关度）")
    private List<Citation> citations = List.of();

    @Schema(description = "向量服务返回的候选数")
    private Integer candidateCount;

    @Schema(description = "复核后被丢弃的候选数（状态/权限/版本不再有效）")
    private Integer filteredOutCount;

    @Schema(description = "参与检索的知识库数")
    private Integer searchedKnowledgeBaseCount;

    @Schema(description = "是否没有可用证据（据此明确说明资料不足，不编造）")
    private Boolean noEvidence;

    /** 引用（只来自本次检索候选）。 */
    @Schema(description = "AI 应用端 - 知识引用")
    @Data
    @Accessors(chain = true)
    public static class Citation {

        @Schema(description = "引用标识（读取片段时回传）")
        private String citationId;

        @Schema(description = "文档标题")
        private String title;

        @Schema(description = "文档版本号")
        private Integer versionNo;

        @Schema(description = "切片序号")
        private Integer chunkIndex;

        @Schema(description = "来源位置（第 N 页 / 段落 N）")
        private String locationRef;

        @Schema(description = "片段正文")
        private String snippet;
    }
}
