package com.basicframework.framework.ai.core.http;

import java.util.concurrent.CompletableFuture;

/**
 * 出站 HTTP 客户端契约（F09 冻结）：平台所有外部调用（模型端点、连接器、Webhook）都必须经由此边界，
 * 不允许业务代码直接使用 {@code RestTemplate}/{@code HttpClient} 访问外部目标。
 *
 * <p>取消语义：{@link #executeAsync} 返回的 future 取消即中止本次调用；调用方不得依赖“取消一定已到达上游”，
 * 需要幂等的上游写入必须另有去重键。资源清理：{@link #close()} 之后不再接受新请求。
 */
public interface ExternalHttpClient extends AutoCloseable {

    /**
     * 同步执行一次出站请求。
     *
     * @param request 服务端构造的请求
     * @return 受大小上限约束的响应
     * @throws ExternalHttpException 目标未允许、私网被拒、超时、超限或连接失败
     */
    ExternalHttpResponse execute(ExternalHttpRequest request);

    /**
     * 异步执行；future 取消表示本次调用中止（取消语义见接口说明）。
     */
    CompletableFuture<ExternalHttpResponse> executeAsync(ExternalHttpRequest request);

    /** 关闭客户端：释放连接资源并拒绝后续请求。 */
    @Override
    void close();
}
