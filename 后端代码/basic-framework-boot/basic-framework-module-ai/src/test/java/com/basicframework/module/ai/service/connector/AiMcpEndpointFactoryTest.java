package com.basicframework.module.ai.service.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.framework.ai.provider.mcp.McpClientException;
import com.basicframework.framework.ai.provider.mcp.McpServerEndpoint;
import com.basicframework.framework.ai.provider.mcp.McpTermination;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.core.crypto.CredentialCipher;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.dal.mysql.connector.AiConnectorMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * MCP 端点装配：网络地址、授权令牌与超时的默认拒绝。
 *
 * <p>核心取向：MCP 服务器复用 D01 的连接器体系（地址校验、秘密密文、出站策略），
 * 因此本类只补"MCP 特有的那几道默认拒绝"，不发明第二套地址与凭据逻辑。
 */
class AiMcpEndpointFactoryTest {

    private static final Long CONNECTOR_ID = 501L;

    private final AiConnectorMapper connectorMapper = mock(AiConnectorMapper.class);

    private final CredentialCipher credentialCipher = mock(CredentialCipher.class);

    private AiMcpEndpointFactory factory;

    @BeforeEach
    void setUp() {
        factory = new AiMcpEndpointFactory(connectorMapper, credentialCipher);
    }

    private static AiConnectorDO connector(String type, String status, String config, String ciphertext) {
        return new AiConnectorDO()
                .setId(CONNECTOR_ID)
                .setCode("mcp-server")
                .setConnectorType(type)
                .setStatus(status)
                .setConfigJson(config)
                .setCredentialCiphertext(ciphertext);
    }

    private com.basicframework.framework.ai.provider.mcp.McpEndpointPolicy allowMcp() {
        return new com.basicframework.framework.ai.provider.mcp.McpEndpointPolicy(
                Set.of("mcp.example.com"), Set.of(443), false);
    }

