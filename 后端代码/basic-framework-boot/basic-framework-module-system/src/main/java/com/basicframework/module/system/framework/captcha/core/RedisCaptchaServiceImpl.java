package com.basicframework.module.system.framework.captcha.core;

import com.anji.captcha.service.CaptchaCacheService;
import java.util.concurrent.TimeUnit;
import lombok.Setter;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 基于 Redis 实现验证码的存储
 *
 */
@Setter
public class RedisCaptchaServiceImpl implements CaptchaCacheService {

    private StringRedisTemplate stringRedisTemplate;

    @Override
    public String type() {
        return "redis";
    }

    @Override
    public void set(String key, String value, long expiresInSeconds) {
        stringRedisTemplate.opsForValue().set(key, value, expiresInSeconds, TimeUnit.SECONDS);
    }

    @Override
    public boolean exists(String key) {
        return stringRedisTemplate.hasKey(key);
    }

    @Override
    public void delete(String key) {
        stringRedisTemplate.delete(key);
    }

    @Override
    public String get(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    /** 原子读取并删除一次性验证码，避免并发请求重放同一令牌。 */
    public String getAndDelete(String key) {
        return stringRedisTemplate.opsForValue().getAndDelete(key);
    }

    @Override
    public Long increment(String key, long val) {
        return stringRedisTemplate.opsForValue().increment(key, val);
    }
}
