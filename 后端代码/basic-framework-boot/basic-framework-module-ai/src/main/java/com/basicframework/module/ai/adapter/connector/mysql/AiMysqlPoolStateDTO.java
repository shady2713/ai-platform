package com.basicframework.module.ai.adapter.connector.mysql;

/**
 * 只读连接池资源状态（D03）：池资源的可观测证据。
 *
 * <p>用途是"池关闭/连接归还"可被验证：关闭后 {@code state = CLOSED}，
 * 执行结束后 {@code activeConnections = 0}（连接已归还，不泄漏）。
 */
public record AiMysqlPoolStateDTO(
        Long connectorId, String state, int activeConnections, int idleConnections, int totalConnections) {

    /** 池不存在（从未建池或已回收）。 */
    public static final String STATE_ABSENT = "ABSENT";

    /** 池存在且可用。 */
    public static final String STATE_OPEN = "OPEN";

    /** 池已关闭。 */
    public static final String STATE_CLOSED = "CLOSED";

    public static AiMysqlPoolStateDTO absent(Long connectorId) {
        return new AiMysqlPoolStateDTO(connectorId, STATE_ABSENT, 0, 0, 0);
    }

    public static AiMysqlPoolStateDTO closed(Long connectorId) {
        return new AiMysqlPoolStateDTO(connectorId, STATE_CLOSED, 0, 0, 0);
    }
}
