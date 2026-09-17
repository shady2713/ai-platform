package com.basicframework.framework.ai.provider.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.ai.config.AiModelProperties;
import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelRequest;
import com.basicframework.framework.ai.core.model.ModelResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;

/**
 * M03 有界重试：只重发可重试失败、次数有上限；上游明确拒绝与调用方错误不重发。
 *
 * <p>对应用户可见行为：AT-003 要求上游 429/超时时"有界重试、稳定错误、无秘密"。
 */
class SpringAiModelRetryTest {

    private static AiModelProperties fastProperties() {
        AiModelProperties properties = new AiModelProperties();
        properties.setRetryBackoff(Duration.ofMillis(10));
        return properties;
    }

    private static SpringAiModelClient client(ChatModel model, AiModelProperties properties) {
        return new SpringAiModelClient(VendorChatResponses.snapshot(ModelCapability.TEXT), model, properties);
    }

    @Test
    void retriesRateLimitedUpstreamAndSucceedsWithinBudget() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                if (calls.incrementAndGet() == 1) {
                    throw new TransientAiException("429 Too Many Requests");
                }
                return VendorChatResponses.textWithFinishReason("done", "stop");
            }
        };

        ModelResponse response = client(model, fastProperties()).generate(ModelRequest.of("gpt-4o-mini", "hi"));

        assertThat(response.text()).isEqualTo("done");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void stopsAfterConfiguredBoundedAttempts() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                calls.incrementAndGet();
                throw new TransientAiException("429 Too Many Requests");
            }
        };
        AiModelProperties properties = fastProperties();
        properties.setMaxAttempts(2);

        assertThatThrownBy(() -> client(model, properties).generate(ModelRequest.of("gpt-4o-mini", "hi")))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.RATE_LIMITED));
        assertThat(calls.get()).as("重试次数由配置上限约束，不无限重发").isEqualTo(2);
    }

    @Test
    void doesNotRetryWhenUpstreamRejectsTheRequest() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                calls.incrementAndGet();
                throw new NonTransientAiException("400 invalid model");
            }
        };
        AiModelProperties properties = fastProperties();
        properties.setMaxAttempts(3);

        assertThatThrownBy(() -> client(model, properties).generate(ModelRequest.of("gpt-4o-mini", "hi")))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> {
                    ModelException modelException = (ModelException) exception;
                    assertThat(modelException.getReason()).isEqualTo(ModelException.Reason.UPSTREAM_REJECTED);
                    assertThat(modelException.getMessage()).doesNotContain("invalid model");
                });
        assertThat(calls.get()).as("上游明确拒绝时不重发").isEqualTo(1);
    }

    @Test
    void retriesTimeoutsAsWell() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                calls.incrementAndGet();
                throw new ProviderTimeoutException("read timed out");
            }
        };

        assertThatThrownBy(() -> client(model, fastProperties()).generate(ModelRequest.of("gpt-4o-mini", "hi")))
                .isInstanceOf(ModelException.class)
                .satisfies(exception ->
                        assertThat(((ModelException) exception).getReason()).isEqualTo(ModelException.Reason.TIMEOUT));
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void retryWhitelistCoversOnlyRecoverableReasons() {
        assertThat(SpringAiModelClient.isRetryable(ModelException.Reason.TIMEOUT))
                .isTrue();
        assertThat(SpringAiModelClient.isRetryable(ModelException.Reason.RATE_LIMITED))
                .isTrue();
        assertThat(SpringAiModelClient.isRetryable(ModelException.Reason.UPSTREAM_FAILED))
                .isTrue();

        assertThat(SpringAiModelClient.isRetryable(ModelException.Reason.UPSTREAM_REJECTED))
                .isFalse();
        assertThat(SpringAiModelClient.isRetryable(ModelException.Reason.CAPABILITY_UNSUPPORTED))
                .isFalse();
        assertThat(SpringAiModelClient.isRetryable(ModelException.Reason.INVALID_STRUCTURED_INPUT))
                .isFalse();
        assertThat(SpringAiModelClient.isRetryable(ModelException.Reason.INVALID_STRUCTURED_OUTPUT))
                .isFalse();
        assertThat(SpringAiModelClient.isRetryable(ModelException.Reason.OUTPUT_LIMIT_EXCEEDED))
                .isFalse();
        assertThat(SpringAiModelClient.isRetryable(ModelException.Reason.AI_DISABLED))
                .isFalse();
        assertThat(SpringAiModelClient.isRetryable(ModelException.Reason.ENDPOINT_NOT_FOUND))
                .isFalse();
        assertThat(SpringAiModelClient.isRetryable(ModelException.Reason.ENDPOINT_DISABLED))
                .isFalse();
        assertThat(SpringAiModelClient.isRetryable(ModelException.Reason.CREDENTIAL_UNAVAILABLE))
                .isFalse();
        assertThat(SpringAiModelClient.isRetryable(ModelException.Reason.TARGET_NOT_ALLOWED))
                .isFalse();
    }

    /** 与厂商超时异常同名（类名含 timeout）的非受检异常，验证超时映射。 */
    private static final class ProviderTimeoutException extends RuntimeException {

        private ProviderTimeoutException(String message) {
            super(message);
        }
    }
}
