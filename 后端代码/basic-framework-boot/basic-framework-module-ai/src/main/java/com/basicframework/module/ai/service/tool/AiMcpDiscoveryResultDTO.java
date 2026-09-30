package com.basicframework.module.ai.service.tool;

/**
 * 一次 MCP 工具发现的摘要（X07）。
 *
 * <p>把"尝试了几次""为什么停""新增几���草稿""阻断几条"一起返回，是为了让验收 3
 * （有界重试 + 明确终止 + 不得静默降级）可以被<b>直接断言</b>，而不是靠读日志推断。
 *
 * @param connectorId  连接器编号
 * @param serverName   服务端实现名（仅供审计）
 * @param protocolVersion 协商出的协议版本
 * @param attempts     实际尝试次数（有界的直接证据）
 * @param termination  终止结果（失败时必有值）
 * @param toolCount    观察到的工具条数
 * @param newToolCount 新生成草稿的条数
 * @param blockedCount 因结构漂移被阻断的条数
 * @param elapsedMillis 耗时（毫秒）
 */
public record AiMcpDiscoveryResultDTO(
        Long connectorId,
        String serverName,
        String protocolVersion,
        int attempts,
        String termination,
        int toolCount,
        int newToolCount,
        int blockedCount,
        long elapsedMillis) {}
