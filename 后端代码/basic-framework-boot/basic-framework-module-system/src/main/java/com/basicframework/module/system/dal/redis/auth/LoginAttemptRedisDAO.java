package com.basicframework.module.system.dal.redis.auth;

import static com.basicframework.module.system.dal.redis.RedisKeyConstants.LOGIN_FAILURES;
import static com.basicframework.module.system.dal.redis.RedisKeyConstants.LOGIN_LOCK;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

/** 账号登录失败计数与锁定状态的 Redis 存储。 */
@Repository
@RequiredArgsConstructor
public class LoginAttemptRedisDAO {

    public enum FailureResult {
        COUNTED,
        NEWLY_LOCKED,
        ALREADY_LOCKED
    }

    private static final DefaultRedisScript<Long> RECORD_FAILURE_SCRIPT = new DefaultRedisScript<>(
            """
            if redis.call('EXISTS', KEYS[2]) == 1 then
                return 0
            end
            local attempts = redis.call('INCR', KEYS[1])
            if attempts == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            if attempts >= tonumber(ARGV[1]) then
                redis.call('DEL', KEYS[1])
                redis.call('SET', KEYS[2], '1', 'PX', ARGV[2])
                return -1
            end
            return attempts
            """,
            Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    public boolean isLocked(Long userId) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(lockKey(userId)));
    }

    /** 原子区分新增锁定和已锁定，调用前的状态查询不能用于推断本次是否新增锁定。 */
    public FailureResult recordFailure(Long userId, int maxFailedAttempts, Duration lockDuration) {
        Long result = stringRedisTemplate.execute(
                RECORD_FAILURE_SCRIPT,
                List.of(failuresKey(userId), lockKey(userId)),
                String.valueOf(maxFailedAttempts),
                String.valueOf(lockDuration.toMillis()));
        if (result == null) {
            throw new IllegalStateException("Redis 未返回登录失败计数结果");
        }
        if (result > 0) {
            return FailureResult.COUNTED;
        }
        if (result == -1L) {
            return FailureResult.NEWLY_LOCKED;
        }
        if (result == 0L) {
            return FailureResult.ALREADY_LOCKED;
        }
        throw new IllegalStateException("Redis 返回了未知的登录失败计数结果");
    }

    public void clear(Long userId) {
        stringRedisTemplate.delete(List.of(failuresKey(userId), lockKey(userId)));
    }

    private static String failuresKey(Long userId) {
        return LOGIN_FAILURES.formatted(userId);
    }

    private static String lockKey(Long userId) {
        return LOGIN_LOCK.formatted(userId);
    }
}
