package com.basicframework.module.system.service.user;

import static com.basicframework.module.system.enums.ErrorCodeConstants.USER_PASSWORD_POLICY_VIOLATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.system.config.PasswordPolicyProperties;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    private final PasswordPolicy passwordPolicy = new PasswordPolicy(new PasswordPolicyProperties());

    @Test
    void validate_acceptsLongPassphraseWithoutChangingIt() {
        assertThatCode(() -> passwordPolicy.validate("violet river orbits quietly!", "alice"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_rejectsStructuralBoundaryViolations() {
        assertPolicyViolation("short-password", "alice");
        assertPolicyViolation("a".repeat(73), "alice");
        assertPolicyViolation("密".repeat(25), "alice");
    }

    @Test
    void validate_rejectsWeakContextualPasswords() {
        assertPolicyViolation("passwordpassword", "alice");
        assertPolicyViolation("alice-secure-passphrase", "alice");
        assertPolicyViolation("basic-framework-secure", "alice");
        assertPolicyViolation("abababababababab", "alice");
    }

    private void assertPolicyViolation(String password, String username) {
        assertThatThrownBy(() -> passwordPolicy.validate(password, username))
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(USER_PASSWORD_POLICY_VIOLATION.getCode()));
    }
}
