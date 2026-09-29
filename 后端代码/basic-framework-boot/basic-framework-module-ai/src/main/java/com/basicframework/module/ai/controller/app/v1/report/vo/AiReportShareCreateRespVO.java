package com.basicframework.module.ai.controller.app.v1.report.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 创建报表分享响应（应用端协议层 VO）：明文令牌**只在这次响应出现**，之后平台只认其摘要，
 * 任何查询、审计与日志都不再返回明文。
 */
@Schema(description = "AI 应用端 - 报表分享创建响应")
@Data
@Accessors(chain = true)
public class AiReportShareCreateRespVO {

    @Schema(description = "分享编号（授予者撤销与查审计用）")
    private Long shareId;

    @Schema(description = "明文分享令牌（一次性返回；接收者用它调用 /ai/report/share/read）", requiredMode = Schema.RequiredMode.REQUIRED)
    @ToString.Exclude
    private String token;

    @Schema(description = "分享时固定的版本号")
    private Integer versionNo;

    @Schema(description = "接收者外部用户标识")
    private String granteeExternalUserId;

    @Schema(description = "接收者显示名（创建时快照）")
    private String granteeDisplayName;

    @Schema(description = "过期时间（空为长期有效）")
    private LocalDateTime expiresTime;
}
