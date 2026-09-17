package com.basicframework.module.ai.api.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AiRunCommonApiTest {

    @Test
    void shouldDelegateRunStatusQuery() {
        AtomicReference<String> captured = new AtomicReference<>();
        AiRunCommonApi api = runKey -> {
            captured.set(runKey);
            return AiRunStatusEnum.RUNNING;
        };

        assertThat(api.getRunStatus("run_abcdef")).isEqualTo(AiRunStatusEnum.RUNNING);
        assertThat(captured).hasValue("run_abcdef");
    }
}
