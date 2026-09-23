package com.basicframework.module.ai.controller.app.v1.report.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 报表版本响应（应用端协议层 VO）。
 *
 * <p>{@code scopeFingerprint} **不对外回显**：它是授权复核用的内部凭据，回显会让客户端误以为
 * 可以自行比对或提交指纹。读取是否放行由服务端每次重新判定。
 */
@Schema(description = "AI 应用端 - 报表版本")
@Data
@Accessors(chain = true)
public class AiReportVersionRespVO {

    @Schema(description = "版本编号")
    private Long id;

    @Schema(description = "报表编号")
    private Long reportId;

    @Schema(description = "版本号")
    private Integer versionNo;

    @Schema(description = "模式：SNAPSHOT / REFRESHABLE")
    private String mode;

    @Schema(description = "ReportSpec（JSON）")
    private String specJson;

    @Schema(description = "快照数据（JSON，仅快照模式）")
    private String dataJson;

    @Schema(description = "来源与资源依赖（JSON）")
    private String sourcesJson;

    @Schema(description = "数据截至时间（仅快照模式）")
    private LocalDateTime asOf;

    @Schema(description = "数据完整性：COMPLETE / PARTIAL / FAILED")
    private String completeness;

    @Schema(description = "来源运行标识")
    private String createdByRun;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
