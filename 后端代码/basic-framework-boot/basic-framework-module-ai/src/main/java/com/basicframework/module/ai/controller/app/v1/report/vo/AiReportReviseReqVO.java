package com.basicframework.module.ai.controller.app.v1.report.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 报表对话修改请求（应用端协议层 VO）。
 *
 * <p>请求体**不提供归属、行范围与允许数据集**：归属由服务端会话解析；行范围来自授权层；
 * 允许重新查询的数据集由服务端按 A03 判定。客户端能表达的只有"改哪张报表、以哪一版为基础、改什么"。
 */
@Schema(description = "AI 应用端 - 报表对话修改请求")
@Data
public class AiReportReviseReqVO {

    @Schema(description = "报表编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long id;

    @Schema(description = "基础版本号（候选规格以它为准）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Integer baseVersionNo;

    @Schema(description = "乐观锁版本（与当前版本不一致返回 409，并发修改不覆盖）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Min(0)
    private Integer version;

    @Schema(description = "修改指令（自然语言）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty
    @Size(max = 500)
    private String instruction;

    @Schema(description = "模型端点编号（用于产出修订操作清单）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long endpointId;

    @Schema(description = "需要重新查询的数据集编号（数据类修改时提供；服务端按 A03 判定是否可读）")
    @Positive
    private Long datasetId;

    @Schema(description = "数据集版本编号（可选；缺省取最新已发布版本）")
    @Positive
    private Long datasetVersionId;

    @Schema(description = "来源运行标识（可选；跨主体引用会被拒绝）")
    @Size(max = 64)
    private String createdByRun;
}
