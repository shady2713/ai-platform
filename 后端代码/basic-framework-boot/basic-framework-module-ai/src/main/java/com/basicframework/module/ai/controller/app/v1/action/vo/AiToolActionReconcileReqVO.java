package com.basicframework.module.ai.controller.app.v1.action.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 工具动作核对请求（协议层 VO）：显式核对"结果未定"的写动作。
 *
 * <p>{@code mode=PROGRAM} 调用动作登记的核对查询（只按业务幂等键查），{@code outcome} 可空；
 * {@code mode=MANUAL} 由操作员给出结论，{@code outcome} 必填，{@code note} 是可选的补偿/核对说明
 * （≤200 字符，不得写入凭据与上游正文）。
 */
@Schema(description = "AI 应用端 - 工具动作核对请求")
@Data
public class AiToolActionReconcileReqVO {

    @Schema(description = "动作编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long actionId;

    @Schema(
            description = "核对方式（PROGRAM 程序核对：调用登记的核对查询；MANUAL 人工核对：给定结论）",
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"PROGRAM", "MANUAL"})
    @NotEmpty
    @Size(max = 16)
    private String mode;

    @Schema(description = "人工核对结论（MANUAL 必填：APPLIED 已生效 / NOT_APPLIED 未生效）")
    @Size(max = 16)
    private String outcome;

    @Schema(description = "人工核对说明（可选，≤200 字符；不含凭据与上游正文）")
    @Size(max = 200)
    private String note;
}
