package com.basicframework.module.ai.dal.dataobject.action;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 工具动作（D09）：需要确认的工具调用。
 *
 * <p>参数在创建时被冻结（{@code argumentsHash} + {@code argumentsJson}），确认时必须携带同一参数哈希与
 * 一次性 challenge：改参数、换用户、过期都会在执行前被挡住。执行结果只记稳定原因码，不记上游正文。
 * 参数正文不进 {@code toString()}（可能含用户数据）。
 */
@TableName("ai_tool_action")
@KeySequence("ai_tool_action_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiToolActionDO extends SoftDeletableDO {

    /** 状态：待确认 */
    public static final String STATUS_PENDING = "PENDING";

    /** 状态：已确认（可执行一次） */
    public static final String STATUS_CONFIRMED = "CONFIRMED";

    /** 状态：已执行（终态） */
    public static final String STATUS_EXECUTED = "EXECUTED";

    /** 状态：已拒绝（终态） */
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** 状态：已过期（终态） */
    public static final String STATUS_EXPIRED = "EXPIRED";

    /** 状态：执行失败（终态） */
    public static final String STATUS_FAILED = "FAILED";

    /** 动作编号 */
    @TableId
    private Long id;

    /** 运行编号（动作属于某次运行） */
    private Long runId;

    /** 工具编号 */
    private Long toolId;

    /** 工具版本编号（政策与来源来自该快照） */
    private Long toolVersionId;

    /** 所属应用编号（主体归属之一） */
    private Long applicationId;

    /** 主体类型（APP/USER） */
    private String subjectType;

    /** 外部用户标识（确认必须由同一主体完成） */
    private String externalUserId;

    /** 创建时的执行政策（确认时要求仍为 CONFIRM） */
    private String policy;

    /** 参数规范化哈希（确认时比对） */
    private String argumentsHash;

    /** 冻结的参数（确认后按原参数执行；不进 toString、不回显） */
    @ToString.Exclude
    private String argumentsJson;

    /** 一次性确认挑战（与动作绑定；不进 toString，避免日志泄漏） */
    @ToString.Exclude
    private String challenge;

    /** 状态（PENDING/CONFIRMED/EXECUTED/CANCELLED/EXPIRED/FAILED） */
    private String status;

    /** 过期时间 */
    private java.time.LocalDateTime expiresAt;

    /** 确认/拒绝时间 */
    private java.time.LocalDateTime decidedAt;

    /** 执行时间 */
    private java.time.LocalDateTime executedAt;

    /** 执行结论（稳定原因码，不含上游正文） */
    private String resultCode;

    /** 乐观锁版本 */
    private Integer version;
}
