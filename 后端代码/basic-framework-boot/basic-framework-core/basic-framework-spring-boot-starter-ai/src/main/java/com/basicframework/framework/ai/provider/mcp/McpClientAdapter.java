package com.basicframework.framework.ai.provider.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * MCP 工具发现适配器（X07）：**有界重试 + 明确终止 + 绝不代执行**。
 *
 * <h2>为什么重试逻辑必须写在这里</h2>
 * <p>远程断线最常见的两种错误实现是：无限重连（把一次网络抖动变成对上游的持续压力），
 * 以及静默降级（catch 住异常返回空清单，让"连不上"被读成"上游没有工具"）。本类对两者都给出结构答案：
 * <ul>
 *   <li><b>有界</b>：尝试次数由 {@link McpServerEndpoint#maxAttempts()} 决定，且该值在端点
 *       record 的构造器里就被夹紧到 1..{@value McpServerEndpoint#MAX_ATTEMPTS_CEILING}——
 *       上界是<b>类型</b>保证的，不是"约定"；</li>
 *   <li><b>不重试无意义的失败</b>：只有超时与网络不可达可重试；授权被拒、协议错误、地址被拒
 *       重试同样的输入只会得到同样的拒绝（见 {@link McpClientException#retryable()}）；</li>
 *   <li><b>明确终止</b>：耗尽后抛 {@link McpClientException}，终止原因可枚举、可断言、可映射成
 *       稳定错误码。失败路径<b>不产出</b> {@link McpDiscoverySnapshot}，因此下游无法把失败
 *       误读成"工具清单为空"——这正是"不得静默降级"的落点。</li>
 * </ul>
 *
 * <h2>版本漂移默认拒绝</h2>
 * <p>协议版本不在 {@code allowedProtocolVersions} 内即终止（{@link McpTermination#PROTOCOL_VERSION_UNSUPPORTED}），
 * <b>不</b>降级到"能用就行"的旧版本：降级协商意味着对上游行为做出平台没有验证过的假设。
 *
 * <h2>只发现，不执行</h2>
 * <p>本类只调用 {@link McpClientSession#listTools()}；端口上不存在执行方法（见该接口说明），
 * 因此"发现"在结构上无法变成"执行"。
 */
public class McpClientAdapter {

    private final McpSessionFactory sessionFactory;

    private final Set<String> allowedProtocolVersions;

    private final int maxTools;

    /**
     * @param sessionFactory           会话工厂（真实 SDK 实现或测试替身）
     * @param allowedProtocolVersions  允许的协议版本；空集合表示**一律拒绝**（默认拒绝）
     * @param maxTools                 单次发现的工具条数上限（超出即拒绝，不截断）
     */
    public McpClientAdapter(McpSessionFactory sessionFactory, Set<String> allowedProtocolVersions, int maxTools) {
        this.sessionFactory = sessionFactory;
        this.allowedProtocolVersions = allowedProtocolVersions == null ? Set.of() : Set.copyOf(allowedProtocolVersions);
        this.maxTools = maxTools <= 0 ? 1 : maxTools;
    }

    /**
     * 执行一次工具发现。
     *
     * @throws McpClientException 任何终止（失败）都抛出；只有成功才返回
     */
    public McpDiscoverySnapshot discover(McpServerEndpoint endpoint, McpEndpointPolicy policy) {
        if (endpoint == null || policy == null) {
            throw new McpClientException(McpTermination.ADDRESS_DENIED, "MCP 端点声明缺失");
        }
        // 先判地址：默认拒绝发生在**任何网络动作之前**
        policy.requireAllowed(endpoint.baseUri());
        if (allowedProtocolVersions.isEmpty()) {
            // 未配置允许版本 = 没有任何协议版本被批准 = 拒绝，而不是"先连上再看看"
            throw new McpClientException(McpTermination.PROTOCOL_VERSION_UNSUPPORTED, "未配置允许的 MCP 协议版本");
        }
        McpClientException lastFailure = null;
        for (int attempt = 1; attempt <= endpoint.maxAttempts(); attempt++) {
            try {
                return discoverOnce(endpoint, policy, attempt);
            } catch (McpClientException failure) {
                lastFailure = withAttempts(failure, attempt);
                if (!lastFailure.retryable() || attempt == endpoint.maxAttempts()) {
                    // 不可重试的失败立即终止；有界重试耗尽同样立即终止（不进入下一次循环）
                    throw lastFailure;
                }
            }
        }
        // 循环正常退出只可能是最后一次失败（上面已终止）；保留兜底以免未来改动引入"无人终止"的静默成功
        throw lastFailure == null
                ? new McpClientException(McpTermination.RETRIES_EXHAUSTED, "MCP 发现未取得结果", endpoint.maxAttempts())
                : lastFailure;
    }

    /**
     * 给失败补上"这是第几次尝试"。
     *
     * <p>验收 3 要求"有界"可被断言：把尝试次数放进异常而不是只放进日志，
     * 集成测试就能直接断言"最多试了 N 次就停"，而不必去 grep 日志。
     */
    private static McpClientException withAttempts(McpClientException failure, int attempt) {
        return attempt == failure.attempts()
                ? failure
                : new McpClientException(failure.termination(), failure.getMessage(), attempt);
    }

    /** 单次尝试：打开会话 → 校验协议版本 → 取工具清单。 */
    private McpDiscoverySnapshot discoverOnce(McpServerEndpoint endpoint, McpEndpointPolicy policy, int attempt) {
        try (McpClientSession session = sessionFactory.open(endpoint, policy)) {
            String protocolVersion = session.protocolVersion();
            if (!allowedProtocolVersions.contains(protocolVersion)) {
                // 版本漂移：不降级、不"先用着"
                throw new McpClientException(McpTermination.PROTOCOL_VERSION_UNSUPPORTED, "MCP 协议版本不在允许清单内");
            }
            List<McpToolDescriptor> tools = session.listTools();
            if (tools == null) {
                throw new McpClientException(McpTermination.PROTOCOL_ERROR, "MCP 工具清单为空响应");
            }
            if (tools.size() > maxTools) {
                // 超限拒绝而不是截断：截断后的清单会被当成"上游就这些工具"
                throw new McpClientException(McpTermination.RESPONSE_TOO_LARGE, "MCP 工具清单超过单次发现上限");
            }
            return new McpDiscoverySnapshot(
                    session.serverName(), protocolVersion, copyOf(tools), attempt, McpTermination.SUCCESS);
        } catch (McpClientException failure) {
            throw failure;
        } catch (RuntimeException unexpected) {
            // 传输实现抛出的其它异常统一收敛成"不可读"：不把上游异常类型/正文透出边界
            throw new McpClientException(McpTermination.UNREACHABLE, "MCP 会话建立或读取失败");
        }
    }

    private static List<McpToolDescriptor> copyOf(List<McpToolDescriptor> tools) {
        return tools.isEmpty() ? List.of() : List.copyOf(new ArrayList<>(tools));
    }
}
