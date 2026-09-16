package com.basicframework.module.system.framework.captcha.config;

import com.anji.captcha.config.AjCaptchaAutoConfiguration;
import com.anji.captcha.properties.AjCaptchaProperties;
import com.anji.captcha.service.CaptchaCacheService;
import com.anji.captcha.service.impl.CaptchaServiceFactory;
import com.basicframework.module.system.framework.captcha.core.RedisCaptchaServiceImpl;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 验证码的配置类
 *
 */
@Configuration(proxyBeanMethods = false)
@ImportAutoConfiguration(AjCaptchaAutoConfiguration.class) // 目的：解决 aj-captcha 针对 SpringBoot 3.X 自动配置不生效的问题
public class BasicFrameworkCaptchaConfiguration {

    @Bean(name = "AjCaptchaCacheService")
    @Primary
    public CaptchaCacheService captchaCacheService(
            AjCaptchaProperties config, StringRedisTemplate stringRedisTemplate) {
        validateCaptchaProtocol(config);
        CaptchaCacheService captchaCacheService =
                CaptchaServiceFactory.getCache(config.getCacheType().name());
        if (captchaCacheService instanceof RedisCaptchaServiceImpl) {
            ((RedisCaptchaServiceImpl) captchaCacheService).setStringRedisTemplate(stringRedisTemplate);
        }
        return captchaCacheService;
    }

    static void validateCaptchaProtocol(AjCaptchaProperties config) {
        if (!Boolean.FALSE.equals(config.getAesStatus())) {
            throw new IllegalStateException("aj.captcha.aes-status 必须为 false：浏览器端密钥不提供保密性，且框架不支持该伪加密协议");
        }
    }
}
