package com.basicframework.module.ai.dal.dataobject.run;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 运行任务（O02）：受理时与 run 同事务建立的首任务。
 *
 * <p>队列只保存载荷摘要，不保存正文；{@code attemptCount} 与 {@code nextAttemptTime} 是重试等待的落点，
 * 领取、租约与心跳由 O03 在同一张表上补齐（本类只描述 O02 需要的列）。
 */
@TableName("ai_run_task")
@KeySequence("ai_run_task_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiRunTaskDO extends SoftDeletableDO {

    /** 任务类型：运行步骤（首任务） */
    public static final String KIND_RUN_STEP = "RUN_STEP";

    /** 状态：待领取 */
    public static final String STATUS_QUEUED = "QUEUED";

    /** 状态：执行中 */
    public static final String STATUS_RUNNING = "RUNNING";

    /** 状态：成功 */
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";

    /** 状态：失败 */
    public static final String STATUS_FAILED = "FAILED";

    /** 状态：结果未知（不可普通重试） */
    public static final String STATUS_UNKNOWN = "UNKNOWN";

    /** 任务编号 */
    @TableId
    private Long id;

    /** 运行编号 */
    private Long runId;

    /** 任务类型 */
    private String taskKind;

    /** 状态（QUEUED/RUNNING/SUCCEEDED/FAILED/UNKNOWN） */
    private String status;

    /** 已尝试次数 */
    private Integer attemptCount;

    /** 下次可尝试时间（重试等待） */
    private java.time.LocalDateTime nextAttemptTime;

    /** 任务载荷摘要（正文不入队） */
    private String payloadDigest;

    /** 乐观锁版本 */
    private Integer version;
}
