package com.basicframework.module.system.dal.redis.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.system.dal.redis.auth.LoginAttemptRedisDAO.FailureResult;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
class LoginAttemptRedisDAOTest {

    private static final List<String> LOGIN_KEYS = List.of("login_failures:7", "login_lock:7");

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @InjectMocks
    private LoginAttemptRedisDAO loginAttemptRedisDAO;

    @Test
    void isLocked_returnsRedisKeyState() {
        when(stringRedisTemplate.hasKey(LOGIN_KEYS.get(1))).thenReturn(true, false);

        assertThat(loginAttemptRedisDAO.isLocked(7L)).isTrue();
        assertThat(loginAttemptRedisDAO.isLocked(7L)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordFailure_returnsWhetherScriptLockedAccount() {
        when(stringRedisTemplate.execute(any(RedisScript.class), eq(LOGIN_KEYS), eq("5"), eq("900000")))
                .thenReturn(4L, -1L, 0L, -2L);

        assertThat(loginAttemptRedisDAO.recordFailure(7L, 5, Duration.ofMinutes(15)))
                .isEqualTo(FailureResult.COUNTED);
        assertThat(loginAttemptRedisDAO.recordFailure(7L, 5, Duration.ofMinutes(15)))
                .isEqualTo(FailureResult.NEWLY_LOCKED);
        assertThat(loginAttemptRedisDAO.recordFailure(7L, 5, Duration.ofMinutes(15)))
                .isEqualTo(FailureResult.ALREADY_LOCKED);
        assertThatIllegalStateException()
                .isThrownBy(() -> loginAttemptRedisDAO.recordFailure(7L, 5, Duration.ofMinutes(15)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordFailure_rejectsMissingRedisResult() {
        when(stringRedisTemplate.execute(any(RedisScript.class), eq(LOGIN_KEYS), eq("5"), eq("900000")))
                .thenReturn(null);

        assertThatIllegalStateException()
                .isThrownBy(() -> loginAttemptRedisDAO.recordFailure(7L, 5, Duration.ofMinutes(15)))
                .withMessage("Redis 未返回登录失败计数结果");
    }

    @Test
    void clear_deletesCounterAndLockTogether() {
        loginAttemptRedisDAO.clear(7L);

        verify(stringRedisTemplate).delete(LOGIN_KEYS);
    }
}
