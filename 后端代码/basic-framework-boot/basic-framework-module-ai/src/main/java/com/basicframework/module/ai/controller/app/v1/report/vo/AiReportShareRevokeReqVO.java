package com.basicframework.module.ai.controller.app.v1.report.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

/** 撤销报表分享请求（应用端协议层 VO）：乐观锁版本不匹配按状态冲突拒绝，重复撤销幂等成功。 */
@Schema(description = "AI 应用端 - 报表分享撤销请求")
@Data
public class AiReportShareRevokeReqVO {

    @Schema(description = "分享编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long shareId;

    @Schema(description = "分享当前乐观锁版本", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @PositiveOrZero
    private Integer version;
}
