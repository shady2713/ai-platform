package com.basicframework.module.system.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PasswordPolicyPropertiesTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaultReservedTermsAreValid() {
        assertThat(validator.validate(new PasswordPolicyProperties())).isEmpty();
    }

    @Test
    void emptyOrBlankReservedTermsFailValidation() {
        PasswordPolicyProperties empty = new PasswordPolicyProperties();
        empty.setReservedTerms(Set.of());
        PasswordPolicyProperties blank = new PasswordPolicyProperties();
        blank.setReservedTerms(Set.of(" "));

        assertThat(validator.validate(empty)).isNotEmpty();
        assertThat(validator.validate(blank)).isNotEmpty();
    }
}
