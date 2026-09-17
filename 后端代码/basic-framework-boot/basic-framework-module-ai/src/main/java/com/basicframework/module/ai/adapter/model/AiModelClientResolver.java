package com.basicframework.module.ai.adapter.model;

import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelClientFactory;
import com.basicframework.framework.ai.core.model.ModelEndpointSnapshot;
import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelPort;
import com.basicframework.framework.security.core.crypto.CredentialCipher;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointRevisionDO;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 模型客户端解析器（M02）：把"端点配置 + 当前不可变版本 + 解密后的凭据"组装成快照，
 * 交给接缝工厂创建受管客户端。
 *
 * <p>约束：停用或不存在的端点直接拒绝；未配置凭据拒绝；凭据只在内存中解密后传入工厂，
 * 不写入任何缓存、日志或响应；端点被修改/轮换/停用后由调用方调用 {@link #invalidate(Long)} 关闭旧客户端。
 */
@Component
@RequiredArgsConstructor
public class AiModelClientResolver {

    /** 凭据加密的 AAD 上下文，必须与 AiModelEndpointServiceImpl 写入时一致。 */
    private static final String CREDENTIAL_CONTEXT_PREFIX = "ai_model_endpoint:";

    private final AiModelEndpointService endpointService;

    private final CredentialCipher credentialCipher;

    /**
     * 懒解析：工厂只在启用 AI 能力时装配；未启用时调用模型会得到明确错误，
     * 而不是让整个应用上下文因缺少 Bean 起不来。
     */
    private final ObjectProvider<ModelClientFactory> modelClientFactoryProvider;

    /** 按当前启用的端点与当前配置版本解析受管客户端。 */
    public ModelPort resolve(Long endpointId) {
        AiModelEndpointDO endpoint = endpointService.getEnabledEndpoint(endpointId);
        AiModelEndpointRevisionDO revision = currentRevision(endpoint);
        return modelClientFactory()
                .getOrCreate(new ModelEndpointSnapshot(
                        endpoint.getId(),
                        endpoint.getConfigRevision(),
                        endpoint.getCredentialRevision(),
                        endpoint.getProvider(),
                        endpoint.getBaseUrl(),
                        revision.getModelId(),
                        parseCapabilities(revision.getCapabilities()),
                        decryptCredential(endpoint)));
    }

    /**
     * 解析探测专用客户端（M04）：与 {@link #resolve(Long)} 相同，但**不要求端点已启用**。
     *
     * <p>探测的目的是在启用前/停用后验证真实可用性；停用状态不得掩盖探测结论，
     * 因此这里只要求端点存在、版本完整且凭据可解密。
     */
    public ModelPort resolveForProbe(Long endpointId) {
        AiModelEndpointDO endpoint = endpointService.getEndpoint(endpointId);
        AiModelEndpointRevisionDO revision = currentRevision(endpoint);
        return modelClientFactory()
                .getOrCreate(new ModelEndpointSnapshot(
                        endpoint.getId(),
                        endpoint.getConfigRevision(),
                        endpoint.getCredentialRevision(),
                        endpoint.getProvider(),
                        endpoint.getBaseUrl(),
                        revision.getModelId(),
                        parseCapabilities(revision.getCapabilities()),
                        decryptCredential(endpoint)));
    }

    /** 端点被修改、轮换凭据或停用后关闭其全部客户端，旧客户端不再接受新请求。 */
    public void invalidate(Long endpointId) {
        modelClientFactory().invalidate(endpointId);
    }

    private ModelClientFactory modelClientFactory() {
        ModelClientFactory factory = modelClientFactoryProvider.getIfAvailable();
        if (factory == null) {
            throw new ModelException(ModelException.Reason.AI_DISABLED, "AI 能力未启用，无法创建模型客户端");
        }
        return factory;
    }

    private AiModelEndpointRevisionDO currentRevision(AiModelEndpointDO endpoint) {
        return endpointService.getRevisions(endpoint.getId()).stream()
                .findFirst()
                .orElseThrow(() -> new ModelException(ModelException.Reason.ENDPOINT_NOT_FOUND, "端点缺少配置版本"));
    }

    private static Set<ModelCapability> parseCapabilities(String capabilities) {
        if (capabilities == null || capabilities.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(capabilities.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> {
                    try {
                        return ModelCapability.valueOf(value);
                    } catch (IllegalArgumentException exception) {
                        throw new ModelException(ModelException.Reason.CAPABILITY_UNSUPPORTED, "端点声明了未知能力", exception);
                    }
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    private String decryptCredential(AiModelEndpointDO endpoint) {
        if (endpoint.getCredentialRevision() == null
                || endpoint.getCredentialRevision() <= 0
                || endpoint.getCredentialCiphertext() == null) {
            throw new ModelException(ModelException.Reason.CREDENTIAL_UNAVAILABLE, "端点未配置凭据");
        }
        return credentialCipher.decrypt(
                endpoint.getCredentialCiphertext(), CREDENTIAL_CONTEXT_PREFIX + endpoint.getId());
    }
}
