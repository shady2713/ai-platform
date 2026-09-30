package com.basicframework.framework.ai.provider.mcp;

/**
 * MCP 客户端失败（X07）：只携带**稳定终止原因**与**实际尝试次数**，不携带上游正文、主机名或异常细节。
 *
 * <p>为什么不复用通用异常：本类会被 module-ai 的错误码登记表按 {@link #termination()} 映射成
 * 稳定错误码。上游返回的报文可能含内部路径、令牌片段或业务数据，把它们带进异常消息就等于
 * 把远程内容搬进日志与接口响应。因此这里只保留原因枚举、尝试次数与"是否值得重试"这三个判断位。
 *
 * <p>{@link #attempts()} 是验收 3 的直接证据：失败发生时，调用方能确切知道"试了几次才停"，
 * 而不必猜日志里有没有把重试次数打出来。
 */
public class McpClientException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final McpTermination termination;

    private final int attempts;

    public McpClientException(McpTermination termination, String message) {
        this(termination, message, 1);
    }

    public McpClientException(McpTermination termination, String message, int attempts) {
        super(message, null, false, false);
        this.termination = termination == null ? McpTermination.PROTOCOL_ERROR : termination;
        // 归一：尝试次数至少为 1（"没试过就失败"不是本类要表达的状态）
        this.attempts = attempts < 1 ? 1 : attempts;
    }

    public McpTermination termination() {
        return termination;
    }

    /** 实际发起的尝试次数（有界重试的直接证据）。 */
    public int attempts() {
        return attempts;
    }

    /**
     * 该失败是否值得重试。
     *
     * <p>只有**瞬时**故障可重试：超时与网络不可达重试有意义；授权被拒、协议错误、地址被拒、
     * 响应超限重试没有意义（同样的输入必然得到同样的拒绝），重试它们只会放大对上游的压力。
     */
    public boolean retryable() {
        return termination == McpTermination.TIMEOUT || termination == McpTermination.UNREACHABLE;
    }
}
