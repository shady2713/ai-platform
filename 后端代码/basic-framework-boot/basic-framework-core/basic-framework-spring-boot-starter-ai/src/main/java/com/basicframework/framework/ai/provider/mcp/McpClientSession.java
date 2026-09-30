package com.basicframework.framework.ai.provider.mcp;

import java.util.List;

/**
 * MCP 会话端口（X07）：平台与具体 MCP 实现之间的**唯一**接缝。
 *
 * <p><b>本接口刻意没有 {@code callTool}</b>。MCP SDK 的 {@code McpSyncClient} 提供了
 * {@code callTool}，但本卡只做"发现"：发现的结果是**待审批草稿**，不是执行许可。
 * 如果端口上留着 {@code callTool}，那么"发现"与"执行"之间就没有任何结构性关卡，
 * 任何持有端点声明的代码都能绕过 D08 的注册/Schema/权限/政策四道闸门直接调用远程工具——
 * 也就是把 Spring AI MCP starter 想要的那条"callback 直喂模型"的危险路径重新引进来。
 *
 * <p>把 {@code callTool} 挡在端口之外，远程工具就<b>只能</b>经平台注册与审批进入执行面；
 * 要开放执行能力时，正确做法是新增一个**显式的、经过审批与政策判定**的端口，而不是把 SDK 方法透出。
 */
public interface McpClientSession extends AutoCloseable {

    /** 协商出的 MCP 协议版本（用于版本漂移判定）。 */
    String protocolVersion();

    /** 服务端实现名（仅供审计展示，不参与任何授权判定）。 */
    String serverName();

    /**
     * 列出工具（协议级 {@code tools/list}）。
     *
     * @throws McpClientException 失败时抛出，且必须带稳定终止原因
     */
    List<McpToolDescriptor> listTools();

    @Override
    void close();
}
