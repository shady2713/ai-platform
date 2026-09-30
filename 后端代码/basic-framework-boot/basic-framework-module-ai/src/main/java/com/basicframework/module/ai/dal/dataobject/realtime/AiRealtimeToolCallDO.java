package com.basicframework.module.ai.dal.dataobject.realtime;

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
 * 实时会话内工具调用（X05）：模型在会话中提出的工具调用及其**一次性**执行状态。
 *
 * <p>唯一键（会话, 回合, 调用标识）是"重连不重复执行工具"的承重结构：模型/客户端重复提出同一
 * 调用只对应一行；执行入口只允许 {@code PROPOSED → EXECUTING} 的条件更新（单赢家），
 * 因此重放、并发重连都不会产生第二次副作用。已执行的行保存稳定结论码，重连时返回既有结论。
 *
 * <p>参数在提出时冻结（{@code argumentsHash} + {@code argumentsJson}），执行时由 D08/X06 的
 * 政策闸门按**已发布版本**重新校验：参数正文不进 toString（可能含用户数据）。
 *
 * <p>本卡只执行免确认（AUTO）的读工具：需要确认的工具与写工具必须走运行/动作流程
 * （{@code ai_tool_action} 的 run_id 非空且带一次性挑战），会话内直接拒绝。
 */
@TableName("ai_realtime_tool_call")
@KeySequence("ai_realtime_tool_call_seq")
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiRealtimeToolCallDO extends SoftDeletableDO {

    /** 状态：已提出（可执行一次）。 */
    public static final String STATUS_PROPOSED = "PROPOSED";

    /** 状态：执行中（条件更新已消费执行权；进程崩溃会停在这里，不自动重放）。 */
    public static final String STATUS_EXECUTING = "EXECUTING";

    /** 状态：已执行（终态；重连返回该结论，不重复执行）。 */
    public static final String STATUS_EXECUTED = "EXECUTED";

    /** 状态：已拒绝（终态；政策不允许、工具不存在/停用、需要确认等）。 */
    public static final String STATUS_REJECTED = "REJECTED";

    /** 状态：执行失败（终态；稳定结论码给出原因）。 */
    public static final String STATUS_FAILED = "FAILED";

    /** 调用编号 */
    @TableId
    private Long id;

    /** 会话编号 */
    private Long sessionId;

    /** 提出该调用的回合 */
    private Long turnNo;

    /** 上游工具调用标识 */
    private String callId;

    /** 工具标识 */
    private String toolCode;

    /** 判定时的已发布工具版本（拒绝且无判定版本时为空） */
    private Long toolVersionId;

    /** 判定时的政策（只执行 AUTO 读工具） */
    private String policy;

    /** 冻结的参数（JSON；执行时按已发布版本重新校验） */
    @ToString.Exclude
    private String argumentsJson;

    /** 参数规范化哈希 */
    private String argumentsHash;

    /** 状态（PROPOSED/EXECUTING/EXECUTED/REJECTED/FAILED） */
    private String status;

    /** 执行结论稳定码（不含上游正文） */
    private String resultCode;

    /** 执行完成时间 */
    private LocalDateTime executedTime;

    /** 乐观锁版本（状态机 CAS） */
    private Integer version;
}
