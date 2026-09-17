package com.basicframework.module.ai.service.authorization;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_RESOURCE_GRANT_DUPLICATE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_RESOURCE_GRANT_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.grant.AiResourceGrantDO;
import com.basicframework.module.ai.dal.mysql.grant.AiResourceGrantMapper;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 资源授权目录管理实现（A03）：动作白名单校验、唯一性、CAS 与撤销即失效。 */
@Service
@RequiredArgsConstructor
public class AiResourceGrantServiceImpl implements AiResourceGrantService {

    private final AiResourceGrantMapper grantMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createGrant(
            Long applicationId,
            String subjectType,
            String externalUserId,
            String resourceType,
            String resourceKey,
            Set<String> actions) {
        validate(applicationId, subjectType, externalUserId, resourceType, resourceKey, actions);
        String normalizedExternalUserId =
                AiSubjectType.APP.name().equals(subjectType) ? "" : (externalUserId == null ? "" : externalUserId);
        if (grantMapper.selectGrant(applicationId, subjectType, normalizedExternalUserId, resourceType, resourceKey)
                != null) {
            throw exception(AI_RESOURCE_GRANT_DUPLICATE);
        }
        AiResourceGrantDO grant = new AiResourceGrantDO()
                .setApplicationId(applicationId)
                .setSubjectType(subjectType)
                .setExternalUserId(normalizedExternalUserId)
                .setResourceType(resourceType)
                .setResourceKey(resourceKey)
                .setActions(normalizeActions(actions))
                .setStatus(AiResourceGrantDO.STATUS_ACTIVE)
                .setAuthzRevision(1L)
                .setVersion(0);
        grantMapper.insert(grant);
        return grant.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateGrant(Long id, Integer version, Set<String> actions) {
        AiResourceGrantDO existing = validateGrantExists(id);
        requireVersion(version);
        if (actions == null || actions.isEmpty()) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiResourceGrantDO update = new AiResourceGrantDO()
                .setId(id)
                .setActions(normalizeActions(actions))
                .setStatus(AiResourceGrantDO.STATUS_ACTIVE)
                // 授权内容变化即递增授权版本：旧指纹失效，历史产物必须重新鉴权
                .setAuthzRevision(existing.getAuthzRevision() + 1)
                .setVersion(version + 1);
        if (grantMapper.updateWithVersion(update, version) == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeGrant(Long id, Integer version) {
        AiResourceGrantDO existing = validateGrantExists(id);
        requireVersion(version);
        AiResourceGrantDO update = new AiResourceGrantDO()
                .setId(id)
                .setStatus(AiResourceGrantDO.STATUS_REVOKED)
                .setAuthzRevision(existing.getAuthzRevision() + 1)
                .setVersion(version + 1);
        if (grantMapper.updateWithVersion(update, version) == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    public AiResourceGrantDO getGrant(Long id) {
        return validateGrantExists(id);
    }

    @Override
    public PageResult<AiResourceGrantDO> getGrantPage(
            PageParam pageParam, Long applicationId, String subjectType, String externalUserId, String resourceType) {
        return grantMapper.selectPage(pageParam, applicationId, subjectType, externalUserId, resourceType);
    }

    private AiResourceGrantDO validateGrantExists(Long id) {
        AiResourceGrantDO grant = id == null ? null : grantMapper.selectById(id);
        if (grant == null) {
            throw exception(AI_RESOURCE_GRANT_NOT_FOUND);
        }
        return grant;
    }

    private static void validate(
            Long applicationId,
            String subjectType,
            String externalUserId,
            String resourceType,
            String resourceKey,
            Set<String> actions) {
        if (applicationId == null
                || AiSubjectType.parse(subjectType).isEmpty()
                || AiResourceType.parse(resourceType).isEmpty()
                || !StringUtils.hasText(resourceKey)
                || resourceKey.length() > 128
                || actions == null
                || actions.isEmpty()) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (AiSubjectType.USER.name().equals(subjectType) && !StringUtils.hasText(externalUserId)) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (AiSubjectType.APP.name().equals(subjectType) && StringUtils.hasText(externalUserId)) {
            // APP 主体不接受 externalUserId：避免把应用身份伪装成某个用户
            throw exception(AI_REQUEST_INVALID);
        }
        // 只接受白名单动作，未知动作直接拒绝
        normalizeActions(actions);
    }

    private static String normalizeActions(Set<String> actions) {
        List<String> normalized = actions.stream()
                .map(action -> AiAction.parse(action).orElseThrow(() -> exception(AI_REQUEST_INVALID)))
                .map(Enum::name)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        if (normalized.isEmpty()) {
            throw exception(AI_REQUEST_INVALID);
        }
        return String.join(",", normalized);
    }

    private static void requireVersion(Integer version) {
        if (version == null || version < 0) {
            throw exception(AI_REQUEST_INVALID);
        }
    }
}
