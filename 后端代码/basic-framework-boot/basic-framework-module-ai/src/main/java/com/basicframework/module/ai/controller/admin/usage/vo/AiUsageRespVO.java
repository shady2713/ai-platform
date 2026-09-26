package com.basicframework.module.ai.controller.admin.usage.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/** 用量记录（协议层 VO）：只含计量元数据，不含提示词/响应正文/端点地址与密钥。 */
@Schema(description = "管理后台 - AI 用量记录")
@Data
@Accessors(chain = true)
public class AiUsageRespVO {

    @Schema(description = "应用编号")
    private Long applicationId;

    @Schema(description = "上游耗时（毫秒）")
    private Integer durationMs;

    @Schema(description = "端点引用（编号/别名）")
    private String endpointRef;

    @Schema(description = "输入 token（未知为空，不代表 0）")
    @ToString.Exclude
    private Long inputTokens;

    @Schema(description = "调用标识")
    private String invocationId;

    @Schema(description = "模型标识")
    private String modelRef;

    @Schema(description = "模型配置修订号")
    private Integer modelRevision;

    @Schema(description = "发生时间")
    private LocalDateTime occurredAt;

    @Schema(description = "输出 token（未知为空，不代表 0）")
    @ToString.Exclude
    private Long outputTokens;

    @Schema(description = "运行编号")
    private Long runId;

    @Schema(description = "服务编号")
    private Long serviceId;

    @Schema(description = "调用结果（SUCCEEDED/FAILED/CANCELLED）")
    private String status;

    @Schema(description = "任务编号")
    private Long taskId;

    @Schema(description = "计量来源（REPORTED 上游报告/ESTIMATED 平台估算/UNKNOWN 未知）")
    private String usageSource;
}
