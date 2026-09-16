package com.basicframework.framework.ratelimiter.core.keyresolver.impl;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.system.SystemUtil;
import com.basicframework.framework.ratelimiter.core.annotation.RateLimiter;
import com.basicframework.framework.ratelimiter.core.keyresolver.RateLimiterKeyResolver;
import org.aspectj.lang.JoinPoint;

/**
 * Server 节点级别的限流 Key 解析器，使用方法签名 + 节点标识组装 Key。
 *
 * 可变请求参数不参与节点级限流桶；需要按业务字段限流时使用 {@code ExpressionRateLimiterKeyResolver}。
 *
 * 为了避免 Key 过长，使用 MD5 进行“压缩”
 *
 */
public class ServerNodeRateLimiterKeyResolver implements RateLimiterKeyResolver {

    @Override
    public String resolver(JoinPoint joinPoint, RateLimiter rateLimiter) {
        String methodName = joinPoint.getSignature().toString();
        String serverNode = String.format("%s@%d", SystemUtil.getHostInfo().getAddress(), SystemUtil.getCurrentPID());
        return SecureUtil.md5(methodName + serverNode);
    }
}
