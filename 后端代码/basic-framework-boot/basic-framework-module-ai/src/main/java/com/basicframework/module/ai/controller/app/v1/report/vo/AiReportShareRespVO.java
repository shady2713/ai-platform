package com.basicframework.module.ai.controller.app.v1.report.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/** 报表分享条目（应用端协议层 VO，授予者视角）：接收范围 = 接收者标识 + 创建时快照的显示名。 */
@Schema(description = "AI 应用端 - 报表分享条目")
@Data
@Accessors(chain = true)
public class AiReportShareRespVO {

    @Schema(description = "分享编号")
    private Long id;

    @Schema(description = "报表编号")
    private Long reportId;

    @Schema(description = "分享时固定的版本号")
    private Integer versionNo;

    @Schema(description = "状态：ACTIVE / REVOKED / EXPIRED")
    private String status;

    @Schema(description = "接收者外部用户标识")
    private String granteeExternalUserId;

    @Schema(description = "接收者显示名（创建时快照）")
    private String granteeDisplayName;

    @Schema(description = "过期时间（空为长期有效）")
    private LocalDateTime expiresTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
