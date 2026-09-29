package com.basicframework.module.ai.controller.app.v1.report.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 报表分享访问审计条目（应用端协议层 VO，授予者视角）。
 *
 * <p>不含令牌摘要、报表规格与数据正文：审计只回答"谁、何时、读到什么程度、为什么"。
 */
@Schema(description = "AI 应用端 - 报表分享访问审计条目")
@Data
@Accessors(chain = true)
public class AiReportShareAccessRespVO {

    @Schema(description = "访问记录编号")
    private Long id;

    @Schema(description = "分享编号")
    private Long shareId;

    @Schema(description = "访问者主体类型")
    private String subjectType;

    @Schema(description = "访问者外部用户标识")
    private String externalUserId;

    @Schema(description = "结论：GRANTED（读取被受理，含降级态）/ DENIED（读取被拒绝）")
    private String outcome;

    @Schema(description = "稳定原因码（subject-mismatch/revoked/expired/grantor-unavailable/scope-uncovered；完整成功为空）")
    private String reasonCode;

    @Schema(description = "本次是否真的出库了报表内容")
    private Boolean contentAuthorized;

    @Schema(description = "访问时间")
    private LocalDateTime createTime;
}
