package com.basicframework.module.ai.service.serviceconfig;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_AUTHORIZATION_DENIED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MODEL_CAPABILITY_UNSUPPORTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_SERVICE_ENDPOINT_CONFIG_CHANGED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_SERVICE_EVAL_BELOW_THRESHOLD;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_SERVICE_EVAL_MISSING;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_SERVICE_NOT_PUBLISHED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_SERVICE_NOT_READY;
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
import com.basicframework.module.ai.domain.serviceconfig.AiServiceContentHash;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceCapabilityDTO;
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
 * AI 服务发布实现（S02）。
 *
 * <p>发布版本的内容在创建候选时一次性冻结，之后只允许状态流转；发布预检查把
 * "能力 / 资源 / Schema / 评测"四类证据一次性验证完，任何一项不满足都不切换别名。
 * 别名切换用服务行的乐观锁串行化（同一服务并发发布只有一个成功），失败时事务回滚，
 * 当前 active 保持不变。
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
            throw exception(AI_STATE_CONFLICT);
        }
        AiServiceDO service = requireService(release.getServiceId());
        List<ErrorCode> blockers = publishBlockers(release, service);
        if (!blockers.isEmpty()) {
            throw exception(blockers.get(0));
        }
        // 别名切换：服务行乐观锁串行化并发发布；先退役旧 ACTIVE，再激活候选
        advanceServiceVersion(service);
        releaseMapper.retireActive(release.getServiceId());
        if (releaseMapper.updateWithVersion(
                        new AiServiceReleaseDO()
                                .setId(releaseId)
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
        if (configRevisionChanged(release)) {
            throw exception(AI_SERVICE_ENDPOINT_CONFIG_CHANGED);
        }
        List<AiServiceResourceDO> bindings = resourceMapper.selectReleaseBindings(release.getId());
        for (AiServiceResourceDO binding : bindings) {
            if (!AiServiceResourceDO.STATUS_ACTIVE.equals(binding.getStatus())) {
                // 资源被禁用（绑定已解除）后新运行拒绝：旧版本不保留旧权限
                throw exception(AI_SERVICE_RESOURCE_UNAVAILABLE);
            }
        }
        return new AiServiceRunSnapshotDTO().setRelease(release).setBindings(bindings);
    }

    /** 发布预检查：返回全部未满足项（空表示可发布），顺序即错误优先级。 */
    private List<ErrorCode> publishBlockers(AiServiceReleaseDO release, AiServiceDO service) {
        List<ErrorCode> blockers = new ArrayList<>();
        AiServiceCapabilityDTO capability = serviceService.checkCapabilities(service.getId());
        if (!capability.isSatisfied()) {
            blockers.add(AI_MODEL_CAPABILITY_UNSUPPORTED);
        }
        if (configRevisionChanged(release)) {
            blockers.add(AI_SERVICE_ENDPOINT_CONFIG_CHANGED);
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
