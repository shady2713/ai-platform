package com.basicframework.module.ai.service.query.crosssource;

import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceCallerRole;
import java.util.Set;

/**
 * 一次跨源响应请求的入参（Y07）。
 *
 * <p>刻意<b>不</b>包含"计划声明了哪些来源"：判定范围必须来自执行台账，
 * 否则调用方可以少报来源、把无权来源挤出判定范围，再拿一个看起来干净的合计。
 * 来源清单由 {@link AiCrossSourceMergeService} 从台账读出，调用方无法干预。
 *
 * @param executionKey       跨源执行幂等键（定位台账里那一次执行）
 * @param applicationId      应用编号（A03 授权上下文）
 * @param subjectType        主体类型（APP/USER）
 * @param externalUserId     可信外部用户标识
 * @param callerRoles        调用方在本应用下的跨源角色，决定可见字段
 * @param previouslySeenRoles 调用方<b>此前已经拿到过合计</b>覆盖的来源角色，
 *                           用于识别"差额可解"（历史覆盖范围 ⊋ 本次覆盖范围）
 */
public record CrossSourceMergeQuery(
        String executionKey,
        Long applicationId,
        String subjectType,
        String externalUserId,
        Set<CrossSourceCallerRole> callerRoles,
        Set<String> previouslySeenRoles) {

    public CrossSourceMergeQuery {
        callerRoles = callerRoles == null ? Set.of() : Set.copyOf(callerRoles);
        previouslySeenRoles = previouslySeenRoles == null ? Set.of() : Set.copyOf(previouslySeenRoles);
    }
}
