package com.basicframework.framework.ai.core.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * F12：调用方取消必须与连接失败**可区分**。
 *
 * <p>取消之前落到兜底分支，报成 {@code CONNECT_FAILED} + "出站请求失败"——这是错误的归因：
 * 取消是调用方自己的决定，与网络和上游无关，报成连接失败会把排查方向引到根本没问题的东西。
 *
 * <p>本类逐条钉住两侧：
 * <ul>
 *   <li><b>正向</b>：取消 → {@code CANCELLED}，且分别断言它<b>不是</b> {@code CONNECT_FAILED}、
 *       <b>不是</b> {@code TIMEOUT}（两者都是"看起来同样合理"的错误归因，必须分开断言）。</li>
 *   <li><b>反向</b>：真正的连接失败仍映射 {@code CONNECT_FAILED}、真正的超时仍映射 {@code TIMEOUT}；
 *       一个"因果链里恰好挂着取消"的真实 IO 失败也不许被改判成取消——否则就是本卡自己的反向 bug。</li>
 *   <li><b>不删减</b>：取消后仍然拿不到任何响应（F11/M07 已钉住的性质，这里再钉一遍）。</li>
 * </ul>
 *
 * <p>全部使用本地 {@link HttpServer}，不访问外网。
 */
class OutboundCancellationAttributionTest {

    /** 请求体与凭据里的标记：用于证明错误消息不把请求内容带出来。 */
    private static final String BODY_MARKER = "sk-live-BODY-MARKER-should-never-be-echoed";

    private static final String CREDENTIAL_MARKER = "Bearer sk-live-CREDENTIAL-MARKER";

    private HttpServer server;

    private int port;

    /** {@code /blocked} 收到请求时置零：取消用例必须先确认请求在途，否则 cancel 可能落在竞态上。 */
    private final CountDownLatch arrived = new CountDownLatch(1);

    /** 用例结束后放行 {@code /blocked} 的处理线程，避免它挂到超时。 */
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        // 收到请求后一直阻塞：让取消确定地发生在"请求已在途、响应还没到"的窗口里
        server.createContext("/blocked", exchange -> {
            arrived.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            // 这份响应在取消之后才写：调用方绝不应该看到它
            byte[] body = "late-response".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(2_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    // ==================== 正向：取消可区分 ====================

    @Test
    void callerCancellationIsAttributedToCancelledAndNotToConnectFailureOrTimeout() throws Exception {
        AiHttpProperties properties = properties(port, true);
        // 超时给足：让用例测的是"取消"而不是"超时"
        properties.setReadTimeout(Duration.ofSeconds(30));
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(properties)) {
            CompletableFuture<ExternalHttpResponse> future = client.executeAsync(get(url("/blocked"), Map.of()));
            AtomicReference<ExternalHttpResponse> delivered = new AtomicReference<>();
            future.whenComplete((response, error) -> {
                if (response != null) {
                    delivered.set(response);
                }
            });

            assertThat(arrived.await(5, TimeUnit.SECONDS))
                    .as("请求应已抵达服务端：取消才是对在途请求生效")
                    .isTrue();
            try {
                assertThat(future.cancel(true)).as("取消在途请求").isTrue();

                Throwable thrown = catchThrowable(future::join);
                assertThat(thrown).as("取消后 join 必须失败，绝不能返回任何响应").isInstanceOf(CompletionException.class);
                ExternalHttpException mapped = (ExternalHttpException) thrown.getCause();
                assertThat(mapped.getReason()).as("取消有独立的稳定码").isEqualTo(ExternalHttpException.Reason.CANCELLED);
                // 两条"看起来同样合理"的错误归因分别断言：只看上面一条不够，
                // 最可能的回归就是把取消塞进这两个既有取值里的某一个
                assertThat(mapped.getReason())
                        .as("取消不得再被报成连接失败")
                        .isNotEqualTo(ExternalHttpException.Reason.CONNECT_FAILED);
                assertThat(mapped.getReason()).as("取消不得被报成超时").isNotEqualTo(ExternalHttpException.Reason.TIMEOUT);
                assertThat(mapped.getMessage()).as("文案要说清已取消").contains("取消").doesNotContain("失败");
                assertThat(future.isDone()).isTrue();
                assertThat(delivered.get()).as("取消后不得返回任何响应").isNull();
            } finally {
                release.countDown();
            }
        }
    }

    // ==================== 反向：连接失败与超时没有被带偏 ====================

    @Test
    void realConnectFailureIsStillAttributedToConnectFailure() throws Exception {
        int closedPort = findClosedPort();
        AiHttpProperties properties = properties(closedPort, true);
        properties.setConnectTimeout(Duration.ofMillis(500));
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(properties)) {
            assertThatThrownBy(() -> client.execute(get(url(closedPort, "/ok"), Map.of())))
                    .isInstanceOf(ExternalHttpException.class)
                    .satisfies(exception -> {
                        ExternalHttpException mapped = (ExternalHttpException) exception;
                        assertThat(mapped.getReason())
                                .as("真正的连接失败仍然映射 CONNECT_FAILED")
                                .isEqualTo(ExternalHttpException.Reason.CONNECT_FAILED);
                        assertThat(mapped.getReason()).isNotEqualTo(ExternalHttpException.Reason.CANCELLED);
                        assertThat(mapped.getMessage()).contains("连接").doesNotContain("取消");
                    });
        }
    }

    @Test
    void realTimeoutIsStillAttributedToTimeout() {
        AiHttpProperties properties = properties(port, true);
        properties.setReadTimeout(Duration.ofMillis(300));
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(properties)) {
            assertThatThrownBy(() -> client.execute(get(url("/slow"), Map.of())))
                    .isInstanceOf(ExternalHttpException.class)
                    .satisfies(exception -> {
                        ExternalHttpException mapped = (ExternalHttpException) exception;
                        assertThat(mapped.getReason())
                                .as("真正的超时仍然映射 TIMEOUT")
                                .isEqualTo(ExternalHttpException.Reason.TIMEOUT);
                        assertThat(mapped.getReason()).isNotEqualTo(ExternalHttpException.Reason.CANCELLED);
                        assertThat(mapped.getMessage()).contains("超时").doesNotContain("取消");
                    });
        }
    }