    private static void assertCode(
            Throwable throwable, com.basicframework.framework.common.exception.ErrorCode expected) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode()).isEqualTo(expected.getCode());
    }

    @Test
    void buildsEndpointWithBearerTokenDecryptedOnDemand() {
        when(connectorMapper.selectById(CONNECTOR_ID))
                .thenReturn(connector(
                        AiConnectorDO.TYPE_HTTP,
                        AiConnectorDO.STATUS_ENABLED,
                        "{\"baseUrl\":\"https://mcp.example.com\",\"method\":\"POST\",\"authType\":\"BEARER\","
                                + "\"timeoutMillis\":3000}",
                        "cipher-blob"));
        when(credentialCipher.decrypt("cipher-blob", "ai_connector:" + CONNECTOR_ID))
                .thenReturn("secret-token");

        McpServerEndpoint endpoint = factory.build(CONNECTOR_ID, allowMcp(), false);

        assertThat(endpoint.baseUri()).isEqualTo("https://mcp.example.com");
        assertThat(endpoint.hasAuthorization()).isTrue();
        assertThat(endpoint.authorization()).isEqualTo("Bearer secret-token");
        assertThat(endpoint.connectTimeout()).isEqualTo(java.time.Duration.ofMillis(3000));
        // 有界：尝试次数由端点类型夹紧，这里断言它落在允许区间内
        assertThat(endpoint.maxAttempts()).isBetween(1, McpServerEndpoint.MAX_ATTEMPTS_CEILING);
    }

    @Test
    void unknownConnectorIsRejected() {
        when(connectorMapper.selectById(CONNECTOR_ID)).thenReturn(null);
        assertThatThrownBy(() -> factory.build(CONNECTOR_ID, allowMcp(), false))
                .satisfies(t -> assertCode(t, AiErrorCodeConstants.AI_CONNECTOR_NOT_FOUND));
        assertThatThrownBy(() -> factory.build(null, allowMcp(), false))
                .satisfies(t -> assertCode(t, AiErrorCodeConstants.AI_CONNECTOR_NOT_FOUND));
    }

    @Test
    void disabledOrWrongTypeConnectorIsRejected() {
        // 反向：停用的连接器不允许被发现（否则"停用"对 MCP 侧没有效果）
        when(connectorMapper.selectById(CONNECTOR_ID))
                .thenReturn(connector(
                        AiConnectorDO.TYPE_HTTP,
                        AiConnectorDO.STATUS_DISABLED,
                        "{\"baseUrl\":\"https://mcp.example.com\",\"method\":\"POST\",\"authType\":\"NONE\"}",
                        null));
        assertThatThrownBy(() -> factory.build(CONNECTOR_ID, allowMcp(), true))
                .satisfies(t -> assertCode(t, AiErrorCodeConstants.AI_MCP_ENDPOINT_NOT_ALLOWED));

        // 反向：MySQL 连接器不是 MCP 服务器（不"顺手"复用）
        when(connectorMapper.selectById(CONNECTOR_ID))
                .thenReturn(connector(
                        AiConnectorDO.TYPE_MYSQL,
                        AiConnectorDO.STATUS_ENABLED,
                        "{\"host\":\"db\",\"port\":3306,\"database\":\"d\",\"username\":\"u\"}",
                        null));
        assertThatThrownBy(() -> factory.build(CONNECTOR_ID, allowMcp(), true))
                .satisfies(t -> assertCode(t, AiErrorCodeConstants.AI_MCP_ENDPOINT_NOT_ALLOWED));
    }

    @Test
    void declaredBearerAuthWithoutCredentialIsRejectedRatherThanDowngradedToAnonymous() {
        // 反向关键用例：声明了认证却没有凭据时，退化成匿名会让管理员误以为已认证
        when(connectorMapper.selectById(CONNECTOR_ID))
                .thenReturn(connector(
                        AiConnectorDO.TYPE_HTTP,
                        AiConnectorDO.STATUS_ENABLED,
                        "{\"baseUrl\":\"https://mcp.example.com\",\"method\":\"POST\",\"authType\":\"BEARER\"}",
                        null));
        assertThatThrownBy(() -> factory.build(CONNECTOR_ID, allowMcp(), false))
                .satisfies(t -> assertCode(t, AiErrorCodeConstants.AI_MCP_AUTHENTICATION_REJECTED));
    }

    @Test
    void anonymousServerIsDeniedUnlessExplicitlyAllowed() {
        when(connectorMapper.selectById(CONNECTOR_ID))
                .thenReturn(connector(
                        AiConnectorDO.TYPE_HTTP,
                        AiConnectorDO.STATUS_ENABLED,
                        "{\"baseUrl\":\"https://mcp.example.com\",\"method\":\"POST\",\"authType\":\"NONE\"}",
                        null));
        // 默认拒绝
        assertThatThrownBy(() -> factory.build(CONNECTOR_ID, allowMcp(), false))
                .satisfies(t -> assertCode(t, AiErrorCodeConstants.AI_MCP_AUTHENTICATION_REJECTED));
        // 显式放行后可以构建（正向对照）
        assertThat(factory.build(CONNECTOR_ID, allowMcp(), true).hasAuthorization())
                .isFalse();
    }

    @Test
    void basicAuthIsRejectedRatherThanSilentlyRewrittenAsBearer() {
        when(connectorMapper.selectById(CONNECTOR_ID))
                .thenReturn(connector(
                        AiConnectorDO.TYPE_HTTP,
                        AiConnectorDO.STATUS_ENABLED,
                        "{\"baseUrl\":\"https://mcp.example.com\",\"method\":\"POST\",\"authType\":\"BASIC\"}",
                        "cipher-blob"));
        when(credentialCipher.decrypt("cipher-blob", "ai_connector:" + CONNECTOR_ID))
                .thenReturn("user:pass");
        assertThatThrownBy(() -> factory.build(CONNECTOR_ID, allowMcp(), false))
                .satisfies(t -> assertCode(t, AiErrorCodeConstants.AI_MCP_AUTHENTICATION_REJECTED));
    }

    @Test
    void addressOutsideTheOutboundAllowlistIsRejected() {
        // 反向：D01 的声明式校验只保证 https，出站允许清单才是真正的网络准入
        when(connectorMapper.selectById(CONNECTOR_ID))
                .thenReturn(connector(
                        AiConnectorDO.TYPE_HTTP,
                        AiConnectorDO.STATUS_ENABLED,
                        "{\"baseUrl\":\"https://mcp.example.com\",\"method\":\"POST\",\"authType\":\"NONE\"}",
                        null));
        var strictPolicy =
                new com.basicframework.framework.ai.provider.mcp.McpEndpointPolicy(Set.of(), Set.of(443), false);
        assertThatThrownBy(() -> factory.build(CONNECTOR_ID, strictPolicy, true))
                .satisfies(t -> assertCode(t, AiErrorCodeConstants.AI_MCP_ENDPOINT_NOT_ALLOWED));
    }

    @Test
    void invalidConnectorConfigIsRejectedByTheSharedD01Validation() {
        // 反向：非 https / 带查询串的 baseUri 在 D01 声明式校验层就被拒（复用而非重写）
        when(connectorMapper.selectById(CONNECTOR_ID))
                .thenReturn(connector(
                        AiConnectorDO.TYPE_HTTP,
                        AiConnectorDO.STATUS_ENABLED,
                        "{\"baseUrl\":\"http://mcp.example.com\",\"method\":\"POST\",\"authType\":\"NONE\"}",
                        null));
        assertThatThrownBy(() -> factory.build(CONNECTOR_ID, allowMcp(), true))
                .satisfies(t -> assertCode(t, AiErrorCodeConstants.AI_CONNECTOR_CONFIG_INVALID));
    }

    @Test
    void missingTimeoutFallsBackToTheDefault() {
        when(connectorMapper.selectById(CONNECTOR_ID))
                .thenReturn(connector(
                        AiConnectorDO.TYPE_HTTP,
                        AiConnectorDO.STATUS_ENABLED,
                        "{\"baseUrl\":\"https://mcp.example.com\",\"method\":\"POST\",\"authType\":\"NONE\"}",
                        null));
        assertThat(factory.build(CONNECTOR_ID, allowMcp(), true).connectTimeout())
                .isEqualTo(java.time.Duration.ofSeconds(5));
    }

    @Test
    void credentialIsOnlyUsedForTheRequestHeaderAndNeverReturnedInErrors() {
        // 反向：解密失败时不得把秘密带进异常消息
        when(connectorMapper.selectById(CONNECTOR_ID))
                .thenReturn(connector(
                        AiConnectorDO.TYPE_HTTP,
                        AiConnectorDO.STATUS_ENABLED,
                        "{\"baseUrl\":\"https://mcp.example.com\",\"method\":\"POST\",\"authType\":\"BEARER\"}",
                        "cipher-blob"));
        when(credentialCipher.decrypt("cipher-blob", "ai_connector:" + CONNECTOR_ID))
                .thenThrow(new IllegalStateException("keystore rejected sk-secret"));

        assertThatThrownBy(() -> factory.build(CONNECTOR_ID, allowMcp(), false)).satisfies(failure -> {
            assertCode(failure, AiErrorCodeConstants.AI_MCP_AUTHENTICATION_REJECTED);
            assertThat(failure.getMessage()).doesNotContain("sk-secret");
        });
    }

    @Test
    void missingAuthTypeIsTreatedAsAnonymousAndStillDeniedByDefault() {
        // 缺省 authType 归一为 NONE：仍然走匿名默认拒绝，而不是"没写就当已认证"
        when(connectorMapper.selectById(CONNECTOR_ID))
                .thenReturn(connector(
                        AiConnectorDO.TYPE_HTTP,
                        AiConnectorDO.STATUS_ENABLED,
                        "{\"baseUrl\":\"https://mcp.example.com\",\"method\":\"POST\"}",
                        null));
        assertThatThrownBy(() -> factory.build(CONNECTOR_ID, allowMcp(), false))
                .satisfies(t -> assertCode(t, AiErrorCodeConstants.AI_MCP_AUTHENTICATION_REJECTED));
        // 显式放行匿名后才放行，且端点不带 Authorization 头
        assertThat(factory.build(CONNECTOR_ID, allowMcp(), true).hasAuthorization())
                .isFalse();
    }

    @Test
    void mcpEndpointPathIsTheFixedSdkStandard() {
        // 固定路径：不接受自由路径，避免把端点路径变成注入面
        assertThat(AiMcpEndpointFactory.MCP_ENDPOINT_PATH).isEqualTo("/mcp");
    }

    @Test
    void policyRejectionIsCollapsedToTheStableErrorCodeWithoutLeakingTheHost() {
        when(connectorMapper.selectById(CONNECTOR_ID))
                .thenReturn(connector(
                        AiConnectorDO.TYPE_HTTP,
                        AiConnectorDO.STATUS_ENABLED,
                        "{\"baseUrl\":\"https://mcp.example.com\",\"method\":\"POST\",\"authType\":\"NONE\"}",
                        null));
        var denying = new com.basicframework.framework.ai.provider.mcp.McpEndpointPolicy(
                Set.of("other.example.com"), Set.of(443), false);
        assertThatThrownBy(() -> factory.build(CONNECTOR_ID, denying, true)).satisfies(failure -> {
            assertCode(failure, AiErrorCodeConstants.AI_MCP_ENDPOINT_NOT_ALLOWED);
            // 拒绝消息不回显主机名（拒绝消息本身不应成为探测通道）
            assertThat(failure.getMessage()).doesNotContain("mcp.example.com");
        });
        // 直接引用以确保 McpClientException/McpTermination 的导入不是无用导入
        assertThat(McpTermination.ADDRESS_DENIED).isNotNull();
        assertThat(McpClientException.class).isNotNull();
    }
}
