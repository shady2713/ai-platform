package com.basicframework.framework.ai.provider.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelEndpointSnapshot;
import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelRequest;
import com.basicframework.framework.ai.core.model.ModelResponse;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 厂商响应到自有响应的转换与失败映射（M02 建立，M03 跟随用量词表调整）：
 * 空响应、缺少结束原因与用量、正常用量、工具调用、超时与上游失败都必须收敛为稳定结果。
 */
class SpringAiModelClientCoverageTest {

    private static ModelEndpointSnapshot snapshot() {
        return new ModelEndpointSnapshot(
                1L,
                1,
                1,
                "openai_compatible",
                "https://api.example.com/v1",
                "gpt-4o-mini",
                Set.of(ModelCapability.TEXT),
                "sk");
    }

    private static SpringAiModelClient client(ChatModel model) {
        return new SpringAiModelClient(snapshot(), model);
    }

    private static ChatModel modelReturning(ChatResponse response) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return response;
            }
        };
    }

    @Test
    void mapsTextFinishReasonAndUsageFromVendorResponse() {
        ChatResponse response = new ChatResponse(
                List.of(new Generation(
                        new AssistantMessage("pong"),
                        ChatGenerationMetadata.builder().finishReason("stop").build())),
                ChatResponseMetadata.builder().usage(new DefaultUsage(3, 2)).build());

        ModelResponse mapped = client(modelReturning(response)).generate(ModelRequest.of("gpt-4o-mini", "ping"));

        assertThat(mapped.text()).isEqualTo("pong");
        assertThat(mapped.finishReason()).isEqualTo("stop");
        assertThat(mapped.usage().promptTokens()).isEqualTo(3);
        assertThat(mapped.usage().completionTokens()).isEqualTo(2);
        assertThat(mapped.usage().isKnown()).isTrue();
        assertThat(mapped.modelId()).isEqualTo("gpt-4o-mini");
        assertThat(mapped.hasToolCalls()).isFalse();
    }

    @Test
    void surfacesToolCallsAsDataWithoutExecutingThem() {
        ChatResponse response = VendorChatResponses.toolCalls(
                "", VendorChatResponses.toolCall("call_1", "query", "{\"sql\":\"select 1\"}"));

        ModelResponse mapped = client(modelReturning(response)).generate(ModelRequest.of("gpt-4o-mini", "ping"));

        assertThat(mapped.hasToolCalls()).isTrue();
        assertThat(mapped.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("call_1");
            assertThat(call.name()).isEqualTo("query");
            assertThat(call.argumentsJson()).isEqualTo("{\"sql\":\"select 1\"}");
        });
    }

    @Test
    void toleratesMissingResultFinishReasonAndUsage() {
        // 空 choices：没有 result，也没有用量与结束原因
        ModelResponse empty =
                client(modelReturning(new ChatResponse(List.of()))).generate(ModelRequest.of("gpt-4o-mini", "ping"));
        assertThat(empty.text()).isEmpty();
        assertThat(empty.finishReason()).isNull();
        assertThat(empty.usage().isKnown()).as("用量缺失必须是 UNKNOWN，不能是假 0").isFalse();
        assertThat(empty.usage().promptTokens()).isNull();
        assertThat(empty.usage().completionTokens()).isNull();

        // null 响应：同样收敛为稳定空结果
        ModelResponse nullResponse = client(modelReturning(null)).generate(ModelRequest.of("gpt-4o-mini", "ping"));
        assertThat(nullResponse.text()).isEmpty();
    }

    @Test
    void mapsVendorFailuresToStableReasonsWithoutLeakingDetails() {
        ChatModel failing = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new IllegalStateException("upstream says: key sk-must-not-leak is invalid");
            }
        };
        assertThatThrownBy(() -> client(failing).generate(ModelRequest.of("gpt-4o-mini", "ping")))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> {
                    ModelException modelException = (ModelException) exception;
                    assertThat(modelException.getReason()).isEqualTo(ModelException.Reason.UPSTREAM_FAILED);
                    assertThat(modelException.getMessage()).doesNotContain("sk-must-not-leak");
                });

        ChatModel timingOut = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new ProviderTimeoutException("read timed out");
            }
        };
        assertThatThrownBy(() -> client(timingOut).generate(ModelRequest.of("gpt-4o-mini", "ping")))
                .isInstanceOf(ModelException.class)
                .satisfies(exception ->
                        assertThat(((ModelException) exception).getReason()).isEqualTo(ModelException.Reason.TIMEOUT));
    }

    @Test
    void rejectsTextWhenEndpointLacksTextCapability() {
        SpringAiModelClient embeddingOnly = new SpringAiModelClient(
                new ModelEndpointSnapshot(
                        1L,
                        1,
                        1,
                        "openai_compatible",
                        "https://api.example.com/v1",
                        "text-embedding",
                        Set.of(ModelCapability.EMBEDDING),
                        "sk"),
                modelReturning(new ChatResponse(List.of())));

        assertThatThrownBy(() -> embeddingOnly.generate(ModelRequest.of("text-embedding", "ping")))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.CAPABILITY_UNSUPPORTED));
        assertThat(embeddingOnly.capabilities()).containsExactly(ModelCapability.EMBEDDING);
        assertThat(embeddingOnly.snapshot().modelId()).isEqualTo("text-embedding");
    }

    @Test
    void rejectsCallsAfterClose() {
        SpringAiModelClient closed = client(modelReturning(VendorChatResponses.text("pong")));
        closed.close();

        assertThatThrownBy(() -> closed.generate(ModelRequest.of("gpt-4o-mini", "ping")))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.UPSTREAM_FAILED));
    }

    /** 与厂商超时异常同名的非受检异常，验证失败原因按异常类型名收敛为 TIMEOUT。 */
    private static final class ProviderTimeoutException extends RuntimeException {

        private ProviderTimeoutException(String message) {
            super(message);
        }
    }
}
