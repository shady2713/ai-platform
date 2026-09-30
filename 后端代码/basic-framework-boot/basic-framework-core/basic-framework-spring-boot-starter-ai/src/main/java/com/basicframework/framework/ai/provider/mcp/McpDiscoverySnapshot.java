package com.basicframework.framework.ai.provider.mcp;

import java.util.List;

/**
 * 一次 MCP 工具发现的结果（X07）。
 *
 * <p>两条承重不变量：
 * <ol>
 *   <li><b>成功与失败在类型上可区分</b>：失败时 {@link #tools()} 恒为空列表，调用方无法把
 *       "连不上"误当成"上游没有工具"。这是"不得静默降级"的落点——静默降级的典型写法是
 *       catch 住异常返回 {@code List.of()}，本 record 用终止原因堵死了这条路；</li>
 *   <li><b>尝试次数是结果的一部分</b>：{@link #attempts()} 让"重试了几次才终止"可观测、可断言，
 *       有界重试因此不是"实现细节"而是可验收事实。</li>
 * </ol>
 *
 * @param serverName       服务端实现名（协议协商所得，仅供审计展示）
 * @param protocolVersion  协商出的 MCP 协议版本（漂移判定的依据）
 * @param tools            工具清单；失败时为空列表
 * @param attempts         实际发起的尝试次数（1 表示未重试）
 * @param termination      终止原因（失败时必有值）
 */
public record McpDiscoverySnapshot(
        String serverName,
        String protocolVersion,
        List<McpToolDescriptor> tools,
        int attempts,
        McpTermination termination) {

    public McpDiscoverySnapshot {
        serverName = serverName == null ? "" : serverName;
        protocolVersion = protocolVersion == null ? "" : protocolVersion;
        // 防御性归一：终止原因缺失一律按最严格处理，绝不"猜成成功"
        termination = termination == null ? McpTermination.PROTOCOL_ERROR : termination;
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (attempts < 1) {
            // 至少发起过一次尝试才有事实可言；0 次不可能发生，归一到 1 以免下游算出负数语义
            attempts = 1;
        }
    }

    /** 是否成功（只有成功才允许消费 {@link #tools()}）。 */
    public boolean succeeded() {
        return termination == McpTermination.SUCCESS;
    }

    /** 成功时的工具清单便捷访问；失败即空列表（不会把失败伪装成"没有工具"）。 */
    public List<McpToolDescriptor> toolsOrEmpty() {
        return succeeded() ? tools : List.of();
    }
}
