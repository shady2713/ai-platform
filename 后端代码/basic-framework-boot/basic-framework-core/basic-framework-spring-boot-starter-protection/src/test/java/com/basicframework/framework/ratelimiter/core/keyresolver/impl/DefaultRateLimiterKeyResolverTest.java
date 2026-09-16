package com.basicframework.framework.ratelimiter.core.keyresolver.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.hutool.crypto.SecureUtil;
import com.basicframework.framework.ratelimiter.core.annotation.RateLimiter;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 默认限流 Key 解析器单元测试
 *
 * 契约：key = md5(方法签名)，全局限流不得受请求参数影响。
 */
@ExtendWith(MockitoExtension.class)
class DefaultRateLimiterKeyResolverTest {

    private static final String METHOD_SIGNATURE = "OrderService#create";

    @Mock
    private JoinPoint joinPoint;

    @Mock
    private MethodSignature signature;

    @Mock
    private RateLimiter rateLimiter;

    private final DefaultRateLimiterKeyResolver resolver = new DefaultRateLimiterKeyResolver();

    @Test
    void resolver_usesMethodSignatureOnly() {
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.toString()).thenReturn(METHOD_SIGNATURE);

        String key = resolver.resolver(joinPoint, rateLimiter);

        assertThat(key).isEqualTo(SecureUtil.md5(METHOD_SIGNATURE)).hasSize(32);
        verify(joinPoint, never()).getArgs();
    }

    @Test
    void resolver_isStableWithoutRequestArguments() {
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.toString()).thenReturn(METHOD_SIGNATURE);

        String key = resolver.resolver(joinPoint, rateLimiter);

        assertThat(key).isEqualTo(SecureUtil.md5(METHOD_SIGNATURE)).hasSize(32);
        verify(joinPoint, never()).getArgs();
    }
}
