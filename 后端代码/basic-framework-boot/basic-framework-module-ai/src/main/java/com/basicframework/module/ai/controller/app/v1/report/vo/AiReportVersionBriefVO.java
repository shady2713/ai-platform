package com.basicframework.module.ai.controller.app.v1.report.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/** 报表版本摘要（列表用）：不带规格与数据正文，避免列表接口批量搬运大字段。 */
@Schema(description = "AI 应用端 - 报表版本摘要")
@Data
@Accessors(chain = true)
public class AiReportVersionBriefVO {

    @Schema(description = "版本编号")
    private Long id;

    @Schema(description = "版本号")
    private Integer versionNo;

    @Schema(description = "模式：SNAPSHOT / REFRESHABLE")
    private String mode;

    @Schema(description = "数据完整性：COMPLETE / PARTIAL / FAILED")
    private String completeness;

    @Schema(description = "数据截至时间（仅快照模式）")
    private LocalDateTime asOf;

    @Schema(description = "来源运行标识")
    private String createdByRun;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
