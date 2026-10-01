package com.basicframework.module.ai.service.authorization.crosssource;

import java.util.List;
import java.util.Map;

/**
 * 跨源授权事实的解析端口（Y07）。
 *
 * <p>Y05 的 {@link AiCrossSourceAuthorizationJudge} 只接受"已经判定好的事实"，
 * 从不自己查授权。生产链路里必须有人把**当前真实授权**（A03）翻译成事实，
 * 否则 HTTP 入口要么让调用方自报事实（等于让调用方自证有权），要么直接拼 map
 * （授权口径散落到 Controller 层）。本端口就是那个"有人"。
 *
 * <p>刻意做成<b>窄接口</b>而不是直接依赖具体实现：判定算法（Y05）不关心授权怎么来，
 * 验收 IT 也不必为了测判定而先造出一整套应用、主体与授权记录。
 */
public interface CrossSourceAccessFactsResolver {

    /**
     * 解析逐角色的授权事实。
     *
     * @param query 主体上下文与来源绑定（执行台账里记录的角色 + 数据集）
     * @return 角色 → 事实。实现必须对<b>查不到授权</b>的来源返回
     *         {@code systemAuthorized=false}，而不是省略该角色：
     *         省略会让 {@code judge} 抛"入参不合法"而不是"无权"，
     *         两种拒绝的处置动作不同（补参数 vs 申请授权），不能混。
     */
    Map<String, CrossSourceAccessFacts> resolve(CrossSourceFactsQuery query);

    /**
     * 主体上下文 + 来源绑定。
     *
     * @param applicationId  应用编号
     * @param subjectType    主体类型（APP/USER）
     * @param externalUserId 可信外部用户标识
     * @param bindings      台账里记录的来源绑定（角色 + 数据集编号）
     */
    record CrossSourceFactsQuery(
            Long applicationId, String subjectType, String externalUserId, List<SourceBinding> bindings) {

        public CrossSourceFactsQuery {
            bindings = bindings == null ? List.of() : List.copyOf(bindings);
        }
    }

    /**
     * 一个来源与数据集的绑定（来自执行台账，不是用户输入）。
     *
     * @param role        来源角色
     * @param datasetCode 数据集编号
     */
    record SourceBinding(String role, String datasetCode) {}
}
