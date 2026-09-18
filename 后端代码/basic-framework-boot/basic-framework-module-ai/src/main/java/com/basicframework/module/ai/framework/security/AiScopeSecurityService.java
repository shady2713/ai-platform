package com.basicframework.module.ai.framework.security;

import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.framework.security.core.util.SecurityFrameworkUtils;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.authorization.dto.AiAuthorizationDecisionDTO;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AI 应用端 scope 表达式（A05）：{@code @PreAuthorize("@aiScope.hasScope('REPORT','report-1','READ')")}。
 *
 * <p>语义与安全取向：
 * <ul>
 *   <li>判定完全委托 {@link AiAuthorizationService#authorize}（应用 + 主体范围 + 授权目录 + 动作白名单），
 *       表达式本身不缓存、不复制判定逻辑；</li>
 *   <li>**fail-closed**：没有登录用户、缺少 AI 会话信息、资源类型/动作不在白名单、
 *       判定过程抛错，一律返回 false（拒绝），绝不因为"取不到上下文"而放行；</li>
 *   <li>只读取 MEMBER 会话中的服务端字段（A05 Provider 写入），不读取任何请求参数。</li>
 * </ul>
 */
@Slf4j
@Component("aiScope")
@RequiredArgsConstructor
public class AiScopeSecurityService {

    private final AiAuthorizationService authorizationService;

    /** 是否具备对某类某资源执行某动作的授权。 */
    public boolean hasScope(String resourceType, String resourceKey, String action) {
        return decide(resourceType, resourceKey, action).isAllowed();
    }

    /** 是否至少具备任一动作的授权（用于读/执行共用入口）。 */
    public boolean hasAnyScope(String resourceType, String resourceKey, String... actions) {
        if (actions == null || actions.length == 0) {
            return false;
        }
        for (String action : actions) {
            if (decide(resourceType, resourceKey, action).isAllowed()) {
                return true;
            }
        }
        return false;
    }

    private AiAuthorizationDecisionDTO decide(String resourceType, String resourceKey, String action) {
        AiAuthorizationDecisionDTO denied =
                new AiAuthorizationDecisionDTO().setAllowed(false).setDenyReason("CONTEXT_UNAVAILABLE");
        AiResourceType type = AiResourceType.parse(resourceType).orElse(null);
        AiAction parsedAction = AiAction.parse(action).orElse(null);
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        if (type == null || parsedAction == null || loginUser == null || resourceKey == null || resourceKey.isBlank()) {
            return denied;
        }
        Long applicationId = parseApplicationId(
                loginUser.getInfo() == null
                        ? null
                        : loginUser.getInfo().get(AiUserSessionCommonApi.INFO_KEY_APPLICATION_ID));
        String subjectType = loginUser.getInfo() == null
                ? null
                : loginUser.getInfo().get(AiUserSessionCommonApi.INFO_KEY_SUBJECT_TYPE);
        String externalUserId = loginUser.getInfo() == null
                ? null
                : loginUser.getInfo().get(AiUserSessionCommonApi.INFO_KEY_EXTERNAL_USER_ID);
        if (applicationId == null || subjectType == null) {
            return denied;
        }
        try {
            return authorizationService.authorize(
                    applicationId, subjectType, externalUserId, type, resourceKey, parsedAction, List.of(resourceKey));
        } catch (RuntimeException exception) {
            // 判定异常按拒绝处理；只记录资源类型等非敏感信息，不记录异常正文
            log.warn("scope 判定异常，按拒绝处理：applicationId={}, resourceType={}", applicationId, type);
            return new AiAuthorizationDecisionDTO().setAllowed(false).setDenyReason("AUTHORIZATION_FAILED");
        }
    }

    private static Long parseApplicationId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** 供测试与文档引用：表达式支持的动作词表。 */
    static List<String> supportedActions() {
        List<String> actions = new ArrayList<>();
        for (AiAction action : AiAction.values()) {
            actions.add(action.name());
        }
        return List.copyOf(actions);
    }
}
