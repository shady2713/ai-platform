package com.basicframework.module.ai.domain.identity;

import java.util.List;
import java.util.Optional;

/**
 * 可信范围解析器（A02）：由业务侧（或企业授权服务）实现，把外部主体映射为本平台可用的范围白名单。
 *
 * <p>契约：
 * <ul>
 *   <li>输入只有服务端已知的身份事实（应用、主体类型、可信 externalUserId）与业务侧自己的查询参数，
 *       **不含**请求体里的 roles/deptIds 等客户端可伪造字段；</li>
 *   <li>实现必须返回"业务侧当前真实范围"；无法判定时返回 {@link Optional#empty()}（调用方按 DENY 处理），
 *       不得返回"全部"；</li>
 *   <li>实现抛异常同样按 DENY 处理：解析器失败绝不放行为全部数据。</li>
 * </ul>
 */
@FunctionalInterface
public interface SubjectScopeResolver {

    /** 解析失败约定的返回：空 Optional。 */
    Optional<SubjectScope> resolve(SubjectScopeRequest request);

    /**
     * 解析输入（A02 冻结）：只包含服务端可信字段与业务侧自有的查询键。
     *
     * @param applicationId  应用编号
     * @param subjectType    主体类型
     * @param externalUserId 可信外部用户标识（APP 主体为空串）
     * @param scopeSource    范围来源标识
     * @param scopeVersion   中台记录的范围版本（实现可用于判断是否需要重新拉取）
     * @param resourceHints  业务侧对象提示（例如待访问的报表键），由调用方在服务端拼装
     */
    record SubjectScopeRequest(
            Long applicationId,
            AiSubjectType subjectType,
            String externalUserId,
            String scopeSource,
            long scopeVersion,
            List<String> resourceHints) {}
}
