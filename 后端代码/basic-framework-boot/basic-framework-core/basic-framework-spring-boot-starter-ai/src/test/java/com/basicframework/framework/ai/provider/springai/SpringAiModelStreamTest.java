package com.basicframework.framework.ai.provider.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.ai.config.AiModelProperties;
import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelEvent;
import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelRequest;
import com.basicframework.framework.ai.core.model.ModelStream;
import com.basicframework.framework.ai.core.model.ModelUsage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * M03 文本流适配：增量、工具调用聚合、用量缺失、空闲超时、输出上限与关闭释放连接。
 *
 * <p>对应用户可见行为：AT-003（超时有界、稳定错误、无秘密）、AT-060（用量缺失标 UNKNOWN）、
 * 畸形输出与流关闭两项专项验收。
 */
class SpringAiModelStreamTest {

    private static AiModelProperties properties() {
        return new AiModelProperties();
    }

    private static SpringAiModelClient client(ChatModel model) {
        return new SpringAiModelClient(
                VendorChatResponses.snapshot(ModelCapability.TEXT, ModelCapability.TEXT_STREAM), model);
    }

    @Test
    void mapsDeltasToolCallsAndUsageIntoOwnEvents() {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(
                        VendorChatResponses.text("你"),
                        VendorChatResponses.text("好"),
                        // 厂商以分片增量传输工具调用参数：适配层必须聚合后再给出
                        VendorChatResponses.toolCalls("", VendorChatResponses.toolCall("call_1", "query", "{\"sql\"")),
                        VendorChatResponses.toolCalls(
                                "", VendorChatResponses.toolCall("call_1", "query", ":\"select 1\"}")),
                        VendorChatResponses.textWithUsage("", 7, 3));
            }
        };

        List<ModelEvent> events = drain(client(model).stream(ModelRequest.of("gpt-4o-mini", "hi")));

        assertThat(events).hasSize(4);
        assertThat(events.get(0).text()).isEqualTo("你");
        assertThat(events.get(1).text()).isEqualTo("好");
        assertThat(events.get(2).type()).isEqualTo(ModelEvent.Type.TOOL_CALL);
        assertThat(events.get(2).toolCall().name()).isEqualTo("query");
        assertThat(events.get(2).toolCall().argumentsJson()).isEqualTo("{\"sql\":\"select 1\"}");
        assertThat(events.get(3).type()).isEqualTo(ModelEvent.Type.COMPLETED);
        assertThat(events.get(3).isTerminal()).isTrue();
        assertThat(events.get(0).isTerminal()).isFalse();
        assertThat(events.get(3).usage().promptTokens()).isEqualTo(7);
        assertThat(events.get(3).usage().totalTokens()).isEqualTo(10);
        assertThat(events.get(3).usage().completionTokens()).isEqualTo(3);
        assertThat(events.get(3).usage().estimated()).isFalse();
    }

    @Test
    void marksUsageUnknownInsteadOfFakeZero() {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(VendorChatResponses.text("ok"));
            }
        };

        List<ModelEvent> events = drain(client(model).stream(ModelRequest.of("gpt-4o-mini", "hi")));

        ModelEvent completed = events.get(events.size() - 1);
        assertThat(completed.usage().isKnown()).isFalse();
        assertThat(completed.usage().promptTokens()).isNull();
        assertThat(completed.usage().completionTokens()).isNull();
        assertThat(completed.usage().totalTokens()).isNull();
        assertThat(ModelUsage.estimated(5, null).estimated()).isTrue();
        assertThat(ModelUsage.estimated(5, null).isKnown()).isTrue();
        assertThat(ModelUsage.estimated(null, null).isKnown()).isFalse();
    }

    @Test
    void neverRegistersToolCallbacksSoToolsAreNotExecuted() {
        List<Prompt> seen = new ArrayList<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                seen.add(prompt);
                return Flux.just(VendorChatResponses.text("data only"));
            }
        };

        drain(client(model).stream(ModelRequest.of("gpt-4o-mini", "hi")));

        Prompt prompt = seen.get(0);
        assertThat(prompt.getInstructions()).singleElement().isInstanceOf(UserMessage.class);
        assertThat(prompt.getOptions()).as("接缝不得注册任何工具回调或厂商选项，工具只能作为数据交给平台策略层").isNull();
    }

    @Test
    void timesOutWhenUpstreamStopsEmittingAndReleasesTheStream() {
        AtomicBoolean disposed = new AtomicBoolean();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(VendorChatResponses.text("开始"))
                        .concatWith(Flux.<ChatResponse>never())
                        .doOnCancel(() -> disposed.set(true));
            }
        };
        AiModelProperties properties = properties();
        properties.setStreamIdleTimeout(Duration.ofMillis(200));

        ModelStream stream = new SpringAiModelClient(
                        VendorChatResponses.snapshot(ModelCapability.TEXT, ModelCapability.TEXT_STREAM),
                        model,
                        properties)
                .stream(ModelRequest.of("gpt-4o-mini", "hi"));

        assertThat(stream.idleTimeout()).isEqualTo(Duration.ofMillis(200));
        assertThat(stream.hasNext()).isTrue();
        assertThat(stream.next().text()).isEqualTo("开始");
        assertThatThrownBy(stream::hasNext).isInstanceOf(ModelException.class).satisfies(exception -> assertThat(
                        ((ModelException) exception).getReason())
                .isEqualTo(ModelException.Reason.TIMEOUT));
        assertThat(disposed).as("空闲超时必须取消上游订阅（释放连接）").isTrue();
    }

    @Test
    void closeReleasesUpstreamSubscriptionAndIsIdempotent() throws InterruptedException {
        CountDownLatch subscribed = new CountDownLatch(1);
        AtomicBoolean disposed = new AtomicBoolean();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.create(sink -> {
                    subscribed.countDown();
                    sink.onCancel(() -> disposed.set(true));
                });
            }
        };

        ModelStream stream = client(model).stream(ModelRequest.of("gpt-4o-mini", "hi"));
        assertThat(subscribed.await(5, TimeUnit.SECONDS)).as("上游订阅必须已建立").isTrue();

        stream.close();
        stream.close();

        assertThat(disposed).as("close 必须取消上游订阅（释放连接）").isTrue();
        assertThat(stream.hasNext()).isFalse();
    }

    @Test
    void interruptsWhenOutputExceedsConfiguredLimit() {
        String huge = "x".repeat(2048);
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(VendorChatResponses.text("开头"), VendorChatResponses.text(huge));
            }
        };
        AiModelProperties properties = properties();
        properties.setMaxOutputChars(1024);

        ModelStream stream = new SpringAiModelClient(
                        VendorChatResponses.snapshot(ModelCapability.TEXT, ModelCapability.TEXT_STREAM),
                        model,
                        properties)
                .stream(ModelRequest.of("gpt-4o-mini", "hi"));

        assertThat(stream.hasNext()).isTrue();
        assertThat(stream.next().text()).isEqualTo("开头");
        assertThatThrownBy(() -> {
                    while (stream.hasNext()) {
                        stream.next();
                    }
                })
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.OUTPUT_LIMIT_EXCEEDED));
    }

    @Test
    void mapsUpstreamFailureWithoutLeakingVendorBody() {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.error(new IllegalStateException("provider said: key sk-must-not-leak invalid"));
            }
        };

        ModelStream stream = client(model).stream(ModelRequest.of("gpt-4o-mini", "hi"));

        assertThatThrownBy(stream::hasNext).isInstanceOf(ModelException.class).satisfies(exception -> {
            ModelException modelException = (ModelException) exception;
            assertThat(modelException.getReason()).isEqualTo(ModelException.Reason.UPSTREAM_FAILED);
            assertThat(modelException.getMessage()).doesNotContain("sk-must-not-leak");
        });
    }

    @Test
    void rejectsStreamingWhenEndpointLacksStreamCapability() {
        SpringAiModelClient textOnly =
                new SpringAiModelClient(VendorChatResponses.snapshot(ModelCapability.TEXT), prompt -> {
                    throw new UnsupportedOperationException();
                });

        assertThatThrownBy(() -> textOnly.stream(ModelRequest.of("gpt-4o-mini", "hi")))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> {
                    ModelException modelException = (ModelException) exception;
                    assertThat(modelException.getReason()).isEqualTo(ModelException.Reason.CAPABILITY_UNSUPPORTED);
                    assertThat(modelException.getMessage()).contains("TEXT_STREAM");
                });
        assertThat(textOnly.capabilities()).containsExactly(ModelCapability.TEXT);
    }

    @Test
    void rejectsNextBeforeHasNext() {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(VendorChatResponses.text("a"));
            }
        };

        ModelStream stream = client(model).stream(ModelRequest.of("gpt-4o-mini", "hi"));

        assertThatThrownBy(stream::next).isInstanceOf(IllegalStateException.class);
        stream.close();
    }

    private static List<ModelEvent> drain(ModelStream stream) {
        List<ModelEvent> events = new ArrayList<>();
        try (stream) {
            while (stream.hasNext()) {
                events.add(stream.next());
            }
        }
        return events;
    }
}
