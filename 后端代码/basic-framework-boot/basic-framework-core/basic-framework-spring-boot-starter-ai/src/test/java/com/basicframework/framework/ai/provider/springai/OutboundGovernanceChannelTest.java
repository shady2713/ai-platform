package com.basicframework.framework.ai.provider.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.ai.config.AiModelProperties;
import com.basicframework.framework.ai.core.http.AiHttpProperties;
import com.basicframework.framework.ai.core.http.ExternalHttpClient;
import com.basicframework.framework.ai.core.http.ExternalHttpException;
import com.basicframework.framework.ai.core.http.ExternalHttpRequest;
import com.basicframework.framework.ai.core.http.ExternalHttpResponse;
import com.basicframework.framework.ai.core.http.GuardedExternalHttpClient;
import com.basicframework.framework.ai.core.model.EmbeddingRequest;
import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelEndpointSnapshot;
import com.basicframework.framework.ai.core.model.ModelEvent;
import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelPort;
import com.basicframework.framework.ai.core.model.ModelProbeKind;
import com.basicframework.framework.ai.core.model.ModelProbeResult;
import com.basicframework.framework.ai.core.model.ModelRequest;
import com.basicframework.framework.ai.core.model.ModelStream;
import com.basicframework.framework.ai.core.model.media.SpeechSynthesisRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * M07 请求级出站治理专项：四条通道逐条断言"每一次出站请求都过 F09 守卫"，并用**反向用例**
 * 证明治理真的生效（出站到不允许目标在请求期被拒、请求根本没离开进程）。
 *
 * <p>通道与客户端的对应关系（Spring AI 1.1.8 字节码实测）：
 * <ul>
 *   <li>聊天同步、嵌入 → {@code OpenAiApi} 的 {@code restClient}；</li>
 *   <li>聊天流式 → {@code OpenAiApi} 的 {@code webClient}；</li>
 *   <li>转写、语音合成 → {@code OpenAiAudioApi} 的 {@code restClient}。</li>
 * </ul>
 *
 * <p>"经守卫"的证据不是配置而是计数：{@link RecordingHttpClient} 包住**真实**守卫并记录每次
 * {@code execute} 调用，因此 {@code attempts} 递增即证明该通道的请求走过守卫，
 * {@code attempts == 1} 同时证明厂商层重试没有放大（被拒目标不会被重发）。
 */
class OutboundGovernanceChannelTest {

    private static final String API_KEY = "sk-guard-test";

    /** 包住真实守卫的计数装饰器：只统计，不改写任何决策（放行与拒绝都出自守卫）。 */
    private static final class RecordingHttpClient implements ExternalHttpClient {

        private final ExternalHttpClient delegate;

        private final List<String> urls = Collections.synchronizedList(new ArrayList<>());

        private final AtomicInteger attempts = new AtomicInteger();

