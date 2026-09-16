package com.basicframework.framework.idempotent.core.redis;

import java.util.concurrent.TimeUnit;
import lombok.AllArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.Assert;

/**
 * 幂等 Redis DAO
 *
 */
@AllArgsConstructor
public class IdempotentRedisDAO {

    /**
     * 幂等操作
     *
     * KEY 格式：idempotent:%s // 参数为 uuid
     * VALUE 格式：String
     * 过期时间：不固定
     */
    private static final String IDEMPOTENT = "idempotent:%s";

    private final StringRedisTemplate redisTemplate;

    public boolean setIfAbsent(String key, long timeout, TimeUnit timeUnit) {
        Assert.hasText(key, "幂等 Key 不能为空");
        Assert.isTrue(timeout > 0, "幂等超时时间必须大于 0");
        Assert.notNull(timeUnit, "幂等超时时间单位不能为空");
        String redisKey = formatKey(key);
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(redisKey, "", timeout, timeUnit));
    }

    public void delete(String key) {
        Assert.hasText(key, "幂等 Key 不能为空");
        String redisKey = formatKey(key);
        redisTemplate.delete(redisKey);
    }

    private static String formatKey(String key) {
        return String.format(IDEMPOTENT, key);
    }
}
