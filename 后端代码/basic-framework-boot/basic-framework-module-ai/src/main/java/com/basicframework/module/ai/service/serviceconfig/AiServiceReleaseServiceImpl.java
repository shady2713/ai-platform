package com.basicframework.module.ai.service.serviceconfig;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_AUTHORIZATION_DENIED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MODEL_CAPABILITY_UNSUPPORTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MODEL_ENDPOINT_DISABLED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_SERVICE_ENDPOINT_CONFIG_CHANGED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_SERVICE_EVAL_BELOW_THRESHOLD;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_SERVICE_EVAL_MISSING;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_SERVICE_NOT_PUBLISHED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_SERVICE_NOT_READY;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_SERVICE_RELEASE_NOT_PUBLISHED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_SERVICE_RELEASE_PIN_STALE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_SERVICE_RESOURCE_UNAVAILABLE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceReleaseDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceReleaseEvaluationDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceResourceDO;
import com.basicframework.module.ai.dal.mysql.serviceconfig.AiServiceMapper;
import com.basicframework.module.ai.dal.mysql.serviceconfig.AiServiceReleaseEvaluationMapper;
import com.basicframework.module.ai.dal.mysql.serviceconfig.AiServiceReleaseMapper;
import com.basicframework.module.ai.dal.mysql.serviceconfig.AiServiceResourceMapper;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.domain.runtime.AiRunSnapshot;
import com.basicframework.module.ai.domain.serviceconfig.AiServiceContentHash;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.model.AiModelCapabilityProbeService;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceEvaluationSaveDTO;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceRunSnapshotDTO;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * AI 服务发布实现（S02 发布预检查与别名切换，S03 版本回退与运行快照解析）。
 *
 * <p>发布版本的内容在创建候选时一次性冻结，之后只允许状态流转；发布与回退共用同一套预检查
 * （端点可用性 / 能力 / 资源 / 完整性 / 当前授权 / 评测证据），任何一项不满足都不切换别名。
 * 别名切换用服务行的乐观锁串行化（同一服务并发切换只有一个成功），失败时事务回滚，
 * 当前 active 保持不变。
 *
 * <p>运行解析分两种：新运行按别名解析到唯一 ACTIVE 版本；会话沿用运行时按固定值
 * （releaseId + 内容摘要 + 端点配置版本 + 资源版本）解析回同一版本。两者的资源状态与授权
 * 都在**每次解析时**按当前值判定，版本固定不是权限副本。
 */
@Service
@RequiredArgsConstructor
public class AiServiceReleaseServiceImpl implements AiServiceReleaseService {

    /** 评测门槛与得分上限（百分制）。 */
    private static final int MAX_SCORE = 100;

    private static final int MAX_NOTES_LENGTH = 512;

    private final AiServiceMapper serviceMapper;

    private final AiServiceReleaseMapper releaseMapper;

    private final AiServiceResourceMapper resourceMapper;

    private final AiServiceReleaseEvaluationMapper evaluationMapper;

    private final AiAuthorizationService authorizationService;

    private final AiModelEndpointService endpointService;

    private final AiModelCapabilityProbeService capabilityProbeService;

