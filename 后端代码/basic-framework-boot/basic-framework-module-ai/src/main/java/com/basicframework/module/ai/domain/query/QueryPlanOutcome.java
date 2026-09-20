package com.basicframework.module.ai.domain.query;

import java.util.List;

/**
 * 查询规划结果（D05）：运行层必须区分"可执行计划"与"需要澄清"。
 *
 * <p>为什么不把澄清做成异常：澄清是**正常结果**（用户问题本身需要补全），
 * 调用方要把它当成一轮对话继续，而不是当失败重试；反之"校验失败"才是错误。
 * 执行器只接受 {@link Plan}，因此"模型说了一句可能是销售额"永远进不了执行路径。
 */
public sealed interface QueryPlanOutcome {

    /** 结果类型名（协议层与运行层共用的稳定词表）。 */
    String kind();

    /** 可执行计划。 */
    record Plan(ValidatedQueryPlan plan) implements QueryPlanOutcome {

        @Override
        public String kind() {
            return "PLAN";
        }
    }

    /**
     * 需要澄清（或明确说明不在支持范围内）。
     *
     * @param question   面向用户的追问（不含内部字段名与权限码以外的实现细节）
     * @param candidates 有限候选（可为空；只来自已授权目录）
     * @param reason     稳定原因（AMBIGUOUS/UNSUPPORTED/OUT_OF_SCOPE）
     */
    record Clarification(String question, List<Candidate> candidates, String reason) implements QueryPlanOutcome {

        /** 原因：口径/字段有歧义，需要用户选择。 */
        public static final String REASON_AMBIGUOUS = "AMBIGUOUS";

        /** 原因：问题本身不受支持（例如要求 SQL、要求全库扫描）。 */
        public static final String REASON_UNSUPPORTED = "UNSUPPORTED";

        /** 原因：超出当前数据集/服务的范围。 */
        public static final String REASON_OUT_OF_SCOPE = "OUT_OF_SCOPE";

        public Clarification {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        @Override
        public String kind() {
            return "CLARIFICATION";
        }
    }

    /** 澄清候选：只允许已授权的逻辑码与展示名。 */
    record Candidate(String code, String label) {}
}
