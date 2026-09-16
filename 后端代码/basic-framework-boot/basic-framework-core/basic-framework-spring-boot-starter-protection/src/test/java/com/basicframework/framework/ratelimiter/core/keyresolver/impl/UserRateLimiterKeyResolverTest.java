package com.basicframework.framework.ratelimiter.core.keyresolver.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.hutool.crypto.SecureUtil;
import com.basicframework.framework.ratelimiter.core.annotation.RateLimiter;
import com.basicframework.framework.web.core.util.WebFrameworkUtils;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 用户级限流 Key 解析器单元测试
 *
 * 契约：key = md5(方法签名 + userId + userType)，请求参数不能拆分同一用户的限流桶。
 * 登录态取 {@link WebFrameworkUtils} 静态上下文，用 mockStatic 隔离。
 */
@ExtendWith(MockitoExtension.class)
class UserRateLimiterKeyResolverTest {

    private static final String METHOD_SIGNATURE = "OrderService#create";

    @Mock
    private JoinPoint joinPoint;

    @Mock
    private MethodSignature signature;

    @Mock
    private RateLimiter rateLimiter;

    private MockedStatic<WebFrameworkUtils> utils;

    private final UserRateLimiterKeyResolver resolver = new UserRateLimiterKeyResolver();

    @AfterEach
    void tearDown() {
        if (utils != null) {
            utils.close();
        }
    }

    @Test
    void resolver_usesOnlyLoginUserIdAndType() {
        utils = mockStatic(WebFrameworkUtils.class);
        utils.when(WebFrameworkUtils::getLoginUserId).thenReturn(100L);
        utils.when(WebFrameworkUtils::getLoginUserType).thenReturn(2);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.toString()).thenReturn(METHOD_SIGNATURE);

        Long userId = 100L;
        Integer userType = 2;
        String key = resolver.resolver(joinPoint, rateLimiter);

        assertThat(key)
                .isEqualTo(SecureUtil.md5(METHOD_SIGNATURE + userId + userType))
                .hasSize(32);
        verify(joinPoint, never()).getArgs();
    }

    @Test
    void resolver_unauthenticatedUsesNullIdentityTokensWithoutRequestArguments() {
        utils = mockStatic(WebFrameworkUtils.class);
        utils.when(WebFrameworkUtils::getLoginUserId).thenReturn(null);
        utils.when(WebFrameworkUtils::getLoginUserType).thenReturn(null);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.toString()).thenReturn(METHOD_SIGNATURE);

        Long userId = null;
        Integer userType = null;
        String key = resolver.resolver(joinPoint, rateLimiter);

        assertThat(key)
                .isEqualTo(SecureUtil.md5(METHOD_SIGNATURE + userId + userType))
                .hasSize(32);
        verify(joinPoint, never()).getArgs();
    }
}
