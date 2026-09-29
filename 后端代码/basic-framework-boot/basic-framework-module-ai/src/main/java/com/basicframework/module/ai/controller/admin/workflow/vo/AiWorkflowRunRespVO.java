package com.basicframework.module.ai.controller.admin.workflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/** AI 流程运行响应（协议层 VO）：状态、输出与稳定错误码；受理响应带逐节点事实，不含图 JSON 全文。 */
@Schema(description = "管理后台 - AI 流程运行 Response VO")
@Data
@Accessors(chain = true)
public class AiWorkflowRunRespVO {

    @Schema(description = "运行编号")
    private Long id;

    @Schema(description = "流程编号")
    private Long workflowId;

    @Schema(description = "固定的流程版本编号")
    private Long workflowVersionId;

    @Schema(description = "受理幂等键")
    private String idempotencyKey;

    @Schema(description = "状态（RUNNING/SUCCEEDED/FAILED）")
    private String status;

    @Schema(description = "数据等级")
    private String dataLevel;

    @Schema(description = "运行输出（成功时为结束节点的上游文本）")
    private String outputText;

    @Schema(description = "失败稳定原因码（成功为空）")
    private String errorCode;

    @Schema(description = "当前（或最后）执行的节点键")
    private String currentNodeKey;

    @Schema(description = "已执行节点数")
    private Integer nodeExecuted;

    @Schema(description = "图节点总数")
    private Integer nodeTotal;

    @Schema(description = "执行耗时（毫秒）")
    private Long durationMs;

    @Schema(description = "受理时间")
    private LocalDateTime startedTime;

    @Schema(description = "结束时间")
    private LocalDateTime finishedTime;

    @Schema(description = "节点事实（仅受理响应返回；按执行顺序）")
    private List<AiWorkflowRunNodeRespVO> nodes;
}
