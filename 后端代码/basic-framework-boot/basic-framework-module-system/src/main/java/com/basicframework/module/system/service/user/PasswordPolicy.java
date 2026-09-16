package com.basicframework.module.system.service.user;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.USER_PASSWORD_POLICY_VIOLATION;

import com.basicframework.framework.common.util.validation.ValidationUtils;
import com.basicframework.module.system.config.PasswordPolicyProperties;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 用户自选密码的服务端安全策略；只用于校验，不修改密码原文。 */
@Component
public class PasswordPolicy {

    private static final int MIN_DISTINCT_CODE_POINTS = 4;
    private static final Set<String> COMMON_WEAK_PASSWORDS = Set.of(
            "123456789012345", "passwordpassword", "qwertyuiopasdfg", "letmeinletmeinletmein", "iloveyouiloveyou");

    private final PasswordPolicyProperties properties;

    public PasswordPolicy(PasswordPolicyProperties properties) {
        this.properties = properties;
    }

    public void validate(String password, String username) {
        if (!ValidationUtils.isPassword(password)
                || hasLowDiversity(password)
                || containsUsername(password, username)
                || containsReservedTerm(password)
                || COMMON_WEAK_PASSWORDS.contains(compactForComparison(password))) {
            throw exception(USER_PASSWORD_POLICY_VIOLATION);
        }
    }

    private static boolean hasLowDiversity(String password) {
        return password.codePoints().distinct().limit(MIN_DISTINCT_CODE_POINTS).count() < MIN_DISTINCT_CODE_POINTS;
    }

    private static boolean containsUsername(String password, String username) {
        String normalizedUsername = ValidationUtils.normalizeUsername(username);
        return normalizedUsername != null
                && !normalizedUsername.isEmpty()
                && normalizeForComparison(password).contains(normalizedUsername);
    }

    private boolean containsReservedTerm(String password) {
        String comparable = compactForComparison(password);
        return properties.getReservedTerms().stream()
                .map(PasswordPolicy::compactForComparison)
                .anyMatch(comparable::contains);
    }

    private static String compactForComparison(String password) {
        return normalizeForComparison(password).replaceAll("[\\s_-]", "");
    }

    private static String normalizeForComparison(String password) {
        return Normalizer.normalize(password, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
