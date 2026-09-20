package com.basicframework.module.ai.adapter.connector.http;

import com.basicframework.framework.security.core.crypto.CredentialCipher;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.service.connector.AiConnectorConfig;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 连接器认证头构造（D02）：请求头**只能**由连接器自身配置生成。
 *
 * <p>模型与调用方都不能提供或覆盖请求头：执行链路只把本类返回的头交给出站客户端。
 * 秘密在内存中解密后只放进本次请求，不写日志、不进异常。
 */
@Component
@RequiredArgsConstructor
public class AiConnectorAuthHeaders {

    /** 与 D01 写入密文时一致的 AAD 上下文。 */
    private static final String CREDENTIAL_CONTEXT_PREFIX = "ai_connector:";

    private final CredentialCipher credentialCipher;

    /** 按连接器配置生成认证头（无认证或未配置秘密时为空）。 */
    public Map<String, String> of(AiConnectorDO connector, AiConnectorConfig config) {
        String authType = config.string("authType") == null
                ? "NONE"
                : config.string("authType").toUpperCase(java.util.Locale.ROOT);
        if ("NONE".equals(authType) || !StringUtils.hasText(connector.getCredentialCiphertext())) {
            return Map.of();
        }
        String credential = credentialCipher.decrypt(
                connector.getCredentialCiphertext(), CREDENTIAL_CONTEXT_PREFIX + connector.getId());
        if ("BEARER".equals(authType)) {
            return Map.of("Authorization", "Bearer " + credential);
        }
        // BASIC：秘密按 "用户名:密码" 提供
        return Map.of(
                "Authorization",
                "Basic " + Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.UTF_8)));
    }
}
