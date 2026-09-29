package com.basicframework.module.ai.service.workflow.dto;

import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 流程运行受理/查询结果（服务层 DTO，X08）。
 *
 * <p>受理与查询共用同一形态：状态、输出、稳定错误码与**逐节点事实**（步骤可视化与失败定位）。
 * 响应不含图 JSON 全文（那是版本接口的事），节点留痕就是执行轨迹。
 */
@Data
@Accessors(chain = true)
public class AiWorkflowRunResultDTO {

    /** 运行编号 */
    private Long runId;

    /** 流程编号 */
    private Long workflowId;

    /** 固定的流程版本编号 */
    private Long workflowVersionId;

    /** 固定的版本序号 */
    private Integer versionNo;

    /** 是否命中幂等复用 */
    private boolean reused;

    /** 状态（SUCCEEDED/FAILED；受理返回时执行已结束） */
    private String status;

    /** 运行输出（成功时为结束节点的上游文本，截断存储） */
    private String outputText;

    /** 失败稳定原因码（成功为空） */
    private String errorCode;

    /** 已执行节点数 / 图节点总数 */
    private Integer nodeExecuted;

    private Integer nodeTotal;

    /** 执行耗时（毫秒） */
    private Long durationMs;

    /** 节点事实（按执行顺序） */
    private List<Node> nodes = List.of();

    /** 单个节点的执行事实。 */
    @Data
    @Accessors(chain = true)
    public static class Node {

        /** 节点键 */
        private String nodeKey;

        /** 节点类型 */
        private String nodeType;

        /** 状态（SUCCEEDED/FAILED） */
        private String status;

        /** 输出摘要 */
        private String outputText;

        /** 稳定错误码 */
        private String errorCode;

        /** 耗时（毫秒） */
        private Long durationMs;
    }
}
