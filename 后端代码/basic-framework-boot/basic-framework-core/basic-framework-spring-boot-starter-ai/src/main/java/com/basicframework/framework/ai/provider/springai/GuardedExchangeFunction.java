package com.basicframework.framework.ai.provider.springai;

import com.basicframework.framework.ai.core.http.ExternalHttpClient;
import com.basicframework.framework.ai.core.http.ExternalHttpRequest;
import com.basicframework.framework.ai.core.http.ExternalHttpStreamResponse;
import com.basicframework.framework.ai.core.http.ExternalHttpStreamSupport;
import com.basicframework.framework.ai.core.model.ModelException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.Arrays;
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
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 响应式传输适配器：把 Spring 的 {@link ExchangeFunction} 桥到 F09 受控出站边界（M07 建，F11 改为流式）。
 *
 * <p>覆盖走 {@code WebClient} 的流式通道：聊天流式（{@code OpenAiApi.chatCompletionStream}）与
 * 音频流式（{@code OpenAiAudioApi.stream}）。
 *
 * <p><b>F11 修掉的降级</b>：M07 走守卫的请求/响应入口（整包读进受 {@code max-response-bytes} 约束的字节数组），
 * 因此 SSE 响应是"读完整个再交给 Reactor"——流式不再增量到达，长流还会被整体拒绝。本类改为走守卫的
 * {@link ExternalHttpStreamSupport#openStream} 入口，响应体**逐块**交给 Reactor，首块一到就往下游发。
 *
 * <p><b>治理没有被削弱</b>：改的是守卫的哪个入口，不是哪些闸门。流式请求与阻塞请求共用守卫的
 * {@code prepare}（允许清单、私网判定、协议、请求头卫生、超时），所以流式到非允许清单地址仍然
 * <b>在请求期被拒、请求不离开进程</b>；响应体上限从"读完再整体拒绝"改为"超限终止流并给
 * {@code RESPONSE_TOO_LARGE}"，已发出的块不回收成完整结果。
 *
 * <p><b>为什么不做块对齐</b>：Spring AI 按 {@code bodyToFlux(String.class)} 逐个 DataBuffer 解码，
 * 但 {@code ServerSentEventHttpMessageReader} 对 {@code text/event-stream} 会自行按事件边界重新组帧，
 * 所以本类按网络块原样下发即可，块边界落在事件中间也不会破坏 SSE 解析（响应头里的
 * {@code content-type} 必须原样透传，读者据此选解码器）。
 *
 * <p>守卫拒绝以 {@code Mono} 错误信号上抛（不包成 IO 异常），稳定错误码才立得住。
 */
final class GuardedExchangeFunction implements ExchangeFunction {

    private static final ExchangeStrategies STRATEGIES = ExchangeStrategies.withDefaults();

    /** 单次读取的块大小：足够小以保证增量到达，又不至于过度切分。 */
    private static final int CHUNK_SIZE = 8 * 1024;

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
        ExternalHttpStreamResponse stream = openGuardedStream(request);
        return ClientResponse.create(HttpStatusCode.valueOf(stream.status()))
                .headers(headers -> stream.headers().forEach(headers::set))
                .body(readChunks(stream))
                .build();
    }

    /**
     * 打开受控流：请求期的允许清单/私网判定由守卫在本方法内完成（被拒不建连）。
     *
     * <p>不支持流式的出站实现**显式失败**，不退回整包读取——退回去就等于在治理边界上开一个静默的口子。
     */
    private ExternalHttpStreamResponse openGuardedStream(ClientRequest request) {
        if (!(httpClient instanceof ExternalHttpStreamSupport support)) {
            throw new ModelException(ModelException.Reason.UPSTREAM_FAILED, "受控出站边界不支持流式响应");
        }
        return support.openStream(new ExternalHttpRequest(
                request.method().name(),
                request.url().toString(),
                GuardedExternalHttpTransport.toGuardHeaders(request.headers()),
                writeBody(request),
                null));
    }

    /**
     * 逐块下发响应体：读到一块就 {@code sink.next} 一块，首块不等末块。
     *
     * <p>三条终止路径都收敛：正常读尽 → {@code complete}；守卫拒绝（超限/读取失败/超时）→ 原样
     * {@code error}（稳定错误码不包成 IO 异常）；下游取消 → 停止读取并关闭上游连接。
     * 读取在有界弹性线程池上进行（{@code subscribeOn}），不占用 Reactor 事件循环。
     */
    private static Flux<DataBuffer> readChunks(ExternalHttpStreamResponse stream) {
        return Flux.<DataBuffer>create(
                        sink -> {
                            byte[] buffer = new byte[CHUNK_SIZE];
                            try {
                                int read = stream.readChunk(buffer);
                                while (read > 0) {
                                    if (sink.isCancelled()) {
                                        return;
                                    }
                                    sink.next(
                                            DefaultDataBufferFactory.sharedInstance.wrap(Arrays.copyOf(buffer, read)));
                                    read = stream.readChunk(buffer);
                                }
                                if (!sink.isCancelled()) {
                                    sink.complete();
                                }
                            } catch (RuntimeException exception) {
                                sink.error(exception);
                            } finally {
                                stream.close();
                            }
                        },
                        // 下游消费慢时形成缓冲；总量受守卫的 max-response-bytes 约束，不会无界增长
                        FluxSink.OverflowStrategy.BUFFER)
                .subscribeOn(Schedulers.boundedElastic())
                // 取消/超时/异常三条路径都兜底释放上游连接（close 幂等）
                .doFinally(signal -> stream.close());
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
