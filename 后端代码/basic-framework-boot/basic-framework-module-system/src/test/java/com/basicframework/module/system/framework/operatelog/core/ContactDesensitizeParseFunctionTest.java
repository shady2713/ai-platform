package com.basicframework.module.system.framework.operatelog.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContactDesensitizeParseFunctionTest {

    private final EmailDesensitizeParseFunction emailFunction = new EmailDesensitizeParseFunction();
    private final MobileDesensitizeParseFunction mobileFunction = new MobileDesensitizeParseFunction();

    @Test
    void functionsExposeStableRegistrationNames() {
        assertThat(emailFunction.functionName()).isEqualTo(EmailDesensitizeParseFunction.NAME);
        assertThat(mobileFunction.functionName()).isEqualTo(MobileDesensitizeParseFunction.NAME);
    }

    @Test
    void functionsMaskDirectContactsAfterDiffComparison() {
        assertThat(emailFunction.executeBefore()).isFalse();
        assertThat(mobileFunction.executeBefore()).isFalse();
        assertThat(emailFunction.apply("admin@example.com")).isEqualTo("a****@example.com");
        assertThat(mobileFunction.apply("13800000000")).isEqualTo("138****0000");
    }

    @Test
    void functionsFailClosedForInvalidOrUnexpectedValues() {
        assertThat(emailFunction.apply("invalid-email")).isEqualTo(OperationLogSensitiveDataMasker.REDACTED);
        assertThat(emailFunction.apply("a@")).isEqualTo(OperationLogSensitiveDataMasker.REDACTED);
        assertThat(emailFunction.apply("a@b@c")).isEqualTo(OperationLogSensitiveDataMasker.REDACTED);
        assertThat(emailFunction.apply(1)).isEqualTo(OperationLogSensitiveDataMasker.REDACTED);
        assertThat(mobileFunction.apply("12345")).isEqualTo(OperationLogSensitiveDataMasker.REDACTED);
        assertThat(mobileFunction.apply("1380000a000")).isEqualTo(OperationLogSensitiveDataMasker.REDACTED);
        assertThat(mobileFunction.apply(1)).isEqualTo(OperationLogSensitiveDataMasker.REDACTED);
        assertThat(emailFunction.apply(null)).isEmpty();
        assertThat(mobileFunction.apply(null)).isEmpty();
    }
}
