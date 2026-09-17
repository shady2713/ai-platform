package com.basicframework.framework.ai.provider.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.ai.config.AiModelProperties;
import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.StructuredModelRequest;
import com.basicframework.framework.ai.core.model.StructuredModelResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * M03 结构化输出适配：合法 JSON 通过、能力缺失明确报错、畸形 JSON 有界修复后失败，
 * 且整个链路不把 Schema 以外的厂商原文交给业务。
 */
class SpringAiStructuredOutputTest {

    private static final String SCHEMA = "{\"type\":\"object\",\"required\":[\"answer\"]}";

    private static SpringAiModelClient client(ChatModel model, AiModelProperties properties) {
        return new SpringAiModelClient(
                VendorChatResponses.snapshot(ModelCapability.TEXT, ModelCapability.STRUCTURED_OUTPUT),
                model,
                properties);
    }

    private static ChatModel returning(String content) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return VendorChatResponses.textWithUsage(content, 11, 4);
            }
        };
    }

    @Test
    void returnsValidatedJsonObjectWithUsage() {
        StructuredModelResult result = client(returning("{\"answer\":\"ok\"}"), new AiModelProperties())
                .generateStructured(StructuredModelRequest.of("gpt-4o-mini", "问题", SCHEMA));

        assertThat(result.json()).isEqualTo("{\"answer\":\"ok\"}");
        assertThat(result.value().get("answer").asText()).isEqualTo("ok");
        assertThat(result.usage().promptTokens()).isEqualTo(11);
        assertThat(result.usage().completionTokens()).isEqualTo(4);
        assertThat(result.usage().estimated()).isFalse();
        assertThat(result.modelId()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void repairsFencedJsonWithinBudget() {
        StructuredModelResult result = client(returning("```json\n{\"answer\":\"ok\"}\n```"), new AiModelProperties())
                .generateStructured(StructuredModelRequest.of("gpt-4o-mini", "问题", SCHEMA));

        assertThat(result.value().get("answer").asText()).isEqualTo("ok");
    }

    @Test
    void failsAfterBoundedRepairWithoutRetryingTheModel() {
        List<Integer> calls = new ArrayList<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                calls.add(1);
                return VendorChatResponses.text("答案在这里：{\"answer\": 不是 JSON}");
            }
        };
        AiModelProperties properties = new AiModelProperties();
        properties.setMaxAttempts(3);

        assertThatThrownBy(() -> client(model, properties)
                        .generateStructured(StructuredModelRequest.of("gpt-4o-mini", "问题", SCHEMA)))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.INVALID_STRUCTURED_OUTPUT));
        assertThat(calls).as("畸形 JSON 只做有界修复，不重复请求上游").hasSize(1);
    }

    @Test
    void rejectsNonObjectJsonOutput() {
        assertThatThrownBy(() -> client(returning("[1,2,3]"), new AiModelProperties())
                        .generateStructured(StructuredModelRequest.of("gpt-4o-mini", "问题", SCHEMA)))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.INVALID_STRUCTURED_OUTPUT));
    }

    @Test
    void rejectsInvalidSchemaBeforeCallingUpstream() {
        List<Integer> calls = new ArrayList<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                calls.add(1);
                return VendorChatResponses.text("{}");
            }
        };

        assertThatThrownBy(() -> client(model, new AiModelProperties())
                        .generateStructured(StructuredModelRequest.of("gpt-4o-mini", "问题", "not-json")))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> assertThat(((ModelException) exception).getReason())
                        .isEqualTo(ModelException.Reason.INVALID_STRUCTURED_INPUT));
        assertThat(calls).isEmpty();
    }

    @Test
    void sendsSchemaAsOutputContractToUpstream() {
        List<Prompt> seen = new ArrayList<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                seen.add(prompt);
                return VendorChatResponses.text("{\"answer\":\"ok\"}");
            }
        };

        client(model, new AiModelProperties())
                .generateStructured(StructuredModelRequest.of("gpt-4o-mini", "问题内容", SCHEMA));

        String sent = seen.get(0).getInstructions().get(0).getText();
        assertThat(sent).contains(SCHEMA).contains("问题内容").contains("JSON 对象");
    }

    @Test
    void rejectsStructuredOutputWhenEndpointLacksCapability() {
        SpringAiModelClient textOnly = new SpringAiModelClient(
                VendorChatResponses.snapshot(ModelCapability.TEXT), prompt -> VendorChatResponses.text("{}"));

        assertThatThrownBy(() -> textOnly.generateStructured(StructuredModelRequest.of("gpt-4o-mini", "问题", SCHEMA)))
                .isInstanceOf(ModelException.class)
                .satisfies(exception -> {
                    ModelException modelException = (ModelException) exception;
                    assertThat(modelException.getReason()).isEqualTo(ModelException.Reason.CAPABILITY_UNSUPPORTED);
                    assertThat(modelException.getMessage()).contains("STRUCTURED_OUTPUT");
                });
    }
}
