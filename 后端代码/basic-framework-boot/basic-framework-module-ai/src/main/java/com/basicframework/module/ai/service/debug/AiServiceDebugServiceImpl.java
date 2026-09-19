package com.basicframework.module.ai.service.debug;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_AUTHORIZATION_DENIED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_RESOURCE_NOT_FOUND;

import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelRequest;
import com.basicframework.framework.ai.core.model.ModelResponse;
import com.basicframework.framework.ai.core.model.ModelUsage;
import com.basicframework.framework.ai.core.model.StructuredModelRequest;
import com.basicframework.framework.ai.core.model.StructuredModelResult;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointRevisionDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceResourceDO;
import com.basicframework.module.ai.domain.identity.AiSubjectType;
import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiOutboundLevel;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.domain.runtime.AiContextBudget;
import com.basicframework.module.ai.domain.runtime.AiModelFailureCodes;
import com.basicframework.module.ai.domain.runtime.AiRunSnapshot;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.context.AiContextBuilder;
import com.basicframework.module.ai.service.context.dto.AiContextResultDTO;
import com.basicframework.module.ai.service.debug.dto.AiDebugStageDTO;
import com.basicframework.module.ai.service.debug.dto.AiServiceDebugResultDTO;
import com.basicframework.module.ai.service.debug.dto.AiServiceDebugRunDTO;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.AiModelInvocationResult;
import com.basicframework.module.ai.service.model.AiModelInvocationService;
import com.basicframework.module.ai.service.serviceconfig.AiServiceReleaseService;
import com.basicframework.module.ai.service.serviceconfig.AiServiceService;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceRunSnapshotDTO;
import com.basicframework.module.ai.service.subject.AiSubjectService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 服务调试实现（S04）。
 *
 * <p>链路与线上运行一致：别名解析发布版本 → 按显式测试主体判定资源授权 → 上下文拼装 →
 * 统一模型调用（外发策略、端点解析、计量）。调试不额外读取业务资源、不绕过授权、
 * 不返回提示词正文；每个阶段只留下"阶段名 + 稳定说明 + 耗时"。
 */
@Service
@RequiredArgsConstructor
public class AiServiceDebugServiceImpl implements AiServiceDebugService {

    /** 默认模型调用超时。 */
    private static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;

    /** 调试允许的最大模型调用超时。 */
    private static final long MAX_TIMEOUT_MILLIS = 120_000L;

    private final AiServiceReleaseService releaseService;

    private final AiServiceService serviceService;

    private final AiAuthorizationService authorizationService;

    private final AiSubjectService subjectService;

    private final AiModelEndpointService endpointService;

    private final AiContextBuilder contextBuilder;

    private final AiModelInvocationService invocationService;

    @Override
    public AiServiceDebugResultDTO debugRun(AiServiceDebugRunDTO request) {
        validate(request);
        long startedAt = System.nanoTime();
        List<AiDebugStageDTO> stages = new ArrayList<>();

        // 1) 解析：调试使用当前生效版本，与线上新运行的解析路径一致
        long stageStart = System.nanoTime();
        AiServiceRunSnapshotDTO snapshot = releaseService.resolveForNewRun(request.getServiceId());
        AiRunSnapshot pin = snapshot.getPin();
        stages.add(stage("RESOLVE", "OK", "release=" + pin.getReleaseVersion(), stageStart));

        // 2) 授权：按显式测试主体逐条判定绑定动作，测试主体没有的权限调试也拿不到
        stageStart = System.nanoTime();
        AiSubjectType subjectType = parseSubjectType(request.getTestSubjectType());
        String externalUserId = AiSubjectType.USER == subjectType ? request.getTestSubjectId() : "";
        AiModelEndpointDO endpoint = endpointService.getEndpoint(pin.getModelEndpointId());
        AiServiceDO service = serviceService.getService(request.getServiceId());
        Long appId = service.getAppId();
        subjectService
                .findActiveSubject(appId, subjectType, AiSubjectType.USER == subjectType ? externalUserId : null)
                .orElseThrow(() -> exception(AI_RESOURCE_NOT_FOUND));
        List<String> authorized = authorizeBindings(snapshot, appId, subjectType, externalUserId);
        stages.add(stage("AUTHORIZE", "OK", "bindings=" + authorized.size(), stageStart));

        // 3) 上下文：固定分区与预算规则，超预算给出可解释失败
        stageStart = System.nanoTime();
        AiContextResultDTO context = contextBuilder.build(request.toContextRequest(
                snapshot.getRelease().getPromptTemplate(),
                AiContextBudget.of(request.getMaxMessages(), request.getMaxTokens(), null)));
        stages.add(stage("CONTEXT", "OK", "sections=" + context.getSections().size(), stageStart));

        // 4) 模型：统一调用编排（策略先于网络调用），上游失败按稳定错误结束，不返回假成功
        stageStart = System.nanoTime();
        String modelId = currentModelId(pin.getModelEndpointId());
        AiOutboundLevel dataLevel =
                AiOutboundLevel.parse(request.getDataLevel()).orElseThrow(() -> exception(AI_REQUEST_INVALID));
        Duration timeout = Duration.ofMillis(timeoutMillis(request));
        AiServiceDebugResultDTO result = new AiServiceDebugResultDTO();
        String outputSchema = snapshot.getRelease().getOutputSchema();
        try {
            if (StringUtils.hasText(outputSchema)) {
                AiModelInvocationResult<StructuredModelResult> invoked = invocationService.generateStructured(
                        pin.getModelEndpointId(),
                        new StructuredModelRequest(modelId, context.getPrompt(), outputSchema, timeout),
                        dataLevel);
                StructuredModelResult output = invoked.output();
                applyUsage(result, output.usage());
                result.setOutput(output.json()).setStructured(true);
            } else {
                AiModelInvocationResult<ModelResponse> invoked = invocationService.generate(
                        pin.getModelEndpointId(), new ModelRequest(modelId, context.getPrompt(), timeout), dataLevel);
                ModelResponse output = invoked.output();
                applyUsage(result, output.usage());
                result.setOutput(output.text()).setStructured(false);
            }
        } catch (ModelException failure) {
            // 上游失败按平台稳定错误结束：不返回假成功，也不把上游报文与提示词带出去
            throw AiModelFailureCodes.toServiceException(failure);
        }
        stages.add(stage("MODEL", "OK", "endpoint=" + endpoint.getId(), stageStart));

        return result.setReleaseId(pin.getReleaseId())
                .setReleaseVersion(pin.getReleaseVersion())
                .setContentHash(pin.getContentHash())
                .setModelEndpointId(pin.getModelEndpointId())
                .setModelRevision(pin.getModelRevision())
                .setTestSubjectType(subjectType.name())
                .setTestSubjectId(request.getTestSubjectId())
                .setAuthorizedBindings(authorized)
                .setSections(context.getSections())
                .setEstimatedTokens(context.getEstimatedTokens())
                .setTruncated(context.isTruncated())
                .setStages(stages)
                .setDurationMs(millisSince(startedAt));
    }

