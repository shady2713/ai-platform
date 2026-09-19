package com.basicframework.module.ai.service.serviceconfig;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_AUTHORIZATION_DENIED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MODEL_CAPABILITY_UNSUPPORTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_STATE_CONFLICT;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceReleaseDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceResourceDO;
import com.basicframework.module.ai.dal.mysql.serviceconfig.AiServiceMapper;
import com.basicframework.module.ai.dal.mysql.serviceconfig.AiServiceReleaseMapper;
import com.basicframework.module.ai.dal.mysql.serviceconfig.AiServiceResourceMapper;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.model.AiModelCapabilityProbeService;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceCapabilityDTO;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceResourceSaveDTO;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceSaveDTO;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * AI 服务草稿实现（S01）。
 *
 * <p>绑定越权校验的基准是**服务所属应用在应用级主体（APP）上的授权**：
 * 服务代表应用运行，绑定资源必须落在应用自身被授权的资源范围内；
 * 逐次运行的主体范围（A02/A03）仍会在运行期再次校验，两层都不放松。
 */
@Service
@RequiredArgsConstructor
public class AiServiceServiceImpl implements AiServiceService {

    /** 绑定资源时默认需要的动作（读取）。 */
    private static final String DEFAULT_BIND_ACTION = "READ";

    private final AiServiceMapper serviceMapper;

    private final AiServiceResourceMapper resourceMapper;

    private final AiServiceReleaseMapper releaseMapper;

    private final AiAuthorizationService authorizationService;

