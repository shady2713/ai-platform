package com.basicframework.framework.ai.provider.mcp;

/**
 * MCP 会话工厂（X07）：把端点声明变成一次可关闭的会话。
 *
 * <p>单独成接口是为了让"协议实现"与"有界重试策略"解耦：重试、终止与漂移判定由
 * {@link McpClientAdapter} 负责，传输细节（怎么握手、怎么带令牌）由本接口的实现负责。
 * 测试因此可以用一个确定性的假传输断言"重试了几次、为什么停"，而不必真的把网络抖起来。
 */
@FunctionalInterface
public interface McpSessionFactory {

    /**
     * 打开一次会话（内部完成 initialize 握手）。
     *
     * @throws McpClientException 握手失败时抛出（必须带稳定终止原因）
     */
    McpClientSession open(McpServerEndpoint endpoint, McpEndpointPolicy policy);
}
