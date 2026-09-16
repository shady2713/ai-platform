package com.basicframework.module.system.framework.operatelog.core;

/** 操作日志中直接联系方式的最小化展示规则。 */
final class OperationLogSensitiveDataMasker {

    static final String REDACTED = "[REDACTED]";

    private OperationLogSensitiveDataMasker() {}

    static String maskEmail(Object value) {
        String email = normalizedText(value);
        if (email == null) {
            return REDACTED;
        }
        if (email.isEmpty()) {
            return "";
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 0 || atIndex == email.length() - 1 || email.indexOf('@', atIndex + 1) >= 0) {
            return REDACTED;
        }
        return email.charAt(0) + "****" + email.substring(atIndex);
    }

    static String maskMobile(Object value) {
        String mobile = normalizedText(value);
        if (mobile == null) {
            return REDACTED;
        }
        if (mobile.isEmpty()) {
            return "";
        }
        if (mobile.length() != 11 || !mobile.chars().allMatch(Character::isDigit)) {
            return REDACTED;
        }
        return mobile.substring(0, 3) + "****" + mobile.substring(7);
    }

    private static String normalizedText(Object value) {
        if (value == null) {
            return "";
        }
        if (!(value instanceof CharSequence text)) {
            return null;
        }
        return text.toString().trim();
    }
}
