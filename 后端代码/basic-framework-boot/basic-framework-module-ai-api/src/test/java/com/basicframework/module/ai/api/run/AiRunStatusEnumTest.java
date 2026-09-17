package com.basicframework.module.ai.api.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AiRunStatusEnumTest {

    @Test
    void shouldMirrorFrozenProtocolVocabulary() {
        assertThat(AiRunStatusEnum.values())
                .extracting(Enum::name)
                .containsExactly(
                        "QUEUED",
                        "RUNNING",
                        "WAITING_INPUT",
                        "WAITING_CONFIRMATION",
                        "SUCCEEDED",
                        "FAILED",
                        "CANCELLED");
    }

    @Test
    void shouldMarkOnlySucceededFailedCancelledAsTerminal() {
        Set<AiRunStatusEnum> terminal = EnumSet.noneOf(AiRunStatusEnum.class);
        for (AiRunStatusEnum status : AiRunStatusEnum.values()) {
            if (status.isTerminal()) {
                terminal.add(status);
            }
        }

        assertThat(terminal)
                .containsExactlyInAnyOrder(
                        AiRunStatusEnum.SUCCEEDED, AiRunStatusEnum.FAILED, AiRunStatusEnum.CANCELLED);
    }
}
