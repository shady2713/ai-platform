package com.basicframework.module.ai.dal.dataobject.crosssource;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 跨源执行记录（Y04）：一次跨源聚合的锚点与幂等依据。
 *
 * <p>存在的理由是**重试幂等必须有真实机制**。跨源执行天然会重试（来源超时、连接抖动），
 * 而"重试时小心一点别重复加"不是机制——它是一句请求。真正的机制是：
 * 一个执行键对应一行，每次来源计入时把该来源的金额与数据时间点写进本行，
 * 重试时按"来源是否已计入"判定只覆盖不叠加。
 *
 * <p>{@code planHash} 让"同一执行键 + 不同计划"成为可检出的冲突（409），
 * 否则同一个键先后跑出两个不同计划的结果，调用方无法分辨哪个是它要的。
 *
 * <p>{@code status} 只允许受控的终态流转：RUNNING → SUCCEEDED / PARTIAL / FAILED，
 * 且不可从终态回到 RUNNING（重跑必须换执行键），否则"上一次失败留下的半截数据"会混进新结果。
 */
@TableName("ai_cross_source_execution")
@KeySequence("ai_cross_source_execution_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Accessors(chain = true)
public class AiCrossSourceExecutionDO extends SoftDeletableDO {

    /** 状态：执行中（唯一可写中间态）。 */
    public static final String STATUS_RUNNING = "RUNNING";

    /** 状态：全部来源成功且偏移在容忍窗口内。 */
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";

    /** 状态：仅可选来源缺失（结果显式标注不完整，不是失败）。 */
    public static final String STATUS_PARTIAL = "PARTIAL";

    /** 状态：受控结束（预算超限/来源截断/必需来源失败/偏移超限/容量超限）。 */
    public static final String STATUS_FAILED = "FAILED";

    /** 状态：超过数仓容量上限，已转登记等待人工或批量窗口处理。 */
    public static final String STATUS_REGISTERED = "REGISTERED";

    /** 执行编号 */
    @TableId
    private Long id;

    /** 执行幂等键（调用方提供；同一键只能对应一份计划与一次合计） */
    private String executionKey;

    /** 跨源指标口径标识 */
    private String metricCode;

    /** 口径版本号（显式钉住，换版本不改旧结果） */
    private Integer semanticsRevision;

    /** 计划指纹（同一执行键提交不同计划即冲突） */
    private String planHash;

    /** 参与关联的实体键映射版本（各来源必须一致） */
    private Long mappingRevision;

    /** 状态（RUNNING/SUCCEEDED/PARTIAL/FAILED/REGISTERED） */
    private String status;

    /** 跨源合计金额 */
    private BigDecimal totalAmount;

    /** 结果币种（与口径一致；多币种无换算规则时 Y03 已在上游阻断） */
    private String currency;

    /** 一致性时间点：各来源数据时间的最小值（唯一"所有来源都成立"的时刻） */
    private LocalDateTime consistencyAsOf;

    /** 各来源数据时间点最大偏移（毫秒） */
    private Long maxSkewMillis;

    /** 缺失的来源角色（JSON 数组；显式缺失而不是按 0 补齐） */
    private String missingRoles;

    /** 中间结果总字节（预算用量证据） */
    private Long totalBytes;

    /** 中间结果总行数（预算用量证据） */
    private Integer totalRows;

    /** 并发来源数峰值（预算用量证据） */
    private Integer concurrentPeak;

    /** 受控结束的失败编号（稳定错误码；无失败为空） */
    private Integer failureCode;

    /** 乐观锁版本 */
    private Integer version;
}
