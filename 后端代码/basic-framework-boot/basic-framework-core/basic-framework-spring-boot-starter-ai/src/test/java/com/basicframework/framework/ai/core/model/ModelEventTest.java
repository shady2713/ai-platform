package com.basicframework.framework.ai.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * M03 契约不变量：事件字段自洽、空值收敛（用量缺失记 UNKNOWN、工具调用缺失记空列表）。
 */
class ModelEventTest {

    @Test
    void rejectsEventsThatDoNotMatchTheirType() {
        assertThatThrownBy(() -> new ModelEvent(null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelEvent.delta(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelEvent.toolCall(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultsCompletedUsageToUnknown() {
        ModelEvent event = ModelEvent.completed(null, "stop");

        assertThat(event.usage()).isSameAs(ModelUsage.UNKNOWN);
        assertThat(event.isTerminal()).isTrue();
        assertThat(event.finishReason()).isEqualTo("stop");
    }

    @Test
    void toolCallEventCarriesStructuredDataOnly() {
        ModelToolCall call = new ModelToolCall("call_1", "query", null);
        ModelEvent event = ModelEvent.toolCall(call);

        assertThat(event.type()).isEqualTo(ModelEvent.Type.TOOL_CALL);
        assertThat(event.toolCall()).isSameAs(call);
        assertThat(event.toolCall().argumentsJson()).as("参数缺失用空对象表达").isEqualTo("{}");
        assertThat(event.isTerminal()).isFalse();
    }

    @Test
    void responseConvergesNullUsageAndToolCalls() {
        ModelResponse response = new ModelResponse("text", null, null, "gpt-4o-mini", null);

        assertThat(response.usage()).isSameAs(ModelUsage.UNKNOWN);
        assertThat(response.toolCalls()).isEmpty();
        assertThat(response.hasToolCalls()).isFalse();
    }

    @Test
    void usageWithoutCountsIsUnknownAndTotalIsNull() {
        assertThat(ModelUsage.UNKNOWN.isKnown()).isFalse();
        assertThat(ModelUsage.UNKNOWN.totalTokens()).isNull();
        assertThat(ModelUsage.of(3, 4).totalTokens()).isEqualTo(7);
        assertThat(ModelUsage.of(3, 4).estimated()).isFalse();
        assertThat(ModelUsage.estimated(3, 4).estimated()).isTrue();
        assertThat(List.of(ModelUsage.UNKNOWN, ModelUsage.of(1, 1))).doesNotContainNull();
    }
}
