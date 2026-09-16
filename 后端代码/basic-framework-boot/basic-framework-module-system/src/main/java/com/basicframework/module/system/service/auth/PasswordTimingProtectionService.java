package com.basicframework.module.system.service.auth;

import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 为没有可用凭据的认证分支提供等成本密码校验。
 *
 * <p>假哈希由部署实际使用的 {@link PasswordEncoder} 在启动期生成，因此会继承当前 BCrypt 工作因子；请求期只执行
 * {@code matches}，不会因账号不存在或已锁定而跳过密码学成本。
 */
@Service
public final class PasswordTimingProtectionService {

    private final PasswordEncoder passwordEncoder;
    private final String dummyPasswordHash;

    PasswordTimingProtectionService(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * 对提交的密码执行一次结果不可观察的假哈希校验。
     *
     * @param rawPassword 匿名调用方提交的原始密码
     */
    public void verifyAgainstDummyHash(String rawPassword) {
        passwordEncoder.matches(rawPassword, dummyPasswordHash);
    }
}
