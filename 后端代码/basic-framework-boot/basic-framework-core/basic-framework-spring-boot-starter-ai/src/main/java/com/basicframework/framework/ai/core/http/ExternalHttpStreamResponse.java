package com.basicframework.framework.ai.core.http;

import java.util.Map;

/**
 * 流式出站响应（F11）：与 {@link ExternalHttpResponse} **并存**的另一种形态——响应体不整体缓冲，由调用方逐块消费。
 *
 * <p>与请求/响应入口的差别只有"什么时候判定响应体上限"：{@link ExternalHttpClient#execute} 是
 * "先读完再判"，超限整体拒绝；本接口是**读一块判一块**，累计超过
 * {@code basic-framework.ai.http.max-response-bytes} 即以
 * {@link ExternalHttpException.Reason#RESPONSE_TOO_LARGE} **终止流**，此前已交付的块不回收成完整结果。
 *
 * <p>取消与资源：{@link #close()} 释放上游连接且幂等（正常读完、取消后重复调用都安全）；关闭后
 * {@link #readChunk} 返回 -1。调用方必须在不再消费时关闭（try-with-resources 或取消回调），
 * 否则上游连接不会释放。
 */
public interface ExternalHttpStreamResponse extends AutoCloseable {

    /** 响应状态码（3xx 原样返回，是否改址由业务决策，与请求/响应入口一致）。 */
    int status();

    /** 响应头：与 {@link ExternalHttpResponse#headers()} 同一形态（{@code Map<String,String>}，键为小写）。 */
    Map<String, String> headers();

    /** 声明的响应体长度；上游未声明长度（如分块传输的 SSE）时为 -1。 */
    long declaredLength();

    /** 已交付给调用方的字节数：恒不超过 {@code max-response-bytes}。 */
    long deliveredBytes();

    /**
     * 读下一块到 {@code target}。
     *
     * @return 读到的字节数（大于 0），流结束返回 -1
     * @throws ExternalHttpException 累计超过响应体上限（{@link ExternalHttpException.Reason#RESPONSE_TOO_LARGE}）、
     *         读取失败或超时。调用方不需要理解底层异常类型
     */
    int readChunk(byte[] target);

    /** 关闭并释放上游连接；幂等。 */
    @Override
    void close();
}
