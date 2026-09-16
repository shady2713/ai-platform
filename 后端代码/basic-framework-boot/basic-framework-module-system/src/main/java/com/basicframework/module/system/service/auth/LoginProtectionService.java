package com.basicframework.module.system.service.auth;

import com.basicframework.module.system.config.LoginProtectionProperties;
import com.basicframework.module.system.dal.redis.auth.LoginAttemptRedisDAO;
import com.basicframework.module.system.dal.redis.auth.LoginAttemptRedisDAO.FailureResult;
import com.basicframework.module.system.service.metrics.SecuritySignalMetrics;
import org.springframework.stereotype.Service;

/** 账号密码登录的失败计数与锁定策略。 */
@Service
public class LoginProtectionService {

    private final LoginAttemptRedisDAO loginAttemptRedisDAO;
    private final LoginProtectionProperties properties;
    private final SecuritySignalMetrics securitySignalMetrics;

    public LoginProtectionService(
            LoginAttemptRedisDAO loginAttemptRedisDAO,
            LoginProtectionProperties properties,
            SecuritySignalMetrics securitySignalMetrics) {
        this.loginAttemptRedisDAO = loginAttemptRedisDAO;
        this.properties = properties;
        this.securitySignalMetrics = securitySignalMetrics;
    }

    public boolean isLocked(Long userId) {
        return loginAttemptRedisDAO.isLocked(userId);
    }

    /**
     * 记录一次登录失败，并计入安全信号指标。
     *
     * <p>锁定信号只记录 Redis 原子判定的新增锁定，已通过前置检查的并发请求不会重复计数。
     *
     * @param userId 用户编号
     * @return 记录后账号是否已锁定
     */
    public boolean recordFailure(Long userId) {
        FailureResult result = loginAttemptRedisDAO.recordFailure(
                userId, properties.getMaxFailedAttempts(), properties.getLockDuration());
        securitySignalMetrics.recordLoginFailure();
        if (result == FailureResult.NEWLY_LOCKED) {
            securitySignalMetrics.recordAccountLock();
        }
        return result != FailureResult.COUNTED;
    }

    public void clear(Long userId) {
        loginAttemptRedisDAO.clear(userId);
    }
}
