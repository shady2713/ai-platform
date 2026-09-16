package com.basicframework.module.system.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class LoginProtectionPropertiesTest {

    @Test
    void defaults_areValidAndFailClosed() {
        LoginProtectionProperties properties = new LoginProtectionProperties();

        assertThat(properties.getMaxFailedAttempts()).isEqualTo(5);
        assertThat(properties.getLockDuration()).isEqualTo(Duration.ofMinutes(15));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties)).isEmpty();
        }
    }

    @Test
    void invalidThresholdAndDuration_areRejected() {
        LoginProtectionProperties properties = new LoginProtectionProperties();
        properties.setMaxFailedAttempts(1);
        properties.setLockDuration(Duration.ofSeconds(30));

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties)).hasSize(2);
        }
    }
}