    @Test
    void aRealIoFailureIsNotMisreadAsCancellationJustBecauseItsChainMentionsOne() {
        // 反向：取消识别只允许剥 CompletionException/ExecutionException 这类"纯包装"，
        // 沿真实 IO 失败的因果链搜索取消，就会把连接失败改判成取消——那正是本卡要避免的错误方向
        IOException realFailure = new IOException("connection reset", new CancellationException("inner marker"));

        assertThat(GuardedExternalHttpClient.mapFailure(realFailure).getReason())
                .as("真实 IO 失败仍归因 CONNECT_FAILED")
                .isEqualTo(ExternalHttpException.Reason.CONNECT_FAILED);
    }

    // ==================== 映射本身：逐个取值都测 ====================

    @Test
    void everyReasonValuePassesThroughUnchangedAndCancellationIsInTheWordList() {
        assertThat(ExternalHttpException.Reason.values())
                .as("稳定词表：F12 之后包含 CANCELLED")
                .contains(ExternalHttpException.Reason.CANCELLED);
        for (ExternalHttpException.Reason reason : ExternalHttpException.Reason.values()) {
            ExternalHttpException stable = new ExternalHttpException(reason, "已稳定的原因");
            assertThat(GuardedExternalHttpClient.mapFailure(stable))
                    .as("已有稳定原因原样透传，不被改判：%s", reason)
                    .isSameAs(stable);
        }
    }

    @Test
    void cancellationIsRecognisedThroughEveryCompletionWrapperShape() {
        assertThat(GuardedExternalHttpClient.mapFailure(new CancellationException("cancelled"))
                        .getReason())
                .isEqualTo(ExternalHttpException.Reason.CANCELLED);
        assertThat(GuardedExternalHttpClient.mapFailure(new CompletionException(new CancellationException()))
                        .getReason())
                .isEqualTo(ExternalHttpException.Reason.CANCELLED);
        assertThat(GuardedExternalHttpClient.mapFailure(new ExecutionException(new CancellationException()))
                        .getReason())
                .isEqualTo(ExternalHttpException.Reason.CANCELLED);
        assertThat(GuardedExternalHttpClient.mapFailure(
                                new CompletionException(new CompletionException(new CancellationException())))
                        .getReason())
                .as("双层包装仍然可区分")
                .isEqualTo(ExternalHttpException.Reason.CANCELLED);
    }

    @Test
    void timeoutConnectAndUnknownCausesKeepTheirExistingAttribution() {
        // F09 冻结的既有归因，逐条复核：新增取消分支没有动到它们
        assertThat(GuardedExternalHttpClient.mapFailure(new HttpTimeoutException("t"))
                        .getReason())
                .isEqualTo(ExternalHttpException.Reason.TIMEOUT);
        assertThat(GuardedExternalHttpClient.mapFailure(new java.net.ConnectException("c"))
                        .getReason())
                .isEqualTo(ExternalHttpException.Reason.CONNECT_FAILED);
        assertThat(GuardedExternalHttpClient.mapFailure(new java.net.UnknownHostException("h"))
                        .getReason())
                .isEqualTo(ExternalHttpException.Reason.CONNECT_FAILED);
        assertThat(GuardedExternalHttpClient.mapFailure(new IOException("io")).getReason())
                .isEqualTo(ExternalHttpException.Reason.CONNECT_FAILED);
        assertThat(GuardedExternalHttpClient.mapFailure(new IllegalStateException("?"))
                        .getReason())
                .as("未知失败仍收敛到兜底的 CONNECT_FAILED（本卡不改这一支）")
                .isEqualTo(ExternalHttpException.Reason.CONNECT_FAILED);
    }

