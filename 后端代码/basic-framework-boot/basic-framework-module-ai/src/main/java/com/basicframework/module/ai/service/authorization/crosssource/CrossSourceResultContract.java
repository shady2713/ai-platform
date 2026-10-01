package com.basicframework.module.ai.service.authorization.crosssource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 跨源合并的对外响应契约（Y07）：**口径恒非空，且口径决定数字出不出**。
 *
 * <p>本 record 是 Y04 执行结果与 Y05 授权结论在响应层的合成物。它承担两件在
 * 之前没有任何一处承担的事：
 *
 * <ol>
 *   <li><b>口径恒带</b>：{@link #integrity()} 永远是 {@link CrossSourceIntegrity}，
 *       构造器把 null 归一成 {@code WITHHELD}。因此"某个分支忘了设置口径"
 *       在类型层面就不可能发生——这正是 Y05 §9.1 记录的 fail-open 缺口。</li>
 *   <li><b>口径即闸门</b>：{@code WITHHELD} 时构造器<b>强制抹掉</b>
 *       {@code totalAmount}、{@code sourceCount}、{@code sources}、
 *       {@code consistencyAsOf}、{@code maxSkewMillis}。这不是"调用方记得别填"，
 *       而是结构上就填不进去。</li>
 * </ol>
 *
 * <p>第 2 条是"无权来源不可反推明细"的实现方式。两条腿都要堵：
 * <b>差额可解</b>（拿旧合计减新合计解出被禁来源）靠没有 {@code totalAmount}；
 * <b>条数可数</b>（"这次合了几个来源"本身是信道）靠没有 {@code sourceCount}
 * 与空的 {@code sources}。只堵合计而留下来源条数，等于把明细换成计数再送出去。
 *
 * <p>本契约刻意<b>不含</b>"缺失来源"字段：能走到本契约的必然是 Y04 的
 * {@code SUCCEEDED} 终态，而该终态按 Y04 的不变量（{@code complete = missingRoles.isEmpty()}）
 * 意味着一个来源都没缺。把它列进契约只会得到一个恒为空的字段。
 * 技术完整性仍由 Y04 自带的 {@code completeness} 维度表达，两者不混。
 */
public record CrossSourceResultContract(
        String executionKey,
        String metricCode,
        String currency,
        BigDecimal totalAmount,
        Integer sourceCount,
        List<SourceAmount> sources,
        LocalDateTime consistencyAsOf,
        Long maxSkewMillis,
        boolean complete,
        CrossSourceIntegrity integrity) {

    /**
     * 规范构造器：口径恒非空 + 口径即闸门。
     *
     * <p>顺序不可颠倒：先归一口径，再按口径决定抹不抹数字。
     * 若先抹后归一，一个 {@code null} 口径会在抹数字之前就被判成"放行"，
     * 恰好产生本契约要消灭的那种越权披露。
     */
    public CrossSourceResultContract {
        sources = sources == null ? List.of() : List.copyOf(sources);
        integrity = CrossSourceIntegrity.ofNullable(integrity);
        if (integrity.rendersNothing()) {
            // 一个数字都不出：合计、来源计数、来源明细、口径时间点与偏移全部抹掉。
            totalAmount = null;
            sourceCount = null;
            sources = List.of();
            consistencyAsOf = null;
            maxSkewMillis = null;
        }
    }

    /**
     * 放行结果：来源全部在授权范围内，可出合计。
     *
     * <p>唯一允许携带合计与来源计数的工厂方法——不可出具的入口一律走
     * {@link #withheld} 或 {@link #missingIntegrity}，因此"忘记判定就出数"
     * 在本契约里没有构造路径。
     *
     * @param sources 分来源明细；角色不允许时传空列表（此时口径应为 {@code PARTIAL}）
     */
    public static CrossSourceResultContract disclosable(
            String executionKey,
            String metricCode,
            String currency,
            BigDecimal totalAmount,
            Integer sourceCount,
            List<SourceAmount> sources,
            LocalDateTime consistencyAsOf,
            Long maxSkewMillis,
            boolean complete,
            CrossSourceIntegrity integrity) {
        return new CrossSourceResultContract(
                executionKey,
                metricCode,
                currency,
                totalAmount,
                sourceCount,
                sources,
                consistencyAsOf,
                maxSkewMillis,
                complete,
                integrity);
    }

    /**
     * <b>不可出具</b>：整份结果一个数字都不给。
     *
     * <p>除执行键与口径外全部字段为空——调用方拿它只能知道"这份结果没给出来"，
     * 拿不到任何可用于反推的量。
     *
     * @param reason 静态理由（不含执行键、来源角色与金额）
     */
    public static CrossSourceResultContract withheld(String executionKey, String reason) {
        return new CrossSourceResultContract(
                executionKey,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                false,
                CrossSourceIntegrity.withheld(reason));
    }

    /**
     * <b>口径缺失</b>时的唯一规定行为：按 {@code WITHHELD} 处理，绝不按 {@code COMPLETE}。
     *
     * <p>与 {@link #withheld} 的区别在 reason 与<b>调用时机</b>：本方法服务于
     * "台账读到了，但某条来源行没有数据集绑定、因而无法建立授权事实"这种情况。
     * 那既不是一次授权拒绝（不该让用户去申请权限），也不是一次技术失败
     * （不该让用户重跑），而是"这份响应给不出可断言的授权口径"——
     * 按 Y07 的规定，缺口径一律落到 {@code WITHHELD}。
     */
    public static CrossSourceResultContract missingIntegrity(String executionKey) {
        return new CrossSourceResultContract(
                executionKey, null, null, null, null, List.of(), null, null, false, CrossSourceIntegrity.missing());
    }

    /** 该响应是否要求"一个数字都不渲染"（前端据此不出表格、不出条数）。 */
    public boolean rendersNothing() {
        return integrity.rendersNothing();
    }

    /**
     * 单个来源在本次合并中贡献的金额。
     *
     * <p>只含角色与金额：<b>不</b>含数据集编号、来源系统与实体键。这三者各自都是
     * 跨系统事实（Y05 专项二：映射本身即授权对象），把它们放进响应等于把
     * "你无权的那部分"换一种形式再送一次。
     *
     * @param role 来源角色
     * @param amount 该来源预聚合后的金额
     */
    public record SourceAmount(String role, BigDecimal amount) {

        public SourceAmount {
            amount = amount == null ? BigDecimal.ZERO : amount;
        }
    }
}
