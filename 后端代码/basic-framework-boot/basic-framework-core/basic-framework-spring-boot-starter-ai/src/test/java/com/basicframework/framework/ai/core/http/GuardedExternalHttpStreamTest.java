package com.basicframework.framework.ai.core.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * F11 流式出站治理测试：与 {@link GuardedExternalHttpClientTest}（请求/响应入口）并存，
 * 逐条证明流式入口**没有少一道闸门**，且超限是**有界终止**而不是"读完再整体拒绝"。
 *
 * <p>全部使用本地 {@link HttpServer}，不访问外网。
 */
class GuardedExternalHttpStreamTest {

    /** 分块端点每块字节数。 */
    private static final int CHUNK_BYTES = 256;

    /** 分块端点总块数（分块模式下每块之间延时，用于观测增量到达）。 */
    private static final int CHUNK_COUNT = 4;

    private static final long CHUNK_DELAY_MILLIS = 120L;

    /** 长流端点：块小、间隔短、总量远大于上限，用于证明"有界终止"而不是"读完再判"。 */
    private static final int FLOOD_CHUNKS = 2_048;

    private static final long FLOOD_DELAY_MILLIS = 30L;

    private HttpServer server;

    private int port;

    private final AtomicInteger received = new AtomicInteger();

    private final AtomicLong written = new AtomicLong();

    private final AtomicBoolean clientGone = new AtomicBoolean();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/chunks", this::handleChunks);
        server.createContext("/flood", exchange -> handleChunked(exchange, FLOOD_CHUNKS, FLOOD_DELAY_MILLIS));
        server.createContext("/slow-headers", exchange -> {
            received.incrementAndGet();
            sleep(2_000L);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/fixed", exchange -> {
            received.incrementAndGet();
            byte[] body = "fixed-body".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** 逐块 flush 的分块响应：每块之间延时，首块先于末块到达可被观测。 */
    private void handleChunks(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        received.incrementAndGet();
        handleChunked(exchange, CHUNK_COUNT, CHUNK_DELAY_MILLIS);
    }

    /**
     * 分块写出的长流：响应头先发（守卫的 {@code openStream} 立即返回），之后逐块 flush。
     *
     * <p>每写一块累加 {@code written}；客户端提前终止流时后续写入失败，据此置 {@code clientGone}。
     */
    private void handleChunked(com.sun.net.httpserver.HttpExchange exchange, int chunks, long delay) {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        // 长度未知 ⇒ HTTP/1.1 分块传输（与真实 SSE 服务端一致，不声明 content-length）
        try {
            exchange.sendResponseHeaders(200, 0);
        } catch (IOException clientGone) {
            this.clientGone.set(true);
            return;
        }
        try (OutputStream output = exchange.getResponseBody()) {
            for (int index = 0; index < chunks; index++) {
                byte[] chunk = new byte[CHUNK_BYTES];
                Arrays.fill(chunk, (byte) ('a' + index % 26));
                output.write(chunk);
                output.flush();
                written.addAndGet(chunk.length);
                if (delay > 0) {
                    sleep(delay);
                }
            }
        } catch (IOException gone) {
            clientGone.set(true);
        }
    }

    private AiHttpProperties properties(List<String> hosts, boolean allowPrivate) {
        AiHttpProperties properties = new AiHttpProperties();
        properties.setAllowedHosts(hosts);
        properties.setAllowedPorts(List.of(port));
        properties.setAllowPrivateTargets(allowPrivate);
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(Duration.ofSeconds(5));
        return properties;
    }

    private static ExternalHttpRequest get(String url) {
        return ExternalHttpRequest.get(url);
    }

    private static String url(int port, String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private String url(String path) {
        return url(port, path);
    }

    // ==================== 增量到达 ====================

    @Test
    void firstChunkArrivesBeforeTheStreamIsFinished() {
        AiHttpProperties properties = properties(List.of("127.0.0.1"), true);
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(properties)) {
            ExternalHttpStreamResponse stream = client.openStream(get(url("/chunks")));

            assertThat(stream.status()).isEqualTo(200);
            assertThat(stream.headers()).containsKey("content-type");
            assertThat(stream.declaredLength()).as("分块传输没有声明长度").isEqualTo(-1L);

            byte[] buffer = new byte[8 * 1024];
            int first = stream.readChunk(buffer);

            assertThat(first).isPositive();
            assertThat(Arrays.copyOf(buffer, first))
                    .as("首块内容就是第一块（不是等全部读完后才交付的整包）")
                    .isEqualTo(chunkOf('a'));
            assertThat(written.get())
                    .as("首块交付时上游还没写完（已写 %s，总量 %s）", written.get(), totalBytes())
                    .isLessThan(totalBytes());
            assertThat(stream.deliveredBytes()).isEqualTo(first);

            stream.close();
        }
    }

    @Test
    void drainingAStreamYieldsEveryChunkAndThenEnds() {
        AiHttpProperties properties = properties(List.of("127.0.0.1"), true);
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(properties)) {
            ExternalHttpStreamResponse stream = client.openStream(get(url("/chunks")));

            byte[] buffer = new byte[8 * 1024];
            byte[] collected = new byte[totalBytes()];
            int total = 0;
            int read = stream.readChunk(buffer);
            while (read > 0) {
                System.arraycopy(buffer, 0, collected, total, read);
                total += read;
                read = stream.readChunk(buffer);
            }

            assertThat(read).as("流结束返回 -1").isEqualTo(-1);
            assertThat(total).isEqualTo(totalBytes());
            assertThat(collected).isEqualTo(expectedChunks());
            assertThat(stream.deliveredBytes()).isEqualTo(totalBytes());
            stream.close();
        }
    }

    // ==================== 超限：有界终止 + 稳定错误码 ====================

    @Test
    void exceedingTheCapTerminatesTheStreamWithStableReasonInsteadOfBufferingItAll() {
        AiHttpProperties properties = properties(List.of("127.0.0.1"), true);
        properties.setMaxResponseBytes(2_000);
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(properties)) {
            ExternalHttpStreamResponse stream = client.openStream(get(url("/flood")));

            byte[] buffer = new byte[8 * 1024];
            int delivered = 0;
            ExternalHttpException failure;
            try {
                int read = stream.readChunk(buffer);
                while (read > 0) {
                    delivered += read;
                    read = stream.readChunk(buffer);
                }
                throw new AssertionError("超限的流必须以 RESPONSE_TOO_LARGE 终止，不能干净结束");
            } catch (ExternalHttpException exception) {
                failure = exception;
            }

            assertThat(failure.getReason()).isEqualTo(ExternalHttpException.Reason.RESPONSE_TOO_LARGE);
            assertThat(delivered).as("此前的块确实交付了（不是先读完再判）").isPositive();
            assertThat(stream.deliveredBytes())
                    .as("已交付量恒不超过上限")
                    .isLessThanOrEqualTo(2_000)
                    .isEqualTo(delivered);
            assertThat(delivered).as("上限远小于长流总量").isLessThan(floodBytes());
            assertThat(written.get())
                    .as("守卫提前终止了读取：上游没把整个流写完（已写 %s，总量 %s）", written.get(), floodBytes())
                    .isLessThan(floodBytes());
            stream.close();
        }
    }