    private final AiModelCapabilityProbeService capabilityProbeService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createDraft(AiServiceSaveDTO saveDTO) {
        validateDraft(saveDTO);
        if (serviceMapper.selectByAppAndCode(saveDTO.getAppId(), saveDTO.getCode()) != null) {
            throw exception(AI_STATE_CONFLICT);
        }
        AiServiceDO service = new AiServiceDO()
                .setAppId(saveDTO.getAppId())
                .setCode(saveDTO.getCode())
                .setName(saveDTO.getName())
                .setDescription(saveDTO.getDescription() == null ? "" : saveDTO.getDescription())
                .setStatus(AiServiceDO.STATUS_DRAFT)
                .setModelEndpointId(saveDTO.getModelEndpointId())
                .setPromptTemplate(saveDTO.getPromptTemplate())
                .setInputSchema(saveDTO.getInputSchema())
                .setOutputSchema(saveDTO.getOutputSchema())
                .setRequiredCapabilities(joinCapabilities(saveDTO.getRequiredCapabilities()))
                .setRunSubjectType(saveDTO.getRunSubjectType())
                .setEvalThreshold(normalizeThreshold(saveDTO.getEvalThreshold()))
                .setDraftRevision(1)
                .setVersion(0);
        serviceMapper.insert(service);
        return service.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDraft(AiServiceSaveDTO saveDTO) {
        validateDraft(saveDTO);
        requireVersion(saveDTO.getVersion());
        AiServiceDO existing = validateServiceExists(saveDTO.getId());
        if (!existing.getCode().equals(saveDTO.getCode())) {
            // 服务标识是对接方的稳定引用：改标识等于换一个服务
            throw exception(AI_STATE_CONFLICT);
        }
        AiServiceDO update = new AiServiceDO()
                .setId(existing.getId())
                .setName(saveDTO.getName())
                .setDescription(saveDTO.getDescription() == null ? "" : saveDTO.getDescription())
                .setModelEndpointId(saveDTO.getModelEndpointId())
                .setPromptTemplate(saveDTO.getPromptTemplate())
                .setInputSchema(saveDTO.getInputSchema())
                .setOutputSchema(saveDTO.getOutputSchema())
                .setRequiredCapabilities(joinCapabilities(saveDTO.getRequiredCapabilities()))
                .setRunSubjectType(saveDTO.getRunSubjectType())
                .setEvalThreshold(normalizeThreshold(saveDTO.getEvalThreshold()))
                // 配置变更：修订号递增（发布时据此固定版本）；配置一变，READY 需要重新确认
                .setDraftRevision(existing.getDraftRevision() + 1)
                .setStatus(AiServiceDO.STATUS_DRAFT)
                .setVersion(saveDTO.getVersion() + 1);
        if (serviceMapper.updateWithVersion(update, saveDTO.getVersion()) == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDraft(Long id, Integer version) {
        requireVersion(version);
        validateServiceExists(id);
        if (!resourceMapper.selectDraftBindings(id).isEmpty()) {
            throw exception(AI_STATE_CONFLICT);
        }
        if (serviceMapper.updateWithVersion(
                        new AiServiceDO()
                                .setId(id)
                                .setStatus(AiServiceDO.STATUS_ARCHIVED)
                                .setVersion(version + 1),
                        version)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
        serviceMapper.deleteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markReady(Long id, Integer version) {
        requireVersion(version);
        AiServiceDO service = validateServiceExists(id);
        AiServiceCapabilityDTO capability = checkCapabilities(id);
        if (!capability.isSatisfied()) {
            throw exception(AI_MODEL_CAPABILITY_UNSUPPORTED);
        }
        if (serviceMapper.updateWithVersion(
                        new AiServiceDO()
                                .setId(id)
                                .setStatus(AiServiceDO.STATUS_READY)
                                .setVersion(version + 1),
                        version)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    public AiServiceCapabilityDTO checkCapabilities(Long id) {
        AiServiceDO service = validateServiceExists(id);
        List<String> required = splitCapabilities(service.getRequiredCapabilities());
        // 可发布能力来自 M04 的能力总览：声明与探测确认的交集
        List<String> publishable = capabilityProbeService
                .getCapabilityOverview(service.getModelEndpointId())
                .getPublishable();
        List<String> missing = required.stream()
                .filter(capability -> !publishable.contains(capability))
                .collect(Collectors.toList());
        return new AiServiceCapabilityDTO()
                .setRequired(required)
                .setPublishable(publishable)
                .setMissing(missing)
                .setSatisfied(missing.isEmpty());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long bindResource(AiServiceResourceSaveDTO saveDTO) {
        AiServiceDO service = validateServiceExists(saveDTO.getServiceId());
        AiResourceType resourceType =
                AiResourceType.parse(saveDTO.getResourceType()).orElseThrow(() -> exception(AI_REQUEST_INVALID));
        if (!StringUtils.hasText(saveDTO.getResourceKey())
                || saveDTO.getResourceKey().length() > 128) {
            throw exception(AI_REQUEST_INVALID);
        }
        Set<String> actions = new LinkedHashSet<>();
        for (String action : saveDTO.getActions() == null ? List.of(DEFAULT_BIND_ACTION) : saveDTO.getActions()) {
            actions.add(AiAction.parse(action)
                    .orElseThrow(() -> exception(AI_REQUEST_INVALID))
                    .name());
        }
        if (actions.isEmpty()) {
            throw exception(AI_REQUEST_INVALID);
        }
        // 越权绑定拒绝：服务所属应用必须自身被授权访问该资源（应用级主体）
        for (String action : actions) {
            boolean allowed = authorizationService
                    .authorize(
                            service.getAppId(),
                            AiSubjectType.APP.name(),
                            "",
                            resourceType,
                            saveDTO.getResourceKey(),
                            AiAction.valueOf(action),
                            List.of(saveDTO.getResourceKey()))
                    .isAllowed();
            if (!allowed) {
                throw exception(AI_AUTHORIZATION_DENIED);
            }
        }
        if (resourceMapper.selectDraftBinding(service.getId(), resourceType.name(), saveDTO.getResourceKey()) != null) {
            // 只与草稿绑定比较：发布版本快照不参与草稿的重复判定
            throw exception(AI_STATE_CONFLICT);
        }
        AiServiceResourceDO binding = new AiServiceResourceDO()
                .setServiceId(service.getId())
                .setResourceType(resourceType.name())
                .setResourceKey(saveDTO.getResourceKey())
                .setActions(String.join(",", actions))
                .setStatus(AiServiceResourceDO.STATUS_ACTIVE)
                .setVersion(0);
        resourceMapper.insert(binding);
        return binding.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unbindResource(Long bindingId, Integer version) {
        requireVersion(version);
        AiServiceResourceDO binding = bindingId == null ? null : resourceMapper.selectById(bindingId);
        if (binding == null || !AiServiceResourceDO.STATUS_ACTIVE.equals(binding.getStatus())) {
            throw exception(AI_RESOURCE_NOT_FOUND);
        }
        if (resourceMapper.updateWithVersion(
                        new AiServiceResourceDO()
                                .setId(bindingId)
                                .setStatus(AiServiceResourceDO.STATUS_RELEASED)
                                .setVersion(version + 1),
                        version)
                == 0) {
            throw exception(AI_STATE_CONFLICT);
        }
    }

    @Override
    public List<AiServiceResourceDO> listDraftBindings(Long serviceId) {
        validateServiceExists(serviceId);
        return resourceMapper.selectDraftBindings(serviceId);
    }

    @Override
    public List<AiServiceReleaseDO> listReleases(Long serviceId) {
        validateServiceExists(serviceId);
        return releaseMapper.selectByService(serviceId);
    }

    @Override
    public AiServiceDO getService(Long id) {
        return validateServiceExists(id);
    }

    @Override
    public PageResult<AiServiceDO> getServicePage(PageParam pageParam, Long appId, String code, String status) {
        return serviceMapper.selectPage(pageParam, appId, code, status);
    }

    private AiServiceDO validateServiceExists(Long id) {
        AiServiceDO service = id == null ? null : serviceMapper.selectById(id);
        if (service == null) {
            throw exception(AI_RESOURCE_NOT_FOUND);
        }
        return service;
    }

    private static void validateDraft(AiServiceSaveDTO saveDTO) {
        if (saveDTO == null
                || saveDTO.getAppId() == null
                || !StringUtils.hasText(saveDTO.getCode())
                || saveDTO.getCode().length() > 64
                || !StringUtils.hasText(saveDTO.getName())
                || saveDTO.getName().length() > 128
                || saveDTO.getModelEndpointId() == null
                || !StringUtils.hasText(saveDTO.getPromptTemplate())) {
            throw exception(AI_REQUEST_INVALID);
        }
        AiSubjectType.parse(saveDTO.getRunSubjectType()).orElseThrow(() -> exception(AI_REQUEST_INVALID));
        // 输入/输出 Schema 必须是可解析的 JSON 对象（结构校验在协议层再做一次）
        requireJsonObject(saveDTO.getInputSchema());
        if (StringUtils.hasText(saveDTO.getOutputSchema())) {
            requireJsonObject(saveDTO.getOutputSchema());
        }
        splitCapabilities(joinCapabilities(saveDTO.getRequiredCapabilities()));
    }

    /** Schema 必须是可解析的 JSON 对象（数组、标量与非法 JSON 都拒绝）。 */
    private static void requireJsonObject(String json) {
        if (!StringUtils.hasText(json)) {
            throw exception(AI_REQUEST_INVALID);
        }
        try {
            Object parsed = JsonUtils.parseObject(json, java.util.Map.class);
            if (!(parsed instanceof java.util.Map)) {
                throw exception(AI_REQUEST_INVALID);
            }
        } catch (ServiceException serviceException) {
            throw serviceException;
        } catch (RuntimeException exception) {
            // 解析失败（类型不匹配或非法 JSON）统一收敛为入参错误
            throw exception(AI_REQUEST_INVALID);
        }
    }

    private static String joinCapabilities(List<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            throw exception(AI_REQUEST_INVALID);
        }
        List<String> normalized = capabilities.stream()
                .map(AiServiceServiceImpl::parseCapability)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        return String.join(",", normalized);
    }

    private static String parseCapability(String capability) {
        try {
            return com.basicframework.framework.ai.core.model.ModelCapability.valueOf(
                            capability.trim().toUpperCase(java.util.Locale.ROOT))
                    .name();
        } catch (IllegalArgumentException exception) {
            throw exception(AI_REQUEST_INVALID);
        }
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

    private static void requireVersion(Integer version) {
        if (version == null || version < 0) {
            throw exception(AI_REQUEST_INVALID);
        }
    }

    /** 评测门槛：百分制，缺省 0（只要求评测通过）。 */
    private static Integer normalizeThreshold(Integer threshold) {
        if (threshold == null) {
            return 0;
        }
        if (threshold < 0 || threshold > 100) {
            throw exception(AI_REQUEST_INVALID);
        }
        return threshold;
    }
}
