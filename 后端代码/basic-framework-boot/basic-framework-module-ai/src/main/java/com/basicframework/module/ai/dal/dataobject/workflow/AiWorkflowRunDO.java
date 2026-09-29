package com.basicframework.module.ai.dal.dataobject.workflow;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 流程运行（X08）：受理即固定版本的同步有界执行。
 *
 * <p>幂等键（同一流程内唯一）防止重复发起：同键同摘要返回首次运行，同键异摘要拒绝。
 * 终态只能写一次（CAS {@code RUNNING} → 终态），失败原因是稳定错误码（不含上游正文）。
 * 预算在受理时快照（{@link #maxSteps}/{@link #maxDurationMillis}）。
 */
@TableName("ai_workflow_run")
@KeySequence("ai_workflow_run_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Accessors(chain = true)
public class AiWorkflowRunDO extends SoftDeletableDO {

    /** 状态：执行中（同步执行期间） */
    public static final String STATUS_RUNNING = "RUNNING";

    /** 状态：成功（结束节点输出已产生） */
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";

    /** 状态：失败（节点失败或预算受控结束） */
    public static final String STATUS_FAILED = "FAILED";

    /** 流程运行编号 */
    @TableId
    private Long id;

    /** 流程编号 */
    private Long workflowId;

    /** 流程版本编号（受理时固定的不可变快照） */
    private Long workflowVersionId;

    /** 受理幂等键（同一流程内唯一） */
    private String idempotencyKey;

    /** 受理请求摘要（同键异摘要拒绝） */
    private String requestDigest;

    /** 状态（RUNNING/SUCCEEDED/FAILED） */
    private String status;

    /** 数据等级（模型节点外发等级） */
    private String dataLevel;

    /** 运行输入（开始节点的透传文本） */
    private String inputText;

    /** 运行输出（结束节点的上游文本，截断存储） */
    private String outputText;

    /** 失败稳定原因码（成功为空） */
    private String errorCode;

    /** 当前（或最后）执行的节点键 */
    private String currentNodeKey;

    /** 流程图节点总数（发布时冻结） */
    private Integer nodeTotal;

    /** 已执行节点数 */
    private Integer nodeExecuted;

    /** 步数预算（受理快照） */
    private Integer maxSteps;

    /** 耗时预算（毫秒，受理快照） */
    private Long maxDurationMillis;

    /** 受理时间 */
    private LocalDateTime startedTime;

    /** 结束时间 */
    private LocalDateTime finishedTime;

    /** 执行耗时（毫秒） */
    private Long durationMs;

    /** 乐观锁版本（终态写入用 CAS） */
    private Integer version;
}
