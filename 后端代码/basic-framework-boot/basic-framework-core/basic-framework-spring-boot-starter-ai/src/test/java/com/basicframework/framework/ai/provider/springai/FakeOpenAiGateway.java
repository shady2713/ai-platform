package com.basicframework.framework.ai.provider.springai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 假 OpenAI 兼容端点（M07 专项夹具）：同时提供四条通道的协议端点
 * （{@code /v1/chat/completions}、{@code /v1/embeddings}、{@code /v1/audio/transcriptions}、
 * {@code /v1/audio/speech}），并记录每次收到的请求。
 *
 * <p>只监听 127.0.0.1，不访问外网。{@code chatStatus}/{@code chatDelayMillis} 用于制造
 * 上游 5xx 与超时，用于断言 M02 既有语义（超时、5xx 重试）没有因为接线守卫而回退。
 */
final class FakeOpenAiGateway implements AutoCloseable {

    /** 语音合成返回的固定音频字节（内容无关，只判"非空且与夹具一致"）。 */
    static final byte[] SPEECH_BYTES = {9, 8, 7, 6, 5};

    /** 上游 5xx 的响应体：故意带一句像密钥的文案，用来断言平台错误里不泄漏上游内容。 */
    private static final String UPSTREAM_ERROR_BODY =
            "{\"error\":{\"message\":\"upstream boom sk-must-not-leak\",\"type\":\"server_error\"}}";

    private static final byte[] CHAT_BODY =
            """
            {"id":"chatcmpl-fake","object":"chat.completion","created":1,"model":"fake-model",\
            "choices":[{"index":0,"message":{"role":"assistant","content":"同步回复"},"finish_reason":"stop"}],\
            "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
                    .getBytes(StandardCharsets.UTF_8);

    private static final byte[] STREAM_BODY =
            """
            data: {"id":"chatcmpl-fake","object":"chat.completion.chunk","created":1,"model":"fake-model",\
            "choices":[{"index":0,"delta":{"role":"assistant","content":"流式"},"finish_reason":null}]}\n\n\
            data: {"id":"chatcmpl-fake","object":"chat.completion.chunk","created":1,"model":"fake-model",\
            "choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}\n\n\
            data: [DONE]\n\n"""
                    .getBytes(StandardCharsets.UTF_8);

    private static final byte[] EMBEDDINGS_BODY =
            """
            {"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.5,0.25]},\
            {"object":"embedding","index":1,"embedding":[0.1,0.2]}],"model":"fake-model",\
            "usage":{"prompt_tokens":2,"total_tokens":2}}"""
                    .getBytes(StandardCharsets.UTF_8);

    private static final byte[] TRANSCRIPTION_BODY = "{\"text\":\"转写夹具\"}".getBytes(StandardCharsets.UTF_8);

    private final HttpServer server;

    private final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());

    private final int chatStatus;

    private final long chatDelayMillis;

    FakeOpenAiGateway() throws IOException {
        this(200, 0L);
    }

    FakeOpenAiGateway(int chatStatus, long chatDelayMillis) throws IOException {
        this.chatStatus = chatStatus;
        this.chatDelayMillis = chatDelayMillis;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handleChat);
        server.createContext("/v1/embeddings", exchange -> answer(exchange, 200, "application/json", EMBEDDINGS_BODY));
        server.createContext(
                "/v1/audio/transcriptions", exchange -> answer(exchange, 200, "application/json", TRANSCRIPTION_BODY));
        server.createContext("/v1/audio/speech", exchange -> answer(exchange, 200, "audio/mpeg", SPEECH_BYTES.clone()));
        server.start();
    }

    private void handleChat(HttpExchange exchange) throws IOException {
        String requestBody = readBody(exchange);
        record(exchange, requestBody);
        if (chatDelayMillis > 0) {
            sleep(chatDelayMillis);
        }
        if (chatStatus != 200) {
            send(exchange, chatStatus, "application/json", UPSTREAM_ERROR_BODY.getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (requestBody.contains("\"stream\":true")) {
            send(exchange, 200, "text/event-stream", STREAM_BODY);
            return;
        }
        send(exchange, 200, "application/json", CHAT_BODY);
    }

    /** 其它通道：记录请求后直接回固定夹具响应。 */
    private void answer(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        record(exchange, readBody(exchange));
        send(exchange, status, contentType, body);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void record(HttpExchange exchange, String requestBody) {
        requests.add(new Recorded(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                header(exchange, "Authorization"),
                requestBody));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static String header(HttpExchange exchange, String name) {
        List<String> values = exchange.getRequestHeaders().get(name);
        return values == null || values.isEmpty() ? "" : values.get(0);
    }

    int port() {
        return server.getAddress().getPort();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    List<Recorded> requests() {
        return List.copyOf(requests);
    }

    int requestCount() {
        return requests.size();
    }

    List<String> paths() {
        return requests.stream().map(Recorded::path).toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** 一次被夹具端点收到的请求（凭据与路径只用于断言，不落盘、不进日志）。 */
    record Recorded(String method, String path, String authorization, String body) {}
}
