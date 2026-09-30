package com.basicframework.module.ai.service.tool;

import com.basicframework.module.ai.dal.dataobject.mcp.AiMcpDiscoveryRunDO;
import com.basicframework.module.ai.dal.mysql.mcp.AiMcpDiscoveryRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发现运行留痕的独立记录器（X07）。
 *
 * <p><b>为什么必须独立事务</b>：失败留痕的价值恰恰在于"失败之后还能查到"。
 * 若把 {@code runMapper.insert} 留在 {@code discover} 的同一个事务里，
 * 紧随其后的抛异常会把留痕一起回滚——于是"断线有界终止"这件事在数据库里
 * 什么都不剩，运维只能靠日志猜，正好违背本卡"不得静默降级"的要求。
 *
 * <p>{@code REQUIRES_NEW} 让留痕先独立提交，外层事务随后回滚也不影响它。
 * 代价是留痕不会随业务事务回滚——但这正是我们要的：它记录的是
 * "一次尝试发生过什么"，不是"业务是否成功"。
 */
@Component
@RequiredArgsConstructor
public class AiMcpDiscoveryRunRecorder {

    private final AiMcpDiscoveryRunMapper runMapper;

    /**
     * 记录一次失败的发现尝试（独立事务提交）。
     *
     * @param connectorId    连接器编号
     * @param serverName     服务端实现名（失败时通常为空）
     * @param protocolVersion 协商出的协议版本（失败时通常为空）
     * @param attempts       实际尝试次数（有界的直接证据）
     * @param termination    终止原因名
     * @param failureCode    稳定失败编号
     * @param toolCount      观察到的工具条数（失败恒为 0）
     * @param newToolCount   新增草稿数
     * @param blockedCount   漂移阻断数
     * @param elapsedMillis  耗时
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordFailure(
            Long connectorId,
            String serverName,
            String protocolVersion,
            int attempts,
            String termination,
            Integer failureCode,
            int toolCount,
            int newToolCount,
            int blockedCount,
            long elapsedMillis) {
        runMapper.insert(new AiMcpDiscoveryRunDO()
                .setConnectorId(connectorId)
                .setServerName(serverName == null ? "" : serverName)
                .setProtocolVersion(protocolVersion == null ? "" : protocolVersion)
                .setAttempts(attempts)
                .setTermination(termination)
                .setFailureCode(failureCode)
                .setToolCount(toolCount)
                .setNewToolCount(newToolCount)
                .setBlockedCount(blockedCount)
                .setElapsedMillis(elapsedMillis)
                .setVersion(0));
    }
}
