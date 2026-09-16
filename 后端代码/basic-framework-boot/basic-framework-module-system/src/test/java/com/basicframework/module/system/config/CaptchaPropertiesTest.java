package com.basicframework.module.system.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CaptchaPropertiesTest {

    @Test
    void defaultsToEnabledButCanBeOverriddenByProfileBinding() {
        CaptchaProperties properties = new CaptchaProperties();

        assertThat(properties.isEnable()).isTrue();

        properties.setEnable(false);

        assertThat(properties.isEnable()).isFalse();
    }
}
