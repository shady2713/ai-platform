package com.basicframework.framework.idempotent.core.keyresolver.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import cn.hutool.crypto.SecureUtil;
import com.basicframework.framework.idempotent.core.annotation.Idempotent;
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
 * 用户级幂等 Key 解析器单元测试
 *
 * 契约：key = md5(方法签名 + 参数拼接 + userId + userType)。
 * 登录态取 {@link WebFrameworkUtils} 静态上下文，用 mockStatic 隔离。
 */
@ExtendWith(MockitoExtension.class)
class UserIdempotentKeyResolverTest {

    private static final String METHOD_SIGNATURE = "OrderService#create";

    @Mock
    private JoinPoint joinPoint;

    @Mock
    private MethodSignature signature;

    @Mock
    private Idempotent idempotent;

    private MockedStatic<WebFrameworkUtils> utils;

    private final UserIdempotentKeyResolver resolver = new UserIdempotentKeyResolver();

    @AfterEach
    void tearDown() {
        if (utils != null) {
            utils.close();
        }
    }

    @Test
    void resolver_appendsLoginUserIdAndTypeToKey() {
        utils = mockStatic(WebFrameworkUtils.class);
        utils.when(WebFrameworkUtils::getLoginUserId).thenReturn(100L);
        utils.when(WebFrameworkUtils::getLoginUserType).thenReturn(2);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.toString()).thenReturn(METHOD_SIGNATURE);
        when(joinPoint.getArgs()).thenReturn(new Object[] {"a"});

        Long userId = 100L;
        Integer userType = 2;
        String key = resolver.resolver(joinPoint, idempotent);

        assertThat(key)
                .isEqualTo(SecureUtil.md5(METHOD_SIGNATURE + "a" + userId + userType))
                .hasSize(32);
    }

    @Test
    void resolver_unauthenticatedAppendsNullTokensToKey() {
        utils = mockStatic(WebFrameworkUtils.class);
        utils.when(WebFrameworkUtils::getLoginUserId).thenReturn(null);
        utils.when(WebFrameworkUtils::getLoginUserType).thenReturn(null);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.toString()).thenReturn(METHOD_SIGNATURE);
        when(joinPoint.getArgs()).thenReturn(new Object[] {"a"});

        Long userId = null;
        Integer userType = null;
        String key = resolver.resolver(joinPoint, idempotent);

        assertThat(key)
                .isEqualTo(SecureUtil.md5(METHOD_SIGNATURE + "a" + userId + userType))
                .hasSize(32);
    }
}