        RecordingHttpClient(ExternalHttpClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public ExternalHttpResponse execute(ExternalHttpRequest request) {
            attempts.incrementAndGet();
            urls.add(request.url());
            return delegate.execute(request);
        }

        @Override
        public CompletableFuture<ExternalHttpResponse> executeAsync(ExternalHttpRequest request) {
            attempts.incrementAndGet();
            urls.add(request.url());
            return delegate.executeAsync(request);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /** 一次受控装配：策略 + 记录用守卫 + 工厂。 */
    private record Harness(
            AiHttpProperties properties, RecordingHttpClient guard, SpringAiModelClientFactory factory) {}

    /**
     * 受控装配：{@code allowPrivateTargets} 决定守卫是否放行 127.0.0.1。
     *
     * <p>注意允许清单（主机 + 端口）在这两种情况下都放行——因此**创建期校验必然通过**，
     * 反向用例拒绝的只能是请求期守卫。
     */
    private static Harness harness(boolean allowPrivateTargets, int port, int maxAttempts) {
        AiHttpProperties properties = new AiHttpProperties();
        properties.setAllowedHosts(List.of("127.0.0.1"));
        properties.setAllowedPorts(List.of(port));
        properties.setAllowPrivateTargets(allowPrivateTargets);
        RecordingHttpClient guard = new RecordingHttpClient(new GuardedExternalHttpClient(properties));
        AiModelProperties model = new AiModelProperties();
        model.setMaxAttempts(maxAttempts);
        model.setRetryBackoff(Duration.ofMillis(10));
        model.setStreamIdleTimeout(Duration.ofSeconds(5));
        return new Harness(properties, guard, new SpringAiModelClientFactory(properties, model, 8, guard, false));
    }

    /**
     * 端点快照：baseUrl 只到根。厂商客户端自己再拼 {@code /v1/chat/completions} 等路径
     * （与 M02 既有隔离用例写法一致）；凭据固定为 {@link #API_KEY}，用于断言凭据确实随请求外发。
     */
    private static ModelEndpointSnapshot snapshot(FakeOpenAiGateway gateway, ModelCapability... capabilities) {
        return new ModelEndpointSnapshot(
                1L, 1, 1, "openai_compatible", gateway.baseUrl(), "fake-model", Set.of(capabilities), API_KEY);
    }

    // ==================== 正向：四条通道逐条断言都经守卫 ====================

    @Test
    void chatChannelSendsThroughGuardOnSyncCall() throws Exception {
        try (FakeOpenAiGateway gateway = new FakeOpenAiGateway()) {
            Harness harness = harness(true, gateway.port(), 3);
            ModelPort client = harness.factory().getOrCreate(snapshot(gateway, ModelCapability.TEXT));

            String text = client.generate(ModelRequest.of("fake-model", "ping")).text();

            assertThat(text).as("聊天同步：响应内容正常").isEqualTo("同步回复");
            assertThat(harness.guard().attempts.get()).as("聊天同步：守卫被调用一次").isEqualTo(1);
            assertThat(harness.guard().urls)
                    .allSatisfy(url -> assertThat(url).startsWith(gateway.baseUrl() + "/v1/chat/completions"));
            assertThat(gateway.paths()).containsExactly("/v1/chat/completions");
            assertThat(gateway.requests().get(0).authorization()).isEqualTo("Bearer " + API_KEY);
            harness.factory().close();
        }
    }

    @Test
    void chatChannelSendsThroughGuardOnStreamingCall() throws Exception {
        try (FakeOpenAiGateway gateway = new FakeOpenAiGateway()) {
            Harness harness = harness(true, gateway.port(), 3);
            ModelPort client =
                    harness.factory().getOrCreate(snapshot(gateway, ModelCapability.TEXT, ModelCapability.TEXT_STREAM));

            StringBuilder deltas = new StringBuilder();
            try (ModelStream stream = client.stream(ModelRequest.of("fake-model", "ping"))) {
                while (stream.hasNext()) {
                    ModelEvent event = stream.next();
                    if (event.type() == ModelEvent.Type.DELTA) {
                        deltas.append(event.text());
                    }
                }
            }

            assertThat(deltas.toString()).as("聊天流式：增量文本正常").isEqualTo("流式");
            assertThat(harness.guard().attempts.get()).as("聊天流式：守卫被调用一次").isEqualTo(1);
            assertThat(harness.guard().urls.get(0)).endsWith("/v1/chat/completions");
            assertThat(gateway.requests().get(0).body()).as("流式请求带 stream=true").contains("\"stream\":true");
            harness.factory().close();
        }
    }

    @Test
    void embeddingChannelSendsThroughGuard() throws Exception {
        try (FakeOpenAiGateway gateway = new FakeOpenAiGateway()) {
            Harness harness = harness(true, gateway.port(), 3);
            ModelPort client = harness.factory().getOrCreate(snapshot(gateway, ModelCapability.EMBEDDING));

            var response = client.embed(EmbeddingRequest.of("fake-model", List.of("a", "b")));

            assertThat(response.dimensions()).as("嵌入：向量维度来自上游响应").isEqualTo(2);
            assertThat(response.vector(0)).containsExactly(0.5f, 0.25f);
            assertThat(harness.guard().attempts.get()).as("嵌入：守卫被调用一次").isEqualTo(1);
            assertThat(harness.guard().urls.get(0)).endsWith("/v1/embeddings");
            assertThat(gateway.paths()).containsExactly("/v1/embeddings");
            harness.factory().close();
        }
    }

    @Test
    void transcriptionChannelSendsThroughGuard() throws Exception {
        try (FakeOpenAiGateway gateway = new FakeOpenAiGateway()) {
            Harness harness = harness(true, gateway.port(), 3);
            ModelPort client = harness.factory().getOrCreate(snapshot(gateway, ModelCapability.SPEECH_TO_TEXT));

            ModelProbeResult probe = client.probe(ModelProbeKind.SPEECH_TO_TEXT);

            assertThat(probe.status())
                    .as(
                            "转写：探测成功（明细码=%s，守卫调用=%s）",
                            probe.detailCode(), harness.guard().attempts.get())
                    .isEqualTo(ModelProbeResult.Status.SUPPORTED);
            assertThat(harness.guard().attempts.get()).as("转写：守卫被调用一次").isEqualTo(1);
            assertThat(harness.guard().urls.get(0)).endsWith("/v1/audio/transcriptions");
            assertThat(gateway.paths()).containsExactly("/v1/audio/transcriptions");
            assertThat(gateway.requests().get(0).authorization()).isEqualTo("Bearer " + API_KEY);
            harness.factory().close();
        }
    }

    @Test
    void speechChannelSendsThroughGuard() throws Exception {
        try (FakeOpenAiGateway gateway = new FakeOpenAiGateway()) {
            Harness harness = harness(true, gateway.port(), 3);
            ModelPort client = harness.factory().getOrCreate(snapshot(gateway, ModelCapability.TEXT_TO_SPEECH));

            var response = client.synthesizeSpeech(SpeechSynthesisRequest.of("fake-model", "你好"));

            assertThat(response.audio().content()).isEqualTo(FakeOpenAiGateway.SPEECH_BYTES);
            assertThat(harness.guard().attempts.get()).as("语音合成：守卫被调用一次").isEqualTo(1);
            assertThat(harness.guard().urls.get(0)).endsWith("/v1/audio/speech");
            assertThat(gateway.paths()).containsExactly("/v1/audio/speech");
            harness.factory().close();
        }
    }

    // ==================== 反向：请求期被拒（核心验收） ====================

    @Test
    void chatChannelIsRefusedAtRequestTimeWhenTargetNotAllowed() throws Exception {
        try (FakeOpenAiGateway gateway = new FakeOpenAiGateway()) {
            Harness harness = harness(false, gateway.port(), 3);
            ModelPort client = harness.factory().getOrCreate(snapshot(gateway, ModelCapability.TEXT));

            assertThatThrownBy(() -> client.generate(ModelRequest.of("fake-model", "ping")))
                    .isInstanceOf(ModelException.class)
                    .satisfies(exception -> {
                        ModelException denied = (ModelException) exception;
                        assertThat(denied.getReason())
                                .as("聊天同步：稳定错误码")
                                .isEqualTo(ModelException.Reason.TARGET_NOT_ALLOWED);
                        assertThat(denied.getMessage()).doesNotContain(API_KEY).doesNotContain("127.0.0.1");
                        assertThat(SpringAiModelClient.isRetryable(denied.getReason()))
                                .as("被出站策略拒绝不可重试")
                                .isFalse();
                    });
            assertThat(harness.guard().attempts.get()).as("守卫确实在请求期做了判定").isEqualTo(1);
            assertThat(gateway.requestCount()).as("请求根本没离开进程").isZero();
            harness.factory().close();
        }
    }

    @Test
    void embeddingChannelIsRefusedAtRequestTimeWhenTargetNotAllowed() throws Exception {
        try (FakeOpenAiGateway gateway = new FakeOpenAiGateway()) {
            Harness harness = harness(false, gateway.port(), 3);
            ModelPort client = harness.factory().getOrCreate(snapshot(gateway, ModelCapability.EMBEDDING));

            assertThatThrownBy(() -> client.embed(EmbeddingRequest.of("fake-model", List.of("a"))))
                    .isInstanceOf(ModelException.class)
                    .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                            .isEqualTo(ModelException.Reason.TARGET_NOT_ALLOWED));
            assertThat(harness.guard().attempts.get()).isEqualTo(1);
            assertThat(gateway.requestCount()).as("请求根本没离开进程").isZero();
            harness.factory().close();
        }
    }

    @Test
    void transcriptionChannelIsRefusedAtRequestTimeWhenTargetNotAllowed() throws Exception {
        try (FakeOpenAiGateway gateway = new FakeOpenAiGateway()) {
            Harness harness = harness(false, gateway.port(), 3);
            ModelPort client = harness.factory().getOrCreate(snapshot(gateway, ModelCapability.SPEECH_TO_TEXT));

            ModelProbeResult probe = client.probe(ModelProbeKind.SPEECH_TO_TEXT);

            assertThat(probe.status()).as("转写：探测收敛为失败结论").isEqualTo(ModelProbeResult.Status.FAILED);
            assertThat(probe.detailCode()).as("转写：稳定错误码").isEqualTo(ModelException.Reason.TARGET_NOT_ALLOWED.name());
            assertThat(harness.guard().attempts.get()).isEqualTo(1);
            assertThat(gateway.requestCount()).as("请求根本没离开进程").isZero();
            harness.factory().close();
        }
    }

    @Test
    void speechChannelIsRefusedAtRequestTimeWhenTargetNotAllowed() throws Exception {
        try (FakeOpenAiGateway gateway = new FakeOpenAiGateway()) {
            Harness harness = harness(false, gateway.port(), 3);
            ModelPort client = harness.factory().getOrCreate(snapshot(gateway, ModelCapability.TEXT_TO_SPEECH));

            assertThatThrownBy(() -> client.synthesizeSpeech(SpeechSynthesisRequest.of("fake-model", "你好")))
                    .isInstanceOf(ModelException.class)
                    .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                            .isEqualTo(ModelException.Reason.TARGET_NOT_ALLOWED));
            assertThat(harness.guard().attempts.get()).isEqualTo(1);
            assertThat(gateway.requestCount()).as("请求根本没离开进程").isZero();
            harness.factory().close();
        }
    }

