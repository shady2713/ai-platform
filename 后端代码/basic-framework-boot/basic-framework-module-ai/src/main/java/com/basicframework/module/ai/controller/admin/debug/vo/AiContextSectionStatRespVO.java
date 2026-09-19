package com.basicframework.module.ai.controller.admin.debug.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/** 上下文分区统计（协议层 VO）：只暴露分区名与计数，不暴露分区正文。 */
@Schema(description = "管理后台 - AI 上下文分区统计")
@Data
@Accessors(chain = true)
@ToString(exclude = {"estimatedTokens"})
public class AiContextSectionStatRespVO {

    @Schema(description = "分区（POLICY/SYSTEM/CONTEXT/KNOWLEDGE/HISTORY/MESSAGE）")
    private String section;

    @Schema(description = "实际纳入的条目数")
    private Integer includedCount;

    @Schema(description = "被丢弃的条目数")
    private Integer droppedCount;

    @Schema(description = "该分区估算占用的 token 数")
    private Integer estimatedTokens;

    @Schema(description = "是否发生裁剪")
    private Boolean truncated;

    @Schema(description = "是否中和过伪造的分区标记")
    private Boolean sanitized;
}
