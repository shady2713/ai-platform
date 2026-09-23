package com.basicframework.module.ai.controller.app.v1.report.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 报表刷新请求（应用端协议层 VO）。
 *
 * <p>请求体不提供归属与行范围：归属由服务端会话决定；行范围来自授权层——
 * 缺少行范围上下文时刷新按稳定原因失败并留痕，而不是查全库。
 */
@Schema(description = "AI 应用端 - 报表刷新请求")
@Data
public class AiReportRefreshReqVO {

    @Schema(description = "报表编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long id;

    @Schema(description = "来源运行标识（可选；跨主体引用会被拒绝）")
    @Size(max = 64)
    private String createdByRun;
}
