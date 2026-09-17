package com.basicframework.module.ai.service.subject;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;

import com.basicframework.module.ai.dal.dataobject.subject.AiSubjectDO;
import com.basicframework.module.ai.dal.mysql.subject.AiSubjectMapper;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.identity.SubjectScope;
import com.basicframework.module.ai.domain.identity.SubjectScopeResolver;
import com.basicframework.module.ai.service.subject.dto.AiSubjectScopeDTO;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 外部主体与范围实现（A02）。
 *
 * <p>DENY 是默认结果：主体不存在/停用、解析器未装配、解析器失败、结果为空集合或超过预算，
 * 全部返回 {@code denied=true} 并带稳定原因码——**任何一条路径都不会退化成"访问全部数据"**。
 */
@Slf4j
@Service
public class AiSubjectServiceImpl implements AiSubjectService {

    /** 单主体范围预算：超过即视为异常（正常企业授权不会把全量组织逐个下发）。 */
    static final int SCOPE_BUDGET = 10_000;

    private final AiSubjectMapper subjectMapper;

    private final ObjectProvider<SubjectScopeResolver> scopeResolverProvider;

    public AiSubjectServiceImpl(
            AiSubjectMapper subjectMapper, ObjectProvider<SubjectScopeResolver> scopeResolverProvider) {
        this.subjectMapper = subjectMapper;
        this.scopeResolverProvider = scopeResolverProvider;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiSubjectDO syncSubject(
            Long applicationId,
            AiSubjectType subjectType,
            String externalUserId,
            String displayName,
            String scopeSource,
            Long scopeVersion) {
        validateIdentity(applicationId, subjectType, externalUserId);
        if (!StringUtils.hasText(scopeSource)) {
            throw exception(AI_REQUEST_INVALID);
        }
        String normalizedExternalUserId = subjectType == AiSubjectType.APP ? "" : externalUserId;
        long version = scopeVersion == null || scopeVersion < 1 ? 1L : scopeVersion;
        AiSubjectDO existing =
                subjectMapper.selectByIdentity(applicationId, subjectType.name(), normalizedExternalUserId);
        if (existing == null) {
            AiSubjectDO created = new AiSubjectDO()
                    .setApplicationId(applicationId)
                    .setSubjectType(subjectType.name())
                    .setExternalUserId(normalizedExternalUserId)
                    .setDisplayName(displayName == null ? "" : displayName)
                    .setStatus(AiSubjectDO.STATUS_ACTIVE)
                    .setScopeSource(scopeSource)
                    .setScopeVersion(version)
                    .setVersion(0);
            subjectMapper.insert(created);
            return created;
        }
        // 已存在：只更新可变字段；范围版本只允许前进，避免用旧版本覆盖新范围
        long nextScopeVersion = Math.max(version, existing.getScopeVersion() == null ? 1L : existing.getScopeVersion());
        AiSubjectDO update = new AiSubjectDO()
                .setId(existing.getId())
                .setDisplayName(displayName == null ? "" : displayName)
                .setStatus(AiSubjectDO.STATUS_ACTIVE)
                .setScopeSource(scopeSource)
                .setScopeVersion(nextScopeVersion)
                .setVersion(existing.getVersion() + 1);
        subjectMapper.updateWithVersion(update, existing.getVersion());
        return subjectMapper.selectById(existing.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void disableSubject(Long applicationId, AiSubjectType subjectType, String externalUserId) {
        validateIdentity(applicationId, subjectType, externalUserId);
        String normalizedExternalUserId = subjectType == AiSubjectType.APP ? "" : externalUserId;
        AiSubjectDO existing =
                subjectMapper.selectByIdentity(applicationId, subjectType.name(), normalizedExternalUserId);
        if (existing == null) {
            return;
        }
        subjectMapper.updateWithVersion(
                new AiSubjectDO()
                        .setId(existing.getId())
                        .setStatus(AiSubjectDO.STATUS_DISABLED)
                        .setVersion(existing.getVersion() + 1),
                existing.getVersion());
    }

    @Override
    public Optional<AiSubjectDO> findActiveSubject(
            Long applicationId, AiSubjectType subjectType, String externalUserId) {
        if (applicationId == null || subjectType == null) {
            return Optional.empty();
        }
        String normalizedExternalUserId = subjectType == AiSubjectType.APP ? "" : externalUserId;
        if (subjectType == AiSubjectType.USER && !StringUtils.hasText(normalizedExternalUserId)) {
            return Optional.empty();
        }
        AiSubjectDO subject =
                subjectMapper.selectByIdentity(applicationId, subjectType.name(), normalizedExternalUserId);
        return subject != null && AiSubjectDO.STATUS_ACTIVE.equals(subject.getStatus())
                ? Optional.of(subject)
                : Optional.empty();
    }

    @Override
    public AiSubjectScopeDTO resolveScope(
            Long applicationId, AiSubjectType subjectType, String externalUserId, List<String> resourceHints) {
        Optional<AiSubjectDO> subject = findActiveSubject(applicationId, subjectType, externalUserId);
        if (subject.isEmpty()) {
            return deny(applicationId, subjectType, externalUserId, "SUBJECT_NOT_FOUND");
        }
        AiSubjectDO identity = subject.get();
        SubjectScopeResolver resolver = scopeResolverProvider.getIfAvailable();
        if (resolver == null) {
            // 没有可信解析器时一律 DENY：宁可拒绝，也不把"未配置"当成"全部可见"
            return deny(applicationId, subjectType, externalUserId, "RESOLVER_UNAVAILABLE");
        }
        SubjectScope scope;
        try {
            Optional<SubjectScope> resolved = resolver.resolve(new SubjectScopeResolver.SubjectScopeRequest(
                    applicationId,
                    subjectType,
                    identity.getExternalUserId(),
                    identity.getScopeSource(),
                    identity.getScopeVersion() == null ? 0L : identity.getScopeVersion(),
                    resourceHints == null ? List.of() : List.copyOf(resourceHints)));
            if (resolved == null || resolved.isEmpty()) {
                return deny(applicationId, subjectType, externalUserId, "RESOLVER_FAILED");
            }
            scope = resolved.get();
        } catch (RuntimeException exception) {
            // 解析失败不记录异常正文（可能含业务数据），只记录来源与主体标识
            log.warn("主体范围解析失败，按 DENY 处理：applicationId={}, subjectType={}", applicationId, subjectType);
            return deny(applicationId, subjectType, externalUserId, "RESOLVER_FAILED");
        }
        if (scope.isDeny()) {
            return deny(applicationId, subjectType, externalUserId, "EMPTY_SCOPE");
        }
        if (scope.organizationIds().size() > SCOPE_BUDGET
                || scope.resourceKeys().size() > SCOPE_BUDGET) {
            return deny(applicationId, subjectType, externalUserId, "SCOPE_OVER_BUDGET");
        }
        return new AiSubjectScopeDTO()
                .setApplicationId(applicationId)
                .setSubjectType(subjectType.name())
                .setExternalUserId(identity.getExternalUserId())
                .setDenied(false)
                .setOrganizationIds(scope.organizationIds())
                .setResourceKeys(scope.resourceKeys())
                .setScopeSource(scope.source() == null ? identity.getScopeSource() : scope.source())
                .setScopeVersion(scope.version());
    }

    private static AiSubjectScopeDTO deny(
            Long applicationId, AiSubjectType subjectType, String externalUserId, String reason) {
        return new AiSubjectScopeDTO()
                .setApplicationId(applicationId)
                .setSubjectType(subjectType == null ? null : subjectType.name())
                .setExternalUserId(externalUserId)
                .setDenied(true)
                .setDenyReason(reason)
                .setOrganizationIds(java.util.Set.of())
                .setResourceKeys(java.util.Set.of())
                .setScopeVersion(0L);
    }

    private static void validateIdentity(Long applicationId, AiSubjectType subjectType, String externalUserId) {
        if (applicationId == null || subjectType == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (subjectType == AiSubjectType.USER && !StringUtils.hasText(externalUserId)) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (externalUserId != null && externalUserId.length() > 128) {
            throw exception(AI_REQUEST_INVALID);
        }
    }
}
