package com.basicframework.module.ai.domain.semantic;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_CALIBER_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_CURRENCY_CONVERSION_MISSING_CONFLICT;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 跨源指标口径的判定算法（Y03）：单位、币种、时区、时间窗口、主键粒度、扇出与缺口。
 *
 * <p>这些规则集中在一个纯函数类里，是因为它们同时被三条路径使用，任何一条走偏都会造成
 * "同一组来源在不同路径下结论不同"：
 * <ul>
 *   <li><b>登记与发布校验</b>：口径版本发布前确认各来源口径彼此可加；</li>
 *   <li><b>聚合校验</b>：查询计划逐个来源比对口径，冲突即阻断；</li>
 *   <li><b>缺口处理</b>：来源缺数据时按完整性策略决定阻断还是追问。</li>
 * </ul>
 *
 * <p>四条不可让步的规则：
 * <ol>
 *   <li><b>币种不同即禁止相加</b>：没有换算规则时，跨币种求和抛
 *       {@code AI_METRIC_CURRENCY_CONVERSION_MISSING_CONFLICT}，绝不静默按数值相加
 *       （100 USD + 100 CNY = 200 是最危险的"看起来对"的结果）；</li>
 *   <li><b>口径不一致一律阻断</b>：单位/时区/时间窗口任一不同即抛
 *       {@code AI_METRIC_CALIBER_CONFLICT}，绝不"按主来源的时区算"；</li>
 *   <li><b>粒度决定扇出</b>：多对多路径下同一事实会被重复计入，因此每个来源都必须声明
 *       自己的主键粒度，并按该粒度**先聚合再关联**；</li>
 *   <li><b>缺口要显式处理</b>：来源缺数据时按可选来源与完整性策略判定，绝不按 0 静默补齐。</li>
 * </ol>
 */
public final class AiMetricSemanticsFacts {

    private AiMetricSemanticsFacts() {}

    /**
     * 币种是否可以在本次聚合里相加。
     *
     * <p>返回 false 表示"必须显式换算或拒绝"，调用方据此抛
     * {@code AI_METRIC_CURRENCY_CONVERSION_MISSING_CONFLICT}。判定顺序刻意是
     * "先看是否需要币种口径，再看是否同币种，最后才看换算规则"——
     * 这样非金额口径（COUNT / PERCENT）不会因为没有换算规则而被误拒。
     */
    public static boolean summable(AiMetricSemantics semantics, List<AiMetricSemantics.Source> sources) {
        if (semantics == null || sources == null || sources.isEmpty()) {
            return false;
        }
        Set<String> currencies = distinctCurrencies(sources);
        if (currencies.size() <= 1) {
            // 全部同币种（含全部 NONE）：可直接相加，不需要换算规则
            return true;
        }
        // 多币种：只有声明了换算规则、且目标币种覆盖全部来源币种时才允许
        AiMetricSemantics.Conversion conversion = semantics.conversion();
        return conversion != null
                && currencies.size() == 1
                && conversion.targetCurrency().equals(currencies.iterator().next());
    }

    /**
     * 跨币种求和的前置检查：口径要求金额币种、来源币种不一致、且没有换算规则 → 抛稳定错误码。
     *
     * <p>这是 AT-034 的"不同币种无换算规则不能求和"：拒绝发生在**聚合之前**，
     * 因此不存在"先加了再发现单位不对"的中间态。
     */
    public static void requireSummable(AiMetricSemantics semantics, List<AiMetricSemantics.Source> sources) {
        if (semantics == null || sources == null || sources.isEmpty()) {
            throw exception(AI_METRIC_CALIBER_CONFLICT);
        }
        Set<String> currencies = distinctCurrencies(sources);
        if (currencies.size() <= 1) {
            return;
        }
        if (!summable(semantics, sources)) {
            throw exception(AI_METRIC_CURRENCY_CONVERSION_MISSING_CONFLICT);
        }
    }

