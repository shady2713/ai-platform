package com.basicframework.framework.ai.provider.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.ai.config.AiModelProperties;
import com.basicframework.framework.ai.core.http.AiHttpProperties;
import com.basicframework.framework.ai.core.model.EmbeddingRequest;
import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelEndpointSnapshot;
import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelProbeKind;
import com.basicframework.framework.ai.core.model.ModelProbeResult;
import com.basicframework.framework.ai.core.model.ModelRequest;
import com.basicframework.framework.ai.core.model.StructuredModelRequest;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.retry.TransientAiException;
import reactor.core.publisher.Flux;

/**
 * M03/M04 边角分支：空提示词、同步抛错的流式调用、厂商空响应与乱序 index、
 * 工具被上游自动执行的防护、以及重试等待的边界。
 */
class CoverageBranchesTest {

    private static ModelEndpointSnapshot snapshot(ModelCapability... capabilities) {
        return new ModelEndpointSnapshot(
                1L,
                1,
                1,
                "openai_compatible",
                "https://api.example.com/v1",
                "gpt-4o-mini",
                Set.of(capabilities),
                "sk-test");
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void generateAndStreamTolerateBlankPrompt() {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return VendorChatResponses.text("ok");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(VendorChatResponses.text("ok"));
            }
        };
        SpringAiModelClient client =
                new SpringAiModelClient(snapshot(ModelCapability.TEXT, ModelCapability.TEXT_STREAM), model);

