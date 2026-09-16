package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.system.config.LoginProtectionProperties;
import com.basicframework.module.system.dal.redis.RedisKeyConstants;
import com.basicframework.module.system.dal.redis.auth.LoginAttemptRedisDAO;
import com.basicframework.module.system.dal.redis.auth.LoginAttemptRedisDAO.FailureResult;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 使用真实 Redis 验证账号失败计数、锁定和管理员解锁。 */
class PersistenceLoginProtectionIT extends AbstractPersistenceIntegrationTest {

    private static final Long TEST_USER_ID = 9_001L;

    @Autowired
    private LoginAttemptRedisDAO loginAttemptRedisDAO;

    @Autowired
    private LoginProtectionProperties properties;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void concurrentFailures_createExactlyOneLockTransition() throws Exception {
        long userId = TEST_USER_ID + 1;
        loginAttemptRedisDAO.clear(userId);
        var executor = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var results = new ArrayList<Future<FailureResult>>();
            for (int index = 0; index < 12; index++) {
                results.add(executor.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("并发测试未按时启动");
                    }
                    return loginAttemptRedisDAO.recordFailure(userId, 5, properties.getLockDuration());
                }));
            }
            start.countDown();
            var completed = new ArrayList<FailureResult>();
            for (Future<FailureResult> result : results) {
                completed.add(result.get(20, TimeUnit.SECONDS));
            }
            assertThat(completed.stream()
                            .filter(value -> value == FailureResult.COUNTED)
                            .count())
                    .isEqualTo(4);
            assertThat(completed.stream()
                            .filter(value -> value == FailureResult.NEWLY_LOCKED)
                            .count())
                    .isEqualTo(1);
            assertThat(completed.stream()
                            .filter(value -> value == FailureResult.ALREADY_LOCKED)
                            .count())
                    .isEqualTo(7);
            assertThat(loginAttemptRedisDAO.isLocked(userId)).isTrue();
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
            loginAttemptRedisDAO.clear(userId);
        }
    }

    @Test
    void loginFailures_lockAtThresholdAndClearAtomically() {
        loginAttemptRedisDAO.clear(TEST_USER_ID);
        try {
            for (int attempt = 1; attempt < properties.getMaxFailedAttempts(); attempt++) {
                assertThat(loginAttemptRedisDAO.recordFailure(
                                TEST_USER_ID, properties.getMaxFailedAttempts(), properties.getLockDuration()))
                        .isEqualTo(FailureResult.COUNTED);
            }

            assertThat(loginAttemptRedisDAO.recordFailure(
                            TEST_USER_ID, properties.getMaxFailedAttempts(), properties.getLockDuration()))
                    .isEqualTo(FailureResult.NEWLY_LOCKED);
            assertThat(loginAttemptRedisDAO.isLocked(TEST_USER_ID)).isTrue();
            assertThat(stringRedisTemplate.hasKey(RedisKeyConstants.LOGIN_FAILURES.formatted(TEST_USER_ID)))
                    .isFalse();
            assertThat(stringRedisTemplate.getExpire(RedisKeyConstants.LOGIN_LOCK.formatted(TEST_USER_ID)))
                    .isPositive();

            loginAttemptRedisDAO.clear(TEST_USER_ID);

            assertThat(loginAttemptRedisDAO.isLocked(TEST_USER_ID)).isFalse();
        } finally {
            loginAttemptRedisDAO.clear(TEST_USER_ID);
        }
    }
}
