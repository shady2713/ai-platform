package com.basicframework.module.ai.service.authorization.crosssource;

import java.util.Locale;

/**
 * 跨源响应的授权完整性口径（Y07 后端侧）：**跨源合并响应恒带**这个口径。
 *
 * <p>本类存在的唯一理由是消除 Y05 §9.1 记录的 fail-open 缺口：在此之前后端零产出
 * {@code crossSourceIntegrity}，前端把它当可选字段，缺失时按普通表格渲染全部数字。
 * 本类把"这份结果里有没有你没权看的来源"变成响应里**不可省略**的字段，
 * 与前端的 {@code CrossSourceIntegrity} 排他联合一一对应：
 *
 * <pre>
 *   COMPLETE  全部来源均在授权范围内   —— 不带 reason（没有理由可编）
 *   PARTIAL   部分来源不可出具         —— 必带 reason
 *   WITHHELD  整份结果不可出具         —— 必带 reason
 * </pre>
 *
 * <p>三个状态名与前端 {@code cross-source-integrity.ts} 的字面量**逐字一致**，
 * 不得另造：两边各写一套名字会让前端在解析期就把合法响应判成"契约漂移"，
 * 而解析期拒绝的降级路径是不渲染——明明有权看却什么都看不到，比泄露更糟。
 *
 * <p><b>缺失即 {@code WITHHELD}</b>：规范构造器把任何无法识别或未提供的口径归一到
 * {@code WITHHELD}，而不是默认成 {@code COMPLETE}。凭空替后端宣布"你有权看"
 * 与 Y05 的 fail-closed 原则直接冲突——把默认值选成放行，等于把一次代码缺陷
 * 变成一次真实的越权披露。
 *
 * <p>与 Y04 的 {@code completeness} 是<b>两个维度</b>，不可互相替代：
 * 本口径回答"有没有来源你无权"（授权），{@code completeness} 回答
 * "口径内有没有来源没跑成"（技术）。混成一个字段会把授权拒绝显示成"结果可能不完整"，
 * 用户会以为是临时故障反复重试，而重试永远不会成功。
 */
public record CrossSourceIntegrity(String state, String reason) {

    /** 全部来源均在授权范围内。**不得携带 reason**：放行没有理由可编。 */
    public static final String STATE_COMPLETE = "COMPLETE";

    /** 部分来源不可出具，结果仍然可读。必带 reason。 */
    public static final String STATE_PARTIAL = "PARTIAL";

    /** 整份结果不可出具。必带 reason。 */
    public static final String STATE_WITHHELD = "WITHHELD";

    /**
     * 字段缺失时的规定理由（静态，不含任何插参）。
     *
     * <p>不写"字段缺失"这类实现细节给最终用户看，也不写被拒来源的标识：
     * 消息只说"这份结果没给出口径，因此按未出具处理"，
     * 让调用方知道该找谁，而不是让它知道自己缺了哪一项授权。
     */
    public static final String MISSING_REASON = "本次跨源合并未给出授权完整性口径，按未出具处理。";

    /**
     * 规范构造器：<b>fail-closed 归一</b>。
     *
     * <p>三条归一规则，都只往"更不出具"的方向走，绝不往"更放行"的方向走：
     * <ol>
     *   <li>{@code state} 为 null、空白或不在三个字面量之内 → {@code WITHHELD}；</li>
     *   <li>{@code COMPLETE} 必须不带 {@code reason}（放行没有理由），
     *       带了也强制抹掉，避免拼装代码为一个已放行的结果编造理由；</li>
     *   <li>{@code PARTIAL}/{@code WITHHELD} 必须带非空 {@code reason}，
     *       缺失时退回 {@link #MISSING_REASON} 静态文案。</li>
     * </ol>
     *
     * <p>第 2 条不是洁癖：前端的排他联合里 {@code COMPLETE} 变体
     * <b>没有</b> {@code reason} 字段可填，两侧对不上会让前端解析失败。
     * 第 3 条同理：前端对 {@code reason} 做必填文本校验，
     * 缺失会让整块降级成"不渲染"——明明该告知用户"结果没出具"，
     * 却退化成一句无法解释的空白。
     */
    public CrossSourceIntegrity {
        state = normalizeState(state);
        if (STATE_COMPLETE.equals(state)) {
            reason = null;
        } else if (reason == null || reason.isBlank()) {
            reason = MISSING_REASON;
        }
    }

    /**
     * 全部来源均有权（不放行）。刻意不提供"带理由的 COMPLETE"入口。
     *
     * <p>{@code complete()} 是构造 {@code COMPLETE} 的<b>唯一</b>公开路径，
     * 因此调用方无法为一次已放行的结果编造理由。
     */
    public static CrossSourceIntegrity complete() {
        return new CrossSourceIntegrity(STATE_COMPLETE, null);
    }

    /**
     * 部分来源不可出具。
     *
     * @param reason 静态理由（必填；空白时按缺失处理并归一为 {@code WITHHELD}）
     */
    public static CrossSourceIntegrity partial(String reason) {
        return new CrossSourceIntegrity(STATE_PARTIAL, reason);
    }

    /**
     * 整份结果不可出具。
     *
     * @param reason 静态理由（必填；空白时退回 {@link #MISSING_REASON}）
     */
    public static CrossSourceIntegrity withheld(String reason) {
        return new CrossSourceIntegrity(STATE_WITHHELD, reason);
    }

    /**
     * <b>字段缺失时的唯一规定行为</b>：按 {@code WITHHELD} 处理，绝不按 {@code COMPLETE}。
     *
     * <p>这是 Y07 的核心：调用方拿不到口径时走这条路，而不是走"看起来完整"。
     * {@code reason} 走静态文案，保证"缺字段"这条路径上也不泄露任何被拒对象标识。
     */
    public static CrossSourceIntegrity missing() {
        return new CrossSourceIntegrity(STATE_WITHHELD, MISSING_REASON);
    }

    /**
     * 从任意（可能为空的）口径值解析出可用口径，<b>永不返回 null</b>。
     *
     * <p>这是"响应恒带口径"在代码里的落点：服务层与 VO 转换层都走这里，
     * 因此不存在"某个分支忘了设置口径"的可能。null 输入按 {@link #missing()} 处理。
     */
    public static CrossSourceIntegrity ofNullable(CrossSourceIntegrity declared) {
        return declared == null ? missing() : declared;
    }

    /** 该口径是否要求"一个数字都不渲染"。 */
    public boolean rendersNothing() {
        return STATE_WITHHELD.equals(state);
    }

    /** 该口径是否放行全部来源（无理由、无数字）。 */
    public boolean disclosesAll() {
        return STATE_COMPLETE.equals(state);
    }

    /** 状态归一：null、空白与未知字面量一律落到 {@code WITHHELD}（只降不放）。 */
    private static String normalizeState(String raw) {
        if (raw == null) {
            return STATE_WITHHELD;
        }
        String trimmed = raw.trim().toUpperCase(Locale.ROOT);
        return switch (trimmed) {
            case STATE_COMPLETE, STATE_PARTIAL, STATE_WITHHELD -> trimmed;
            default -> STATE_WITHHELD;
        };
    }
}
