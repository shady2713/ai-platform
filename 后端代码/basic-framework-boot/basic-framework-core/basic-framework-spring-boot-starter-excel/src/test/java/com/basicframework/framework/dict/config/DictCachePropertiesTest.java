package com.basicframework.framework.dict.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DictCachePropertiesTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void cacheRefreshAfterWrite_defaultsToOneMinute() {
        DictCacheProperties properties = new DictCacheProperties();

        assertThat(properties.getCacheRefreshAfterWrite()).isEqualTo(Duration.ofMinutes(1));
        assertThat(VALIDATOR.validate(properties)).isEmpty();
    }

    @Test
    void cacheRefreshAfterWrite_rejectsNullZeroAndNegativeValues() {
        DictCacheProperties properties = new DictCacheProperties();

        properties.setCacheRefreshAfterWrite(null);
        assertThat(VALIDATOR.validate(properties)).isNotEmpty();
        properties.setCacheRefreshAfterWrite(Duration.ZERO);
        assertThat(VALIDATOR.validate(properties)).isNotEmpty();
        properties.setCacheRefreshAfterWrite(Duration.ofSeconds(-1));
        assertThat(VALIDATOR.validate(properties)).isNotEmpty();
    }
}
