package com.basicframework.module.ai.service.queryplan;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_AGGREGATION_ORDER_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_CALIBER_MISSING_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_FANOUT_UNSAFE_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_GAP_CLARIFICATION_REQUIRED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_PLAN_SELECTION_REQUIRED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_PLAN_SOURCE_NOT_DECLARED;

import com.basicframework.module.ai.domain.semantic.AiMetricSemantics;
import com.basicframework.module.ai.domain.semantic.AiMetricSemanticsFacts;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 跨源查询计划校验器（Y03）：把"选哪些来源、怎么聚合"变成可执行计划的**唯一**通道。
 *
 * <p>校验顺序固定（选择完整性 → 口径一致性 → 币种可加 → 扇出安全 → 聚合顺序），
 * 顺序本身是安全语义：先确认每个来源都被**显式**钉住（数据集版本 + 映射版本），
 * 再讨论口径是否一致，最后才允许进入"能不能相加"的判定。这样任何一个来源漏选或选错
 * 都会在口径讨论之前就被挡住，不会出现"按三个来源算完了才发现其中一个没钉版本"。
 *
 * <p>三类结论必须分清：
 * <ul>
 *   <li><b>拒绝</b>：来源未显式选择 / 口径冲突 / 币种无换算规则 / 扇出不安全 / 聚合顺序错误
 *       ——全部是稳定错误码，绝不"挑一个看起来对的"继续；</li>
 *   <li><b>需要澄清</b>（{@code AI_METRIC_GAP_CLARIFICATION_REQUIRED}）：
 *       口径本身合法但来源数据有缺口且未声明完整性策略——这是用户要补的信息，不是缺陷；</li>
 *   <li><b>通过</b>：产出带口径版本与计划哈希的 {@link CrossSourceQueryPlan}。</li>
 * </ul>
 *
 * <p>本类不执行查询，只做判定：真正的扇出防护还依赖执行侧按 {@code aggregationOrder}
 * 先聚合再关联，而本类保证"没声明先聚合"这件事进不了执行路径。
 */
public final class AiCrossSourceQueryPlanValidator {

    /** 跨源聚合的来源数上限（与口径定义的来源上限一致）。 */
    public static final int MAX_SOURCES = 16;

    /**
     * 校验跨源查询计划。
     *
     * @param planJson  模型/调用方给出的计划 JSON（结构化选择，不含 SQL）
     * @param semantics 已解析并核验的口径定义
     * @param gaps      实际存在数据缺口的来源角色（空集合表示无缺口）
     */
    public CrossSourceQueryPlan validate(String planJson, AiMetricSemantics semantics, Set<String> gaps) {
        if (semantics == null) {
            throw exception(AI_METRIC_CALIBER_MISSING_CONFLICT);
        }
        AiCrossSourcePlanRequest request = AiCrossSourcePlanRequest.parse(planJson);
        // 1) 选择完整性：每个声明的来源都必须显式给出数据集版本与映射版本
        List<AiMetricSemantics.Source> declared = resolveDeclaredSources(request, semantics);
        // 2) 口径一致性：单位 / 时区必须与口径一致（不做"按主来源对齐"的兜底）
        AiMetricSemanticsFacts.requireConsistentCaliber(semantics, declared);
        // 3) 币种：多币种且无换算规则 → 阻断（绝不静默相加）
        AiMetricSemanticsFacts.requireSummable(semantics, declared);
        // 4) 扇出：必须按各自主键粒度先聚合再关联
        requireFanoutSafe(request, declared);
        // 5) 聚合顺序：必须先聚合再关联，且覆盖全部来源
        requireAggregationOrder(request, declared);
        // 6) 缺口：有缺口且来源非可选 → 澄清（绝不按 0 静默补齐）
        requireGapPolicy(gaps, declared);
        return toPlan(request, semantics, declared);
    }

    /**
     * 计划选择的来源必须都在口径版本的来源声明内。
     *
     * <p>不在声明内即拒绝：口径版本是"哪些来源可以相加"的唯一事实，
     * 计划凭空引入一个来源会让"这条数字是怎么算出来的"无法按版本复现。
     */
    private static List<AiMetricSemantics.Source> resolveDeclaredSources(
            AiCrossSourcePlanRequest request, AiMetricSemantics semantics) {
        List<AiMetricSemantics.Source> declared = new ArrayList<>();
        for (AiCrossSourcePlanRequest.Selection selection : request.selections()) {
            AiMetricSemantics.Source source = semantics.sourceOf(selection.datasetCode(), selection.datasetVersion());
            if (source == null) {
                throw exception(AI_METRIC_PLAN_SOURCE_NOT_DECLARED);
            }
            // 映射版本也必须与口径声明一致：跨系统标识的判定依据不能由计划单方面改
            if (!source.mappingRevision().equals(selection.mappingRevision())) {
                throw exception(AI_METRIC_PLAN_SELECTION_REQUIRED);
            }
            declared.add(source);
        }
        if (declared.isEmpty()) {
            throw exception(AI_METRIC_PLAN_SELECTION_REQUIRED);
        }
        return declared;
    }

