package com.basicframework.module.ai.controller.admin.crosssource.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 跨源合并响应（Y07 对外契约）。
 *
 * <p><b>字段语义</b>：
 * <ul>
 *   <li>{@link #crossSource} —— 跨源标记，<b>本响应恒为 {@code true}</b>。
 *       它是"这是一份跨源合并结果"的判别键，与完整性口径<b>分开</b>存在：
 *       口径缺失时前端仍然知道该按跨源规则处理，从而按 {@code WITHHELD} 渲染而不是
 *       当成普通表格。两者合成一个字段的话，"字段缺失"就与"单系统响应"无法区分，
 *       fail-open 缺口会以另一种形式回来。</li>
 *   <li>{@link #integrity} —— 授权维度完整性口径，<b>恒非空</b>。
 *       {@code state ∈ {COMPLETE, PARTIAL, WITHHELD}}：
 *       {@code COMPLETE} 全部来源在授权范围内；{@code PARTIAL} 只出合计、不出分来源明细；
 *       {@code WITHHELD} 整份不出具，<b>一个数字都不返回</b>。</li>
 *   <li>{@link #totalAmount} / {@link #sourceCount} / {@link #sources} ——
 *       仅在口径放行时有值；{@code WITHHELD} 时全部为 null/空，
 *       合计（差额可解的腿）与来源计数（条数可数的腿）两条信道同时堵死。</li>
 * </ul>
 *
 * <p><b>缺失时的规定行为</b>：后端不存在"不带口径"的响应——契约的构造器把 null 归一为
 * {@code WITHHELD}。前端侧的兜底同样如此：解析到 {@code crossSource=true} 却找不到
 * {@code integrity} 时，一律按 {@code WITHHELD} 渲染，绝不按 {@code COMPLETE}。
 *
 * <p>与技术完整性（{@code completeness}）刻意分开：后者回答"口径内有没有来源没跑成"，
 * 前者回答"有没有来源你无权"。混成一个字段会把授权拒绝显示成"结果可能不完整"，
 * 用户会以为是临时故障反复重试，而重试永远不会成功。
 */
@Data
@JsonInclude(JsonInclude.Include.ALWAYS)
public class AiCrossSourceMergeRespVO {

    /** 跨源标记：本响应恒为 {@code true}（见类注释：它与口径是两个字段）。 */
    private Boolean crossSource = Boolean.TRUE;

    /** 授权完整性口径，恒非空。 */
    private AiCrossSourceIntegrityRespVO integrity;

    /** 跨源执行幂等键。 */
    private String executionKey;

    /** 指标码。 */
    private String metricCode;

    /** 币种。 */
    private String currency;

    /** 合计金额；{@code WITHHELD} 时为 null（差额不可解）。 */
    private BigDecimal totalAmount;

    /** 参与合并的来源数；{@code WITHHELD} 时为 null（条数不可数）。 */
    private Integer sourceCount;

    /** 分来源明细（只含角色与金额）；角色不允许或 {@code WITHHELD} 时为空。 */
    private List<AiCrossSourceSourceAmountVO> sources;

    /** 跨源口径时间点：各来源数据时间的最小值；{@code WITHHELD} 时为 null。 */
    private LocalDateTime consistencyAsOf;

    /** 各来源数据时间偏移（毫秒）；{@code WITHHELD} 时为 null。 */
    private Long maxSkewMillis;

    /** 技术完整性：本次执行是否产出完整结果（与授权口径是两个维度）。 */
    private Boolean complete;
}
