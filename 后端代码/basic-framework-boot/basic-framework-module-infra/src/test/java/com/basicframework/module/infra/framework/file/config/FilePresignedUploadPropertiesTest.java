package com.basicframework.module.infra.framework.file.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class FilePresignedUploadPropertiesTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaultsStayWithinBoundedUploadWindow() {
        FilePresignedUploadProperties properties = new FilePresignedUploadProperties();

        assertThat(validator.validate(properties)).isEmpty();
        assertThat(properties.getTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.getMaxSize()).isEqualTo(DataSize.ofMegabytes(16));
    }

    @Test
    void rejectsUnboundedSizeAndLifetime() {
        FilePresignedUploadProperties properties = new FilePresignedUploadProperties();
        properties.setTtl(Duration.ofDays(2));
        properties.setMaxSize(DataSize.ofBytes(0));

        assertThat(validator.validate(properties)).hasSize(2);
    }
}
