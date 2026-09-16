package com.basicframework.module.system.dal.redis.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/** {@link SmsCodeAttemptRedisDAO} 失败计数脚本调用与 TTL 传递测试。 */
@ExtendWith(MockitoExtension.class)
class SmsCodeAttemptRedisDAOTest {

    private static final List<String> FAILURE_KEYS = List.of("sms_code_validate_failures:42");

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @InjectMocks
    private SmsCodeAttemptRedisDAO smsCodeAttemptRedisDAO;

    @Test
    @SuppressWarnings("unchecked")
    void recordFailure_passesCodeValidityAsCounterTtl() {
        when(stringRedisTemplate.execute(any(RedisScript.class), eq(FAILURE_KEYS), eq("300000")))
                .thenReturn(1L, 3L);

        assertThat(smsCodeAttemptRedisDAO.recordFailure(42L, Duration.ofMinutes(5)))
                .isEqualTo(1L);
        assertThat(smsCodeAttemptRedisDAO.recordFailure(42L, Duration.ofMinutes(5)))
                .isEqualTo(3L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordFailure_rejectsMissingRedisResult() {
        when(stringRedisTemplate.execute(any(RedisScript.class), eq(FAILURE_KEYS), eq("300000")))
                .thenReturn(null);

        assertThatIllegalStateException()
                .isThrownBy(() -> smsCodeAttemptRedisDAO.recordFailure(42L, Duration.ofMinutes(5)))
                .withMessage("Redis 未返回验证码失败计数结果");
    }

    @Test
    void clear_deletesFailureCounter() {
        smsCodeAttemptRedisDAO.clear(42L);

        verify(stringRedisTemplate).delete(FAILURE_KEYS.get(0));
    }
}
