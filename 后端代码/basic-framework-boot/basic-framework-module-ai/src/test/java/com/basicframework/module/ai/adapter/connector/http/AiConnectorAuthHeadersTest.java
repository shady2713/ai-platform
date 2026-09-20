package com.basicframework.module.ai.adapter.connector.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.framework.security.core.crypto.CredentialCipher;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.service.connector.AiConnectorConfig;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** D02 请求头构造：请求头只能来自连接器自身配置，秘密只在内存中解密后进本次请求。 */
class AiConnectorAuthHeadersTest {

    private final CredentialCipher credentialCipher = mock(CredentialCipher.class);

    private final AiConnectorAuthHeaders authHeaders = new AiConnectorAuthHeaders(credentialCipher);

    private static AiConnectorDO connector(String ciphertext) {
        return new AiConnectorDO()
                .setId(71L)
                .setConnectorType(AiConnectorDO.TYPE_HTTP)
                .setCredentialCiphertext(ciphertext);
    }

    private static AiConnectorConfig config(String authType) {
        return AiConnectorConfig.parse(
                "HTTP",
                "{\"baseUrl\":\"https://crm.example.com\",\"method\":\"GET\",\"authType\":\"" + authType + "\"}");
    }

    @Test
    void buildsBearerAndBasicHeadersFromConnectorSecretOnly() {
        when(credentialCipher.decrypt(any(), any())).thenReturn("it-connector-secret");

        assertThat(authHeaders.of(connector("v1:encrypted"), config("BEARER")))
                .containsExactlyEntriesOf(java.util.Map.of("Authorization", "Bearer it-connector-secret"));

        String expectedBasic =
                "Basic " + Base64.getEncoder().encodeToString("it-connector-secret".getBytes(StandardCharsets.UTF_8));
        assertThat(authHeaders.of(connector("v1:encrypted"), config("BASIC")))
                .containsExactlyEntriesOf(java.util.Map.of("Authorization", expectedBasic));

        // 解密上下文必须与写入时一致（AAD 绑定连接器编号）
        org.mockito.Mockito.verify(credentialCipher, org.mockito.Mockito.times(2))
                .decrypt("v1:encrypted", "ai_connector:" + 71L);
    }

    @Test
    void producesNoHeaderWithoutAuthTypeOrSecret() {
        assertThat(authHeaders.of(connector("v1:encrypted"), config("NONE"))).isEmpty();
        assertThat(authHeaders.of(connector(null), config("BEARER")))
                .as("未配置秘密时不带认证头（由执行链路按上游 401 结束）")
                .isEmpty();
        assertThat(authHeaders.of(connector("  "), config("BASIC"))).isEmpty();
    }
}
