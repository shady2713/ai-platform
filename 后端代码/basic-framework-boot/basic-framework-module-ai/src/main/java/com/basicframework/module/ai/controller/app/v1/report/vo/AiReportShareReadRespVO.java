package com.basicframework.module.ai.controller.app.v1.report.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 按令牌读取分享响应（应用端协议层 VO）。
 *
 * <p>{@code contentAuthorized=false} 是**降级态**（HTTP 200，不抛错）：分享凭据有效，但接收者
 * 当前源权限覆盖不了分享时的范围——{@code specJson}/{@code dataJson}/{@code asOf}/
 * {@code completeness} 全部为空（隐藏统计与快照不出库），{@code reasonCode} 说明原因。
 * 前置失败（非接收者/已撤销/已过期/授予者停用/凭据未知）一律 404，与"分享不存在"同语义。
 */
@Schema(description = "AI 应用端 - 报表分享读取响应")
@Data
@Accessors(chain = true)
public class AiReportShareReadRespVO {

    @Schema(description = "分享编号")
    private Long shareId;

    @Schema(description = "报表编号")
    private Long reportId;

    @Schema(description = "报表名称")
    private String reportName;

    @Schema(description = "分享时固定的版本号")
    private Integer versionNo;

    @Schema(description = "模式：SNAPSHOT / REFRESHABLE")
    private String mode;

    @Schema(description = "内容是否出库（false = 降级态：内容字段全部为空）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean contentAuthorized;

    @Schema(description = "稳定原因码（完整成功为空；降级态为 scope-uncovered）")
    private String reasonCode;

    @Schema(description = "ReportSpec（JSON；降级态为空）")
    private String specJson;

    @Schema(description = "快照数据（JSON；降级态为空）")
    private String dataJson;

    @Schema(description = "数据截至时间（降级态为空）")
    private LocalDateTime asOf;

    @Schema(description = "数据完整性：COMPLETE / PARTIAL / FAILED（降级态为空）")
    private String completeness;

    @Schema(description = "过期时间（空为长期有效）")
    private LocalDateTime expiresTime;
}
