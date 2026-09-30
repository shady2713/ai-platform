package com.basicframework.module.ai.service.connector;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_NOT_FOUND;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MCP_AUTHENTICATION_REJECTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_MCP_ENDPOINT_NOT_ALLOWED;

import com.basicframework.framework.ai.provider.mcp.McpEndpointPolicy;
import com.basicframework.framework.ai.provider.mcp.McpServerEndpoint;
import com.basicframework.framework.security.core.crypto.CredentialCipher;
import com.basicframework.module.ai.dal.dataobject.connector.AiConnectorDO;
import com.basicframework.module.ai.dal.mysql.connector.AiConnectorMapper;
import java.time.Duration;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * MCP 端点装配（X07）：把 D01 已登记的连接器翻译成 {@link McpServerEndpoint}。
 *
 * <p><b>为什么不新建一张 MCP 服务器表</b>：D01 的连接器已经解决了本卡真正需要解决的三件事——
 * 声明式地址校验（{@link AiConnectorConfig}：https、无查询串/片段/用户信息）、
 * 秘密的加密存储与按需解密（{@link CredentialCipher}）、以及出站允许策略。
 * 复用它们意味着 MCP 侧<b>不需要第二套地址与凭据体系</b>（卡片 §9 明确禁止"引入第二套身份/存储体系"）。
 *
 * <p>因此约定：<b>MCP 服务器以 HTTP 连接器类型登记</b>（Streamable HTTP 本就是 HTTP 传输），
 * 端点路径固定为 SDK 的标准 {@code /mcp}。本类在此之上再加三道本卡特有的默认拒绝：
 * <ol>
 *   <li>连接器必须存在、为 HTTP 类型且**启用**（停用/错类型一律拒绝，不"顺手"放行）；</li>
 *   <li>出站允许清单（{@link McpEndpointPolicy}）在发出任何请求前判定地址；</li>
 *   <li>声明了 BEARER 认证却没有凭据 → <b>拒绝</b>（而不是退化成匿名请求：
 *       "本来要认证却没带凭据"如果静默变成匿名，管理员会以为已认证）；</li>
 *   <li>匿名（authType=NONE）默认拒绝，除非运维在准入矩阵里显式放行匿名 MCP 服务器。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class AiMcpEndpointFactory {

    /** 凭据上下文前缀：与 D01 连接器服务保持同一命名空间，避免同一秘密在不同体系里被当成不同秘密。 */
    private static final String CREDENTIAL_CONTEXT_PREFIX = "ai_connector:";

    /** MCP 端点的固定路径（SDK 标准 Streamable HTTP 端点；不接受自由路径）。 */
    static final String MCP_ENDPOINT_PATH = "/mcp";

    /** 未声明时的默认尝试次数（有界；类型构造器还会再夹紧一次）。 */
    private static final int DEFAULT_MAX_ATTEMPTS = 2;

    private static final int DEFAULT_TIMEOUT_MILLIS = 5_000;

    private final AiConnectorMapper connectorMapper;

    private final CredentialCipher credentialCipher;

    /**
     * 装配端点。
     *
     * @param connectorId       连接器编号
     * @param policy            出站地址准入策略
     * @param allowAnonymous    是否允许匿名（authType=NONE）MCP 服务器
     */
    public McpServerEndpoint build(Long connectorId, McpEndpointPolicy policy, boolean allowAnonymous) {
        AiConnectorDO connector = connectorId == null ? null : connectorMapper.selectById(connectorId);
        if (connector == null) {
            throw exception(AI_CONNECTOR_NOT_FOUND);
        }
        if (!AiConnectorDO.TYPE_HTTP.equals(connector.getConnectorType())
                || !AiConnectorDO.STATUS_ENABLED.equals(connector.getStatus())) {
            // 停用与类型不符都拒绝：发现只对"明确登记且启用"的服务器发生
            throw exception(AI_MCP_ENDPOINT_NOT_ALLOWED);
        }
        // 复用 D01 的声明式校验：https、无查询串/片段/用户信息在这里已保证
        AiConnectorConfig config = AiConnectorConfig.parse(AiConnectorDO.TYPE_HTTP, connector.getConfigJson());
        String authType = config.string("authType") == null
                ? "NONE"
                : config.string("authType").toUpperCase(Locale.ROOT);
        String authorization = null;
        if ("NONE".equals(authType)) {
            if (!allowAnonymous) {
                // 匿名默认拒绝：MCP 服务器通常持有真实数据面
                throw exception(AI_MCP_AUTHENTICATION_REJECTED);
            }
        } else if ("BEARER".equals(authType)) {
            if (!StringUtils.hasText(connector.getCredentialCiphertext())) {
                // 声明了认证却没有凭据：拒绝，而不是退化成匿名请求
                throw exception(AI_MCP_AUTHENTICATION_REJECTED);
            }
            String credential = decryptQuietly(connector, connectorId);
            // 令牌只出现在本次请求头里：不写日志、不进异常消息
            authorization = "Bearer " + credential;
        } else {
            // BASIC 不适用于 MCP 的 Authorization 语义，不静默改写为 Bearer
            throw exception(AI_MCP_AUTHENTICATION_REJECTED);
        }
        Integer declaredTimeout = config.integer("timeoutMillis");
        Duration timeout = Duration.ofMillis(
                declaredTimeout == null || declaredTimeout <= 0 ? DEFAULT_TIMEOUT_MILLIS : declaredTimeout);
        McpServerEndpoint endpoint =
                new McpServerEndpoint(config.string("baseUrl"), authorization, timeout, timeout, DEFAULT_MAX_ATTEMPTS);
        // 默认拒绝发生在任何网络动作之前；失败即抛，不返回"尽力而为"的端点
        try {
            policy.requireAllowed(endpoint.baseUri());
        } catch (RuntimeException denied) {
            // 统一收敛成稳定错误码：地址与主机名都不进错误消息（拒绝消息本身不应成为探测通道）
            throw exception(AI_MCP_ENDPOINT_NOT_ALLOWED);
        }
        return endpoint;
    }

    private static String credentialContext(Long connectorId) {
        return CREDENTIAL_CONTEXT_PREFIX + connectorId;
    }

    /**
     * 解密秘密，失败时收敛成稳定错误码。
     *
     * <p>为什么不直接让 {@code credentialCipher} 的异常冒出去：密钥库类异常常常把上下文
     * （密钥别名、明文片段、内部路径）写在消息里，一旦冒泡就会跟着接口响应或日志走。
     * 这里统一收敛成"凭据不可用"，既不泄漏秘密，也给运维一个可据以排查的稳定编号。
     */
    private String decryptQuietly(AiConnectorDO connector, Long connectorId) {
        try {
            return credentialCipher.decrypt(connector.getCredentialCiphertext(), credentialContext(connectorId));
        } catch (RuntimeException unusable) {
            throw exception(AI_MCP_AUTHENTICATION_REJECTED);
        }
    }
}
