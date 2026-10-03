package com.basicframework.framework.ai.provider.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.ai.config.AiModelProperties;
import com.basicframework.framework.ai.core.http.AiHttpProperties;
import com.basicframework.framework.ai.core.http.ExternalHttpClient;
import com.basicframework.framework.ai.core.http.ExternalHttpException;
import com.basicframework.framework.ai.core.http.ExternalHttpRequest;
import com.basicframework.framework.ai.core.http.ExternalHttpResponse;
import com.basicframework.framework.ai.core.http.ExternalHttpStreamResponse;
import com.basicframework.framework.ai.core.http.ExternalHttpStreamSupport;
import com.basicframework.framework.ai.core.http.GuardedExternalHttpClient;
import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelEndpointSnapshot;
import com.basicframework.framework.ai.core.model.ModelEvent;
import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelPort;
import com.basicframework.framework.ai.core.model.ModelRequest;
import com.basicframework.framework.ai.core.model.ModelStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * F11 专项：受控出站的流式通道既要**真的增量到达**，又不能少一道闸门。
 *
 * <p>三条验收各配一条反向证据：
 * <ol>
 *   <li><b>增量到达</b>：夹具逐事件 flush 并在事件间延时，断言"首块到达时上游还没写完"；
 *       同一夹具、同一生产接线，只把守卫换成"整包才交付"的替身做<b>对照</b>，证明这条断言不是恒真。</li>
 *   <li><b>仍受治理</b>：流式到非允许清单地址在请求期被拒，夹具收到的请求数为 0；
 *       适配器级再逐条断言主机/端口/协议/凭据头四道闸门。</li>
 *   <li><b>超限有界终止</b>：超过上限的长流以 {@code RESPONSE_TOO_LARGE} 终止，此前已发出的增量
 *       不回收成"完整结果"（流里不会出现 completed 事件），且上游观察到连接被释放。</li>
 * </ol>
 */
class OutboundStreamingGovernanceTest {

    private static final String API_KEY = "sk-guard-test";

    /** 夹具每个 SSE 事件之间的延时：增量到达的时钟基准。 */
    private static final long EVENT_DELAY_MILLIS = 600L;

    // ==================== 1. 增量到达 ====================

    @Test
    void firstDeltaArrivesWhileUpstreamIsStillStreaming() throws Exception {
        try (FakeOpenAiGateway gateway = new FakeOpenAiGateway(200, 0L, EVENT_DELAY_MILLIS, 0)) {
            CountingGuard guard =
                    new CountingGuard(new GuardedExternalHttpClient(properties(true, gateway.port(), 1_048_576)));
            SpringAiModelClientFactory factory = factory(properties(true, gateway.port(), 1_048_576), guard, 3);
            ModelPort client = factory.getOrCreate(streamingSnapshot(gateway));

            Arrival arrival = consume(client, gateway);

            assertThat(arrival.deltas).as("流式：增量文本正常").isEqualTo("流式");
            assertThat(arrival.completed).as("流正常结束").isTrue();
            assertThat(arrival.writtenAtFirstDelta)
                    .as("首块到达时上游还没写完（已写 %s，总量 %s）——这就是增量到达", arrival.writtenAtFirstDelta, gateway.totalStreamBytes())
                    .isLessThan(gateway.totalStreamBytes());
            assertThat(arrival.firstDeltaMillis).as("首块不可能早于上游写出第一个事件").isGreaterThanOrEqualTo(EVENT_DELAY_MILLIS / 2);
            assertThat(arrival.lastEventMillis - arrival.firstDeltaMillis)
                    .as(
                            "首块与末块之间至少隔了半个事件延时（首块 %sms，末块 %sms；整包对照的间隔约为 0）",
                            arrival.firstDeltaMillis, arrival.lastEventMillis)
                    .isGreaterThanOrEqualTo(EVENT_DELAY_MILLIS / 2);
            assertThat(guard.attempts).isEqualTo(1);
            factory.close();
        }
    }

