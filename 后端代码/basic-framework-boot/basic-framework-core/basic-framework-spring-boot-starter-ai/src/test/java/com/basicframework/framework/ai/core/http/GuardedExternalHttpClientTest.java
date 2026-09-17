package com.basicframework.framework.ai.core.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 出站 HTTP 边界测试：允许清单、私网拒绝与显式批准、不跟随重定向、超时、响应上限、
 * 请求头卫生、取消与关闭语义。全部使用本地 {@link HttpServer}，不访问外网。
 */
class GuardedExternalHttpClientTest {

    private HttpServer server;

    private int port;

    private final AtomicReference<String> receivedHeader = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/ok", exchange -> {
            receivedHeader.set(exchange.getRequestHeaders().getFirst("X-Test"));
            byte[] body = "pong".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/ok");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/large", exchange -> {
            byte[] body = new byte[8192];
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException ignored) {
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

    private AiHttpProperties properties(List<String> hosts, boolean allowPrivate) {
        AiHttpProperties properties = new AiHttpProperties();
        properties.setAllowedHosts(hosts);
        properties.setAllowedPorts(List.of(port));
        properties.setAllowPrivateTargets(allowPrivate);
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(Duration.ofSeconds(5));
        return properties;
    }

    private static ExternalHttpRequest get(String url, Map<String, String> headers) {
        return new ExternalHttpRequest("GET", url, headers, null, null);
    }

    @Test
    void denyAllWhenAllowListIsEmpty() {
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(new AiHttpProperties())) {
            assertReason(client, "https://api.example.com/v1", ExternalHttpException.Reason.TARGET_NOT_ALLOWED);
        }
    }

