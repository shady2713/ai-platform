package com.basicframework.module.ai.controller.app.v1.report.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/** 报表元信息响应（应用端协议层 VO）：归属列不回显，客户端用不到也无法据此枚举他人报表。 */
@Schema(description = "AI 应用端 - 报表元信息")
@Data
@Accessors(chain = true)
public class AiReportRespVO {

    @Schema(description = "报表编号")
    private Long id;

    @Schema(description = "报表标识")
    private String code;

    @Schema(description = "报表名称")
    private String name;

    @Schema(description = "说明")
    private String description;

    @Schema(description = "模式：SNAPSHOT / REFRESHABLE")
    private String mode;

    @Schema(description = "来源服务编号")
    private Long serviceId;

    @Schema(description = "来源服务发布版本编号")
    private Long releaseId;

    @Schema(description = "主题标识")
    private String themeId;

    @Schema(description = "主题修订号")
    private Integer themeRevision;

    @Schema(description = "ReportSpec 契约版本")
    private String schemaVersion;

    @Schema(description = "最新版本号")
    private Integer latestVersionNo;

    @Schema(description = "当前生效版本号")
    private Integer publishedVersionNo;

    @Schema(description = "乐观锁版本（保存新版本时回传）")
    private Integer version;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
