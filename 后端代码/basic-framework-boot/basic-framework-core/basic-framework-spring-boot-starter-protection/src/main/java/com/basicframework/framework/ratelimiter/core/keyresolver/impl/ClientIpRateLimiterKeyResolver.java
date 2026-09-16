package com.basicframework.framework.ratelimiter.core.keyresolver.impl;

import cn.hutool.crypto.SecureUtil;
import com.basicframework.framework.common.util.servlet.ServletUtils;
import com.basicframework.framework.ratelimiter.core.annotation.RateLimiter;
import com.basicframework.framework.ratelimiter.core.keyresolver.RateLimiterKeyResolver;
import org.aspectj.lang.JoinPoint;

/**
 * IP 级别的限流 Key 解析器，使用方法签名 + IP 组装 Key。
 *
 * 请求参数不可参与 key：攻击者若能通过变更用户名、验证码等参数获得新桶，IP 限流即失去保护作用。
 * 需要按业务字段限流时，应使用 {@code ExpressionRateLimiterKeyResolver} 显式声明字段。
 *
 * 为了避免 Key 过长，使用 MD5 进行“压缩”
 *
 */
public class ClientIpRateLimiterKeyResolver implements RateLimiterKeyResolver {

    @Override
    public String resolver(JoinPoint joinPoint, RateLimiter rateLimiter) {
        String methodName = joinPoint.getSignature().toString();
        String clientIp = ServletUtils.getClientIP();
        return SecureUtil.md5(methodName + clientIp);
    }
}
