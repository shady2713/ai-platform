package com.basicframework.framework.ai.provider.springai;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.ai.core.http.AiHttpProperties;
import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelEndpointSnapshot;
import com.basicframework.framework.ai.core.model.ModelRequest;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 端点隔离与轮换的真实调用验证（对应 AT-001 / AT-004）。
 *
 * <p>两个假 OpenAI 兼容端点各自记录收到的 Authorization 与 model 字段：
 * <ul>
 *   <li>并发调用两个端点的客户端时，baseUrl、凭据、modelId 不得串线（AT-001）；</li>
 *   <li>轮换凭据后必须使用新密钥，失效后旧客户端被关闭、重建使用当前版本（AT-004）。</li>
 * </ul>
 */
class SpringAiEndpointIsolationTest {

    private static final Pattern MODEL_PATTERN = Pattern.compile("\"model\"\\s*:\\s*\"([^\"]+)\"");

    /** 假 OpenAI 兼容端点：记录收到的凭据/模型，返回可区分的回答。 */
    private static final class FakeOpenAiEndpoint implements AutoCloseable {

        private final HttpServer server;

        private final List<String> receivedAuthorization = Collections.synchronizedList(new java.util.ArrayList<>());

        private final List<String> receivedModels = Collections.synchronizedList(new java.util.ArrayList<>());

        private final String replyText;

        FakeOpenAiEndpoint(String replyText) throws IOException {
            this.replyText = replyText;
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                receivedAuthorization.add(authorization == null ? "" : authorization);
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Matcher matcher = MODEL_PATTERN.matcher(body);
                receivedModels.add(matcher.find() ? matcher.group(1) : "");
                byte[] response =
                        ("""
                        {"id":"chatcmpl-fake","object":"chat.completion","created":1,"model":"fake","choices":\
                        [{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],\
                        "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
                                        .formatted(replyText))
                                .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static ModelEndpointSnapshot snapshot(
            Long endpointId, int credentialRevision, String baseUrl, String modelId, String apiKey) {
        return new ModelEndpointSnapshot(
                endpointId,
                1,
                credentialRevision,
                "openai_compatible",
                baseUrl,
                modelId,
                Set.of(ModelCapability.TEXT),
                apiKey);
    }

    @Test
    void concurrentCallsToTwoEndpointsDoNotCrossBaseUrlModelOrCredential() throws Exception {
        try (FakeOpenAiEndpoint endpointA = new FakeOpenAiEndpoint("reply-a");
                FakeOpenAiEndpoint endpointB = new FakeOpenAiEndpoint("reply-b")) {
            AiHttpProperties properties = new AiHttpProperties();
            properties.setAllowedHosts(List.of("127.0.0.1"));
            properties.setAllowedPorts(List.of(endpointA.port(), endpointB.port()));
            SpringAiModelClientFactory factory = new SpringAiModelClientFactory(properties);

            ModelEndpointSnapshot snapshotA =
                    snapshot(1L, 1, "http://127.0.0.1:" + endpointA.port(), "model-a", "sk-a");
            ModelEndpointSnapshot snapshotB =
                    snapshot(2L, 1, "http://127.0.0.1:" + endpointB.port(), "model-b", "sk-b");

            CompletableFuture<String> callA = CompletableFuture.supplyAsync(() -> factory.getOrCreate(snapshotA)
                    .generate(ModelRequest.of("model-a", "ping"))
                    .text());
            CompletableFuture<String> callB = CompletableFuture.supplyAsync(() -> factory.getOrCreate(snapshotB)
                    .generate(ModelRequest.of("model-b", "ping"))
                    .text());

            // 响应内容证明没有串线
            assertThat(callA.join()).isEqualTo("reply-a");
            assertThat(callB.join()).isEqualTo("reply-b");
            // 每个端点只见过自己的凭据与模型标识
            assertThat(endpointA.receivedAuthorization).containsOnly("Bearer sk-a");
            assertThat(endpointB.receivedAuthorization).containsOnly("Bearer sk-b");
            assertThat(endpointA.receivedModels).containsOnly("model-a");
            assertThat(endpointB.receivedModels).containsOnly("model-b");
            factory.close();
        }
    }

    @Test
    void credentialRotationUsesNewKeyAndInvalidateClosesOldClient() throws Exception {
        try (FakeOpenAiEndpoint endpoint = new FakeOpenAiEndpoint("reply")) {
            AiHttpProperties properties = new AiHttpProperties();
            properties.setAllowedHosts(List.of("127.0.0.1"));
            properties.setAllowedPorts(List.of(endpoint.port()));
            SpringAiModelClientFactory factory = new SpringAiModelClientFactory(properties);
            String baseUrl = "http://127.0.0.1:" + endpoint.port();

            // 轮换前：使用旧密钥
            factory.getOrCreate(snapshot(1L, 1, baseUrl, "model-a", "sk-old"))
                    .generate(ModelRequest.of("model-a", "ping"));
            // 轮换后（credentialRevision+1）：新请求必须使用新密钥
            factory.getOrCreate(snapshot(1L, 2, baseUrl, "model-a", "sk-new"))
                    .generate(ModelRequest.of("model-a", "ping"));

            assertThat(endpoint.receivedAuthorization).containsExactly("Bearer sk-old", "Bearer sk-new");

            // 显式失效后缓存清空，旧的旧密钥客户端不再被复用
            factory.invalidate(1L);
            assertThat(factory.size()).isZero();
            factory.getOrCreate(snapshot(1L, 2, baseUrl, "model-a", "sk-new"))
                    .generate(ModelRequest.of("model-a", "ping"));
            assertThat(endpoint.receivedAuthorization).hasSize(3);
            assertThat(endpoint.receivedAuthorization.get(2)).isEqualTo("Bearer sk-new");
            factory.close();
        }
    }
}
