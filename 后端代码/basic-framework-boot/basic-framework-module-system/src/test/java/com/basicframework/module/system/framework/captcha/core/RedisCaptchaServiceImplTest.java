package com.basicframework.module.system.framework.captcha.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisCaptchaServiceImplTest {

    @Test
    void getAndDelete_delegatesToAtomicRedisValueOperation() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.getAndDelete("captcha:key")).thenReturn("captcha-value");
        RedisCaptchaServiceImpl cache = new RedisCaptchaServiceImpl();
        cache.setStringRedisTemplate(redisTemplate);

        assertThat(cache.getAndDelete("captcha:key")).isEqualTo("captcha-value");

        verify(values).getAndDelete("captcha:key");
    }

    @Test
    void storageOperations_delegateToRedisTemplate() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(redisTemplate.hasKey("captcha:key")).thenReturn(true);
        when(values.get("captcha:key")).thenReturn("captcha-value");
        when(values.increment("captcha:key", 1L)).thenReturn(2L);
        RedisCaptchaServiceImpl cache = new RedisCaptchaServiceImpl();
        cache.setStringRedisTemplate(redisTemplate);

        cache.set("captcha:key", "captcha-value", 60L);
        assertThat(cache.exists("captcha:key")).isTrue();
        assertThat(cache.get("captcha:key")).isEqualTo("captcha-value");
        assertThat(cache.increment("captcha:key", 1L)).isEqualTo(2L);
        cache.delete("captcha:key");

        verify(values).set("captcha:key", "captcha-value", 60L, TimeUnit.SECONDS);
        verify(redisTemplate).hasKey("captcha:key");
        verify(values).get("captcha:key");
        verify(values).increment("captcha:key", 1L);
        verify(redisTemplate).delete("captcha:key");
    }
}