        assertThat(client.generate(new ModelRequest("gpt-4o-mini", null, null)).text())
                .isEqualTo("ok");
        try (var stream = client.stream(new ModelRequest("gpt-4o-mini", null, null))) {
            assertThat(stream.hasNext()).isTrue();
        }
    }

    @Test
    void streamMapsSynchronousFailureWithoutLeakingVendorBody() {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                throw new IllegalStateException("vendor body sk-must-not-leak");
            }
        };
        SpringAiModelClient client =
                new SpringAiModelClient(snapshot(ModelCapability.TEXT, ModelCapability.TEXT_STREAM), model);

        assertThatThrownBy(() -> client.stream(ModelRequest.of("gpt-4o-mini", "ping")))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> {
                    ModelException modelException = (ModelException) exception;
                    assertThat(modelException.getReason()).isEqualTo(ModelException.Reason.UPSTREAM_FAILED);
                    assertThat(modelException.getMessage()).doesNotContain("sk-must-not-leak");
                });
    }

    @Test
    void embedRejectsNullVendorResponseAndSortsMissingIndexFirst() {
        EmbeddingModel nullResponse = new EmbeddingModel() {
            @Override
            public org.springframework.ai.embedding.EmbeddingResponse call(
                    org.springframework.ai.embedding.EmbeddingRequest request) {
                return null;
            }

            @Override
            public float[] embed(Document document) {
                return new float[2];
            }
        };
        SpringAiModelClient withoutResponse = new SpringAiModelClient(
                snapshot(ModelCapability.EMBEDDING), unusedChatModel(), nullResponse, new AiModelProperties());
        assertThatThrownBy(() -> withoutResponse.embed(EmbeddingRequest.of("m", List.of("a"))))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.UPSTREAM_FAILED));

        EmbeddingModel unordered = new EmbeddingModel() {
            @Override
            public org.springframework.ai.embedding.EmbeddingResponse call(
                    org.springframework.ai.embedding.EmbeddingRequest request) {
                return new org.springframework.ai.embedding.EmbeddingResponse(
                        List.of(new Embedding(new float[] {9f, 9f}, null), new Embedding(new float[] {1f, 1f}, 1)));
            }

            @Override
            public float[] embed(Document document) {
                return new float[2];
            }
        };
        SpringAiModelClient client = new SpringAiModelClient(
                snapshot(ModelCapability.EMBEDDING), unusedChatModel(), unordered, new AiModelProperties());

        assertThat(client.embed(EmbeddingRequest.of("m", List.of("a", "b"))).vector(0))
                .containsExactly(9f, 9f);
    }

    @Test
    void toolProbeFailsWhenUpstreamExecutesThePlaceholderTool() {
        ChatModel autoExecuting = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                var options = (org.springframework.ai.model.tool.ToolCallingChatOptions) prompt.getOptions();
                // 模拟厂商自动执行工具：占位工具会抛错，这里吞掉异常以暴露"被自动执行"这一事实
                options.getToolCallbacks().forEach(callback -> {
                    try {
                        callback.call("{}");
                    } catch (RuntimeException ignored) {
                        // 预期：占位工具拒绝执行
                    }
                });
                return VendorChatResponses.toolCalls(
                        "", VendorChatResponses.toolCall("call_1", "platform_probe", "{}"));
            }
        };
        SpringAiModelClient client =
                new SpringAiModelClient(snapshot(ModelCapability.TEXT, ModelCapability.TOOL_CALLING), autoExecuting);

        ModelProbeResult probe = client.probe(ModelProbeKind.TOOL_CALLING);

        assertThat(probe.status()).isEqualTo(ModelProbeResult.Status.FAILED);
        assertThat(probe.detailCode()).isEqualTo("UPSTREAM_FAILED");
    }

    @Test
    void toolProbeMapsVendorFailure() {
        ChatModel failing = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new TransientAiException("429 Too Many Requests");
            }
        };
        SpringAiModelClient client =
                new SpringAiModelClient(snapshot(ModelCapability.TEXT, ModelCapability.TOOL_CALLING), failing);

        ModelProbeResult probe = client.probe(ModelProbeKind.TOOL_CALLING);

        assertThat(probe.detailCode()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void retryWithoutBackoffStillRetries() {
        int[] calls = {0};
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                if (calls[0]++ == 0) {
                    throw new TransientAiException("429");
                }
                return VendorChatResponses.text("ok");
            }
        };
        AiModelProperties properties = new AiModelProperties();
        properties.setRetryBackoff(Duration.ZERO);
        SpringAiModelClient client = new SpringAiModelClient(snapshot(ModelCapability.TEXT), model, properties);

        assertThat(client.generate(ModelRequest.of("gpt-4o-mini", "ping")).text())
                .isEqualTo("ok");
        assertThat(calls[0]).isEqualTo(2);
    }

    @Test
    void interruptedRetryWaitSurfacesAsTimeout() {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new TransientAiException("429");
            }
        };
        AiModelProperties properties = new AiModelProperties();
        properties.setRetryBackoff(Duration.ofMillis(5));
        SpringAiModelClient client = new SpringAiModelClient(snapshot(ModelCapability.TEXT), model, properties);

        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> client.generate(ModelRequest.of("gpt-4o-mini", "ping")))
                .isInstanceOf(ModelException.class)
                .satisfies(exception ->
                        assertThat(((ModelException) exception).getReason()).isEqualTo(ModelException.Reason.TIMEOUT));
    }

    @Test
    void structuredFailureKeepsStableReason() {
        SpringAiModelClient client = new SpringAiModelClient(
                snapshot(ModelCapability.TEXT, ModelCapability.STRUCTURED_OUTPUT),
                prompt -> VendorChatResponses.text("not json"));

        assertThatThrownBy(() -> client.generateStructured(
                        StructuredModelRequest.of("gpt-4o-mini", null, "{\"type\":\"object\"}")))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.INVALID_STRUCTURED_OUTPUT));
    }

    @Test
    void factoryCreatesEmbeddingModelAndRejectsNonHttpsDefaultPort() {
        AiHttpProperties httpProperties = new AiHttpProperties();
        httpProperties.setAllowedHosts(List.of("api.example.com"));
        httpProperties.setAllowedPorts(List.of(443));
        SpringAiModelClientFactory factory = new SpringAiModelClientFactory(httpProperties, new AiModelProperties());

        assertThat(factory.getOrCreate(snapshot(ModelCapability.EMBEDDING, ModelCapability.TEXT)))
                .isNotNull();

        // 显式 443 端口 + https：允许
        assertThat(factory.getOrCreate(new ModelEndpointSnapshot(
                        2L,
                        1,
                        1,
                        "openai_compatible",
                        "https://api.example.com:443/v1",
                        "m",
                        Set.of(ModelCapability.TEXT),
                        "sk")))
                .isNotNull();

        // 未写端口且非 https：落到 80 端口后被允许清单拒绝
        assertThatThrownBy(() -> factory.getOrCreate(new ModelEndpointSnapshot(
                        3L,
                        1,
                        1,
                        "openai_compatible",
                        "http://api.example.com/v1",
                        "m",
                        Set.of(ModelCapability.TEXT),
                        "sk")))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.TARGET_NOT_ALLOWED));
    }

    private static ChatModel unusedChatModel() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