    /** 逐条绑定按测试主体的**当前**授权判定；任一动作不被放行即整体拒绝。 */
    private List<String> authorizeBindings(
            AiServiceRunSnapshotDTO snapshot, Long appId, AiSubjectType subjectType, String externalUserId) {
        List<String> authorized = new ArrayList<>();
        for (AiServiceResourceDO binding : snapshot.getBindings()) {
            for (String action : splitActions(binding.getActions())) {
                boolean allowed = authorizationService
                        .authorize(
                                appId,
                                subjectType.name(),
                                externalUserId,
                                AiResourceType.parse(binding.getResourceType())
                                        .orElseThrow(() -> exception(AI_REQUEST_INVALID)),
                                binding.getResourceKey(),
                                AiAction.parse(action).orElseThrow(() -> exception(AI_REQUEST_INVALID)),
                                List.of(binding.getResourceKey()))
                        .isAllowed();
                if (!allowed) {
                    // 调试不能越权：测试主体拿不到的资源，调试也不放行
                    throw exception(AI_AUTHORIZATION_DENIED);
                }
            }
            authorized.add(binding.getResourceType() + ":" + binding.getResourceKey());
        }
        return authorized;
    }

    private String currentModelId(Long endpointId) {
        List<AiModelEndpointRevisionDO> revisions = endpointService.getRevisions(endpointId);
        if (revisions.isEmpty()) {
            throw exception(AI_RESOURCE_NOT_FOUND);
        }
        return revisions.get(0).getModelId();
    }

    private static void applyUsage(AiServiceDebugResultDTO result, ModelUsage usage) {
        ModelUsage safe = usage == null ? ModelUsage.UNKNOWN : usage;
        result.setInputTokens(safe.promptTokens()).setOutputTokens(safe.completionTokens());
    }

    private static AiDebugStageDTO stage(String stage, String status, String detail, long stageStart) {
        return new AiDebugStageDTO()
                .setStage(stage)
                .setStatus(status)
                .setDetail(detail)
                .setDurationMs(millisSince(stageStart));
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static long timeoutMillis(AiServiceDebugRunDTO request) {
        long timeout = request.getTimeoutMillis() == null ? DEFAULT_TIMEOUT_MILLIS : request.getTimeoutMillis();
        if (timeout < 1 || timeout > MAX_TIMEOUT_MILLIS) {
            throw exception(AI_REQUEST_INVALID);
        }
        return timeout;
    }

    private static AiSubjectType parseSubjectType(String value) {
        if (value == null || value.isBlank()) {
            throw exception(AI_REQUEST_INVALID);
        }
        try {
            return AiSubjectType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw exception(AI_REQUEST_INVALID);
        }
    }

    private static List<String> splitActions(String actions) {
        if (!StringUtils.hasText(actions)) {
            return List.of();
        }
        return Arrays.stream(actions.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private static void validate(AiServiceDebugRunDTO request) {
        if (request == null
                || request.getServiceId() == null
                || !StringUtils.hasText(request.getUserMessage())
                || !StringUtils.hasText(request.getDataLevel())
                || !StringUtils.hasText(request.getTestSubjectType())) {
            throw exception(AI_REQUEST_INVALID);
        }
        if (AiSubjectType.parse(request.getTestSubjectType())
                        .filter(type -> AiSubjectType.USER == type)
                        .isPresent()
                && !StringUtils.hasText(request.getTestSubjectId())) {
            // USER 测试主体必须显式给出外部用户标识：不接受"当前登录人"这类隐式主体
            throw exception(AI_REQUEST_INVALID);
        }
        AiContextBudget.of(request.getMaxMessages(), request.getMaxTokens(), null);
    }
}
