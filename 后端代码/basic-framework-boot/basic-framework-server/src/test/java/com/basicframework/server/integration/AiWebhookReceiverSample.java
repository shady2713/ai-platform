package com.basicframework.server.integration;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook 接收端样例（X10）：**真实**的本机 HTTP 接收端，按 {@code docs/contracts/ai/webhook-protocol.md}
 * 的校验顺序处理投递，是"接收端可验证"这条验收的可执行证据。
 *
 * <p>校验顺序（任一步不通过即拒绝，且**不做任何业务处理**）：
 * <ol>
 *   <li>必需请求头缺失 → 400；</li>
 *   <li>时间戳超出容忍窗口（默认 ±5 分钟）→ 401 {@code stale-timestamp}（防重放的时间维度）；</li>
 *   <li>HMAC-SHA256 验签失败（常量时间比较）→ 401 {@code forged-signature}（防篡改）；</li>
 *   <li>投递编号已处理过 → 409 {@code replayed-delivery}，**不二次处理**（防重放的事件维度）；</li>
 *   <li>通过 → 记录事件并回 200。</li>
 * </ol>
 *
 * <p>它与平台的关系：平台把"唯一投递编号 + 时间戳 + 签名"放在请求头，正文按入队时冻结的字节重放，
 * 因此接收端可以在不重排正文的前提下验签与去重。故障注入（5xx / 4xx / 3xx / 慢响应）让用例
 * 覆盖"可重试 vs 确定失败 vs 已处理但响应丢失"三类真实形态。
 */
final class AiWebhookReceiverSample implements AutoCloseable {

    /** 接收端行为模式。 */
    enum Mode {
        /** 正常：校验通过即记录并 200。 */
        OK,
        /** 5xx：接收端内部错误（可重试）。 */
        HTTP_500,
        /** 4xx：接收端明确拒绝（确定失败，不重试）。 */
        HTTP_400,
        /** 3xx：改址（受控出站不跟随，确定失败）。 */
        REDIRECT,
        /** 响应前先睡过客户端超时，**不记录**事件（本次投递在接收端没有生效）。 */
        TIMEOUT_WITHOUT_ACCEPT,
        /** 记录事件后再睡过客户端超时（响应丢失：接收端已生效，重试会撞上重复投递）。 */
        ACCEPT_THEN_TIMEOUT
    }

    /** 一条被接收端接受的事件（只保留核验所需字段）。 */
    record ReceivedEvent(String deliveryNo, int attempt, String eventType, String resource, String status) {}

    private static final long TIMESTAMP_TOLERANCE_SECONDS = 300L;

    private final HttpServer server;

    private final int port;

    private final AtomicInteger requests = new AtomicInteger();

    private final List<ReceivedEvent> accepted = new CopyOnWriteArrayList<>();

    private final List<String> rejections = new CopyOnWriteArrayList<>();

    private final Set<String> processedDeliveryNos = ConcurrentHashMap.newKeySet();

    private volatile String secret = "";

    private volatile Mode mode = Mode.OK;

    private volatile int slowMillis = 3_000;

    private AiWebhookReceiverSample(HttpServer server) {
        this.server = server;
        this.port = server.getAddress().getPort();
    }