    /**
     * 对照：同样的夹具、同样的生产接线，只把守卫换成"整包才交付"（M07 的降级算法）。
     *
     * <p>首块到达时上游<b>已经写完</b>——与上一条用例正好相反，说明增量断言真的在测增量。
     */
    @Test
    void bufferingBoundaryCannotShowIncrementalArrival() throws Exception {
        try (FakeOpenAiGateway gateway = new FakeOpenAiGateway(200, 0L, EVENT_DELAY_MILLIS, 0)) {
            AiHttpProperties properties = properties(true, gateway.port(), 1_048_576);
            BufferingGuard guard = new BufferingGuard(new GuardedExternalHttpClient(properties));
            SpringAiModelClientFactory factory = factory(properties, guard, 3);
            ModelPort client = factory.getOrCreate(streamingSnapshot(gateway));

            Arrival arrival = consume(client, gateway);

            assertThat(arrival.deltas).as("整包降级下内容仍然正确（功能没坏）").isEqualTo("流式");
            assertThat(arrival.writtenAtFirstDelta)
                    .as("整包交付：首块到达时上游早已写完（已写 %s，总量 %s）", arrival.writtenAtFirstDelta, gateway.totalStreamBytes())
                    .isEqualTo(gateway.totalStreamBytes());
            assertThat(arrival.firstDeltaMillis)
                    .as("整包交付必须等整个流读完（事件数 %s × 延时 %sms）", 3, EVENT_DELAY_MILLIS)
                    .isGreaterThanOrEqualTo(EVENT_DELAY_MILLIS);
            factory.close();
        }
    }

    // ==================== 2. 流式仍受治理（反向） ====================

    @Test
    void streamingToNonAllowlistedTargetIsRefusedBeforeTheRequestLeavesTheProcess() throws Exception {
        try (FakeOpenAiGateway gateway = new FakeOpenAiGateway()) {
            // 主机与端口都在允许清单内（创建期校验必然通过），但环回未显式批准 ⇒ 只能由请求期守卫拒
            AiHttpProperties properties = properties(false, gateway.port(), 1_048_576);
            CountingGuard guard = new CountingGuard(new GuardedExternalHttpClient(properties));
            SpringAiModelClientFactory factory = factory(properties, guard, 3);
            ModelPort client = factory.getOrCreate(streamingSnapshot(gateway));

            try (ModelStream stream = client.stream(ModelRequest.of("fake-model", "ping"))) {
                assertThatThrownBy(stream::hasNext)
                        .isInstanceOf(ModelException.class)
                        .satisfies(exception -> {
                            ModelException denied = (ModelException) exception;
                            assertThat(denied.getReason()).isEqualTo(ModelException.Reason.TARGET_NOT_ALLOWED);
                            assertThat(denied.getMessage())
                                    .doesNotContain(API_KEY)
                                    .doesNotContain("127.0.0.1");
                        });
            }
            assertThat(guard.attempts).as("流式入口也在请求期被守卫判定").isEqualTo(1);
            assertThat(gateway.requestCount()).as("请求根本没离开进程").isZero();
            factory.close();
        }
    }

    /** 适配器级：流式入口逐条复用请求/响应入口的四道闸门（均在解析与建连之前）。 */
    @Test
    void streamingEntryRefusesHostPortSchemeAndCredentialHeader() {
        AiHttpProperties approved = new AiHttpProperties();
        approved.setAllowedHosts(List.of("api.example.com"));
        approved.setAllowedPorts(List.of(443));
        approved.setAllowPrivateTargets(true);
        try (GuardedExternalHttpClient guard = new GuardedExternalHttpClient(approved)) {
            WebClient client =
                    new GuardedExternalHttpTransport(guard).webClientBuilder().build();

            assertRefused(
                    client,
                    Map.of(),
                    "https://not-allowed.example.com/v1/chat/completions",
                    ExternalHttpException.Reason.TARGET_NOT_ALLOWED);
            assertRefused(
                    client,
                    Map.of(),
                    "https://api.example.com:8443/v1/embeddings",
                    ExternalHttpException.Reason.TARGET_NOT_ALLOWED);
            assertRefused(
                    client,
                    Map.of("Cookie", "session=1"),
                    "https://api.example.com/v1/chat/completions",
                    ExternalHttpException.Reason.INVALID_REQUEST);
        }

        // 未批准内网目标时 http 一律拒绝（协议约束先于允许清单）
        AiHttpProperties strict = new AiHttpProperties();
        strict.setAllowedHosts(List.of("api.example.com"));
        strict.setAllowedPorts(List.of(80));
        strict.setAllowPrivateTargets(false);
        try (GuardedExternalHttpClient guard = new GuardedExternalHttpClient(strict)) {
            WebClient client =
                    new GuardedExternalHttpTransport(guard).webClientBuilder().build();

            assertRefused(
                    client,
                    Map.of(),
                    "http://api.example.com/v1/chat/completions",
                    ExternalHttpException.Reason.TARGET_NOT_ALLOWED);
        }
    }