    @Test
    void denyHostOutsideAllowListAndPortOutsideAllowList() {
        try (GuardedExternalHttpClient client =
                new GuardedExternalHttpClient(properties(List.of("other.example.com"), true))) {
            assertReason(client, "http://127.0.0.1:" + port + "/ok", ExternalHttpException.Reason.TARGET_NOT_ALLOWED);
        }
        AiHttpProperties wrongPort = properties(List.of("127.0.0.1"), true);
        wrongPort.setAllowedPorts(List.of(9_999));
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(wrongPort)) {
            assertReason(client, "http://127.0.0.1:" + port + "/ok", ExternalHttpException.Reason.TARGET_NOT_ALLOWED);
        }
    }

    @Test
    void denyPrivateTargetUnlessExplicitlyApproved() {
        AiHttpProperties unapproved = properties(List.of("127.0.0.1"), false);
        unapproved.setAllowedPorts(List.of(443));
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(unapproved)) {
            // https 通过 scheme 检查，但地址是环回且未显式批准 → 私网拒绝
            assertReason(client, "https://127.0.0.1/v1", ExternalHttpException.Reason.PRIVATE_TARGET_DENIED);
        }
    }

    @Test
    void approvedPrivateTargetSucceedsAndStripsHostHeaders() throws Exception {
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(properties(List.of("127.0.0.1"), true))) {
            ExternalHttpResponse response =
                    client.execute(get("http://127.0.0.1:" + port + "/ok", Map.of("X-Test", "server-built")));

            assertThat(response.status()).isEqualTo(200);
            assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("pong");
            assertThat(response.isSuccessful()).isTrue();
            assertThat(receivedHeader).hasValue("server-built");
        }
    }

    @Test
    void rejectsBlockedHeadersAndTooManyHeaders() {
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(properties(List.of("127.0.0.1"), true))) {
            // 宿主凭据与传输层头不允许透传
            assertReasonForRequest(
                    client,
                    get("http://127.0.0.1:" + port + "/ok", Map.of("Cookie", "session=1")),
                    ExternalHttpException.Reason.INVALID_REQUEST);

            AiHttpProperties limited = properties(List.of("127.0.0.1"), true);
            limited.setMaxHeaderCount(1);
            try (GuardedExternalHttpClient limitedClient = new GuardedExternalHttpClient(limited)) {
                assertReasonForRequest(
                        limitedClient,
                        get("http://127.0.0.1:" + port + "/ok", Map.of("X-A", "1", "X-B", "2")),
                        ExternalHttpException.Reason.INVALID_REQUEST);
            }
        }
    }

    @Test
    void rejectsMalformedTargetsAndUnapprovedSchemes() {
        try (GuardedExternalHttpClient client =
                new GuardedExternalHttpClient(properties(List.of("127.0.0.1"), false))) {
            assertReasonForRequest(
                    client,
                    new ExternalHttpRequest("", "http://127.0.0.1/x", Map.of(), null, null),
                    ExternalHttpException.Reason.INVALID_REQUEST);
            assertReasonForRequest(
                    client,
                    new ExternalHttpRequest("GET", "not-a-url", Map.of(), null, null),
                    ExternalHttpException.Reason.INVALID_REQUEST);
            assertReasonForRequest(
                    client,
                    new ExternalHttpRequest("GET", "https:///x", Map.of(), null, null),
                    ExternalHttpException.Reason.INVALID_REQUEST);
            // http 只在显式批准内网目标时允许
            assertReasonForRequest(
                    client, get("http://127.0.0.1/x", Map.of()), ExternalHttpException.Reason.TARGET_NOT_ALLOWED);
        }
    }

    @Test
    void reportsUnresolvableHostAsConnectFailure() {
        AiHttpProperties props = properties(List.of("no-such-host.invalid"), true);
        props.setAllowedPorts(List.of(443));
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(props)) {
            assertReason(client, "https://no-such-host.invalid/v1", ExternalHttpException.Reason.CONNECT_FAILED);
        }
    }

    @Test
    void doesNotFollowRedirects() throws Exception {
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(properties(List.of("127.0.0.1"), true))) {
            ExternalHttpResponse response = client.execute(get("http://127.0.0.1:" + port + "/redirect", Map.of()));

            assertThat(response.status()).isEqualTo(302);
            assertThat(response.headers().entrySet().stream()
                            .anyMatch(entry -> entry.getKey().equalsIgnoreCase("location")
                                    && entry.getValue().contains("/ok")))
                    .isTrue();
            assertThat(response.isSuccessful()).isFalse();
        }
    }

    @Test
    void rejectsResponseLargerThanLimitByDeclaredAndByActualLength() {
        AiHttpProperties small = properties(List.of("127.0.0.1"), true);
        small.setMaxResponseBytes(1_024);
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(small)) {
            // 声明长度超限：直接拒绝并丢弃响应
            assertReason(
                    client, "http://127.0.0.1:" + port + "/large", ExternalHttpException.Reason.RESPONSE_TOO_LARGE);
        }

        AiHttpProperties tiny = properties(List.of("127.0.0.1"), true);
        tiny.setMaxResponseBytes(2);
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(tiny)) {
            // 实际读取超限：读取上限+1 字节后中断
            assertReason(
                    client, "http://127.0.0.1:" + port + "/large", ExternalHttpException.Reason.RESPONSE_TOO_LARGE);
        }
    }

    @Test
    void reportsTimeoutWhenServerIsTooSlow() {
        AiHttpProperties fast = properties(List.of("127.0.0.1"), true);
        fast.setReadTimeout(Duration.ofMillis(200));
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(fast)) {
            assertReason(client, "http://127.0.0.1:" + port + "/slow", ExternalHttpException.Reason.TIMEOUT);
        }
    }

    @Test
    void cancellationStopsPendingRequest() {
        AiHttpProperties props = properties(List.of("127.0.0.1"), true);
        props.setReadTimeout(Duration.ofSeconds(30));
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(props)) {
            CompletableFuture<ExternalHttpResponse> future =
                    client.executeAsync(get("http://127.0.0.1:" + port + "/slow", Map.of()));

            assertThat(future.cancel(true)).isTrue();
            assertThat(future.isCancelled()).isTrue();
        }
    }

    @Test
    void closedClientRejectsFurtherRequests() {
        GuardedExternalHttpClient client = new GuardedExternalHttpClient(properties(List.of("127.0.0.1"), true));
        client.close();
        client.close();

        assertReason(client, "http://127.0.0.1:" + port + "/ok", ExternalHttpException.Reason.INVALID_REQUEST);
    }

    @Test
    void headerCountDefaultAndPortDefaultAreApplied() {
        AiHttpProperties defaults = new AiHttpProperties();
        assertThat(defaults.getAllowedHosts()).isEmpty();
        assertThat(defaults.getAllowedPorts()).containsExactly(443);
        assertThat(defaults.isAllowPrivateTargets()).isFalse();
        assertThat(defaults.getMaxHeaderCount()).isEqualTo(32);
        assertThat(defaults.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(defaults.getReadTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaults.getMaxResponseBytes()).isEqualTo(1_048_576);
    }

    private static void assertReason(
            GuardedExternalHttpClient client, String url, ExternalHttpException.Reason reason) {
        assertReasonForRequest(client, get(url, Map.of()), reason);
    }

    private static void assertReasonForRequest(
            GuardedExternalHttpClient client, ExternalHttpRequest request, ExternalHttpException.Reason reason) {
        assertThatThrownBy(() -> client.execute(request))
                .isInstanceOf(ExternalHttpException.class)
                .satisfies(exception -> assertThat(((ExternalHttpException) exception).getReason())
                        .isEqualTo(reason));
    }

    @Test
    void executeAsyncFailureCarriesStableReason() {
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(new AiHttpProperties())) {
            CompletableFuture<ExternalHttpResponse> future =
                    client.executeAsync(get("https://api.example.com/v1", Map.of()));

            assertThat(future)
                    .failsWithin(5, TimeUnit.SECONDS)
                    .withThrowableOfType(java.util.concurrent.ExecutionException.class)
                    .withMessageContaining("允许清单");
        }
    }

    @Test
    void postsBodyAndReportsConnectFailureForClosedPort() throws Exception {
        AiHttpProperties props = properties(List.of("127.0.0.1"), true);
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(props)) {
            // POST + 请求体走通
            ExternalHttpRequest post = new ExternalHttpRequest(
                    "POST", "http://127.0.0.1:" + port + "/ok", Map.of("X-Test", "post"), "body".getBytes(), null);
            assertThat(client.execute(post).status()).isEqualTo(200);

            // 该端口无人监听：解析成功但连接被拒 → CONNECT_FAILED
            int closedPort = findClosedPort();
            AiHttpProperties closed = properties(List.of("127.0.0.1"), true);
            closed.setAllowedPorts(List.of(closedPort));
            closed.setConnectTimeout(Duration.ofMillis(500));
            try (GuardedExternalHttpClient closedClient = new GuardedExternalHttpClient(closed)) {
                assertReasonForRequest(
                        closedClient,
                        get("http://127.0.0.1:" + closedPort + "/ok", Map.of()),
                        ExternalHttpException.Reason.CONNECT_FAILED);
            }
        }
    }

    private static int findClosedPort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void rejectsUriThatCannotBeParsed() {
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(properties(List.of("127.0.0.1"), true))) {
            assertReasonForRequest(
                    client,
                    new ExternalHttpRequest("GET", "http://127.0.0.1/a b", Map.of(), null, null),
                    ExternalHttpException.Reason.INVALID_REQUEST);
        }
    }

    @Test
    void rejectsChunkedResponseLargerThanLimit() throws Exception {
        HttpServer chunked = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        chunked.createContext("/chunked", exchange -> {
            // 长度 0 表示分块传输：响应没有 Content-Length，只能靠读取上限拦截
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(new byte[4096]);
            exchange.close();
        });
        chunked.start();
        try {
            AiHttpProperties tiny = properties(List.of("127.0.0.1"), true);
            tiny.setAllowedPorts(List.of(chunked.getAddress().getPort()));
            tiny.setMaxResponseBytes(1_024);
            try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(tiny)) {
                assertReason(
                        client,
                        "http://127.0.0.1:" + chunked.getAddress().getPort() + "/chunked",
                        ExternalHttpException.Reason.RESPONSE_TOO_LARGE);
            }
        } finally {
            chunked.stop(0);
        }
    }

    @Test
    void boundedReadMapsIoFailureAndDrainFailureToStableErrors() throws Exception {
        AiHttpProperties props = properties(List.of("127.0.0.1"), true);
        props.setMaxResponseBytes(16);
        try (GuardedExternalHttpClient client = new GuardedExternalHttpClient(props)) {
            // 声明长度超限且流读取失败：丢弃响应时吞掉 IO 失败，仍按超限拒绝
            assertThatThrownBy(() -> client.readBounded(stubResponse(64, failingStream())))
                    .isInstanceOf(ExternalHttpException.class)
                    .satisfies(exception -> assertThat(((ExternalHttpException) exception).getReason())
                            .isEqualTo(ExternalHttpException.Reason.RESPONSE_TOO_LARGE));
            // 读取过程中 IO 失败：映射为 CONNECT_FAILED
            assertThatThrownBy(() -> client.readBounded(stubResponse(-1, failingStream())))
                    .isInstanceOf(ExternalHttpException.class)
                    .satisfies(exception -> assertThat(((ExternalHttpException) exception).getReason())
                            .isEqualTo(ExternalHttpException.Reason.CONNECT_FAILED));
        }
    }

    private static java.io.InputStream failingStream() {
        return new java.io.InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("stream broken");
            }

            @Override
            public int read(byte[] target, int offset, int length) throws IOException {
                throw new IOException("stream broken");
            }

            @Override
            public byte[] readNBytes(int length) throws IOException {
                throw new IOException("stream broken");
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static java.net.http.HttpResponse<java.io.InputStream> stubResponse(
            long declaredLength, java.io.InputStream body) {
        java.net.http.HttpHeaders headers = java.net.http.HttpHeaders.of(
                declaredLength < 0
                        ? Map.of()
                        : Map.of("content-length", java.util.List.of(String.valueOf(declaredLength))),
                (left, right) -> true);
        return new java.net.http.HttpResponse<>() {
            @Override
            public int statusCode() {
                return 200;
            }

            @Override
            public java.net.http.HttpRequest request() {
                return null;
            }

            @Override
            public java.util.Optional<java.net.http.HttpResponse<java.io.InputStream>> previousResponse() {
                return java.util.Optional.empty();
            }

            @Override
            public java.net.http.HttpHeaders headers() {
                return headers;
            }

            @Override
            public java.io.InputStream body() {
                return body;
            }

            @Override
            public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
                return java.util.Optional.empty();
            }

            @Override
            public java.net.URI uri() {
                return java.net.URI.create("http://127.0.0.1/");
            }

            @Override
            public java.net.http.HttpClient.Version version() {
                return java.net.http.HttpClient.Version.HTTP_1_1;
            }
        };
    }

    @Test
    void failureMappingCoversKnownAndUnknownCauses() {
        assertThat(GuardedExternalHttpClient.mapFailure(new java.net.http.HttpTimeoutException("t"))
                        .getReason())
                .isEqualTo(ExternalHttpException.Reason.TIMEOUT);
        assertThat(GuardedExternalHttpClient.mapFailure(new java.net.ConnectException("c"))
                        .getReason())
                .isEqualTo(ExternalHttpException.Reason.CONNECT_FAILED);
        assertThat(GuardedExternalHttpClient.mapFailure(new IOException("io")).getReason())
                .isEqualTo(ExternalHttpException.Reason.CONNECT_FAILED);
        ExternalHttpException stable = new ExternalHttpException(ExternalHttpException.Reason.INVALID_REQUEST, "x");
        assertThat(GuardedExternalHttpClient.mapFailure(stable)).isSameAs(stable);
    }

    @Test
    void blockedAddressClassificationCoversAllFamilies() throws Exception {
        assertThat(GuardedExternalHttpClient.isBlockedAddress(java.net.InetAddress.getByName("127.0.0.1")))
                .isTrue();
        assertThat(GuardedExternalHttpClient.isBlockedAddress(java.net.InetAddress.getByName("10.1.2.3")))
                .isTrue();
        assertThat(GuardedExternalHttpClient.isBlockedAddress(java.net.InetAddress.getByName("169.254.1.1")))
                .isTrue();
        assertThat(GuardedExternalHttpClient.isBlockedAddress(java.net.InetAddress.getByName("0.0.0.0")))
                .isTrue();
        assertThat(GuardedExternalHttpClient.isBlockedAddress(java.net.InetAddress.getByName("224.0.0.1")))
                .isTrue();
        // IPv6 唯一本地地址（fc00::/7）
        assertThat(GuardedExternalHttpClient.isBlockedAddress(java.net.InetAddress.getByName("fd12:3456::1")))
                .isTrue();
        // 公网地址不拦截（本地地址判定，不发起连接）
        assertThat(GuardedExternalHttpClient.isBlockedAddress(java.net.InetAddress.getByName("8.8.8.8")))
                .isFalse();
    }

    @Test
    void getFactoryBuildsEmptyGetRequest() {
        ExternalHttpRequest request = ExternalHttpRequest.get("https://api.example.com/v1");

        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.url()).isEqualTo("https://api.example.com/v1");
        assertThat(request.headers()).isEmpty();
        assertThat(request.body()).isNull();
        assertThat(request.timeout()).isNull();
    }

    @Test
    void addressRejectionCoversPassAndDenyPaths() throws Exception {
        // 显式批准内网：直接放行（不发起任何连接）
        GuardedExternalHttpClient.rejectBlockedAddresses(
                new java.net.InetAddress[] {java.net.InetAddress.getByName("10.0.0.1")}, true);

        // 全部为公网地址：正常通过
        GuardedExternalHttpClient.rejectBlockedAddresses(
                new java.net.InetAddress[] {java.net.InetAddress.getByName("8.8.8.8")}, false);

        // 含私网地址且未批准：拒绝
        assertThatThrownBy(() -> GuardedExternalHttpClient.rejectBlockedAddresses(
                        new java.net.InetAddress[] {
                            java.net.InetAddress.getByName("8.8.8.8"), java.net.InetAddress.getByName("10.0.0.1")
                        },
                        false))
                .isInstanceOf(ExternalHttpException.class)
                .satisfies(exception -> assertThat(((ExternalHttpException) exception).getReason())
                        .isEqualTo(ExternalHttpException.Reason.PRIVATE_TARGET_DENIED));
    }
}
