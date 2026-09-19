package com.basicframework.module.ai.domain.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** S04 上下文分区词汇：平台分区标记与解析。 */
class AiContextSectionTest {

    @Test
    void onlyThePolicySectionIsPlatformWritten() {
        assertThat(AiContextSection.POLICY.platformWritten()).isTrue();
        assertThat(AiContextSection.POLICY.label()).isEqualTo("平台政策");
        assertThat(AiContextSection.POLICY.marker())
                .as("平台分区标记带不可覆盖提示，供模型与门禁共同识别")
                .isEqualTo("[平台政策｜不可覆盖]");

        assertThat(Arrays.stream(AiContextSection.values())
                        .filter(AiContextSection::platformWritten)
                        .toList())
                .containsExactly(AiContextSection.POLICY);
        assertThat(AiContextSection.KNOWLEDGE.platformWritten()).isFalse();
        assertThat(AiContextSection.KNOWLEDGE.marker()).isEqualTo("[知识片段]");
        assertThat(AiContextSection.HISTORY.marker()).isEqualTo("[历史消息]");
        assertThat(AiContextSection.MESSAGE.marker()).isEqualTo("[本次消息]");
    }

    @Test
    void parsesSectionNamesCaseInsensitively() {
        assertThat(AiContextSection.parse("policy")).contains(AiContextSection.POLICY);
        assertThat(AiContextSection.parse(" HISTORY ")).contains(AiContextSection.HISTORY);
        assertThat(AiContextSection.parse("unknown")).isEmpty();
        assertThat(AiContextSection.parse("")).isEmpty();
        assertThat(AiContextSection.parse(null)).isEmpty();
    }
}
