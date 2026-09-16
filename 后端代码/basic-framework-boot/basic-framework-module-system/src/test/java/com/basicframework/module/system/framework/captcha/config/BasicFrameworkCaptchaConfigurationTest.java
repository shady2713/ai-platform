package com.basicframework.module.system.framework.captcha.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anji.captcha.properties.AjCaptchaProperties;
import org.junit.jupiter.api.Test;

class BasicFrameworkCaptchaConfigurationTest {

    @Test
    void validateCaptchaProtocol_acceptsDisabledBrowserEncryption() {
        AjCaptchaProperties properties = new AjCaptchaProperties();
        properties.setAesStatus(false);

        assertThatCode(() -> BasicFrameworkCaptchaConfiguration.validateCaptchaProtocol(properties))
                .doesNotThrowAnyException();
    }

    @Test
    void validateCaptchaProtocol_rejectsEnabledOrImplicitBrowserEncryption() {
        AjCaptchaProperties enabled = new AjCaptchaProperties();
        enabled.setAesStatus(true);

        assertThatThrownBy(() -> BasicFrameworkCaptchaConfiguration.validateCaptchaProtocol(enabled))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aj.captcha.aes-status 必须为 false");
        assertThatThrownBy(() -> BasicFrameworkCaptchaConfiguration.validateCaptchaProtocol(new AjCaptchaProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aj.captcha.aes-status 必须为 false");
    }
}
