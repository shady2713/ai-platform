package com.basicframework.module.ai.dal.dataobject.action;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 工具动作（D09 + X06）：需要确认的工具调用。
 *
 * <p>参数在创建时被冻结（{@code argumentsHash} + {@code argumentsJson}），确认时必须携带同一参数哈希与
 * 一次性 challenge：改参数、换用户、过期都会在执行前被挡住。执行结果只记稳定原因码，不记上游正文。
 * 参数正文不进 {@code toString()}（可能含用户数据）。
 *
 * <p>写工具（X06）在此之上多了三件事：
 * <ol>
 *   <li><b>业务幂等键</b>（{@code idempotencyParam} + {@code idempotencyKey}）：同一工具 + 同一业务键在
 *       数据层唯一（{@code uk_ai_tool_action_business}），因此同一业务意图不可能产生第二次副作用；</li>
 *   <li><b>结果未定</b>（{@link #STATUS_EXECUTING}/{@link #STATUS_UNKNOWN}）：超时、连接中断、上游未确认
 *       一律不猜"成功/失败"，{@code attemptEpoch} 记录"确认已被消费一次"，只能经核对收敛；</li>
 *   <li><b>核对证据</b>（{@code verifySourceRef}/{@code verifyParam}/{@code verifiedBy}/{@code verifyResult}/
 *       {@code verifyEvidence}）：登记的核对查询与结论，只存稳定事实，不存上游正文。</li>
 * </ol>
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

    /** 状态：执行中（确认已被消费一次；进程崩溃会停在这里，由核对收敛） */
    public static final String STATUS_EXECUTING = "EXECUTING";

    /** 状态：已执行（终态） */
    public static final String STATUS_EXECUTED = "EXECUTED";

    /** 状态：已拒绝（终态） */
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** 状态：已过期（终态） */
    public static final String STATUS_EXPIRED = "EXPIRED";

    /** 状态：执行被明确拒绝（终态，确定无副作用） */
    public static final String STATUS_FAILED = "FAILED";

    /** 状态：结果未定（终态候选：只能经核对收敛为 EXECUTED/FAILED） */
    public static final String STATUS_UNKNOWN = "UNKNOWN";

    /** 工具类型：只读 */
    public static final String TOOL_TYPE_READ = "READ";

    /** 工具类型：写（只经确认入口执行，带业务幂等键与核对查询） */
    public static final String TOOL_TYPE_WRITE = "WRITE";

    /** 核对方式：程序核对（调用登记的核对查询） */
    public static final String VERIFIED_BY_PROGRAM = "PROGRAM";

    /** 核对方式：人工核对（操作员给出结论与说明） */
    public static final String VERIFIED_BY_MANUAL = "MANUAL";

    /** 核对结论：业务系统已生效 */
    public static final String VERIFY_APPLIED = "APPLIED";

    /** 核对结论：业务系统未生效（可重新发起，绝不自动重放） */
    public static final String VERIFY_NOT_APPLIED = "NOT_APPLIED";

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

    /** 工具类型快照（READ/WRITE；写动作只经确认入口执行） */
    private String toolType;

    /** 参数规范化哈希（确认时比对） */
    private String argumentsHash;

    /** 冻结的参数（确认后按原参数执行；不进 toString、不回显） */
    @ToString.Exclude
    private String argumentsJson;

    /** 业务幂等键参数名（写动作必填，来自版本声明快照） */
    private String idempotencyParam;

    /** 业务幂等键值（写动作必填，取自冻结参数；不参与回显与日志） */
    @ToString.Exclude
    private String idempotencyKey;

    /** 登记的核对查询 operationKey（写动作必填；动作创建时冻结） */
    private String verifySourceRef;

    /** 核对查询接收业务键的参数名（写动作必填；动作创建时冻结） */
    private String verifyParam;

    /** 一次性确认挑战（与动作绑定；不进 toString，避免日志泄漏） */
    @ToString.Exclude
    private String challenge;

    /** 状态（PENDING/CONFIRMED/EXECUTING/EXECUTED/CANCELLED/EXPIRED/FAILED/UNKNOWN） */
    private String status;

    /** 过期时间 */
    private java.time.LocalDateTime expiresAt;

    /** 确认/拒绝时间 */
    private java.time.LocalDateTime decidedAt;

    /** 执行尝试时间（进入 EXECUTING 的时间） */
    private java.time.LocalDateTime executedAt;

    /** 执行尝试代数（CAS 消费确认一次；重放不会再推进） */
    private Integer attemptEpoch;

    /** 执行结论（稳定原因码，不含上游正文） */
    private String resultCode;

    /** 核对时间 */
    private java.time.LocalDateTime verifiedAt;

    /** 核对方式（PROGRAM/MANUAL） */
    private String verifiedBy;

    /** 核对结论（APPLIED/NOT_APPLIED） */
    private String verifyResult;

    /** 核对证据（登记操作键与条目数，或人工说明；不含上游正文与凭据） */
    private String verifyEvidence;

    /** 乐观锁版本 */
    private Integer version;
}