    @Test
    void deeplyNestedWrappersTerminateWithAStableErrorInsteadOfLooping() {
        Throwable deep = new IOException("boom");
        for (int level = 0; level < 20; level++) {
            deep = new CompletionException(deep);
        }

        assertThat(GuardedExternalHttpClient.mapFailure(deep))
                .as("解包有深度上限：异常链再长也收敛为稳定错误，不会递归失控")
                .isInstanceOf(ExternalHttpException.class);
    }

    // ==================== 消息卫生 ====================

    @Test
    void cancellationMessageEchoesNeitherRequestBodyNorCredentials() throws Exception {
        AiHttpProperties properties = properties(port, true);
        properties.setReadTimeout(Duration.ofSeconds(30));
        Map<String, String> headers = Map.of("Authorization", CREDENTIAL_MARKER, "X-Trace", BODY_MARKER);
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(properties)) {
            CompletableFuture<ExternalHttpResponse> future = client.executeAsync(new ExternalHttpRequest(
                    "POST", url("/blocked"), headers, BODY_MARKER.getBytes(StandardCharsets.UTF_8), null));

            assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(future.cancel(true)).isTrue();
                ExternalHttpException mapped =
                        (ExternalHttpException) catchThrowable(future::join).getCause();

                assertThat(mapped.getMessage())
                        .as("消息只说清已取消")
                        .contains("取消")
                        .doesNotContain(BODY_MARKER)
                        .doesNotContain(CREDENTIAL_MARKER)
                        .doesNotContain("sk-live")
                        .doesNotContain("Bearer")
                        .doesNotContain(url("/blocked"))
                        .doesNotContain("late-response");
            } finally {
                release.countDown();
            }
        }
    }

    // ==================== 流式入口：与同步同一套归因 ====================

    @Test
    void streamingEntryKeepsItsOwnTimeoutAndConnectFailureAttribution() throws Exception {
        AiHttpProperties slow = properties(port, true);
        slow.setReadTimeout(Duration.ofMillis(300));
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(slow)) {
            assertStreamReason(client, get(url("/slow"), Map.of()), ExternalHttpException.Reason.TIMEOUT);
        }

        int closedPort = findClosedPort();
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(properties(closedPort, true))) {
            assertStreamReason(
                    client, get(url(closedPort, "/ok"), Map.of()), ExternalHttpException.Reason.CONNECT_FAILED);
        }
    }

    @Test
    void bothBlockingEntriesShareOneAwaitSoCancellationCannotStayIndivisible() {
        // execute 与 openStream 都只通过 await() 等待并归因，所以"同一个失败 ⇒ 同一个 Reason"是结构性的，
        // 而不是两处代码靠人工同步。这里直接覆盖 await 的取消分支：future 被取消时 join() 抛的是裸
        // CancellationException，不接住它调用方就会拿到不可区分的原始异常——正是本卡要消除的偏差。
        CompletableFuture<String> cancelled = new CompletableFuture<>();
        assertThat(cancelled.cancel(true)).isTrue();

        ExternalHttpException mapped =
                (ExternalHttpException) catchThrowable(() -> GuardedExternalHttpClient.await(cancelled));
        assertThat(mapped).isNotNull();
        assertThat(mapped.getReason()).isEqualTo(ExternalHttpException.Reason.CANCELLED);
        assertThat(mapped.getMessage()).contains("取消").doesNotContain("失败");

        // 同一入口对失败 future 仍给既有归因：取消分支没有把超时/连接失败带偏
        CompletableFuture<String> timedOut = new CompletableFuture<>();
        timedOut.completeExceptionally(new HttpTimeoutException("t"));
        assertThat(((ExternalHttpException) catchThrowable(() -> GuardedExternalHttpClient.await(timedOut)))
                        .getReason())
                .isEqualTo(ExternalHttpException.Reason.TIMEOUT);

        // 正常路径不受影响
        assertThat(GuardedExternalHttpClient.await(CompletableFuture.completedFuture("ok")))
                .isEqualTo("ok");
    }

    // ==================== 夹具与辅助 ====================

    private AiHttpProperties properties(int allowedPort, boolean allowPrivate) {
        AiHttpProperties properties = new AiHttpProperties();
        properties.setAllowedHosts(List.of("127.0.0.1"));
        properties.setAllowedPorts(List.of(allowedPort));
        properties.setAllowPrivateTargets(allowPrivate);
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(Duration.ofSeconds(5));
        return properties;
    }

    private static ExternalHttpRequest get(String url) {
        return new ExternalHttpRequest("GET", url, Map.of(), null, null);
    }

    private static ExternalHttpRequest get(String url, Map<String, String> headers) {
        return new ExternalHttpRequest("GET", url, headers, null, null);
    }

    private String url(String path) {
        return url(port, path);
    }

    private static String url(int port, String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private static int findClosedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void assertStreamReason(
            GuardedExternalHttpClient client, ExternalHttpRequest request, ExternalHttpException.Reason reason) {
        assertThatThrownBy(() -> client.openStream(request))
                .isInstanceOf(ExternalHttpException.class)
                .satisfies(exception -> assertThat(((ExternalHttpException) exception).getReason())
                        .isEqualTo(reason));
    }
}
