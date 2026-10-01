package com.basicframework.framework.ai.provider.springai;

import com.basicframework.framework.ai.core.http.ExternalHttpClient;
import com.basicframework.framework.ai.core.http.ExternalHttpRequest;
import com.basicframework.framework.ai.core.http.ExternalHttpResponse;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.AbstractClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 响应式传输适配器：把 Spring 的 {@link ExchangeFunction} 桥到 F09 受控出站边界（M07）。
 *
 * <p>覆盖走 {@code WebClient} 的流式通道：聊天流式（{@code OpenAiApi.chatCompletionStream}）与
 * 音频流式（{@code OpenAiAudioApi.stream}）。守卫是请求/响应边界（返回受大小上限约束的字节数组），
 * 因此这里把请求体缓冲后一次性执行，再把响应体交给 Reactor：
 * <b>流式通道的响应不再是增量到达的</b>，且同样受 {@code basic-framework.ai.http.max-response-bytes}
 * 与 {@code read-timeout} 约束。这是 F09 边界的既有语义（守卫不做流式）带来的残余风险，
 * 证据文档已逐条列明；本卡不修改 {@code core/http}，因此不改守卫契约。
 *
 * <p>守卫拒绝以 {@code Mono} 错误信号上抛（不包成 IO 异常），稳定错误码才立得住。
 */
final class GuardedExchangeFunction implements ExchangeFunction {

    private static final ExchangeStrategies STRATEGIES = ExchangeStrategies.withDefaults();

    private final ExternalHttpClient httpClient;

    GuardedExchangeFunction(ExternalHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public Mono<ClientResponse> exchange(ClientRequest request) {
        // 守卫是阻塞 IO：放到有界弹性线程池，避免占用 Reactor 事件循环
        return Mono.fromCallable(() -> guardedExchange(request)).subscribeOn(Schedulers.boundedElastic());
    }

    private ClientResponse guardedExchange(ClientRequest request) {
        ExternalHttpResponse response = httpClient.execute(new ExternalHttpRequest(
                request.method().name(),
                request.url().toString(),
                GuardedExternalHttpTransport.toGuardHeaders(request.headers()),
                writeBody(request),
                null));
        return ClientResponse.create(HttpStatusCode.valueOf(response.status()))
                .headers(headers -> response.headers().forEach(headers::set))
                .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(
                        response.body() == null ? new byte[0] : response.body())))
                .build();
    }

    /** 把 {@link ClientRequest} 的响应式请求体（BodyInserter）落成字节数组；无请求体时返回 null。 */
    private static byte[] writeBody(ClientRequest request) {
        BufferingReactiveRequest target = new BufferingReactiveRequest(request.method(), request.url());
        request.writeTo(target, STRATEGIES).block();
        return target.body();
    }

    /**
     * 收集 Spring 写入的请求体字节；只作为 {@code BodyInserter} 的落地目标，不发起任何网络动作。
     *
     * <p>包内可见以便契约测试直接覆盖分块写入、提交与落地这几条路径（与 F09 守卫把
     * {@code mapFailure}/{@code rejectBlockedAddresses} 放开到包内可见同一做法）。
     */
    static final class BufferingReactiveRequest extends AbstractClientHttpRequest {

        private final HttpMethod method;

        private final URI uri;

        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        BufferingReactiveRequest(HttpMethod method, URI uri) {
            this.method = method;
            this.uri = uri;
        }

        byte[] body() {
            return body.size() == 0 ? null : body.toByteArray();
        }

        @Override
        public HttpMethod getMethod() {
            return method;
        }

        @Override
        public URI getURI() {
            return uri;
        }

        @Override
        public <T> T getNativeRequest() {
            return null;
        }

        @Override
        public DataBufferFactory bufferFactory() {
            return DefaultDataBufferFactory.sharedInstance;
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> publisher) {
            return DataBufferUtils.write(Flux.from(publisher), body).then();
        }

        @Override
        public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> publisher) {
            return DataBufferUtils.write(Flux.from(publisher).concatMap(Flux::from), body)
                    .then();
        }

        @Override
        public Mono<Void> setComplete() {
            return Mono.empty();
        }

        @Override
        protected Mono<Void> doCommit() {
            return Mono.empty();
        }

        @Override
        protected void applyHeaders() {
            // 请求头已由 Spring 直接写入 headers，无需再落地一次
        }

        @Override
        protected void applyCookies() {
            // 出站请求不带宿主 Cookie（守卫也拒绝该头）
        }
    }
}
