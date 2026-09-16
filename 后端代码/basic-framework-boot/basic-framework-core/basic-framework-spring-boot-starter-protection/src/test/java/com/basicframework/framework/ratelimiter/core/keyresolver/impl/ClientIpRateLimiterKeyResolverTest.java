package com.basicframework.framework.ratelimiter.core.keyresolver.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.hutool.crypto.SecureUtil;
import com.basicframework.framework.common.util.servlet.ServletUtils;
import com.basicframework.framework.ratelimiter.core.annotation.RateLimiter;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * IP 级限流 Key 解析器单元测试
 *
 * 契约：key = md5(方法签名 + clientIp)，请求参数不能拆分同一 IP 的限流桶。
 * clientIp 取 {@link ServletUtils} 静态上下文，用 mockStatic 隔离。
 */
@ExtendWith(MockitoExtension.class)
class ClientIpRateLimiterKeyResolverTest {

    private static final String METHOD_SIGNATURE = "OrderService#create";

    @Mock
    private JoinPoint joinPoint;

    @Mock
    private MethodSignature signature;

    @Mock
    private RateLimiter rateLimiter;

    private MockedStatic<ServletUtils> utils;

    private final ClientIpRateLimiterKeyResolver resolver = new ClientIpRateLimiterKeyResolver();

    @AfterEach
    void tearDown() {
        if (utils != null) {
            utils.close();
        }
    }

    @Test
    void resolver_usesOnlyMethodAndClientIp() {
        utils = mockStatic(ServletUtils.class);
        utils.when(ServletUtils::getClientIP).thenReturn("192.168.1.10");
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.toString()).thenReturn(METHOD_SIGNATURE);

        String clientIp = "192.168.1.10";
        String key = resolver.resolver(joinPoint, rateLimiter);

        assertThat(key).isEqualTo(SecureUtil.md5(METHOD_SIGNATURE + clientIp)).hasSize(32);
        verify(joinPoint, never()).getArgs();
    }
}
