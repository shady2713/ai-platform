package com.basicframework.module.system.framework.sms.config;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.SMS_CALLBACK_PAYLOAD_INVALID;
import static com.basicframework.module.system.enums.ErrorCodeConstants.SMS_CALLBACK_UNAUTHORIZED;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class SmsCallbackAuthenticator {

    public static final String TOKEN_HEADER = "X-Sms-Callback-Token";

    private final SmsCallbackProperties properties;

    public void authenticate(String providedToken) {
        String expectedToken = properties.getToken();
        if (providedToken == null
                || providedToken.length() != SmsCallbackProperties.TOKEN_HEX_LENGTH
                || expectedToken == null
                || !MessageDigest.isEqual(
                        providedToken.getBytes(StandardCharsets.UTF_8),
                        expectedToken.getBytes(StandardCharsets.UTF_8))) {
            throw exception(SMS_CALLBACK_UNAUTHORIZED);
        }
    }

    public void validatePayload(String payload) {
        if (payload == null || payload.isBlank() || payload.length() > properties.getMaxPayloadLength()) {
            throw exception(SMS_CALLBACK_PAYLOAD_INVALID);
        }
    }
}