    @Test
    void closingTheStreamLetsUpstreamObserveTheAbort() {
        AiHttpProperties properties = properties(List.of("127.0.0.1"), true);
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(properties)) {
            ExternalHttpStreamResponse stream = client.openStream(get(url("/flood")));
            byte[] buffer = new byte[8 * 1024];
            assertThat(stream.readChunk(buffer)).isPositive();

            stream.close();

            awaitTrue(clientGone, "取消/终止后上游写入失败（连接已释放）");
        }
    }

    // ==================== 反向：请求期被拒，请求不离开进程 ====================

    @Test
    void hostOutsideAllowListIsRefusedBeforeAnythingIsSent() {
        AiHttpProperties properties = properties(List.of("api.example.com"), true);
        properties.setAllowedPorts(List.of(443));
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(properties)) {
            assertStreamReason(client, get(url("/chunks")), ExternalHttpException.Reason.TARGET_NOT_ALLOWED);
            assertThat(received.get()).as("请求根本没离开进程").isZero();
        }
    }

    @Test
    void unapprovedPrivateTargetAndUnapprovedSchemeAreRefusedBeforeAnythingIsSent() {
        try (GuardedExternalHttpClient client =
                new GuardedExternalHttpClient(properties(List.of("127.0.0.1"), false))) {
            // http 方案未批准内网 ⇒ 协议约束先拒
            assertStreamReason(client, get(url("/chunks")), ExternalHttpException.Reason.TARGET_NOT_ALLOWED);
            // https 通过方案检查，但解析到环回且未显式批准
            assertStreamReason(
                    client,
                    ExternalHttpRequest.get("https://127.0.0.1:" + port + "/chunks"),
                    ExternalHttpException.Reason.PRIVATE_TARGET_DENIED);
            assertThat(received.get()).isZero();
        }
    }

    @Test
    void credentialHeaderIsRefusedByGuardRuleBeforeAnythingIsSent() {
        AiHttpProperties properties = properties(List.of("127.0.0.1"), true);
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(properties)) {
            ExternalHttpRequest request =
                    new ExternalHttpRequest("GET", url("/chunks"), Map.of("Cookie", "session=1"), null, null);

            assertStreamReason(client, request, ExternalHttpException.Reason.INVALID_REQUEST);
            assertThat(received.get()).isZero();
        }
    }

    @Test
    void closedClientRejectsStreamingRequests() {
        GuardedExternalHttpClient client = new GuardedExternalHttpClient(properties(List.of("127.0.0.1"), true));
        client.close();
        client.close();

        assertStreamReason(client, get(url("/chunks")), ExternalHttpException.Reason.INVALID_REQUEST);
        assertThat(received.get()).isZero();
    }

    // ==================== 失败传播：超时与上游中断 ====================

    @Test
    void waitingTooLongForResponseHeadersReportsStableTimeout() {
        AiHttpProperties properties = properties(List.of("127.0.0.1"), true);
        properties.setReadTimeout(Duration.ofMillis(300));
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(properties)) {
            assertStreamReason(client, get(url("/slow-headers")), ExternalHttpException.Reason.TIMEOUT);
        }
    }

    @Test
    void declaredLengthIsSurfacedForNonChunkedResponses() {
        AiHttpProperties properties = properties(List.of("127.0.0.1"), true);
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(properties)) {
            ExternalHttpStreamResponse stream = client.openStream(get(url("/fixed")));

            assertThat(stream.declaredLength()).isEqualTo("fixed-body".getBytes(StandardCharsets.UTF_8).length);
            assertThat(stream.readChunk(new byte[64])).isEqualTo(10);
        }
    }

    // ==================== 有界读取器本身（确定性，不依赖 socket 时序） ====================

    @Test
    void boundedReaderDeliversUpToTheLimitAndThenReportsStableReason() {
        BoundedExternalHttpStreamResponse stream =
                new BoundedExternalHttpStreamResponse(200, Map.of(), -1L, 4, new ScriptedInputStream(4, 2, -1));

        byte[] buffer = new byte[8];
        assertThat(stream.readChunk(buffer)).as("额度内的块先交付").isEqualTo(4);
        assertThat(stream.deliveredBytes()).isEqualTo(4);
        assertStreamReadReason(stream, ExternalHttpException.Reason.RESPONSE_TOO_LARGE);
        assertThat(stream.deliveredBytes()).as("越界的那一块不计入交付").isEqualTo(4);
    }

    @Test
    void boundedReaderEndsCleanlyWhenTheStreamStopsExactlyAtTheLimit() {
        BoundedExternalHttpStreamResponse stream =
                new BoundedExternalHttpStreamResponse(200, Map.of(), -1L, 4, new ScriptedInputStream(4, -1));

        assertThat(stream.readChunk(new byte[8])).isEqualTo(4);
        assertThat(stream.readChunk(new byte[8])).as("刚好等于上限不算超限").isEqualTo(-1);
    }

    @Test
    void boundedReaderSurvivesEmptyReadsAndRejectsUnusableBuffers() {
        BoundedExternalHttpStreamResponse stream =
                new BoundedExternalHttpStreamResponse(200, Map.of(), -1L, 16, new ScriptedInputStream(0, 3, -1));

        assertThat(stream.readChunk(new byte[8])).as("上游本次没给出数据").isEqualTo(0);
        assertThat(stream.readChunk(new byte[8])).isEqualTo(3);
        assertThat(stream.readChunk(new byte[8])).isEqualTo(-1);

        assertThatThrownBy(() -> stream.readChunk(new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stream.readChunk(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundedReaderMapsReadFailureToStableReasonAndClosesOnce() {
        ScriptedInputStream upstream = new ScriptedInputStream(new IOException("boom"));
        BoundedExternalHttpStreamResponse stream =
                new BoundedExternalHttpStreamResponse(200, Map.of(), -1L, 16, upstream);

        assertStreamReadReason(stream, ExternalHttpException.Reason.CONNECT_FAILED);
        assertThat(upstream.closeCount()).isEqualTo(1);
    }

    @Test
    void closeFailureDoesNotMaskTheTerminationOutcome() {
        ScriptedInputStream upstream = new ScriptedInputStream(4);
        upstream.failOnClose = true;
        BoundedExternalHttpStreamResponse stream =
                new BoundedExternalHttpStreamResponse(200, Map.of(), -1L, 16, upstream);

        stream.close();

        assertThat(upstream.closeCount()).isEqualTo(1);
        assertThat(stream.readChunk(new byte[8])).as("关闭失败也不影响「已关闭」这一事实").isEqualTo(-1);
    }

    @Test
    void closeIsIdempotentAndEndsFurtherReads() {
        ScriptedInputStream upstream = new ScriptedInputStream(8);
        BoundedExternalHttpStreamResponse stream =
                new BoundedExternalHttpStreamResponse(200, Map.of(), -1L, 16, upstream);

        stream.close();
        stream.close();

        assertThat(upstream.closeCount()).isEqualTo(1);
        assertThat(stream.readChunk(new byte[8])).as("关闭后不再交付数据").isEqualTo(-1);
    }

    // ==================== 夹具与断言辅助 ====================

    private static int totalBytes() {
        return CHUNK_BYTES * CHUNK_COUNT;
    }

    private static int floodBytes() {
        return CHUNK_BYTES * FLOOD_CHUNKS;
    }

    private static byte[] expectedChunks() {
        byte[] expected = new byte[totalBytes()];
        for (int index = 0; index < CHUNK_COUNT; index++) {
            System.arraycopy(chunkOf((char) ('a' + index)), 0, expected, index * CHUNK_BYTES, CHUNK_BYTES);
        }
        return expected;
    }

    private static byte[] chunkOf(char fill) {
        byte[] chunk = new byte[CHUNK_BYTES];
        Arrays.fill(chunk, (byte) fill);
        return chunk;
    }

    private static void assertStreamReason(
            GuardedExternalHttpClient client, ExternalHttpRequest request, ExternalHttpException.Reason reason) {
        assertThatThrownBy(() -> client.openStream(request))
                .isInstanceOf(ExternalHttpException.class)
                .satisfies(exception -> assertThat(((ExternalHttpException) exception).getReason())
                        .isEqualTo(reason));
    }

    private static void assertStreamReadReason(ExternalHttpStreamResponse stream, ExternalHttpException.Reason reason) {
        assertThatThrownBy(() -> {
                    byte[] buffer = new byte[8];
                    while (stream.readChunk(buffer) > 0) {
                        // 读到超限/失败为止
                    }
                })
                .isInstanceOf(ExternalHttpException.class)
                .satisfies(exception -> assertThat(((ExternalHttpException) exception).getReason())
                        .isEqualTo(reason));
    }

    private static void awaitTrue(AtomicBoolean flag, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!flag.get() && System.nanoTime() < deadline) {
            sleep(20L);
        }
        assertThat(flag.get()).as(what).isTrue();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** 可编排的响应体：按脚本返回字节数（{@code -1} 表示流结束）或抛 IO 异常。 */
    private static final class ScriptedInputStream extends InputStream {

        private final Deque<Object> steps = new ArrayDeque<>();

        private int closeCount;

        /** 让 {@link #close()} 抛异常：验证关闭失败不掩盖终止语义。 */
        boolean failOnClose;

        ScriptedInputStream(Object... steps) {
            this.steps.addAll(Arrays.asList(steps));
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int read = read(single, 0, 1);
            return read < 0 ? -1 : single[0] & 0xFF;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (steps.isEmpty()) {
                return -1;
            }
            Object step = steps.poll();
            if (step instanceof IOException exception) {
                throw exception;
            }
            int count = Math.min((Integer) step, length);
            if (count > 0) {
                Arrays.fill(target, offset, offset + count, (byte) 'x');
            }
            return count;
        }

        @Override
        public void close() throws IOException {
            closeCount++;
            if (failOnClose) {
                throw new IOException("close boom");
            }
        }

        int closeCount() {
            return closeCount;
        }
    }
}