    /**
     * 扇出阻断（AT-034）：同一事实经多对多路径不得被算两次。
     *
     * <p>判据来自 {@link AiMetricSemanticsFacts#fanoutSafe}：每个来源都必须显式声明
     * 已按自己的主键粒度预聚合。只要有一个来源没声明，或粒度缺失，就无法证明关联不会
     * 放大行数——按不安全阻断，而不是"执行时小心点"。
     */
    private static void requireFanoutSafe(AiCrossSourcePlanRequest request, List<AiMetricSemantics.Source> declared) {
        for (AiCrossSourcePlanRequest.Selection selection : request.selections()) {
            // 必须拿**口径登记的**来源来比，而不是按计划重建一个：
            // 按计划重建会让"计划自称的粒度"自己等于自己，这道检查形同虚设。
            AiMetricSemantics.Source declaredSource = byRole(declared, selection.role());
            if (declaredSource == null) {
                throw exception(AI_METRIC_PLAN_SOURCE_NOT_DECLARED);
            }
            boolean preAggregated =
                    selection.preAggregated() && !selection.primaryKey().isEmpty();
            if (!AiMetricSemanticsFacts.fanoutSafe(declaredSource, declaredSource, preAggregated)) {
                throw exception(AI_METRIC_FANOUT_UNSAFE_CONFLICT);
            }
            if (!selection.primaryKey().equals(declaredSource.primaryKey())) {
                // 计划声称的粒度与口径登记的不一致：不能按计划自称的粒度放行
                throw exception(AI_METRIC_FANOUT_UNSAFE_CONFLICT);
            }
        }
        if (declared.size() > 1) {
            // 多来源关联：两个来源都按主键粒度预聚合后才不会互相放大行数
            for (int left = 0; left < declared.size(); left++) {
                for (int right = left + 1; right < declared.size(); right++) {
                    if (!AiMetricSemanticsFacts.fanoutSafe(declared.get(left), declared.get(right), true)) {
                        throw exception(AI_METRIC_FANOUT_UNSAFE_CONFLICT);
                    }
                }
            }
        }
    }

    /** 按角色定位口径登记的来源（未登记返回 null）。 */
    private static AiMetricSemantics.Source byRole(List<AiMetricSemantics.Source> sources, String role) {
        for (AiMetricSemantics.Source source : sources) {
            if (source.role().equals(role)) {
                return source;
            }
        }
        return null;
    }

    /** 聚合顺序必须恰好覆盖每个已选来源，且不得重复。 */
    private static void requireAggregationOrder(
            AiCrossSourcePlanRequest request, List<AiMetricSemantics.Source> declared) {
        List<String> order = request.aggregationOrder();
        Set<String> expected = new LinkedHashSet<>();
        declared.forEach(source -> expected.add(source.role()));
        if (!new LinkedHashSet<>(order).equals(expected) || order.size() != expected.size()) {
            throw exception(AI_METRIC_AGGREGATION_ORDER_CONFLICT);
        }
    }

    /**
     * 缺口策略：存在缺口时，只有显式声明为可选的来源才能按缺省值继续。
     *
     * <p>其余情况必须追问（{@code AI_METRIC_GAP_CLARIFICATION_REQUIRED}）：
     * "没有回款记录"和"回款金额是 0"在报表上是两件完全不同的事。
     */
    private static void requireGapPolicy(Set<String> gaps, List<AiMetricSemantics.Source> declared) {
        if (gaps == null || gaps.isEmpty()) {
            return;
        }
        for (String role : gaps) {
            // 按角色找不到声明来源时 byRole 返回 null，gapTolerable(null)=false → 澄清
            if (!AiMetricSemanticsFacts.gapTolerable(byRole(declared, role))) {
                throw exception(AI_METRIC_GAP_CLARIFICATION_REQUIRED);
            }
        }
    }

    private static CrossSourceQueryPlan toPlan(
            AiCrossSourcePlanRequest request, AiMetricSemantics semantics, List<AiMetricSemantics.Source> declared) {
        List<CrossSourceQueryPlan.SourceSelection> selections = new ArrayList<>();
        for (AiCrossSourcePlanRequest.Selection selection : request.selections()) {
            selections.add(new CrossSourceQueryPlan.SourceSelection(
                    selection.datasetCode(),
                    selection.datasetVersion(),
                    selection.mappingRevision(),
                    selection.role(),
                    selection.primaryKey(),
                    selection.preAggregated()));
        }
        return new CrossSourceQueryPlan(
                semantics.metricCode(),
                request.semanticsRevision(),
                semantics.definitionHash(),
                selections,
                request.aggregationOrder(),
                semantics.currency(),
                semantics.timezone(),
                semantics.timeWindow(),
                semantics.unit(),
                AiCrossSourcePlanRequest.planHash(request, semantics));
    }
}
