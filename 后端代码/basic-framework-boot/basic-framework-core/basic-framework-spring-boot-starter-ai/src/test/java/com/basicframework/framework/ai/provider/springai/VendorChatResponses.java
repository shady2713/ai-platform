package com.basicframework.framework.ai.provider.springai;

import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelEndpointSnapshot;
import java.util.List;
import java.util.Set;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/** 测试用的厂商响应构造器：把 M03 需要的组合固定在一处，避免每个测试各写一套。 */
final class VendorChatResponses {

    private VendorChatResponses() {}

    static ModelEndpointSnapshot snapshot(ModelCapability... capabilities) {
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

    static ChatResponse text(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    static ChatResponse textWithFinishReason(String content, String finishReason) {
        return new ChatResponse(List.of(new Generation(
                new AssistantMessage(content),
                ChatGenerationMetadata.builder().finishReason(finishReason).build())));
    }

    static ChatResponse textWithUsage(String content, Integer promptTokens, Integer completionTokens) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(content))),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(promptTokens, completionTokens))
                        .build());
    }

    static ChatResponse toolCalls(String content, AssistantMessage.ToolCall... calls) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content(content)
                .toolCalls(List.of(calls))
                .build())));
    }

    static AssistantMessage.ToolCall toolCall(String id, String name, String arguments) {
        return new AssistantMessage.ToolCall(id, "function", name, arguments);
    }
}
