package com.basicframework.module.ai.controller.admin.tool.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/** 工具版本（协议层 VO）：政策与 schema 快照。 */
@Schema(description = "管理后台 - AI 工具版本")
@Data
@Accessors(chain = true)
public class AiToolVersionRespVO {

    @Schema(description = "版本编号")
    private Long id;

    @Schema(description = "工具编号")
    private Long toolId;

    @Schema(description = "版本号")
    private Integer versionNo;

    @Schema(description = "状态（DRAFT/PUBLISHED）")
    private String status;

    @Schema(description = "类型（READ/WRITE）")
    private String toolType;

    @Schema(description = "执行政策（AUTO/CONFIRM/DENY）")
    private String policy;

    @Schema(description = "来源类型")
    private String sourceKind;

    @Schema(description = "来源标识")
    private String sourceRef;

    @Schema(description = "输入 schema")
    private String inputSchemaJson;

    @Schema(description = "输出 schema")
    private String outputSchemaJson;

    @Schema(description = "版本内容哈希")
    private String schemaHash;

    @Schema(description = "发布时间")
    private LocalDateTime publishedAt;

    @Schema(description = "乐观锁版本")
    private Integer version;
}
