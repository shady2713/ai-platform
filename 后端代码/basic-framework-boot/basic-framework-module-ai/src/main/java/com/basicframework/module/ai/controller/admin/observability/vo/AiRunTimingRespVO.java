package com.basicframework.module.ai.controller.admin.observability.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 运行耗时分解（Q03，协议层 VO）：**区分实测与未计量**。
 *
 * <p>模型耗时来自用量账本的真实计量（含"来源未知"的条数，AT-060：未知不写 0）；
 * 检索与业务 API 在运行链路里尚未单独计量，因此以
 * {@link #unmeasuredStages} 明确列出，而不是填 0 冒充"耗时为零"。
 */
@Schema(description = "管理后台 - 运行耗时分解")
@Data
@Accessors(chain = true)
public class AiRunTimingRespVO {

    @Schema(description = "总耗时（受理到终态；仍在执行时按当前时刻计算，毫秒）")
    private Long totalDurationMs;

    @Schema(description = "模型耗时合计（来自用量账本实测值，毫秒；没有计量时为 0）")
    private Long modelDurationMs;

    @Schema(description = "模型调用条数（按用量账本）")
    private Integer modelInvocationCount;

    @Schema(description = "计量来源未知的调用条数（这些调用没有耗时/用量事实）")
    private Integer unknownUsageCount;

    @Schema(description = "检索耗时（首期未单独计量，恒为空）")
    private Long retrievalDurationMs;

    @Schema(description = "业务 API 耗时（首期未单独计量，恒为空）")
    private Long businessApiDurationMs;

    @Schema(description = "尚未单独计量的阶段（稳定阶段名，前端据此显示「未计量」）")
    private List<String> unmeasuredStages;
}
