package com.basicframework.module.system.framework.sms.config;

import com.basicframework.module.system.framework.sms.core.client.SmsClientFactory;
import com.basicframework.module.system.framework.sms.core.client.impl.SmsClientFactoryImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 短信配置类，包括短信客户端、短信验证码两部分
 *
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SmsCodeProperties.class, SmsCallbackProperties.class})
public class SmsConfiguration {

    @Bean
    public SmsClientFactory smsClientFactory() {
        return new SmsClientFactoryImpl();
    }

    @Bean
    @ConditionalOnProperty(prefix = "basic-framework.sms-callback", name = "enabled", havingValue = "true")
    public SmsCallbackAuthenticator smsCallbackAuthenticator(SmsCallbackProperties properties) {
        return new SmsCallbackAuthenticator(properties);
    }
}
