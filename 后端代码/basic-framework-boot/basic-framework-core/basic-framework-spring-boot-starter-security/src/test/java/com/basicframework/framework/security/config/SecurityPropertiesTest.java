package com.basicframework.framework.security.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecurityPropertiesTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaultsAreValid() {
        assertThat(validator.validate(new SecurityProperties())).isEmpty();
    }

    @Test
    void passwordEncoderStrengthOutsideBcryptContractIsRejected() {
        SecurityProperties tooWeak = new SecurityProperties();
        tooWeak.setPasswordEncoderLength(3);
        SecurityProperties tooStrong = new SecurityProperties();
        tooStrong.setPasswordEncoderLength(32);
        SecurityProperties missing = new SecurityProperties();
        missing.setPasswordEncoderLength(null);

        assertThat(validator.validate(tooWeak))
                .anyMatch(error -> error.getMessage().contains("不能小于 4"));
        assertThat(validator.validate(tooStrong))
                .anyMatch(error -> error.getMessage().contains("不能大于 31"));
        assertThat(validator.validate(missing))
                .anyMatch(error -> error.getMessage().contains("不能为空"));
    }

    @Test
    void credentialKeyAcceptsOnlyBlankOrExactlyThirtyTwoDecodedBytes() {
        SecurityProperties properties = new SecurityProperties();
        assertThat(properties.isCredentialEncryptionKeyValid()).isTrue();

        properties.setCredentialEncryptionKey("not-base64");
        assertThat(properties.isCredentialEncryptionKeyValid()).isFalse();

        properties.setCredentialEncryptionKey(Base64.getEncoder().encodeToString(new byte[31]));
        assertThat(properties.isCredentialEncryptionKeyValid()).isFalse();

        properties.setCredentialEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        assertThat(properties.isCredentialEncryptionKeyValid()).isTrue();
    }
}
