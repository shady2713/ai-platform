package com.basicframework.module.system.framework.sms.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

class SmsCallbackAuthenticatorTest {

    private static final String TOKEN = "test".repeat(16);

    @Test
    void authenticateAcceptsOnlyExactToken() {
        SmsCallbackProperties properties = new SmsCallbackProperties();
        properties.setEnabled(true);
        properties.setToken(TOKEN);
        SmsCallbackAuthenticator authenticator = new SmsCallbackAuthenticator(properties);

        assertThatCode(() -> authenticator.authenticate(TOKEN)).doesNotThrowAnyException();
        assertThatThrownBy(() -> authenticator.authenticate(TOKEN.substring(0, TOKEN.length() - 1) + "x"))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> authenticator.authenticate(null)).isInstanceOf(ServiceException.class);
    }

    @Test
    void authenticateRejectsMissingExpectedTokenAndPayloadBounds() {
        SmsCallbackProperties properties = new SmsCallbackProperties();
        properties.setMaxPayloadLength(4);
        SmsCallbackAuthenticator authenticator = new SmsCallbackAuthenticator(properties);

        assertThatThrownBy(() -> authenticator.authenticate(TOKEN)).isInstanceOf(ServiceException.class);
        assertThatCode(() -> authenticator.validatePayload("ok")).doesNotThrowAnyException();
        assertThatThrownBy(() -> authenticator.validatePayload(null)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> authenticator.validatePayload(" ")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> authenticator.validatePayload("12345")).isInstanceOf(ServiceException.class);
    }
}
