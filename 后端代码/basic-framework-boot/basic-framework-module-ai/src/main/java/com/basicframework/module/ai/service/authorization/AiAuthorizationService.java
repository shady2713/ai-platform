package com.basicframework.module.ai.service.authorization;

import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.service.authorization.dto.AiAuthorizationDecisionDTO;
import java.util.List;

/**
 * 统一授权入口（A03）：{@code authorize(主体上下文, 动作, 资源)}。
 *
 * <p>判定要素缺一不可：应用可用、主体可用、主体范围命中、资源授权命中且动作在白名单内。
 * 判定不使用跨请求缓存——每次调用都读取当前版本；主体或授权被撤销后 {@code authzRevision} 递增，
 * 旧结果不会被复用（"先撤销、后放行"不可能发生）。
 */
public interface AiAuthorizationService {

    /**
     * 判定单次访问。
     *
     * @param applicationId  应用编号
     * @param subjectType    主体类型（APP/USER）
     * @param externalUserId 可信外部用户标识（APP 主体为空）
     * @param resourceType   资源类型
     * @param resourceKey    资源标识
     * @param action         动作
     * @param resourceHints  业务侧对象提示（交给可信范围解析器）
     */
    AiAuthorizationDecisionDTO authorize(
            Long applicationId,
            String subjectType,
            String externalUserId,
            AiResourceType resourceType,
            String resourceKey,
            AiAction action,
            List<String> resourceHints);

    /**
     * 历史资源再鉴权（A03）：用于读取此前的产物（聚合/报表/文件）前重新判定。
     *
     * <p>返回的 {@code scopeFingerprint} 若与产物生成时记录的指纹不同，调用方必须拒绝读取旧产物。
     */
    AiAuthorizationDecisionDTO reauthorizeHistorical(
            Long applicationId,
            String subjectType,
            String externalUserId,
            AiResourceType resourceType,
            String resourceKey,
            AiAction action,
            String expectedScopeFingerprint);
}
