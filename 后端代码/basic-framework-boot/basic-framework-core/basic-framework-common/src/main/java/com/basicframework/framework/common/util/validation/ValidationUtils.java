package com.basicframework.framework.common.util.validation;

import cn.hutool.core.collection.CollUtil;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

public class ValidationUtils {

    public static final int PASSWORD_MIN_CODE_POINTS = 15;
    public static final int PASSWORD_MAX_UTF8_BYTES = 72;
    public static final int NICKNAME_MAX_CODE_POINTS = 30;

    private static final Pattern PATTERN_MOBILE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern PATTERN_USERNAME = Pattern.compile("^[a-z][a-z0-9_]{3,29}$");
    private static final Pattern PATTERN_EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    public static boolean isMobile(String mobile) {
        String normalized = normalizeMobile(mobile);
        return StringUtils.hasText(normalized)
                && PATTERN_MOBILE.matcher(normalized).matches();
    }

    public static boolean isUsername(String username) {
        String normalized = normalizeUsername(username);
        return StringUtils.hasText(normalized)
                && PATTERN_USERNAME.matcher(normalized).matches();
    }

    public static String normalizeUsername(String username) {
        if (username == null) {
            return null;
        }
        return Normalizer.normalize(username.trim(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    public static boolean isPassword(String password) {
        return password != null
                && !password.isEmpty()
                && codePointLength(password) >= PASSWORD_MIN_CODE_POINTS
                && password.getBytes(StandardCharsets.UTF_8).length <= PASSWORD_MAX_UTF8_BYTES
                && password.codePoints().noneMatch(codePoint -> Character.getType(codePoint) == Character.SURROGATE);
    }

    public static boolean isEmail(String email) {
        String normalized = normalizeEmail(email);
        return StringUtils.hasText(normalized)
                && PATTERN_EMAIL.matcher(normalized).matches();
    }

    public static String normalizeNickname(String nickname) {
        if (nickname == null) {
            return null;
        }
        return Normalizer.normalize(nickname.strip(), Normalizer.Form.NFC);
    }

    public static boolean isNickname(String nickname) {
        String normalized = normalizeNickname(nickname);
        return nickname != null
                && nickname.codePoints().noneMatch(ValidationUtils::isUnsafeNicknameCodePoint)
                && StringUtils.hasText(normalized)
                && codePointLength(normalized) <= NICKNAME_MAX_CODE_POINTS
                && normalized.codePoints().noneMatch(ValidationUtils::isUnsafeNicknameCodePoint);
    }

    public static String normalizeMobile(String mobile) {
        if (mobile == null) {
            return null;
        }
        String normalized = mobile.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public static String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        String normalized = email.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        int atIndex = normalized.lastIndexOf('@');
        if (atIndex < 0) {
            return normalized;
        }
        return normalized.substring(0, atIndex + 1)
                + normalized.substring(atIndex + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isCodePointLengthBetween(String value, int min, int max) {
        if (value == null) {
            return false;
        }
        int length = codePointLength(value);
        return length >= min && length <= max;
    }

    public static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    public static void validate(Validator validator, Object object, Class<?>... groups) {
        Set<ConstraintViolation<Object>> constraintViolations = validator.validate(object, groups);
        if (CollUtil.isNotEmpty(constraintViolations)) {
            throw new ConstraintViolationException(constraintViolations);
        }
    }

    private static boolean isUnsafeNicknameCodePoint(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint)
                || type == Character.FORMAT
                || type == Character.SURROGATE
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }
}
