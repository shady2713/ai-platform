package com.basicframework.module.system.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class PasswordTimingProtectionServiceTest {

    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    void initializationAndVerification_useConfiguredEncoderAndStableDummyHash() {
        when(passwordEncoder.encode(
                        argThat(value -> value != null && !value.toString().isBlank())))
                .thenReturn("deployment-strength-dummy-hash");
        PasswordTimingProtectionService service = new PasswordTimingProtectionService(passwordEncoder);

        service.verifyAgainstDummyHash("submitted-password");
        service.verifyAgainstDummyHash("another-password");

        verify(passwordEncoder).encode(argThat(value -> {
            assertThat(value).isNotNull();
            return !value.toString().isBlank();
        }));
        verify(passwordEncoder).matches("submitted-password", "deployment-strength-dummy-hash");
        verify(passwordEncoder).matches("another-password", "deployment-strength-dummy-hash");
    }
}
