package com.basicframework.framework.ratelimiter.core.redis;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import lombok.AllArgsConstructor;
import org.redisson.api.*;
import org.springframework.util.Assert;

/**
 * 限流 Redis DAO
 *
 */
@AllArgsConstructor
public class RateLimiterRedisDAO {

    /**
     * 限流操作
     *
     * KEY 格式：rate_limiter:%s // 参数为 uuid
     * VALUE 格式：String
     * 过期时间：不固定
     */
    private static final String RATE_LIMITER = "rate_limiter:%s";

    private final RedissonClient redissonClient;

    public boolean tryAcquire(String key, int count, int time, TimeUnit timeUnit) {
        Assert.hasText(key, "限流 Key 不能为空");
        Assert.isTrue(count > 0, "限流次数必须大于 0");
        Assert.isTrue(time > 0, "限流时间必须大于 0");
        Assert.notNull(timeUnit, "限流时间单位不能为空");
        long rateIntervalMillis = timeUnit.toMillis(time);
        Assert.isTrue(rateIntervalMillis > 0, "限流时间必须至少为 1 毫秒");
        Duration rateInterval = Duration.ofMillis(rateIntervalMillis);
        // 1. 获得 RRateLimiter，并设置 rate 速率
        RRateLimiter rateLimiter = getRRateLimiter(key, count, rateInterval);
        // 2. 尝试获取 1 个
        return rateLimiter.tryAcquire();
    }

    private static String formatKey(String key) {
        return String.format(RATE_LIMITER, key);
    }

    private RRateLimiter getRRateLimiter(String key, long count, Duration rateInterval) {
        String redisKey = formatKey(key);
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(redisKey);
        // 1. 如果不存在，设置 rate 速率
        RateLimiterConfig config = rateLimiter.getConfig();
        if (config == null) {
            rateLimiter.trySetRate(RateType.OVERALL, count, rateInterval);
            // 额外设置过期时间，避免限流器 Key 长期残留
            rateLimiter.expire(rateInterval);
            return rateLimiter;
        }
        // 2. 如果存在，并且配置相同，则直接返回
        if (config.getRateType() == RateType.OVERALL
                && Objects.equals(config.getRate(), count)
                && Objects.equals(config.getRateInterval(), rateInterval.toMillis())) {
            return rateLimiter;
        }
        // 3. 如果存在，并且配置不同，则进行新建
        rateLimiter.setRate(RateType.OVERALL, count, rateInterval);
        // 额外设置过期时间，避免限流器 Key 长期残留
        rateLimiter.expire(rateInterval);
        return rateLimiter;
    }
}
