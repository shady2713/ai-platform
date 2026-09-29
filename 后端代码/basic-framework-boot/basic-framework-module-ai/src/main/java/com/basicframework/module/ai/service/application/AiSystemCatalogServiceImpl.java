package com.basicframework.module.ai.service.application;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_APPLICATION_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_SYSTEM_CATALOG_BUDGET_EXCEEDED;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.application.AiApplicationDO;
import com.basicframework.module.ai.dal.dataobject.federation.AiSubjectFederationDO;
import com.basicframework.module.ai.dal.dataobject.grant.AiResourceGrantDO;
import com.basicframework.module.ai.dal.mysql.grant.AiResourceGrantMapper;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.service.application.dto.AiSystemCatalogDTO;
import com.basicframework.module.ai.service.application.dto.AiSystemCatalogQueryDTO;
import com.basicframework.module.ai.service.application.dto.AiSystemEntryDTO;
import com.basicframework.module.ai.service.application.dto.AiSystemScopeDTO;
import com.basicframework.module.ai.service.authorization.AiSubjectFederationService;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import com.basicframework.module.ai.service.subject.dto.AiSubjectScopeDTO;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 多系统授权发现实现（Y01）。
 *
 * <p>判定顺序（每一步失败都是"不出现"或"整目录拒绝"，绝不退化成"可见全部"）：
 * <ol>
 *   <li>入参形状（应用、主体类型、USER 必须有外部用户标识）→ 否则 400 语义；</li>
 *   <li>当前应用必须存在且启用；</li>
 *   <li><b>当前主体的身份事实</b>：主体 ACTIVE → A02 范围解析非空（否则整个目录拒绝，与
 *       "主体未登记"同形：不区分原因，避免枚举主体）；</li>
 *   <li>逐系统读取 ACTIVE 授权（精确匹配应用 + 主体类型 + 外部用户标识 + 状态，分页且有预算，
 *       超预算直接拒绝而不是返回部分目录）；至少一条可用授权才成为目录条目；</li>
 *   <li>其它系统只能通过**已批准**的联邦映射进入（{@code AiSubjectFederationService}），
 *       目标应用/主体/范围/授权任一不满足就跳过该系统。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AiSystemCatalogServiceImpl implements AiSystemCatalogService {

    /** 单系统授权条数预算：超过即拒绝返回目录，避免"看起来完整"的部分目录。 */
    static final int GRANT_BUDGET = 1_000;

    /** 授权读取分页大小（PageParam 上限为 200）。 */
    static final int GRANT_PAGE_SIZE = 200;

    private final AiApplicationService applicationService;

    private final AiSubjectService subjectService;

    private final AiSubjectFederationService federationService;

    private final AiResourceGrantMapper grantMapper;

    @Override
    public AiSystemCatalogDTO discover(AiSystemCatalogQueryDTO query) {
        Identity identity = validate(query);
        requireEnabledApplication(identity.applicationId());

        Optional<Probe> current = probe(identity);
        if (current.isEmpty()) {
            // 主体不可用或范围解析为空：整个目录拒绝（与"主体未登记"同形）
            return denied(identity);
        }
        List<AiSystemEntryDTO> entries = new ArrayList<>();
        if (hasUsableScope(current.get())) {
            entries.add(entry(current.get(), true, null, null));
        }
        entries.addAll(federatedEntries(identity));
        return build(identity, entries);
    }

    /** 已批准联邦映射指向的系统条目：按系统标识升序，同一系统只取编号最小的映射。 */
    private List<AiSystemEntryDTO> federatedEntries(Identity identity) {
        List<AiSubjectFederationDO> links = federationService.listApprovedTargets(
                identity.applicationId(), identity.subjectType(), identity.externalUserId());
        if (links.isEmpty()) {
            return List.of();
        }
        List<AiSystemEntryDTO> federated = new ArrayList<>();
        Set<String> seenSystemCodes = new LinkedHashSet<>();
        for (AiSubjectFederationDO link : links) {
            Identity target = identity(
                    link.getTargetApplicationId(), link.getTargetSubjectType(), link.getTargetExternalUserId());
            if (target == null) {
                // 映射行是服务端写入的事实；类型不可解析视为脏数据，跳过而不是猜测
                continue;
            }
            Optional<Probe> probe = probe(target);
            if (probe.isEmpty() || !hasUsableScope(probe.get())) {
                continue;
            }
            AiSystemEntryDTO entry = entry(probe.get(), false, link.getId(), link.getRevision());
            if (!seenSystemCodes.add(entry.getAppCode())) {
                // 同一目标系统出现多条已批准映射：目录按系统标识唯一，只取编号最小的那条
                continue;
            }
            federated.add(entry);
        }
        federated.sort(Comparator.comparing(AiSystemEntryDTO::getAppCode));
        return federated;
    }

    /**
     * 读取一个系统的身份与范围事实。
     *
     * <p>返回空表示"无法确认可访问"：应用不存在/已停用、主体不存在/已停用、范围解析为空或失败、
     * 授权超预算（超预算直接抛错，不返回空）。
     */
    private Optional<Probe> probe(Identity identity) {
        AiApplicationDO application = findApplication(identity.applicationId());
        if (application == null || !Boolean.TRUE.equals(application.getEnabled())) {
            return Optional.empty();
        }
        var subject = subjectService.findActiveSubject(
                identity.applicationId(), identity.subjectType(), identity.externalUserId());
        if (subject.isEmpty()) {
            return Optional.empty();
        }
        List<AiResourceGrantDO> grants = readActiveGrants(identity);
        AiSubjectScopeDTO scope = subjectService.resolveScope(
                identity.applicationId(),
                identity.subjectType(),
                identity.externalUserId(),
                grants.stream()
                        .map(AiResourceGrantDO::getResourceKey)
                        .distinct()
                        .toList());
        if (scope == null || scope.isDenied()) {
            return Optional.empty();
        }
        return Optional.of(new Probe(application, scope, grants));
    }

    /**
     * 精确读取主体的 ACTIVE 授权：应用 + 主体类型 + 外部用户标识 + 状态四个等值条件。
     *
     * <p>不使用管理端分页查询（其中的外部用户标识是 LIKE 且空值不过滤）：跨系统发现是授权事实，
     * "u1" 命中 "u10"、或 APP 主体命中同应用下所有用户授权都会造成越权可见。
     */
    private List<AiResourceGrantDO> readActiveGrants(Identity identity) {
        List<AiResourceGrantDO> grants = new ArrayList<>();
        List<AiResourceGrantDO> page;
        int pageNo = 1;
        do {
            PageParam pageParam = new PageParam();
            pageParam.setPageNo(pageNo);
            pageParam.setPageSize(GRANT_PAGE_SIZE);
            PageResult<AiResourceGrantDO> result = grantMapper.selectPage(
                    pageParam,
                    new LambdaQueryWrapperX<AiResourceGrantDO>()
                            .eq(AiResourceGrantDO::getApplicationId, identity.applicationId())
                            .eq(
                                    AiResourceGrantDO::getSubjectType,
                                    identity.subjectType().name())
                            .eq(AiResourceGrantDO::getExternalUserId, identity.externalUserId())
                            .eq(AiResourceGrantDO::getStatus, AiResourceGrantDO.STATUS_ACTIVE)
                            .orderByAsc(AiResourceGrantDO::getId));
            if (result.getTotal() > GRANT_BUDGET) {
                throw exception(AI_SYSTEM_CATALOG_BUDGET_EXCEEDED);
            }
            page = result.getList();
            grants.addAll(page);
            pageNo++;
        } while (page.size() == GRANT_PAGE_SIZE && pageNo <= (GRANT_BUDGET / GRANT_PAGE_SIZE) + 1);
        return grants;
    }

    /** 至少一条授权且至少一个动作在白名单内，才算"有可用范围"。 */
    private static boolean hasUsableScope(Probe probe) {
        return !scopesOf(probe.grants()).isEmpty();
    }

    /** 只保留动作白名单内非空的授权，并按资源类型 + 资源标识排序（摘要与展示都依赖稳定顺序）。 */
    private static List<AiSystemScopeDTO> scopesOf(List<AiResourceGrantDO> grants) {
        TreeMap<String, AiSystemScopeDTO> sorted = new TreeMap<>();
        for (AiResourceGrantDO grant : grants) {
            Set<String> actions = actionsOf(grant.getActions());
            if (actions.isEmpty()) {
                continue;
            }
            String key = grant.getResourceType() + "\u0000" + grant.getResourceKey();
            AiSystemScopeDTO row = sorted.computeIfAbsent(key, ignored -> new AiSystemScopeDTO()
                    .setResourceType(grant.getResourceType())
                    .setResourceKey(grant.getResourceKey())
                    .setActions(new ArrayList<>()));
            for (String action : actions) {
                if (!row.getActions().contains(action)) {
                    row.getActions().add(action);
                }
            }
        }
        sorted.values().forEach(row -> row.getActions().sort(Comparator.naturalOrder()));
        return new ArrayList<>(sorted.values());
    }

    private static Set<String> actionsOf(String actions) {
        Set<String> parsed = new LinkedHashSet<>();
        if (actions == null || actions.isBlank()) {
            return parsed;
        }
        for (String value : actions.split(",")) {
            AiAction.parse(value).ifPresent(action -> parsed.add(action.name()));
        }
        return parsed;
    }

    private static AiSystemEntryDTO entry(
            Probe probe, boolean currentSystem, Long federationId, Long federationRevision) {
        List<AiSystemScopeDTO> scopes = scopesOf(probe.grants());
        AiSystemEntryDTO entry = new AiSystemEntryDTO()
                .setApplicationId(probe.application().getId())
                .setAppCode(probe.application().getAppCode())
                .setSystemName(probe.application().getName())
                .setCurrentSystem(currentSystem)
                .setFederationId(federationId)
                .setFederationRevision(federationRevision)
                .setSubjectType(probe.scope().getSubjectType())
                .setExternalUserId(probe.scope().getExternalUserId())
                .setScopeSource(probe.scope().getScopeSource())
                .setScopeVersion(probe.scope().getScopeVersion())
                .setScopes(scopes);
        return entry.setSystemFingerprint(fingerprint(entry, federationId, federationRevision));
    }

    /** 系统访问指纹：身份 + 范围来源/版本 + 逐条授权 + 映射编号/版本。 */
    private static String fingerprint(AiSystemEntryDTO entry, Long federationId, Long federationRevision) {
        StringBuilder builder = new StringBuilder();
        builder.append("system=").append(entry.getAppCode()).append(';');
        builder.append("subject=")
                .append(entry.getSubjectType())
                .append(':')
                .append(entry.getExternalUserId())
                .append(';');
        builder.append("source=").append(entry.getScopeSource()).append(';');
        builder.append("version=").append(entry.getScopeVersion()).append(';');
        builder.append("federation=")
                .append(federationId)
                .append('/')
                .append(federationRevision)
                .append(';');
        for (AiSystemScopeDTO scope : entry.getScopes()) {
            builder.append("row=")
                    .append(scope.getResourceType())
                    .append('/')
                    .append(scope.getResourceKey())
                    .append('/')
                    .append(String.join("+", scope.getActions()))
                    .append(';');
        }
        return AiCrossSystemFacts.sha256(builder.toString());
    }

    /** 目录指纹：本次身份事实 + 顺序固定的条目指纹（含被拒绝时的稳定值）。 */
    private static String catalogFingerprint(Identity identity, List<AiSystemEntryDTO> entries) {
        StringBuilder builder = new StringBuilder();
        builder.append("application=").append(identity.applicationId()).append(';');
        builder.append("subject=")
                .append(identity.subjectType().name())
                .append(':')
                .append(identity.externalUserId())
                .append(';');
        for (AiSystemEntryDTO entry : entries) {
            builder.append("entry=").append(entry.getSystemFingerprint()).append(';');
        }
        return AiCrossSystemFacts.sha256(builder.toString());
    }

    private static AiSystemCatalogDTO build(Identity identity, List<AiSystemEntryDTO> entries) {
        return new AiSystemCatalogDTO()
                .setApplicationId(identity.applicationId())
                .setSubjectType(identity.subjectType().name())
                .setExternalUserId(identity.externalUserId())
                .setDenied(entries.isEmpty())
                .setEntries(entries)
                .setCatalogFingerprint(catalogFingerprint(identity, entries))
                .setModelCatalog(AiCrossSystemFacts.render(entries));
    }

    private static AiSystemCatalogDTO denied(Identity identity) {
        return build(identity, List.of());
    }

    private AiApplicationDO findApplication(Long applicationId) {
        try {
            return applicationService.getApplication(applicationId);
        } catch (ServiceException exception) {
            if (AI_APPLICATION_NOT_FOUND.getCode().equals(exception.getCode())) {
                // 联邦目标应用已被删除：该系统不可用，而不是整个发现失败
                return null;
            }
            throw exception;
        }
    }

    private void requireEnabledApplication(Long applicationId) {
        AiApplicationDO application = findApplication(applicationId);
        if (application == null) {
            throw exception(AI_APPLICATION_NOT_FOUND);
        }
        if (!Boolean.TRUE.equals(application.getEnabled())) {
            throw exception(AI_REQUEST_INVALID);
        }
    }

    private static Identity validate(AiSystemCatalogQueryDTO query) {
        if (query == null || query.getApplicationId() == null || query.getApplicationId() <= 0) {
            throw exception(AI_REQUEST_INVALID);
        }
        Identity identity = identity(query.getApplicationId(), query.getSubjectType(), query.getExternalUserId());
        if (identity == null) {
            throw exception(AI_REQUEST_INVALID);
        }
        return identity;
    }

    private static Identity identity(Long applicationId, String subjectType, String externalUserId) {
        if (applicationId == null || applicationId <= 0) {
            return null;
        }
        Optional<AiSubjectType> type = AiSubjectType.parse(subjectType);
        if (type.isEmpty()) {
            return null;
        }
        if (externalUserId != null && externalUserId.length() > 128) {
            return null;
        }
        String normalized = externalUserId == null ? "" : externalUserId.trim();
        if (type.get() == AiSubjectType.USER && !StringUtils.hasText(normalized)) {
            return null;
        }
        return new Identity(applicationId, type.get(), type.get() == AiSubjectType.APP ? "" : normalized);
    }

    /** 一次探查的全部事实（应用、范围解析结果、ACTIVE 授权）。 */
    private record Probe(AiApplicationDO application, AiSubjectScopeDTO scope, List<AiResourceGrantDO> grants) {}

    /** 归一化后的主体身份事实。 */
    private record Identity(Long applicationId, AiSubjectType subjectType, String externalUserId) {}
}