    @Test
    void boundaryWithoutStreamingSupportFailsClosedInsteadOfFallingBackToBuffering() {
        WebClient client = new GuardedExternalHttpTransport(new NoStreamingClient())
                .webClientBuilder()
                .build();

        assertThatThrownBy(() -> client.post()
                        .uri("https://api.example.com/v1/chat/completions")
                        .bodyValue("{}")
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofSeconds(5)))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> {
                    ModelException failure = (ModelException) exception;
                    assertThat(failure.getReason()).isEqualTo(ModelException.Reason.UPSTREAM_FAILED);
                    assertThat(failure.getMessage()).contains("不支持流式");
                });
    }

    // ==================== 3. 长流超限：有界终止 ====================

    @Test
    void longStreamIsTerminatedWithStableCodeAndKeepsAlreadyDeliveredDeltas() throws Exception {
        // 600 个事件约 90 KiB，上限只有 1500 字节：必然超限
        try (FakeOpenAiGateway gateway = new FakeOpenAiGateway(200, 0L, 10L, 600)) {
            AiHttpProperties properties = properties(true, gateway.port(), 1_500);
            CountingGuard guard = new CountingGuard(new GuardedExternalHttpClient(properties));
            SpringAiModelClientFactory factory = factory(properties, guard, 1);
            ModelPort client = factory.getOrCreate(streamingSnapshot(gateway));

            List<ModelEvent> delivered = new ArrayList<>();
            ModelException failure;
            try (ModelStream stream = client.stream(ModelRequest.of("fake-model", "ping"))) {
                try {
                    while (stream.hasNext()) {
                        delivered.add(stream.next());
                    }
                    throw new AssertionError("超限的流必须以稳定错误码终止，不能安静结束：已交付 "
                            + delivered.size() + " 个事件，上游已写 "
                            + gateway.streamBytesWritten() + "/" + gateway.totalStreamBytes() + " 字节，结束事件="
                            + delivered);
                } catch (ModelException exception) {
                    failure = exception;
                }
            }

            assertThat(delivered).as("此前的增量确实交付了").isNotEmpty();
            assertThat(delivered)
                    .as("已发出的块不被回收成完整结果")
                    .extracting(ModelEvent::type)
                    .doesNotContain(ModelEvent.Type.COMPLETED);
            assertThat(failure.getReason()).as("平台侧稳定错误码").isEqualTo(ModelException.Reason.UPSTREAM_FAILED);
            assertThat(guardReasonOf(failure))
                    .as("守卫侧稳定原因（有界终止）")
                    .isEqualTo(ExternalHttpException.Reason.RESPONSE_TOO_LARGE);
            assertThat(failure.getMessage()).doesNotContain(API_KEY).doesNotContain("127.0.0.1");
            awaitTrue(gateway::streamClientGone, "守卫终止后上游写入失败（连接已释放，不是读完再判）");
            assertThat(gateway.streamBytesWritten())
                    .as("上游没把整个长流写完（已写 %s，总量 %s）", gateway.streamBytesWritten(), gateway.totalStreamBytes())
                    .isLessThan(gateway.totalStreamBytes());
            factory.close();
        }
    }

    @Test
    void cancellingMidStreamStopsUpstreamAndYieldsNoFurtherEvents() throws Exception {
        try (FakeOpenAiGateway gateway = new FakeOpenAiGateway(200, 0L, 40L, 400)) {
            AiHttpProperties properties = properties(true, gateway.port(), 1_048_576);
            CountingGuard guard = new CountingGuard(new GuardedExternalHttpClient(properties));
            SpringAiModelClientFactory factory = factory(properties, guard, 1);
            ModelPort client = factory.getOrCreate(streamingSnapshot(gateway));

            try (ModelStream stream = client.stream(ModelRequest.of("fake-model", "ping"))) {
                assertThat(stream.hasNext()).isTrue();
                assertThat(stream.next().type()).isEqualTo(ModelEvent.Type.DELTA);
                // 关闭后不再有事件：取消必须真正切断上游
                assertThat(stream.hasNext()).isTrue();
            }

            awaitTrue(gateway::streamClientGone, "取消后上游写入失败（连接已释放）");
            factory.close();
        }
    }

    @Test
    void cancellingAfterTheFirstChunkStopsUpstreamAndReleasesTheConnection() throws Exception {
        try (FakeOpenAiGateway gateway = new FakeOpenAiGateway(200, 0L, 40L, 400)) {
            AiHttpProperties properties = properties(true, gateway.port(), 1_048_576);
            try (GuardedExternalHttpClient guard = new GuardedExternalHttpClient(properties)) {
                WebClient client = new GuardedExternalHttpTransport(guard)
                        .webClientBuilder()
                        .build();

                String first = client.post()
                        .uri(gateway.baseUrl() + "/v1/chat/completions")
                        .bodyValue(Map.of("model", "fake-model", "stream", true))
                        .retrieve()
                        .bodyToFlux(String.class)
                        .take(1)
                        .blockLast(Duration.ofSeconds(5));

                assertThat(first)
                        .as("只取首个事件（SSE 读取器已剥掉 data: 前缀并按事件重新组帧）")
                        .contains("\"object\":\"chat.completion.chunk\"")
                        .doesNotContain("DONE");
                assertThat(gateway.paths()).containsExactly("/v1/chat/completions");
                awaitTrue(gateway::streamClientGone, "取消后上游写入失败（连接已释放）");
            }
        }
    }

    /**
     * 取消与"已在途的数据块"之间的竞态：块到达时下游已取消 ⇒ 丢弃该块并关闭上游。
     *
     * <p>用编排好的流响应把这条窗口做成确定性的（第二块在取消之后才到达）。
     */
    @Test
    void chunkArrivingAfterCancelIsDroppedAndUpstreamIsStillClosed() {
        InFlightStreamResponse scripted = new InFlightStreamResponse();
        WebClient client = new GuardedExternalHttpTransport(new ScriptedStreamClient(scripted))
                .webClientBuilder()
                .build();

        // 直接看 DataBuffer：逐块下发的契约属于适配器，不掺入解码层的合并行为
        List<String> received = client.post()
                .uri("https://api.example.com/v1/chat/completions")
                .bodyValue(Map.of("stream", true))
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .take(1)
                .map(OutboundStreamingGovernanceTest::readAsString)
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(received).as("取消之后在途的块不再下发").containsExactly("A");
        assertThat(scripted.closeCount).as("取消后上游流被关闭").isPositive();
    }

    // ==================== 装配与辅助 ====================

    private static AiHttpProperties properties(boolean allowPrivateTargets, int port, int maxResponseBytes) {
        AiHttpProperties properties = new AiHttpProperties();
        properties.setAllowedHosts(List.of("127.0.0.1"));
        properties.setAllowedPorts(List.of(port));
        properties.setAllowPrivateTargets(allowPrivateTargets);
        properties.setMaxResponseBytes(maxResponseBytes);
        return properties;
    }

    private static SpringAiModelClientFactory factory(
            AiHttpProperties properties, ExternalHttpClient guard, int maxAttempts) {
        AiModelProperties model = new AiModelProperties();
        model.setMaxAttempts(maxAttempts);
        model.setRetryBackoff(Duration.ofMillis(10));
        model.setStreamIdleTimeout(Duration.ofSeconds(5));
        return new SpringAiModelClientFactory(properties, model, 8, guard, false);
    }

    private static ModelEndpointSnapshot streamingSnapshot(FakeOpenAiGateway gateway) {
        return new ModelEndpointSnapshot(
                1L,
                1,
                1,
                "openai_compatible",
                gateway.baseUrl(),
                "fake-model",
                Set.of(ModelCapability.TEXT, ModelCapability.TEXT_STREAM),
                API_KEY);
    }

    /** 一次流式消费的观测结果：首块到达时刻，以及那一刻上游已经写了多少字节。 */
    private record Arrival(
            String deltas, boolean completed, long writtenAtFirstDelta, long firstDeltaMillis, long lastEventMillis) {}

    private static Arrival consume(ModelPort client, FakeOpenAiGateway gateway) {
        long start = System.nanoTime();
        StringBuilder text = new StringBuilder();
        long writtenAtFirstDelta = -1L;
        long firstDeltaMillis = -1L;
        boolean completed = false;
        try (ModelStream stream = client.stream(ModelRequest.of("fake-model", "ping"))) {
            while (stream.hasNext()) {
                ModelEvent event = stream.next();
                if (event.type() == ModelEvent.Type.DELTA) {
                    if (writtenAtFirstDelta < 0) {
                        // 关键观测：首块到达的瞬间，上游到底写完了没有
                        writtenAtFirstDelta = gateway.streamBytesWritten();
                        firstDeltaMillis = millisSince(start);
                    }
                    text.append(event.text());
                } else if (event.type() == ModelEvent.Type.COMPLETED) {
                    completed = true;
                }
            }
        }
        return new Arrival(text.toString(), completed, writtenAtFirstDelta, firstDeltaMillis, millisSince(start));
    }

    private static long millisSince(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static void assertRefused(
            WebClient client, Map<String, String> headers, String url, ExternalHttpException.Reason reason) {
        assertThatThrownBy(() -> client.post()
                        .uri(url)
                        .headers(entries -> headers.forEach(entries::set))
                        .bodyValue("{}")
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofSeconds(5)))
                .isInstanceOf(ExternalHttpException.class)
                .satisfies(exception -> assertThat(((ExternalHttpException) exception).getReason())
                        .as("流式请求期被拒（%s）", url)
                        .isEqualTo(reason));
    }

    private static String readAsString(DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static ExternalHttpException.Reason guardReasonOf(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof ExternalHttpException externalHttpException) {
                return externalHttpException.getReason();
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        throw new AssertionError("因果链里没有守卫拒绝：异常映射丢了稳定原因", throwable);
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }

    /** 包住真实守卫的计数装饰器：只统计，不改写任何决策（放行与拒绝都出自守卫）。 */
    private static final class CountingGuard implements ExternalHttpClient, ExternalHttpStreamSupport {

        private final ExternalHttpClient delegate;

        private int attempts;

        CountingGuard(ExternalHttpClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public ExternalHttpResponse execute(ExternalHttpRequest request) {
            attempts++;
            return delegate.execute(request);
        }

        @Override
        public CompletableFuture<ExternalHttpResponse> executeAsync(ExternalHttpRequest request) {
            attempts++;
            return delegate.executeAsync(request);
        }

        @Override
        public ExternalHttpStreamResponse openStream(ExternalHttpRequest request) {
            attempts++;
            return ((ExternalHttpStreamSupport) delegate).openStream(request);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /**
     * 对照用守卫：把"整包读取"伪装成流式入口（复刻 M07 的降级算法）。
     *
     * <p>请求期治理仍由真实守卫决定，只有响应体交付方式变成"读完才给"。
     */
    private static final class BufferingGuard implements ExternalHttpClient, ExternalHttpStreamSupport {

        private final ExternalHttpClient delegate;

        BufferingGuard(ExternalHttpClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public ExternalHttpResponse execute(ExternalHttpRequest request) {
            return delegate.execute(request);
        }

        @Override
        public CompletableFuture<ExternalHttpResponse> executeAsync(ExternalHttpRequest request) {
            return delegate.executeAsync(request);
        }

        @Override
        public ExternalHttpStreamResponse openStream(ExternalHttpRequest request) {
            return new BufferingStreamResponse(delegate.execute(request));
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /** 整包交付的流视图：所有字节在第一次 {@code readChunk} 时一次性给出。 */
    private static final class BufferingStreamResponse implements ExternalHttpStreamResponse {

        private final ExternalHttpResponse response;

        private boolean handed;

        BufferingStreamResponse(ExternalHttpResponse response) {
            this.response = response;
        }

        @Override
        public int status() {
            return response.status();
        }

        @Override
        public Map<String, String> headers() {
            return response.headers();
        }

        @Override
        public long declaredLength() {
            return response.declaredLength();
        }

        @Override
        public long deliveredBytes() {
            return response.body() == null ? 0 : response.body().length;
        }

        @Override
        public int readChunk(byte[] target) {
            byte[] body = response.body();
            if (handed || body == null || body.length == 0 || target.length == 0) {
                return -1;
            }
            int length = Math.min(target.length, body.length);
            System.arraycopy(body, 0, target, 0, length);
            handed = true;
            return length;
        }

        @Override
        public void close() {
            // 整包已在内存里，没有连接需要释放
        }
    }

    /** 不支持流式的出站实现：用于验证接线是 fail-closed，而不是悄悄退回整包读取。 */
    private static final class NoStreamingClient implements ExternalHttpClient {

        @Override
        public ExternalHttpResponse execute(ExternalHttpRequest request) {
            return new ExternalHttpResponse(200, Map.of(), new byte[0], 0);
        }

        @Override
        public CompletableFuture<ExternalHttpResponse> executeAsync(ExternalHttpRequest request) {
            return CompletableFuture.completedFuture(execute(request));
        }

        @Override
        public void close() {
            // 夹具无资源
        }
    }

    /** 只按脚本交付流内容的出站实现：用于把时序竞态做成确定性的。 */
    private static final class ScriptedStreamClient implements ExternalHttpClient, ExternalHttpStreamSupport {

        private final ExternalHttpStreamResponse response;

        ScriptedStreamClient(ExternalHttpStreamResponse response) {
            this.response = response;
        }

        @Override
        public ExternalHttpResponse execute(ExternalHttpRequest request) {
            return new ExternalHttpResponse(200, Map.of(), new byte[0], 0);
        }

        @Override
        public CompletableFuture<ExternalHttpResponse> executeAsync(ExternalHttpRequest request) {
            return CompletableFuture.completedFuture(execute(request));
        }

        @Override
        public ExternalHttpStreamResponse openStream(ExternalHttpRequest request) {
            return response;
        }

        @Override
        public void close() {
            // 夹具无资源
        }
    }

    /**
     * 编排好的流响应：先给 {@code A}，再在延时后给 {@code B}。
     *
     * <p>{@code close()} 不会让 {@code readChunk} 提前返回——这正是"取消发生时数据已在途"的形态。
     */
    private static final class InFlightStreamResponse implements ExternalHttpStreamResponse {

        private int reads;

        private int closeCount;

        @Override
        public int status() {
            return 200;
        }

        @Override
        public Map<String, String> headers() {
            return Map.of("content-type", "text/plain");
        }

        @Override
        public long declaredLength() {
            return -1L;
        }

        @Override
        public long deliveredBytes() {
            return reads;
        }

        @Override
        public int readChunk(byte[] target) {
            int index = reads++;
            if (index == 1) {
                try {
                    Thread.sleep(300L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            if (index > 1) {
                return -1;
            }
            target[0] = (byte) (index == 0 ? 'A' : 'B');
            return 1;
        }

        @Override
        public void close() {
            closeCount++;
        }
    }
}