    private final AiServiceService serviceService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createCandidate(Long serviceId, Integer draftVersion) {
        requireVersion(draftVersion);
        AiServiceDO service = requireService(serviceId);
        if (!AiServiceDO.STATUS_READY.equals(service.getStatus())) {
            // 未标记可发布（未通过能力校验）的草稿不允许冻结候选
            throw exception(AI_SERVICE_NOT_READY);
        }
        requireSatisfiedCapabilities(serviceId);
        List<AiServiceResourceDO> draftBindings = resourceMapper.selectDraftBindings(serviceId);
        AiModelEndpointDO endpoint = requireEndpoint(service.getModelEndpointId());
        Integer threshold = service.getEvalThreshold() == null ? 0 : service.getEvalThreshold();
        // 冻结端点配置（provider/baseUrl 被引用后不可原地修改）
        endpointService.markReferenced(endpoint.getId(), endpoint.getVersion());
        // 候选创建推进草稿版本：同一 draftVersion 不能被用于创建第二个候选，并发编辑在此冲突
        advanceServiceVersion(service);
        String contentHash = AiServiceContentHash.compute(
                service.getModelEndpointId(),
                endpoint.getConfigRevision(),
                service.getPromptTemplate(),
                service.getInputSchema(),
                service.getOutputSchema(),
                service.getRequiredCapabilities(),
                threshold,
                draftBindings.stream()
                        .map(binding -> new AiServiceContentHash.ResourceBinding(
                                binding.getResourceType(), binding.getResourceKey(), binding.getActions()))
                        .collect(Collectors.toList()));
        int releaseVersion = nextReleaseVersion(serviceId);
        AiServiceReleaseDO release = new AiServiceReleaseDO()
                .setServiceId(serviceId)
                .setReleaseVersion(releaseVersion)
                .setModelEndpointId(service.getModelEndpointId())
                .setEndpointConfigRevision(endpoint.getConfigRevision())
                .setPromptTemplate(service.getPromptTemplate())
                .setInputSchema(service.getInputSchema())
                .setOutputSchema(service.getOutputSchema())
                .setRequiredCapabilities(service.getRequiredCapabilities())
                .setEvalThreshold(threshold)
                .setContentHash(contentHash)
                .setStatus(AiServiceReleaseDO.STATUS_CANDIDATE)
                .setVersion(0);
        releaseMapper.insert(release);
        // 冻结资源绑定快照：运行前以该快照判定"需要哪些资源"（权限仍按当前值检查）
        for (AiServiceResourceDO binding : draftBindings) {
            resourceMapper.insert(new AiServiceResourceDO()
                    .setServiceId(serviceId)
                    .setReleaseId(release.getId())
                    .setResourceType(binding.getResourceType())
                    .setResourceKey(binding.getResourceKey())
                    .setActions(binding.getActions())
                    .setStatus(AiServiceResourceDO.STATUS_ACTIVE)
                    .setVersion(0));
        }
        return release.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long recordEvaluation(AiServiceEvaluationSaveDTO saveDTO) {
        validateEvaluation(saveDTO);
        AiServiceReleaseDO release = requireRelease(saveDTO.getReleaseId());
        if (AiServiceReleaseDO.STATUS_RETIRED.equals(release.getStatus())) {
            throw exception(AI_STATE_CONFLICT);
        }
        if (configRevisionChanged(release)) {
            // 评测必须针对冻结时的端点配置：配置已变时记录结论等于给无效证据盖章
            throw exception(AI_SERVICE_ENDPOINT_CONFIG_CHANGED);
        }
        int threshold = release.getEvalThreshold() == null ? 0 : release.getEvalThreshold();
        // 通过与否由平台判定：得分达到冻结门槛即通过，调用方不能直接提交结论
        boolean passed = saveDTO.getScore() >= threshold;
        AiServiceReleaseEvaluationDO evaluation = new AiServiceReleaseEvaluationDO()
                .setServiceId(release.getServiceId())
                .setReleaseId(release.getId())
                .setContentHash(release.getContentHash())
                .setModelEndpointId(release.getModelEndpointId())
                .setEndpointConfigRevision(release.getEndpointConfigRevision())
                .setScore(saveDTO.getScore())
                .setThreshold(threshold)
                .setPassed(passed)
                .setCaseCount(saveDTO.getCaseCount())
                .setNotes(saveDTO.getNotes() == null ? "" : saveDTO.getNotes())
                .setVersion(0);
        evaluationMapper.insert(evaluation);
        return evaluation.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publish(Long releaseId, Integer releaseVersion) {
        requireVersion(releaseVersion);
        AiServiceReleaseDO release = requireRelease(releaseId);
        if (!AiServiceReleaseDO.STATUS_CANDIDATE.equals(release.getStatus())) {
            // 只有候选能首次上线；历史版本重新上线走回退，两者预检查完全一致
            throw exception(AI_STATE_CONFLICT);
        }
        switchAlias(release, releaseVersion);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rollback(Long releaseId, Integer releaseVersion) {
        requireVersion(releaseVersion);
        AiServiceReleaseDO release = requireRelease(releaseId);
        if (AiServiceReleaseDO.STATUS_CANDIDATE.equals(release.getStatus())) {
            // 从未对运行开放过的候选不能被"回退"到：必须先发布
            throw exception(AI_SERVICE_RELEASE_NOT_PUBLISHED);
        }
        if (AiServiceReleaseDO.STATUS_ACTIVE.equals(release.getStatus())) {
            // 目标已是当前生效版本：回退必须是版本切换，不是原地重放
            throw exception(AI_STATE_CONFLICT);
        }
        switchAlias(release, releaseVersion);
    }

    /**
     * 别名切换：预检查通过后在**同一事务**内退役旧 ACTIVE 并激活目标版本。
     *
     * <p>回退与发布共用本方法：切换的原子性、并发串行化与预检查都不因"往回切"而放宽。
     * 正在运行或已固定版本的会话不会因此改变解析结果——固定值指向的是版本编号而不是别名。
     */
    private void switchAlias(AiServiceReleaseDO release, Integer releaseVersion) {
        AiServiceDO service = requireService(release.getServiceId());
        List<ErrorCode> blockers = publishBlockers(release, service);
        if (!blockers.isEmpty()) {
            throw exception(blockers.get(0));
        }
        advanceServiceVersion(service);
        releaseMapper.retireActive(release.getServiceId());
        if (releaseMapper.updateWithVersion(
                        new AiServiceReleaseDO()
                                .setId(release.getId())
                                .setStatus(AiServiceReleaseDO.STATUS_ACTIVE)
                                .setVersion(releaseVersion + 1),
                        releaseVersion)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void disable(Long serviceId, Integer serviceVersion) {
        requireVersion(serviceVersion);
        AiServiceDO service = requireService(serviceId);
        advanceServiceVersion(service);
        if (releaseMapper.retireActive(serviceId) == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    public List<String> checkPublishReadiness(Long releaseId) {
        AiServiceReleaseDO release = requireRelease(releaseId);
        AiServiceDO service = requireService(release.getServiceId());
        return publishBlockers(release, service).stream().map(ErrorCode::getMsg).collect(Collectors.toList());
    }

    @Override
    public List<AiServiceResourceDO> listReleaseBindings(Long releaseId) {
        requireRelease(releaseId);
        return resourceMapper.selectReleaseBindings(releaseId);
    }

    @Override
    public List<AiServiceReleaseEvaluationDO> listEvaluations(Long releaseId) {
        requireRelease(releaseId);
        return evaluationMapper.selectByRelease(releaseId);
    }

    @Override
    public List<AiServiceReleaseDO> listReleases(Long serviceId) {
        requireService(serviceId);
        return releaseMapper.selectByService(serviceId);
    }

    @Override
    public AiServiceRunSnapshotDTO resolveForNewRun(Long serviceId) {
        requireService(serviceId);
        AiServiceReleaseDO release = releaseMapper.selectActiveByService(serviceId);
        if (release == null) {
            throw exception(AI_SERVICE_NOT_PUBLISHED);
        }
        return resolution(release, resourceMapper.selectReleaseBindings(release.getId()), false);
    }

    @Override
    public AiServiceRunSnapshotDTO resolvePinnedRun(AiRunSnapshot pin) {
        if (pin == null || pin.getReleaseId() == null || !StringUtils.hasText(pin.getContentHash())) {
            throw exception(AI_REQUEST_INVALID);
        }
        requireService(pin.getServiceId());
        AiServiceReleaseDO release = releaseMapper.selectById(pin.getReleaseId());
        if (release == null || !pin.getServiceId().equals(release.getServiceId())) {
            // 版本不存在与跨服务固定同语义：固定值不能被用来探测或跨用其它服务的版本
            throw exception(AI_RESOURCE_NOT_FOUND);
        }
        if (!pin.pinsContentOf(release)) {
            throw exception(AI_SERVICE_RELEASE_PIN_STALE);
        }
        if (AiServiceReleaseDO.STATUS_CANDIDATE.equals(release.getStatus())) {
            throw exception(AI_SERVICE_RELEASE_NOT_PUBLISHED);
        }
        if (releaseMapper.selectActiveByService(pin.getServiceId()) == null) {
            // 停用是显式动作：服务离线后延续的会话同样停止，固定版本不能绕过服务级停用
            throw exception(AI_SERVICE_NOT_PUBLISHED);
        }
        List<AiServiceResourceDO> bindings = resourceMapper.selectReleaseBindings(release.getId());
        if (!pin.sameResources(bindings)) {
            // 绑定被解绑/重建后，固定运行不能静默改用别的绑定
            throw exception(AI_SERVICE_RESOURCE_UNAVAILABLE);
        }
        return resolution(release, bindings, true);
    }

    /**
     * 运行前解析（新运行与固定会话共用）。
     *
     * <p>顺序即错误优先级：端点存在且启用 → 端点配置版本未漂移 → 冻结绑定仍生效 →
     * 当前授权仍允许绑定上的动作。后两步每次都读**当前值**，这正是"旧 release 不保留旧权限"。
     */
    private AiServiceRunSnapshotDTO resolution(
            AiServiceReleaseDO release, List<AiServiceResourceDO> bindings, boolean pinned) {
        AiModelEndpointDO endpoint = requireEndpoint(release.getModelEndpointId());
        if (!Boolean.TRUE.equals(endpoint.getEnabled())) {
            throw exception(AI_MODEL_ENDPOINT_DISABLED);
        }
        if (!release.getEndpointConfigRevision().equals(endpoint.getConfigRevision())) {
            throw exception(AI_SERVICE_ENDPOINT_CONFIG_CHANGED);
        }
        AiServiceDO service = requireService(release.getServiceId());
        for (AiServiceResourceDO binding : bindings) {
            if (!AiServiceResourceDO.STATUS_ACTIVE.equals(binding.getStatus())) {
                // 资源被禁用（绑定已解除）后运行拒绝：旧版本不保留旧权限
                throw exception(AI_SERVICE_RESOURCE_UNAVAILABLE);
            }
        }
        if (authorizationRevoked(release, service)) {
            throw exception(AI_AUTHORIZATION_DENIED);
        }
        return new AiServiceRunSnapshotDTO()
                .setRelease(release)
                .setBindings(bindings)
                .setPin(AiRunSnapshot.of(release, bindings))
                .setPinned(pinned);
    }

    /**
     * 发布/回退共用的预检查：返回全部未满足项（空表示可切换），顺序即错误优先级。
     *
     * <p>能力按**发布版本自己冻结的能力**判定（而不是当前草稿）：候选冻结后草稿可以继续编辑，
     * 用草稿能力判定会让已冻结版本在发布或回退时通过一个它自己并不满足的检查。
     */
    private List<ErrorCode> publishBlockers(AiServiceReleaseDO release, AiServiceDO service) {
        List<ErrorCode> blockers = new ArrayList<>();
        // 端点可用性先判：模型不可用时能力与评测证据都不成立，必须先于其它检查失败
        AiModelEndpointDO endpoint = endpointService.getEndpoint(release.getModelEndpointId());
        if (!Boolean.TRUE.equals(endpoint.getEnabled())) {
            blockers.add(AI_MODEL_ENDPOINT_DISABLED);
        } else if (!release.getEndpointConfigRevision().equals(endpoint.getConfigRevision())) {
            blockers.add(AI_SERVICE_ENDPOINT_CONFIG_CHANGED);
        }
        if (releaseCapabilitiesUnsupported(release)) {
            blockers.add(AI_MODEL_CAPABILITY_UNSUPPORTED);
        }
        if (releasedBindingExists(release)) {
            blockers.add(AI_SERVICE_RESOURCE_UNAVAILABLE);
        }
        if (!integrityHolds(release)) {
            // 内容摘要与冻结内容不一致：发布版本只能由平台写入，绝不带病上线
            blockers.add(AI_STATE_CONFLICT);
        }
        if (authorizationRevoked(release, service)) {
            blockers.add(AI_AUTHORIZATION_DENIED);
        }
        AiServiceReleaseEvaluationDO latest = evaluationMapper.selectLatest(release.getId());
        if (latest == null
                || !release.getContentHash().equals(latest.getContentHash())
                || !release.getEndpointConfigRevision().equals(latest.getEndpointConfigRevision())) {
            blockers.add(AI_SERVICE_EVAL_MISSING);
        } else if (!Boolean.TRUE.equals(latest.getPassed())
                || latest.getScore() == null
                || latest.getScore() < (release.getEvalThreshold() == null ? 0 : release.getEvalThreshold())) {
            blockers.add(AI_SERVICE_EVAL_BELOW_THRESHOLD);
        }
        return blockers;
    }

    /** 版本冻结的能力是否已被端点确认覆盖（探测结论变化即视为不满足）。 */
    private boolean releaseCapabilitiesUnsupported(AiServiceReleaseDO release) {
        List<String> required = splitCapabilities(release.getRequiredCapabilities());
        if (required.isEmpty()) {
            return false;
        }
        List<String> publishable = capabilityProbeService
                .getCapabilityOverview(release.getModelEndpointId())
                .getPublishable();
        return required.stream().anyMatch(capability -> !publishable.contains(capability));
    }

    private boolean integrityHolds(AiServiceReleaseDO release) {
        String recomputed = AiServiceContentHash.compute(
                release.getModelEndpointId(),
                release.getEndpointConfigRevision(),
                release.getPromptTemplate(),
                release.getInputSchema(),
                release.getOutputSchema(),
                release.getRequiredCapabilities(),
                release.getEvalThreshold(),
                resourceMapper.selectReleaseBindings(release.getId()).stream()
                        .map(binding -> new AiServiceContentHash.ResourceBinding(
                                binding.getResourceType(), binding.getResourceKey(), binding.getActions()))
                        .collect(Collectors.toList()));
        return recomputed.equals(release.getContentHash());
    }

    private boolean releasedBindingExists(AiServiceReleaseDO release) {
        return resourceMapper.selectReleaseBindings(release.getId()).stream()
                .anyMatch(binding -> !AiServiceResourceDO.STATUS_ACTIVE.equals(binding.getStatus()));
    }

    /** 逐条绑定重新判定当前授权：绑定后失权的资源不允许发布。 */
    private boolean authorizationRevoked(AiServiceReleaseDO release, AiServiceDO service) {
        for (AiServiceResourceDO binding : resourceMapper.selectActiveReleaseBindings(release.getId())) {
            for (String action : splitActions(binding.getActions())) {
                boolean allowed = authorizationService
                        .authorize(
                                service.getAppId(),
                                AiSubjectType.APP.name(),
                                "",
                                AiResourceType.parse(binding.getResourceType())
                                        .orElseThrow(() -> exception(AI_REQUEST_INVALID)),
                                binding.getResourceKey(),
                                AiAction.parse(action).orElseThrow(() -> exception(AI_REQUEST_INVALID)),
                                List.of(binding.getResourceKey()))
                        .isAllowed();
                if (!allowed) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean configRevisionChanged(AiServiceReleaseDO release) {
        AiModelEndpointDO endpoint = endpointService.getEndpoint(release.getModelEndpointId());
        return !release.getEndpointConfigRevision().equals(endpoint.getConfigRevision());
    }

    private void requireSatisfiedCapabilities(Long serviceId) {
        if (!serviceService.checkCapabilities(serviceId).isSatisfied()) {
            throw exception(AI_MODEL_CAPABILITY_UNSUPPORTED);
        }
    }

    private AiServiceReleaseDO requireRelease(Long releaseId) {
        AiServiceReleaseDO release = releaseId == null ? null : releaseMapper.selectById(releaseId);
        if (release == null) {
            throw exception(AI_RESOURCE_NOT_FOUND);
        }
        return release;
    }

    private AiServiceDO requireService(Long serviceId) {
        AiServiceDO service = serviceId == null ? null : serviceMapper.selectById(serviceId);
        if (service == null) {
            throw exception(AI_RESOURCE_NOT_FOUND);
        }
        return service;
    }

    private AiModelEndpointDO requireEndpoint(Long endpointId) {
        AiModelEndpointDO endpoint = endpointId == null ? null : endpointService.getEndpoint(endpointId);
        if (endpoint == null) {
            throw exception(AI_RESOURCE_NOT_FOUND);
        }
        return endpoint;
    }

    /**
     * 推进服务行乐观锁：读当前值后 CAS。
     *
     * <p>并发发布会同时读到同一版本，只有一个 CAS 成功，另一个以 409 结束——
     * 这是"同一服务最多一条 ACTIVE"的串行化点。
     */
    private void advanceServiceVersion(AiServiceDO service) {
        int current = service.getVersion() == null ? 0 : service.getVersion();
        if (serviceMapper.updateWithVersion(
                        new AiServiceDO().setId(service.getId()).setVersion(current + 1), current)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        service.setVersion(current + 1);
    }

    private int nextReleaseVersion(Long serviceId) {
        Integer max = releaseMapper.selectMaxReleaseVersion(serviceId);
        return max == null ? 1 : max + 1;
    }

    private static List<String> splitActions(String actions) {
        if (!StringUtils.hasText(actions)) {
            return List.of();
        }
        return Arrays.stream(actions.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
    }

    private static List<String> splitCapabilities(String capabilities) {
        if (!StringUtils.hasText(capabilities)) {
            return List.of();
        }
        return Arrays.stream(capabilities.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
    }

    private static void validateEvaluation(AiServiceEvaluationSaveDTO saveDTO) {
        if (saveDTO == null
                || saveDTO.getReleaseId() == null
                || saveDTO.getScore() == null
                || saveDTO.getScore() < 0
                || saveDTO.getScore() > MAX_SCORE
                || saveDTO.getCaseCount() == null
                || saveDTO.getCaseCount() < 1
                || (saveDTO.getNotes() != null && saveDTO.getNotes().length() > MAX_NOTES_LENGTH)) {
            throw exception(AI_REQUEST_INVALID);
        }
    }

    private static void requireVersion(Integer version) {
        if (version == null || version < 0) {
            throw exception(AI_REQUEST_INVALID);
        }
    }
}