    @Test
    void streamingChatChannelIsRefusedAtRequestTimeWhenTargetNotAllowed() throws Exception {
        try (FakeOpenAiGateway gateway = new FakeOpenAiGateway()) {
            Harness harness = harness(false, gateway.port(), 3);
            ModelPort client =
                    harness.factory().getOrCreate(snapshot(gateway, ModelCapability.TEXT, ModelCapability.TEXT_STREAM));

            try (ModelStream stream = client.stream(ModelRequest.of("fake-model", "ping"))) {
                assertThatThrownBy(stream::hasNext)
                        .isInstanceOf(ModelException.class)
                        .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                                .isEqualTo(ModelException.Reason.TARGET_NOT_ALLOWED));
            }
            assertThat(harness.guard().attempts.get()).as("响应式通道同样过一次守卫").isEqualTo(1);
            assertThat(gateway.requestCount()).as("请求根本没离开进程").isZero();
            harness.factory().close();
        }
    }

    // ==================== 非回退：超时与 5xx 仍按 M02 语义收敛 ====================

    @Test
    void upstreamTimeoutStillSurfacesAsRetryableTimeout() throws Exception {
        AiHttpProperties properties = new AiHttpProperties();
        properties.setAllowedHosts(List.of("127.0.0.1"));
        properties.setAllowPrivateTargets(true);
        properties.setReadTimeout(Duration.ofMillis(300));
        try (FakeOpenAiGateway gateway = new FakeOpenAiGateway(200, 1_500L)) {
            properties.setAllowedPorts(List.of(gateway.port()));
            RecordingHttpClient guard = new RecordingHttpClient(new GuardedExternalHttpClient(properties));
            AiModelProperties model = new AiModelProperties();
            model.setMaxAttempts(2);
            model.setRetryBackoff(Duration.ofMillis(10));
            SpringAiModelClientFactory factory = new SpringAiModelClientFactory(properties, model, 8, guard, false);
            ModelPort client = factory.getOrCreate(snapshot(gateway, ModelCapability.TEXT));

            assertThatThrownBy(() -> client.generate(ModelRequest.of("fake-model", "ping")))
                    .isInstanceOf(ModelException.class)
                    .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                            .isEqualTo(ModelException.Reason.TIMEOUT));
            assertThat(guard.attempts.get()).as("超时仍按平台重试预算重发").isEqualTo(2);
            assertThat(SpringAiModelClient.isRetryable(ModelException.Reason.TIMEOUT))
                    .isTrue();
            factory.close();
        }
    }

    @Test
    void upstreamServerErrorStillRetriesWithinPlatformBudget() throws Exception {
        try (FakeOpenAiGateway gateway = new FakeOpenAiGateway(500, 0L)) {
            Harness harness = harness(true, gateway.port(), 2);
            ModelPort client = harness.factory().getOrCreate(snapshot(gateway, ModelCapability.TEXT));

            assertThatThrownBy(() -> client.generate(ModelRequest.of("fake-model", "ping")))
                    .isInstanceOf(ModelException.class)
                    .satisfies(exception -> {
                        ModelException failure = (ModelException) exception;
                        assertThat(failure.getReason())
                                .as("上游 5xx 仍是可重试的短暂故障")
                                .isEqualTo(ModelException.Reason.RATE_LIMITED);
                        assertThat(failure.getMessage())
                                .as("不泄漏上游报文与凭据")
                                .doesNotContain("sk-must-not-leak")
                                .doesNotContain("upstream boom")
                                .doesNotContain(API_KEY);
                    });
            assertThat(gateway.requestCount()).as("重试次数只由平台预算决定（maxAttempts=2）").isEqualTo(2);
            assertThat(harness.guard().attempts.get()).as("厂商层不再叠加重发").isEqualTo(2);
            harness.factory().close();
        }
    }

    /** 关闭语义：注入的守卫由 Bean 生命周期负责（工厂不越权关闭），自建的守卫由工厂关闭。 */
    @Test
    void closeOnlyShutsDownGuardItCreatedItself() throws Exception {
        AiHttpProperties properties = new AiHttpProperties();
        properties.setAllowedHosts(List.of("127.0.0.1"));
        properties.setAllowedPorts(List.of(443));
        ModelEndpointSnapshot loopback = new ModelEndpointSnapshot(
                99L, 1, 1, "openai_compatible", "https://127.0.0.1/v1", "m", Set.of(ModelCapability.TEXT), API_KEY);

        RecordingHttpClient injected = new RecordingHttpClient(new GuardedExternalHttpClient(properties));
        SpringAiModelClientFactory borrowing =
                new SpringAiModelClientFactory(properties, new AiModelProperties(), 8, injected, false);
        borrowing.close();
        assertThatThrownBy(() -> injected.execute(ExternalHttpRequest.get("https://127.0.0.1/v1/x")))
                .as("注入的守卫没有被工厂关闭")
                .isInstanceOf(ExternalHttpException.class)
                .satisfies(exception -> assertThat(((ExternalHttpException) exception).getReason())
                        .isEqualTo(ExternalHttpException.Reason.PRIVATE_TARGET_DENIED));

        SpringAiModelClientFactory owning = new SpringAiModelClientFactory(properties);
        owning.getOrCreate(loopback);
        owning.close();
        // close() 同时清空了客户端缓存：重新取客户端才会真正走到"守卫已关闭"这条路径
        ModelPort afterClose = owning.getOrCreate(loopback);
        assertThatThrownBy(() -> afterClose.generate(ModelRequest.of("m", "ping")))
                .as("自建守卫被工厂关闭后拒绝新请求")
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> {
                    assertThat(((ModelException) exception).getReason())
                            .as("因果链：%s", chainOf(exception))
                            .isEqualTo(ModelException.Reason.TARGET_NOT_ALLOWED);
                    assertThat(denialReason(exception)).isEqualTo(ExternalHttpException.Reason.INVALID_REQUEST);
                });
    }

    /** 取 ModelException 因果链里守卫的原始拒绝原因（区分"被策略拒绝"与"客户端已关闭"）。 */
    private static ExternalHttpException.Reason denialReason(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ExternalHttpException externalHttpException) {
                return externalHttpException.getReason();
            }
            current = current.getCause();
        }
        throw new AssertionError("因果链里没有守卫拒绝：异常映射丢了稳定原因", throwable);
    }

    /** 把因果链拍平成一行，放进断言描述里：断言失败时能直接看到真实异常而不是只看到稳定码。 */
    private static String chainOf(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            builder.append(current.getClass().getSimpleName())
                    .append('(')
                    .append(current.getMessage())
                    .append(") <- ");
        }
        return builder.toString();
    }
}
