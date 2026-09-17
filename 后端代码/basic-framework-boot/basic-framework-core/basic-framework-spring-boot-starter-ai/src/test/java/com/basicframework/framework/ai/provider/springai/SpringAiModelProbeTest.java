package com.basicframework.framework.ai.provider.springai;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.ai.config.AiModelProperties;
import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelEndpointSnapshot;
import com.basicframework.framework.ai.core.model.ModelProbeKind;
import com.basicframework.framework.ai.core.model.ModelProbeResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.retry.TransientAiException;

/**
 * M04 能力探测：五类探测都以**真实调用**为依据，失败不被"端点已启用"掩盖。
 *
 * <p>关键约束：工具调用探测注册占位工具并关闭厂商内部执行循环，探测过程**不执行任何工具**。
 */
class SpringAiModelProbeTest {

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

    private static EmbeddingModel embeddingModel(int dimensions) {
        return new EmbeddingModel() {

            @Override
            public org.springframework.ai.embedding.EmbeddingResponse call(
                    org.springframework.ai.embedding.EmbeddingRequest request) {
                return new org.springframework.ai.embedding.EmbeddingResponse(
                        List.of(new Embedding(new float[dimensions], 0)), null);
            }

            @Override
            public float[] embed(Document document) {
                return new float[dimensions];
            }
        };
    }

    @Test
    void connectivityAndTextProbesUseRealCalls() {
        SpringAiModelClient client =
                new SpringAiModelClient(snapshot(ModelCapability.TEXT), prompt -> VendorChatResponses.text("pong"));

        ModelProbeResult connectivity = client.probe(ModelProbeKind.CONNECTIVITY);
        assertThat(connectivity.isSupported()).isTrue();
        assertThat(connectivity.detailCode()).isNull();

        ModelProbeResult text = client.probe(ModelProbeKind.TEXT);
        assertThat(text.isSupported()).isTrue();
        assertThat(text.latencyMillis()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void emptyTextMakesTextProbeUnsupportedInsteadOfSilentSuccess() {
        SpringAiModelClient client =
                new SpringAiModelClient(snapshot(ModelCapability.TEXT), prompt -> VendorChatResponses.text("   "));

        ModelProbeResult text = client.probe(ModelProbeKind.TEXT);

        assertThat(text.status()).isEqualTo(ModelProbeResult.Status.UNSUPPORTED);
        assertThat(text.detailCode()).isEqualTo(ModelProbeResult.CODE_NO_TEXT_RETURNED);
    }

    @Test
    void structuredProbeFailsWhenModelKeepsReturningUnusableJson() {
        SpringAiModelClient client = new SpringAiModelClient(
                snapshot(ModelCapability.TEXT, ModelCapability.STRUCTURED_OUTPUT),
                prompt -> VendorChatResponses.text("这不是 JSON"));

        ModelProbeResult probe = client.probe(ModelProbeKind.STRUCTURED_OUTPUT);

        assertThat(probe.status()).isEqualTo(ModelProbeResult.Status.FAILED);
        assertThat(probe.detailCode()).isEqualTo("INVALID_STRUCTURED_OUTPUT");
    }

    @Test
    void structuredProbeSucceedsOnValidObject() {
        SpringAiModelClient client = new SpringAiModelClient(
                snapshot(ModelCapability.TEXT, ModelCapability.STRUCTURED_OUTPUT),
                prompt -> VendorChatResponses.text("{\"ok\":true}"));

        assertThat(client.probe(ModelProbeKind.STRUCTURED_OUTPUT).isSupported()).isTrue();
    }

    @Test
    void toolCallingProbeNeverExecutesThePlaceholderTool() {
        List<Prompt> seen = new ArrayList<>();
        SpringAiModelClient client =
                new SpringAiModelClient(snapshot(ModelCapability.TEXT, ModelCapability.TOOL_CALLING), prompt -> {
                    seen.add(prompt);
                    return VendorChatResponses.toolCalls(
                            "", VendorChatResponses.toolCall("call_1", "platform_probe", "{\"value\":\"ping\"}"));
                });

        ModelProbeResult probe = client.probe(ModelProbeKind.TOOL_CALLING);

        assertThat(probe.isSupported()).isTrue();
        ToolCallingChatOptions options = (ToolCallingChatOptions) seen.get(0).getOptions();
        assertThat(options.getInternalToolExecutionEnabled())
                .as("厂商内部工具执行循环必须关闭，探测不得触发任何工具执行")
                .isFalse();
        assertThat(options.getToolCallbacks()).singleElement().satisfies(callback -> assertThat(
                        callback.getToolDefinition().name())
                .isEqualTo("platform_probe"));
    }

    @Test
    void toolCallingProbeReportsUnsupportedWhenModelReturnsNoToolCall() {
        SpringAiModelClient client = new SpringAiModelClient(
                snapshot(ModelCapability.TEXT, ModelCapability.TOOL_CALLING),
                prompt -> VendorChatResponses.text("我直接回答，不需要工具"));

        ModelProbeResult probe = client.probe(ModelProbeKind.TOOL_CALLING);

        assertThat(probe.status()).isEqualTo(ModelProbeResult.Status.UNSUPPORTED);
        assertThat(probe.detailCode()).isEqualTo(ModelProbeResult.CODE_TOOL_CALL_NOT_RETURNED);
    }

    @Test
    void embeddingProbeReportsObservedDimension() {
        SpringAiModelClient client = new SpringAiModelClient(
                snapshot(ModelCapability.TEXT, ModelCapability.EMBEDDING),
                prompt -> VendorChatResponses.text("pong"),
                embeddingModel(1536),
                new AiModelProperties());

        ModelProbeResult probe = client.probe(ModelProbeKind.EMBEDDING);

        assertThat(probe.isSupported()).isTrue();
        assertThat(probe.embeddingDimension()).isEqualTo(1536);
    }

    @Test
    void streamingProbeRequiresAtLeastOneDelta() {
        SpringAiModelClient supported =
                new SpringAiModelClient(snapshot(ModelCapability.TEXT, ModelCapability.TEXT_STREAM), new ChatModel() {

                    @Override
                    public ChatResponse call(Prompt prompt) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
                        return reactor.core.publisher.Flux.just(VendorChatResponses.text("p"));
                    }
                });
        assertThat(supported.probe(ModelProbeKind.TEXT_STREAM).isSupported()).isTrue();

        SpringAiModelClient silent =
                new SpringAiModelClient(snapshot(ModelCapability.TEXT, ModelCapability.TEXT_STREAM), new ChatModel() {

                    @Override
                    public ChatResponse call(Prompt prompt) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
                        return reactor.core.publisher.Flux.empty();
                    }
                });
        ModelProbeResult probe = silent.probe(ModelProbeKind.TEXT_STREAM);
        assertThat(probe.status()).isEqualTo(ModelProbeResult.Status.UNSUPPORTED);
        assertThat(probe.detailCode()).isEqualTo(ModelProbeResult.CODE_NO_TEXT_RETURNED);
    }

