package com.basicframework.module.infra.framework.file.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class FileDeletionPropertiesTest {

    @Test
    void defaults_areValidAndConfigurationIsConstructible() {
        FileDeletionProperties properties = new FileDeletionProperties();

        assertThat(properties.getBatchSize()).isEqualTo(100);
        assertThat(properties.getInitialRetryDelay()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.getMaxRetryDelay()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.isRetryDelayValid()).isTrue();
        assertThat(new FileDeletionConfiguration()).isNotNull();
    }

    @Test
    void retryDelayValidation_rejectsNonPositiveOrInvertedRanges() {
        FileDeletionProperties properties = new FileDeletionProperties();
        properties.setInitialRetryDelay(Duration.ZERO);
        assertThat(properties.isRetryDelayValid()).isFalse();

        properties.setInitialRetryDelay(Duration.ofMinutes(2));
        properties.setMaxRetryDelay(Duration.ofMinutes(1));
        assertThat(properties.isRetryDelayValid()).isFalse();

        properties.setMaxRetryDelay(Duration.ofMinutes(2));
        assertThat(properties.isRetryDelayValid()).isTrue();
    }
}
