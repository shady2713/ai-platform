package com.basicframework.module.system.dal.redis.sms;

import static com.basicframework.module.system.dal.redis.RedisKeyConstants.SMS_CODE_VALIDATE_FAILURES;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

/** 短信验证码校验失败计数的 Redis 存储，按验证码记录代次隔离。 */
@Repository
@RequiredArgsConstructor
public class SmsCodeAttemptRedisDAO {

    private static final DefaultRedisScript<Long> RECORD_FAILURE_SCRIPT = new DefaultRedisScript<>(
            """
            local attempts = redis.call('INCR', KEYS[1])
            if attempts == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return attempts
            """,
            Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 记录一次校验失败，首次失败时以验证码剩余有效期作为 TTL，避免旧验证码计数污染新代次。
     *
     * @param smsCodeId 验证码记录编号
     * @param ttl 计数过期时间（验证码剩余有效期）
     * @return 累计失败次数
     */
    public long recordFailure(Long smsCodeId, Duration ttl) {
        Long result = stringRedisTemplate.execute(
                RECORD_FAILURE_SCRIPT, List.of(failuresKey(smsCodeId)), String.valueOf(ttl.toMillis()));
        if (result == null) {
            throw new IllegalStateException("Redis 未返回验证码失败计数结果");
        }
        return result;
    }

    /**
     * 验证码核销成功后清空失败计数。
     *
     * @param smsCodeId 验证码记录编号
     */
    public void clear(Long smsCodeId) {
        stringRedisTemplate.delete(failuresKey(smsCodeId));
    }

    private static String failuresKey(Long smsCodeId) {
        return SMS_CODE_VALIDATE_FAILURES.formatted(smsCodeId);
    }
}
