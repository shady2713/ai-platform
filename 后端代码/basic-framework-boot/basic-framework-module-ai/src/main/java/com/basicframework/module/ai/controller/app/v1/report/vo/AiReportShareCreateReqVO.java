package com.basicframework.module.ai.controller.app.v1.report.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 创建报表分享请求（应用端协议层 VO）。
 *
 * <p>请求体**不提供归属字段**：授予者来自服务端会话身份，接收者显示名由服务端按主体登记快照，
 * 客户端既不能替他人分享，也不能伪造接收范围。
 */
@Schema(description = "AI 应用端 - 报表分享创建请求")
@Data
public class AiReportShareCreateReqVO {

    @Schema(description = "报表编号（必须是当前主体的报表）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long reportId;

    @Schema(description = "版本号（空取报表当前最新版本）")
    @Positive
    private Integer versionNo;

    @Schema(description = "接收者外部用户标识（同应用内的可用 USER 主体，不能是自己）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 128)
    private String granteeExternalUserId;

    @Schema(description = "过期时间（空为长期有效；必须在未来）")
    private LocalDateTime expiresTime;
}
