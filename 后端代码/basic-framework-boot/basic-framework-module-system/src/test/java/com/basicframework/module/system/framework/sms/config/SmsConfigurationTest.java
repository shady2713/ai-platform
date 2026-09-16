package com.basicframework.module.system.framework.sms.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.system.framework.sms.core.client.SmsClientFactory;
import com.basicframework.module.system.framework.sms.core.client.impl.SmsClientFactoryImpl;
import org.junit.jupiter.api.Test;

/**
 * {@link SmsConfiguration} 单元测试
 *
 */
class SmsConfigurationTest {

    private final SmsConfiguration configuration = new SmsConfiguration();

    @Test
    void smsClientFactory_createsConcreteClientFactory() {
        SmsClientFactory factory = configuration.smsClientFactory();

        assertThat(factory).isInstanceOf(SmsClientFactoryImpl.class);
    }

    @Test
    void smsCallbackAuthenticator_buildsFromCallbackProperties() {
        SmsCallbackProperties properties = new SmsCallbackProperties();

        assertThat(configuration.smsCallbackAuthenticator(properties)).isInstanceOf(SmsCallbackAuthenticator.class);
    }
}
