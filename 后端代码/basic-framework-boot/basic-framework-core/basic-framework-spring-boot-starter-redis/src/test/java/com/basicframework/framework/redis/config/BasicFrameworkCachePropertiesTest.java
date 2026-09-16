package com.basicframework.framework.redis.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class BasicFrameworkCachePropertiesTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void redisScanBatchSize_defaultsToPositiveValue() {
        BasicFrameworkCacheProperties properties = new BasicFrameworkCacheProperties();

        assertThat(properties.getRedisScanBatchSize()).isEqualTo(30);
        assertThat(VALIDATOR.validate(properties)).isEmpty();
    }

    @Test
    void redisScanBatchSize_rejectsNonPositiveValues() {
        BasicFrameworkCacheProperties properties = new BasicFrameworkCacheProperties();

        properties.setRedisScanBatchSize(0);

        assertThat(VALIDATOR.validate(properties))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("redisScanBatchSize"));
    }
}
