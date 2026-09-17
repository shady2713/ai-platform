package com.basicframework.framework.ai.core.model;

import java.util.Set;

/**
 * 端点快照（M02 冻结）：创建受管客户端所需的最小输入。
 *
 * <p>{@code apiKey} 是**已解密的凭据**，只允许存在于受控内存：不进入缓存键、不进入 toString、
 * 不进入日志与 trace（toString 已改写为脱敏形式）。
 */
public record ModelEndpointSnapshot(
        Long endpointId,
        Integer configRevision,
        Integer credentialRevision,
        String provider,
        String baseUrl,
        String modelId,
        Set<ModelCapability> capabilities,
        String apiKey) {

    /** 缓存键：只由三个版本身份组成。 */
    public ModelEndpointKey key() {
        return new ModelEndpointKey(endpointId, configRevision, credentialRevision);
    }

    /** 快照是否已配置凭据。 */
    public boolean credentialConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 脱敏输出：禁止打印凭据。 */
    @Override
    public String toString() {
        return "ModelEndpointSnapshot[endpointId=" + endpointId + ", configRevision=" + configRevision
                + ", credentialRevision=" + credentialRevision + ", provider=" + provider
                + ", baseUrl=" + baseUrl + ", modelId=" + modelId + ", capabilities=" + capabilities
                + ", apiKey=***]";
    }
}
