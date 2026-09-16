package com.basicframework.framework.redis.core;

import cn.hutool.core.util.StrUtil;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;

/**
 * 支持自定义过期时间的 {@link RedisCacheManager} 实现类
 *
 * 在 {@link Cacheable#cacheNames()} 格式为 "key#ttl" 时，# 后面的 ttl 为过期时间。
 * 单位为最后一个字母（支持的单位有：d 天，h 小时，m 分钟，s 秒），默认单位为 s 秒
 *
 */
public class TimeoutRedisCacheManager extends RedisCacheManager {

    private static final String SPLIT = "#";
    private static final Pattern POSITIVE_TTL_PATTERN = Pattern.compile("^[1-9]\\d*[dhms]?$");

    public TimeoutRedisCacheManager(RedisCacheWriter cacheWriter, RedisCacheConfiguration defaultCacheConfiguration) {
        super(cacheWriter, defaultCacheConfiguration);
    }

    @Override
    protected RedisCache createRedisCache(String name, RedisCacheConfiguration cacheConfig) {
        if (StrUtil.isEmpty(name)) {
            return super.createRedisCache(name, cacheConfig);
        }
        // 如果使用 # 分隔，大小不为 2，则说明不使用自定义过期时间
        String[] names = StrUtil.splitToArray(name, SPLIT);
        if (names.length != 2) {
            return super.createRedisCache(name, cacheConfig);
        }

        // 核心：通过修改 cacheConfig 的过期时间，实现自定义过期时间
        if (cacheConfig != null) {
            // 移除 # 后面的 : 以及后面的内容，避免影响解析
            String ttlStr = StrUtil.subBefore(names[1], StrUtil.COLON, false); // 获得 ttlStr 时间部分
            names[1] = StrUtil.subAfter(names[1], ttlStr, false); // 移除掉 ttlStr 时间部分
            // 解析时间
            Duration duration = parseDuration(ttlStr);
            cacheConfig = cacheConfig.entryTtl(duration);
        }

        // 创建 RedisCache 对象，需要忽略掉 ttlStr
        return super.createRedisCache(names[0] + names[1], cacheConfig);
    }

    /**
     * 解析过期时间 Duration
     *
     * @param ttlStr 过期时间字符串
     * @return 过期时间 Duration
     */
    static Duration parseDuration(String ttlStr) {
        if (ttlStr == null || !POSITIVE_TTL_PATTERN.matcher(ttlStr).matches()) {
            throw new IllegalArgumentException("缓存 TTL 必须为正整数并使用可选单位 d/h/m/s: " + ttlStr);
        }
        String timeUnit = StrUtil.subSuf(ttlStr, -1);
        if (Character.isDigit(timeUnit.charAt(0))) {
            return Duration.ofSeconds(parsePositiveLong(ttlStr));
        }
        long amount = parsePositiveLong(ttlStr.substring(0, ttlStr.length() - 1));
        return switch (timeUnit) {
            case "d":
                yield Duration.ofDays(amount);
            case "h":
                yield Duration.ofHours(amount);
            case "m":
                yield Duration.ofMinutes(amount);
            case "s":
                yield Duration.ofSeconds(amount);
            default:
                throw new IllegalArgumentException("不支持的缓存 TTL 单位: " + timeUnit);
        };
    }

    private static long parsePositiveLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("缓存 TTL 超出 Long 范围: " + value, exception);
        }
    }
}
