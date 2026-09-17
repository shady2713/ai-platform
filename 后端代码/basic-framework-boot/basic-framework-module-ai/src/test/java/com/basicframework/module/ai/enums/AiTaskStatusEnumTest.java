package com.basicframework.module.ai.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AiTaskStatusEnumTest {

    @Test
    void shouldAllowClaimOnlyForPendingAndRetryWait() {
        assertThat(AiTaskStatusEnum.PENDING.isClaimable()).isTrue();
        assertThat(AiTaskStatusEnum.RETRY_WAIT.isClaimable()).isTrue();
        assertThat(AiTaskStatusEnum.RUNNING.isClaimable()).isFalse();
        assertThat(AiTaskStatusEnum.UNKNOWN.isClaimable()).isFalse();
        assertThat(AiTaskStatusEnum.SUCCEEDED.isClaimable()).isFalse();
    }

    @Test
    void shouldMarkOnlySucceededFailedCancelledAsTerminal() {
        assertThat(AiTaskStatusEnum.SUCCEEDED.isTerminal()).isTrue();
        assertThat(AiTaskStatusEnum.FAILED.isTerminal()).isTrue();
        assertThat(AiTaskStatusEnum.CANCELLED.isTerminal()).isTrue();
        assertThat(AiTaskStatusEnum.UNKNOWN.isTerminal()).isFalse();
        assertThat(AiTaskStatusEnum.PENDING.isTerminal()).isFalse();
        assertThat(AiTaskStatusEnum.RUNNING.isTerminal()).isFalse();
    }
}
