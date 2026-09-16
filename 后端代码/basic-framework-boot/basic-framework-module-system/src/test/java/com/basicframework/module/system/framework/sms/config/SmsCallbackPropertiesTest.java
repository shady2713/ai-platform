package com.basicframework.module.system.framework.sms.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class SmsCallbackPropertiesTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void disabledCallbackDoesNotRequireToken() {
        SmsCallbackProperties properties = new SmsCallbackProperties();

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void enabledCallbackRequiresHighEntropyToken() {
        SmsCallbackProperties properties = new SmsCallbackProperties();
        properties.setEnabled(true);
        properties.setToken("too-short");

        assertThat(validator.validate(properties)).hasSize(1);

        properties.setToken("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        assertThat(validator.validate(properties)).isEmpty();
    }
}