    /** 启动一个绑定在回环地址、由内核分配端口的接收端。 */
    static AiWebhookReceiverSample start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AiWebhookReceiverSample receiver = new AiWebhookReceiverSample(server);
        server.createContext("/hook", receiver::handle);
        server.setExecutor(Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "webhook-receiver-sample");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        return receiver;
    }

    int port() {
        return port;
    }

    String origin() {
        return "http://127.0.0.1:" + port + "/hook";
    }

    void setSecret(String sharedSecret) {
        this.secret = sharedSecret == null ? "" : sharedSecret;
    }

    void setMode(Mode next) {
        this.mode = next;
    }

    void setSlowMillis(int millis) {
        this.slowMillis = Math.max(500, millis);
    }

    /** 收到的请求总数（含被拒的）。 */
    int requests() {
        return requests.get();
    }

    /** 被接受（业务上处理过）的事件，按到达顺序。 */
    List<ReceivedEvent> acceptedEvents() {
        return List.copyOf(accepted);
    }

    /** 拒绝记录（稳定原因词表：missing-header/stale-timestamp/forged-signature/replayed-delivery）。 */
    List<String> rejections() {
        return List.copyOf(rejections);
    }

    /** 清空计数与已处理集合（用例之间互不影响）。 */
    void reset() {
        requests.set(0);
        accepted.clear();
        rejections.clear();
        processedDeliveryNos.clear();
        mode = Mode.OK;
        slowMillis = 3_000;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---------- 以下为接收端样例的校验实现（与协议文档逐条对应） ----------

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        Mode current = mode;
        try {
            byte[] body = readAll(exchange.getRequestBody());
            Map<String, String> headers = lowerCaseHeaders(exchange);
            if (current == Mode.REDIRECT) {
                exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/moved");
                send(exchange, 302, "");
                return;
            }
            if (current == Mode.HTTP_500 || current == Mode.HTTP_400) {
                send(exchange, current == Mode.HTTP_500 ? 500 : 400, "");
                return;
            }
            if (current == Mode.TIMEOUT_WITHOUT_ACCEPT) {
                sleep();
                send(exchange, 200, "");
                return;
            }
            String deliveryNo = headers.get("x-ai-webhook-id");
            String timestamp = headers.get("x-ai-webhook-timestamp");
            String signature = headers.get("x-ai-webhook-signature");
            if (!hasText(deliveryNo) || !hasText(timestamp) || !hasText(signature)) {
                rejections.add("missing-header");
                send(exchange, 400, "missing-header");
                return;
            }
            long requestTimestamp;
            try {
                requestTimestamp = Long.parseLong(timestamp);
            } catch (NumberFormatException malformed) {
                rejections.add("stale-timestamp");
                send(exchange, 401, "stale-timestamp");
                return;
            }
            if (Math.abs(Instant.now().getEpochSecond() - requestTimestamp) > TIMESTAMP_TOLERANCE_SECONDS) {
                rejections.add("stale-timestamp");
                send(exchange, 401, "stale-timestamp");
                return;
            }
            if (!signatureMatches(timestamp, deliveryNo, body, signature)) {
                rejections.add("forged-signature");
                send(exchange, 401, "forged-signature");
                return;
            }
            if (!processedDeliveryNos.add(deliveryNo)) {
                // 同一投递编号已经处理过：拒绝二次处理（重试沿用同一编号，因此重复投递在此被拒）
                rejections.add("replayed-delivery");
                send(exchange, 409, "replayed-delivery");
                return;
            }
            JsonNode payload = JsonUtils.parseTree(new String(body, StandardCharsets.UTF_8));
            accepted.add(new ReceivedEvent(
                    deliveryNo,
                    Integer.parseInt(headers.getOrDefault("x-ai-webhook-attempt", "0")),
                    headers.get("x-ai-webhook-event"),
                    headers.get("x-ai-webhook-resource"),
                    payload == null ? null : payload.path("status").asText(null)));
            if (current == Mode.ACCEPT_THEN_TIMEOUT) {
                // 已生效但响应丢失：客户端会超时并重试，重试将撞上 409 重复投递
                sleep();
            }
            send(exchange, 200, "{}");
        } catch (RuntimeException unexpected) {
            send(exchange, 500, "");
        } finally {
            exchange.close();
        }
    }

    /** 与平台同一规范串：{@code timestamp.deliveryNo.sha256hex(body)} 的 HMAC-SHA256，常量时间比较。 */
    private boolean signatureMatches(String timestamp, String deliveryNo, byte[] body, String signature) {
        if (secret.isEmpty()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String canonical = timestamp + "." + deliveryNo + "."
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            String expected = "v1=" + HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) {
            return false;
        }
    }

    private void sleep() {
        try {
            Thread.sleep(slowMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        try (stream) {
            return stream.readAllBytes();
        }
    }

    private static Map<String, String> lowerCaseHeaders(HttpExchange exchange) {
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders()
                .forEach((name, values) ->
                        headers.put(name.toLowerCase(java.util.Locale.ROOT), String.join(",", values)));
        return headers;
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
