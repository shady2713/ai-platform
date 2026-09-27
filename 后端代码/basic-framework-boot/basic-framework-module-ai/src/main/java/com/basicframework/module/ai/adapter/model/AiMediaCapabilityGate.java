package com.basicframework.module.ai.adapter.model;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_INPUT_DURATION_EXCEEDED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_INPUT_TOO_LARGE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_INPUT_TYPE_UNSUPPORTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_OUTPUT_EMPTY;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MEDIA_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MODEL_CAPABILITY_NOT_ENABLED;

import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelPort;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointRevisionDO;
import com.basicframework.module.ai.domain.runtime.AiModelFailureCodes;
import com.basicframework.module.ai.service.model.AiModelCapabilityProbeService;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 多模态媒体能力准入闸门（X01，AT-068）。
 *
 * <p>媒体调用必须先通过三道准入，全部在任何客户端解析与网络请求之前完成：
 * <ol>
 *   <li><b>端点可用</b>：存在且启用（停用/不存在按端点错误码拒绝）；</li>
 *   <li><b>能力已声明</b>：当前配置版本的能力集合包含该媒体能力（未声明 → 400）；</li>
 *   <li><b>探测已确认</b>：该能力对应的探测最新结论为 {@code SUPPORTED} 且配置版本与当前一致
 *       （未探测/失败/版本过期 → 400）。</li>
 * </ol>
 *
 * <p><b>无隐藏外发</b>：准入失败时不会解析客户端，也不会调用 {@link ModelPort} 的任何方法；
 * 未开通能力不会静默回退到其它模型/端点/供应商。媒体调用返回的 {@link ModelException}
 * 由本闸门统一映射为平台错误码（不回传上游报文）。
 */
@Component
@RequiredArgsConstructor
public class AiMediaCapabilityGate {

    private final AiModelEndpointService endpointService;

    private final AiModelCapabilityProbeService probeService;

    private final AiModelClientResolver clientResolver;

    /**
     * 媒体调用唯一入口：准入通过后解析受管客户端并执行调用。
     *
     * @param endpointId 端点编号（调用方显式指定，不做候选遍历，因此不存在隐式失败转移）
     * @param capability 媒体能力（{@link ModelCapability#isMedia()} 必须为真）
     * @param call       实际调用（例如 {@code port -> port.understandImage(request)}）
     * @return 调用结果
     */
    public <T> T invoke(Long endpointId, ModelCapability capability, Function<ModelPort, T> call) {
        assertAdmitted(endpointId, capability);
        ModelPort port = clientResolver.resolve(endpointId);
        try {
            return call.apply(port);
        } catch (ModelException exception) {
            throw toServiceException(exception);
        }
    }

    /**
     * 准入校验（可在调用前单独调用）：失败抛平台错误码，且不解析客户端、不发生网络请求。
     *
     * @throws ServiceException {@code AI_MODEL_ENDPOINT_NOT_FOUND}/{@code AI_MODEL_ENDPOINT_DISABLED}
     *                          （端点不可用）或 {@code AI_MODEL_CAPABILITY_NOT_ENABLED}（未声明/未确认）
     */
    public void assertAdmitted(Long endpointId, ModelCapability capability) {
        if (capability == null || !capability.isMedia()) {
            throw new IllegalArgumentException("媒体准入闸门只接受媒体能力：" + capability);
        }
        AiModelEndpointDO endpoint = endpointService.getEnabledEndpoint(endpointId);
        if (!declaredCapabilities(endpoint.getId()).contains(capability.name())) {
            throw exception(AI_MODEL_CAPABILITY_NOT_ENABLED);
        }
        if (!probeConfirmed(endpoint, capability)) {
            throw exception(AI_MODEL_CAPABILITY_NOT_ENABLED);
        }
    }

    /** 当前配置版本的声明能力（与能力总览同源：取倒序版本列表的第一条）。 */
    private List<String> declaredCapabilities(Long endpointId) {
        List<AiModelEndpointRevisionDO> revisions = endpointService.getRevisions(endpointId);
        if (revisions.isEmpty() || revisions.get(0).getCapabilities() == null) {
            return List.of();
        }
        return Arrays.stream(revisions.get(0).getCapabilities().split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    /**
     * 探测确认：该能力对应的探测最新结论为 SUPPORTED，且结论的配置版本等于当前配置版本。
     *
     * <p>配置版本变化后旧结论不再覆盖当前配置，必须重探；探测结论 DTO 不含凭据版本，
     * 凭据轮换后是否重探由管理员决定（轮换后建议立即重探，见准入矩阵）。
     */
    private boolean probeConfirmed(AiModelEndpointDO endpoint, ModelCapability capability) {
        String expectedKind = capability.probeKind().name();
        return probeService.getLatestResults(endpoint.getId()).stream()
                .anyMatch(result -> expectedKind.equals(result.getProbeKind())
                        && "SUPPORTED".equals(result.getStatus())
                        && endpoint.getConfigRevision() != null
                        && endpoint.getConfigRevision().equals(result.getConfigRevision()));
    }

    /** 媒体失败原因到平台错误码的稳定映射；未列入的原因沿用运行链路的既有映射。 */
    private static ServiceException toServiceException(ModelException exception) {
        return switch (exception.getReason()) {
            case CAPABILITY_NOT_ENABLED -> exception(AI_MODEL_CAPABILITY_NOT_ENABLED);
            case MEDIA_INPUT_INVALID -> exception(AI_MEDIA_REQUEST_INVALID);
            case MEDIA_INPUT_TYPE_UNSUPPORTED -> exception(AI_MEDIA_INPUT_TYPE_UNSUPPORTED);
            case MEDIA_INPUT_TOO_LARGE -> exception(AI_MEDIA_INPUT_TOO_LARGE);
            case MEDIA_INPUT_DURATION_EXCEEDED -> exception(AI_MEDIA_INPUT_DURATION_EXCEEDED);
            case MEDIA_OUTPUT_EMPTY -> exception(AI_MEDIA_OUTPUT_EMPTY);
            default -> AiModelFailureCodes.toServiceException(exception);
        };
    }
}
