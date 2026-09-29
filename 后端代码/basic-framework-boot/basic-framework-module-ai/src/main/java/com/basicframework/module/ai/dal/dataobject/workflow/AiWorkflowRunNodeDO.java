package com.basicframework.module.ai.dal.dataobject.workflow;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.BaseDO;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 流程运行节点留痕（X08）：每个节点一条事实（append-retention，无软删除）。
 *
 * <p>支撑步骤可视化与失败定位：状态、稳定错误码与耗时逐节点可查；
 * 输出只存截断摘要（不含凭据与上游正文全文）。同一运行内节点键唯一
 * （图无环，一个节点最多执行一次）。
 */
@TableName("ai_workflow_run_node")
@KeySequence("ai_workflow_run_node_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Accessors(chain = true)
public class AiWorkflowRunNodeDO extends BaseDO {

    /** 状态：成功 */
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";

    /** 状态：失败 */
    public static final String STATUS_FAILED = "FAILED";

    /** 输出摘要长度上限（与列宽一致；超出截断） */
    public static final int MAX_OUTPUT_LENGTH = 2_000;

    /** 节点留痕编号 */
    @TableId
    private Long id;

    /** 运行编号 */
    private Long runId;

    /** 节点键（图内唯一） */
    private String nodeKey;

    /** 节点类型快照（AiWorkflowNodeType） */
    private String nodeType;

    /** 状态（SUCCEEDED/FAILED） */
    private String status;

    /** 节点输出摘要（截断存储） */
    private String outputText;

    /** 稳定错误码（成功为空） */
    private String errorCode;

    /** 节点开始时间 */
    private LocalDateTime startedTime;

    /** 节点结束时间 */
    private LocalDateTime finishedTime;

    /** 节点耗时（毫秒） */
    private Long durationMs;
}
