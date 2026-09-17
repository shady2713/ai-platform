package com.basicframework.module.ai.service.authorization;

import com.basicframework.module.ai.dal.dataobject.application.AiApplicationDO;
import com.basicframework.module.ai.dal.dataobject.grant.AiResourceGrantDO;
import com.basicframework.module.ai.dal.mysql.grant.AiResourceGrantMapper;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.authorization.dto.AiAuthorizationDecisionDTO;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import com.basicframework.module.ai.service.subject.dto.AiSubjectScopeDTO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 统一授权实现（A03）。
 *
 * <p>判定顺序（任一不满足即拒绝，且**不返回**任何范围信息）：
 * <ol>
 *   <li>参数完整（应用/主体/资源/动作都可解析）；</li>
 *   <li>应用存在且启用；</li>
 *   <li>主体存在且可用，且可信范围解析命中（空范围即拒绝）；</li>
 *   <li>该主体在**该资源类型**下的该资源标识有 ACTIVE 授权；</li>
 *   <li>动作在授权白名单内。</li>
 * </ol>
 * 每次判定都重新读取范围与授权并计算范围指纹，不缓存判定结果。
 */
@Service
@RequiredArgsConstructor
public class AiAuthorizationServiceImpl implements AiAuthorizationService {

    private final AiApplicationService applicationService;

    private final AiSubjectService subjectService;

    private final AiResourceGrantMapper grantMapper;

    @Override
    public AiAuthorizationDecisionDTO authorize(
            Long applicationId,
            String subjectType,
            String externalUserId,
            AiResourceType resourceType,
            String resourceKey,
            AiAction action,
            List<String> resourceHints) {
        AiAuthorizationDecisionDTO decision =
                base(applicationId, subjectType, externalUserId, resourceType, resourceKey, action);
        AiSubjectType subject = AiSubjectType.valueOf(subjectType);
        if (resourceType == null || action == null || resourceKey == null || resourceKey.isBlank()) {
            return deny(decision, "REQUEST_INVALID");
        }
        AiApplicationDO application = applicationService.getApplication(applicationId);
        if (application == null || !Boolean.TRUE.equals(application.getEnabled())) {
            return deny(decision, "APPLICATION_DISABLED");
        }
        AiSubjectScopeDTO scope = subjectService.resolveScope(applicationId, subject, externalUserId, resourceHints);
        if (scope.isDenied()) {
            return deny(decision, "SUBJECT_SCOPE_DENIED");
        }
        AiResourceGrantDO grant = grantMapper.selectGrant(
                applicationId,
                subjectType,
                scope.getExternalUserId() == null ? "" : scope.getExternalUserId(),
                resourceType.name(),
                resourceKey);
        if (grant == null || !AiResourceGrantDO.STATUS_ACTIVE.equals(grant.getStatus())) {
            // 不同资源类型、不同主体、不同应用的相同标识都到这里：不串权
            return deny(decision, "GRANT_NOT_FOUND");
        }
        Set<String> actions = parseActions(grant.getActions());
        if (!actions.contains(action.name())) {
            return deny(decision, "ACTION_NOT_GRANTED");
        }
        return decision.setAllowed(true)
                .setDenyReason(null)
                .setAuthzRevision(grant.getAuthzRevision())
                .setGrantedActions(actions)
                .setScopeFingerprint(fingerprint(scope, grant));
    }

    @Override
    public AiAuthorizationDecisionDTO reauthorizeHistorical(
            Long applicationId,
            String subjectType,
            String externalUserId,
            AiResourceType resourceType,
            String resourceKey,
            AiAction action,
            String expectedScopeFingerprint) {
        AiAuthorizationDecisionDTO decision =
                authorize(applicationId, subjectType, externalUserId, resourceType, resourceKey, action, List.of());
        if (!decision.isAllowed()) {
            return decision;
        }
        if (expectedScopeFingerprint == null || !expectedScopeFingerprint.equals(decision.getScopeFingerprint())) {
            // 范围收窄或授权版本变化：即使数据集仍获授权，也不得读取旧聚合
            return decision.setAllowed(false).setDenyReason("SCOPE_FINGERPRINT_CHANGED");
        }
        return decision;
    }

    /** 范围指纹：主体范围（组织/对象集合 + 来源 + 版本）与授权版本的稳定摘要。 */
    private static String fingerprint(AiSubjectScopeDTO scope, AiResourceGrantDO grant) {
        StringBuilder builder = new StringBuilder();
        scope.getOrganizationIds().stream()
                .sorted()
                .forEach(id -> builder.append("o:").append(id).append(';'));
        scope.getResourceKeys().stream()
                .sorted()
                .forEach(key -> builder.append("r:").append(key).append(';'));
        builder.append("src:").append(scope.getScopeSource()).append(';');
        builder.append("sv:").append(scope.getScopeVersion()).append(';');
        builder.append("gv:").append(grant.getAuthzRevision()).append(';');
        builder.append("grant:").append(grant.getResourceType()).append('/').append(grant.getResourceKey());
        return sha256(builder.toString());
    }

    private static Set<String> parseActions(String actions) {
        if (actions == null || actions.isBlank()) {
            return Set.of();
        }
        Set<String> parsed = new LinkedHashSet<>();
        Arrays.stream(actions.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .forEach(value -> AiAction.parse(value).ifPresent(action -> parsed.add(action.name())));
        return parsed;
    }

    private static AiAuthorizationDecisionDTO base(
            Long applicationId,
            String subjectType,
            String externalUserId,
            AiResourceType resourceType,
            String resourceKey,
            AiAction action) {
        return new AiAuthorizationDecisionDTO()
                .setApplicationId(applicationId)
                .setSubjectType(subjectType)
                .setExternalUserId(externalUserId)
                .setResourceType(resourceType == null ? null : resourceType.name())
                .setResourceKey(resourceKey)
                .setAction(action == null ? null : action.name())
                .setAllowed(false)
                .setGrantedActions(Set.of());
    }

    private static AiAuthorizationDecisionDTO deny(AiAuthorizationDecisionDTO decision, String reason) {
        return decision.setAllowed(false).setDenyReason(reason).setGrantedActions(Set.of());
    }

    private static String sha256(String value) {
        try {
            byte[] hashed = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
