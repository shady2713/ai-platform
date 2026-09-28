package com.basicframework.module.ai.service.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Webhook 事件白名单（X10）：只支持运行终态三种事件，解析与序列化稳定、脏数据按"未订阅"处理。
 *
 * <p>与入队扫描 SQL 的一致性由集成测试钉住（SQL 用 {@code 'RUN.' + status} 推导），
 * 这里固定映射本身与非法输入的收窄方向。
 */
class AiWebhookEventTypesTest {

    @Test
    void supportedSetIsExactlyTheThreeRunTerminalEvents() {
        assertThat(AiWebhookEventTypes.supported())
                .containsExactly(
                        AiWebhookEventTypes.RUN_SUCCEEDED,
                        AiWebhookEventTypes.RUN_FAILED,
                        AiWebhookEventTypes.RUN_CANCELLED);
        assertThat(AiWebhookEventTypes.isSupported("RUN.RUNNING")).isFalse();
        assertThat(AiWebhookEventTypes.isSupported(null)).isFalse();
    }

    @Test
    void runStatusMapsToEventTypeAndNonTerminalMapsToNothing() {
        assertThat(AiWebhookEventTypes.ofRunStatus("SUCCEEDED")).isEqualTo("RUN.SUCCEEDED");
        assertThat(AiWebhookEventTypes.ofRunStatus("FAILED")).isEqualTo("RUN.FAILED");
        assertThat(AiWebhookEventTypes.ofRunStatus("CANCELLED")).isEqualTo("RUN.CANCELLED");
        assertThat(AiWebhookEventTypes.ofRunStatus("RUNNING")).isNull();
        assertThat(AiWebhookEventTypes.ofRunStatus("ACCEPTED")).isNull();
        assertThat(AiWebhookEventTypes.ofRunStatus(null)).isNull();
    }

    @Test
    void serializeAndParseRoundTripInWhitelistOrderWithoutDuplicates() {
        String json = AiWebhookEventTypes.serialize(Arrays.asList("RUN.CANCELLED", "RUN.SUCCEEDED", "RUN.SUCCEEDED"));

        assertThat(json).isEqualTo("[\"RUN.SUCCEEDED\",\"RUN.CANCELLED\"]");
        assertThat(AiWebhookEventTypes.parse(json))
                .containsExactly(AiWebhookEventTypes.RUN_SUCCEEDED, AiWebhookEventTypes.RUN_CANCELLED);
    }

    @Test
    void unparsableWhitelistMeansNothingIsSubscribed() {
        assertThat(AiWebhookEventTypes.parse(null)).isEmpty();
        assertThat(AiWebhookEventTypes.parse("")).isEmpty();
        assertThat(AiWebhookEventTypes.parse("not-json")).isEmpty();
        assertThat(AiWebhookEventTypes.parse("{\"eventTypes\":\"RUN.SUCCEEDED\"}"))
                .isEmpty();
    }

    @Test
    void normalizeKeepsOnlyWhitelistedValues() {
        assertThat(AiWebhookEventTypes.normalize(null)).isEmpty();
        assertThat(AiWebhookEventTypes.normalize(List.of("RUN.SUCCEEDED", "RUN.RUNNING")))
                .containsExactly(AiWebhookEventTypes.RUN_SUCCEEDED);
    }
}
