package com.basicframework.module.system.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SessionPropertiesTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaultsAreValid() {
        assertThat(validator.validate(new SessionProperties())).isEmpty();
    }

    @Test
    void rejectsNonPositiveAndInvertedValidityPeriods() {
        SessionProperties zeroAccess = new SessionProperties();
        zeroAccess.setAccessTokenTtl(Duration.ZERO);
        assertThat(validator.validate(zeroAccess)).isNotEmpty();

        SessionProperties shorterRefresh = new SessionProperties();
        shorterRefresh.setAccessTokenTtl(Duration.ofHours(2));
        shorterRefresh.setRefreshTokenTtl(Duration.ofHours(1));
        assertThat(validator.validate(shorterRefresh)).isNotEmpty();
    }
}
