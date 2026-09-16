package com.basicframework.framework.ratelimiter.core.keyresolver.impl;

import cn.hutool.crypto.SecureUtil;
import com.basicframework.framework.ratelimiter.core.annotation.RateLimiter;
import com.basicframework.framework.ratelimiter.core.keyresolver.RateLimiterKeyResolver;
import com.basicframework.framework.web.core.util.WebFrameworkUtils;
import org.aspectj.lang.JoinPoint;

/**
 * 用户级别的限流 Key 解析器，使用方法签名 + userId + userType 组装 Key。
 *
 * 请求参数不可拆分同一用户的限流桶；需要按业务字段限流时使用 {@code ExpressionRateLimiterKeyResolver}。
 *
 * 为了避免 Key 过长，使用 MD5 进行“压缩”
 *
 */
public class UserRateLimiterKeyResolver implements RateLimiterKeyResolver {

    @Override
    public String resolver(JoinPoint joinPoint, RateLimiter rateLimiter) {
        String methodName = joinPoint.getSignature().toString();
        Long userId = WebFrameworkUtils.getLoginUserId();
        Integer userType = WebFrameworkUtils.getLoginUserType();
        return SecureUtil.md5(methodName + userId + userType);
    }
}