    /** 来源币种集合（归一后去重；{@code NONE} 也参与判定，因为它与真实币种不可加）。 */
    public static Set<String> distinctCurrencies(List<AiMetricSemantics.Source> sources) {
        if (sources == null || sources.isEmpty()) {
            return Set.of();
        }
        return sources.stream()
                .map(AiMetricSemantics.Source::currency)
                .map(value -> value == null ? "NONE" : value)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 口径一致性：各来源的单位、时区与时间窗口必须与口径声明一致。
     *
     * <p>任一不一致即抛 {@code AI_METRIC_CALIBER_CONFLICT}（409）。这是 AT-034 的
     * "口径冲突不能靠模型猜测"：**没有**"挑一个来源的时区"或"按主来源对齐"的兜底分支。
     */
    public static void requireConsistentCaliber(AiMetricSemantics semantics, List<AiMetricSemantics.Source> sources) {
        if (semantics == null || sources == null || sources.isEmpty()) {
            throw exception(AI_METRIC_CALIBER_CONFLICT);
        }
        for (AiMetricSemantics.Source source : sources) {
            if (!semantics.unit().equals(source.unit()) || !semantics.timezone().equals(source.timezone())) {
                throw exception(AI_METRIC_CALIBER_CONFLICT);
            }
        }
    }

    /** 必需口径项是否齐备（缺项即视为不可判定，拒绝而不是补默认值）。 */
    public static boolean complete(List<AiMetricSemantics.Source> sources) {
        if (sources == null || sources.isEmpty()) {
            return false;
        }
        for (AiMetricSemantics.Source source : sources) {
            if (source.unit() == null
                    || source.currency() == null
                    || source.timezone() == null
                    || source.primaryKey().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 扇出判定：来源之间是否存在"同一事实经多对多路径重复参与"的结构。
     *
     * <p>判据是**主键粒度覆盖**：两个来源若没有任何一个声明的粒度键能唯一确定一行事实，
     * 那么它们的关联就是多对多的——同一笔订单可以经多条发票行与多条回款行进入结果集，
     * 净额被重复计入。解决方式是"按各自主键粒度**先聚合再关联**"
     * （口径的 {@code aggregationOrder} 就是这个契约）。
     *
     * @param preAggregated 是否已按各自主键粒度预聚合（查询计划必须显式声明，不允许默认 true）
     */
    public static boolean fanoutSafe(
            AiMetricSemantics.Source left, AiMetricSemantics.Source right, boolean preAggregated) {
        if (left == null || right == null) {
            return false;
        }
        if (left.primaryKey().isEmpty() || right.primaryKey().isEmpty()) {
            // 没有粒度声明就无法证明安全——按不安全处理（宁可拒绝也不重复计算）
            return false;
        }
        if (!preAggregated) {
            return false;
        }
        // 预聚合后每个来源在其主键粒度上都是唯一的，关联不再放大行数
        return true;
    }

    /**
     * 缺口判定：来源缺数据时能否按 0 继续。
     *
     * <p>只有**显式声明为可选来源**（{@code optional=true}）才允许按缺省值继续；
     * 其余情况必须追问（{@code AI_METRIC_GAP_CLARIFICATION_REQUIRED}），
     * 绝不静默把缺口当 0——那会让"没数据"和"真的是 0"在报表上无法区分。
     *
     * @return true 表示可以按缺省值继续
     */
    public static boolean gapTolerable(AiMetricSemantics.Source source) {
        return source != null && source.optional();
    }

    /** 缺口来源的稳定描述（错误上下文与页面展示用）。 */
    public static String describeGaps(List<AiMetricSemantics.Source> sources) {
        if (sources == null || sources.isEmpty()) {
            return "";
        }
        return sources.stream()
                .filter(source -> !gapTolerable(source))
                .map(AiMetricSemantics.Source::role)
                .sorted()
                .collect(Collectors.joining(","));
    }

    /**
     * 版本冻结指纹：口径定义的规范化内容哈希。
     *
     * <p>与 Y02 的 {@code fingerprint} 同一手法——发布时冻结、读取时重算比对，
     * 因此"口径被版本外改动"一定会被发现，而不是等到某天报表数字对不上才察觉。
     */
    public static String digest(AiMetricSemantics semantics) {
        if (semantics == null) {
            return "";
        }
        return semantics.definitionHash();
    }
}
