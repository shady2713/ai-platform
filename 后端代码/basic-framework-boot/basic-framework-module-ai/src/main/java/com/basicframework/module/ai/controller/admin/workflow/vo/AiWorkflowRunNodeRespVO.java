package com.basicframework.module.ai.controller.admin.workflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/** AI 流程运行节点留痕响应（协议层 VO）：步骤可视化与失败定位的数据源。 */
@Schema(description = "管理后台 - AI 流程运行节点 Response VO")
@Data
@Accessors(chain = true)
public class AiWorkflowRunNodeRespVO {

    @Schema(description = "节点键")
    private String nodeKey;

    @Schema(description = "节点类型")
    private String nodeType;

    @Schema(description = "状态（SUCCEEDED/FAILED）")
    private String status;

    @Schema(description = "输出摘要")
    private String outputText;

    @Schema(description = "稳定错误码（成功为空）")
    private String errorCode;

    @Schema(description = "耗时（毫秒）")
    private Long durationMs;
}