    @Test
    void undeclaredCapabilitiesReportUnsupportedNotFailed() {
        SpringAiModelClient textOnly =
                new SpringAiModelClient(snapshot(ModelCapability.TEXT), prompt -> VendorChatResponses.text("pong"));

        ModelProbeResult structured = textOnly.probe(ModelProbeKind.STRUCTURED_OUTPUT);
        assertThat(structured.status()).isEqualTo(ModelProbeResult.Status.UNSUPPORTED);
        assertThat(structured.detailCode()).isEqualTo(ModelProbeResult.CODE_CAPABILITY_NOT_DECLARED);

        ModelProbeResult embedding = textOnly.probe(ModelProbeKind.EMBEDDING);
        assertThat(embedding.detailCode()).isEqualTo(ModelProbeResult.CODE_CAPABILITY_NOT_DECLARED);
    }

    @Test
    void probeFailuresCarryStableReasonAndKeepEnabledEndpointVisible() {
        SpringAiModelClient client = new SpringAiModelClient(snapshot(ModelCapability.TEXT), prompt -> {
            throw new TransientAiException("429 Too Many Requests");
        });

        ModelProbeResult probe = client.probe(ModelProbeKind.TEXT);

        assertThat(probe.status()).isEqualTo(ModelProbeResult.Status.FAILED);
        assertThat(probe.detailCode()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void probeOnClosedClientReportsFailureInsteadOfSuccess() {
        SpringAiModelClient client =
                new SpringAiModelClient(snapshot(ModelCapability.TEXT), prompt -> VendorChatResponses.text("pong"));
        client.close();

        ModelProbeResult probe = client.probe(ModelProbeKind.TEXT);

        assertThat(probe.status()).isEqualTo(ModelProbeResult.Status.FAILED);
        assertThat(probe.detailCode()).isEqualTo("AI_DISABLED");
    }

    @Test
    void probeNeverLeaksVendorText() {
        SpringAiModelClient client = new SpringAiModelClient(snapshot(ModelCapability.TEXT), prompt -> {
            throw new IllegalStateException("vendor body with sk-must-not-leak");
        });

        ModelProbeResult probe = client.probe(ModelProbeKind.CONNECTIVITY);

        assertThat(probe.detailCode()).isEqualTo("UPSTREAM_FAILED");
    }
}
