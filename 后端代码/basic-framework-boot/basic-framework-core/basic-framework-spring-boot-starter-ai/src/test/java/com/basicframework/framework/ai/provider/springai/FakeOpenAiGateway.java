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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 假 OpenAI 兼容端点（M07 专项夹具，F11 扩展）：同时提供四条通道的协议端点
 * （{@code /v1/chat/completions}、{@code /v1/embeddings}、{@code /v1/audio/transcriptions}、
 * {@code /v1/audio/speech}），并记录每次收到的请求。
 *
 * <p>只监听 127.0.0.1，不访问外网。{@code chatStatus}/{@code chatDelayMillis} 用于制造
 * 上游 5xx 与超时，用于断言 M02 既有语义（超时、5xx 重试）没有因为接线守卫而回退。
 *
 * <p><b>F11 增量到达</b>：默认构造（M07 既有行为不变）仍是一次性写出整个 SSE 响应；
 * 给出 {@code streamEventDelayMillis > 0} 时改为**分块传输 + 逐事件 flush + 事件间延时**，
 * 于是"首块先于末块到达"可以被真实观测（而不是靠断言"能流"）。
 * {@code streamEventCount} 超过内置事件数时继续补事件，用于制造超过
 * {@code max-response-bytes} 的长流。
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

    /**
     * SSE 事件逐个列出：分块模式按事件 flush 写出；整包模式由它们拼成整体。
     * 两种模式字节相同，差别只在"什么时候发出去"。
     */
    private static final List<String> STREAM_EVENTS = List.of(
            """
            data: {"id":"chatcmpl-fake","object":"chat.completion.chunk","created":1,"model":"fake-model",\
            "choices":[{"index":0,"delta":{"role":"assistant","content":"流式"},"finish_reason":null}]}\n\n""",
            """
            data: {"id":"chatcmpl-fake","object":"chat.completion.chunk","created":1,"model":"fake-model",\
            "choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}\n\n""",
            "data: [DONE]\n\n");

    private static final byte[] STREAM_BODY = String.join("", STREAM_EVENTS).getBytes(StandardCharsets.UTF_8);

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

    /** >0 时 SSE 改为分块传输：逐事件 flush 并在事件之间延时（F11 增量到达用）。 */
    private final long streamEventDelayMillis;

    /** 事件总数；超过内置事件数时补"填充"事件（长流用）。 */
    private final int streamEventCount;

    private final AtomicLong streamBytesWritten = new AtomicLong();

    private final AtomicBoolean streamClientGone = new AtomicBoolean();

    FakeOpenAiGateway() throws IOException {
        this(200, 0L);
    }

    FakeOpenAiGateway(int chatStatus, long chatDelayMillis) throws IOException {
        this(chatStatus, chatDelayMillis, 0L, 0);
    }

    FakeOpenAiGateway(int chatStatus, long chatDelayMillis, long streamEventDelayMillis, int streamEventCount)
            throws IOException {
        this.chatStatus = chatStatus;
        this.chatDelayMillis = chatDelayMillis;
        this.streamEventDelayMillis = streamEventDelayMillis;
        this.streamEventCount = streamEventCount;
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
            if (streamEventDelayMillis > 0) {
                handleStreaming(exchange);
                return;
            }
            send(exchange, 200, "text/event-stream", STREAM_BODY);
            return;
        }
        send(exchange, 200, "application/json", CHAT_BODY);
    }

    /**
     * 分块 SSE：响应头先发（守卫的 {@code openStream} 立即返回），之后逐事件 flush。
     *
     * <p>每写一块就累加 {@link #streamBytesWritten}，因此"客户端在夹具写完之前就收到了首块"可以被
     * 观测为 {@code streamBytesWritten() < totalStreamBytes()}。客户端提前终止流时（守卫超限终止），
     * 后续写入会失败，据此置 {@link #streamClientGone()}。
     */
    private void handleStreaming(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        // 长度未知 ⇒ HTTP/1.1 分块传输（与真实 SSE 服务端一致，不声明 content-length）
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream output = exchange.getResponseBody()) {
            for (String event : streamEvents()) {
                byte[] bytes = event.getBytes(StandardCharsets.UTF_8);
                output.write(bytes);
                output.flush();
                streamBytesWritten.addAndGet(bytes.length);
                if (streamEventDelayMillis > 0) {
                    sleep(streamEventDelayMillis);
                }
            }
        } catch (IOException clientGone) {
            // 客户端提前终止了流（守卫超限终止/取消）：这正是"有界终止"的证据
            streamClientGone.set(true);
        }
    }

    private List<String> streamEvents() {
        if (streamEventCount <= STREAM_EVENTS.size()) {
            return STREAM_EVENTS;
        }
        // 长流只补"增量"事件：内建事件里含 data: [DONE]，重复它会让厂商协议正常结束流（而不是撞上限）
        List<String> events = new ArrayList<>(streamEventCount);
        String delta = STREAM_EVENTS.get(0);
        for (int index = 0; index < streamEventCount; index++) {
            events.add(delta);
        }
        return events;
    }

    /** 夹具打算写出的 SSE 总字节数：用来与 {@link #streamBytesWritten()} 对比判断"是否被提前终止"。 */
    int totalStreamBytes() {
        int total = 0;
        for (String event : streamEvents()) {
            total += event.getBytes(StandardCharsets.UTF_8).length;
        }
        return total;
    }

    /** 已经写到网络上的 SSE 字节数（未 flush 出去的不计）。 */
    long streamBytesWritten() {
        return streamBytesWritten.get();
    }

    /** 夹具在写完之前就被客户端终止了（连接已释放）。 */
    boolean streamClientGone() {
        return streamClientGone.get();
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
