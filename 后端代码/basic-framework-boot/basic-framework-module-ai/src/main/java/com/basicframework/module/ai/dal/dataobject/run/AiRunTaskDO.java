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
 * <p>队列只保存载荷摘要，不保存正文；{@code attemptCount} 与 {@code nextAttemptTime} 是重试等待的落点。
 * 租约列（{@code leaseOwner}/{@code leaseExpiresTime}/{@code heartbeatTime}/{@code claimedEpoch}/
 * {@code maxAttempts}/{@code lastErrorCode}）由 O03 在同一聚合映射上补齐：领取、续租与落库
 * 都以 (owner, claimedEpoch) 作栅栏，迟到的旧 worker 不能覆盖新 worker。
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

    /** 租约持有者（worker 标识；未领取时为空） */
    private String leaseOwner;

    /** 租约到期时间（过期即可被恢复扫描接管） */
    private java.time.LocalDateTime leaseExpiresTime;

    /** 最近一次心跳时间 */
    private java.time.LocalDateTime heartbeatTime;

    /** 领取代次（续租与落库的栅栏：旧 worker 迟到不能覆盖新 worker） */
    private Integer claimedEpoch;

    /** 最大尝试次数（达到后置 FAILED，不再重试） */
    private Integer maxAttempts;

    /** 最近一次失败原因码（稳定词表） */
    private String lastErrorCode;

    /** 乐观锁版本 */
    private Integer version;
}
