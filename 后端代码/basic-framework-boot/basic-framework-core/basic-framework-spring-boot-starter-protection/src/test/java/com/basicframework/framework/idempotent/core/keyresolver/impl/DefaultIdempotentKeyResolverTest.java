package com.basicframework.framework.idempotent.core.keyresolver.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import cn.hutool.crypto.SecureUtil;
import com.basicframework.framework.idempotent.core.annotation.Idempotent;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 默认幂等 Key 解析器单元测试
 *
 * 契约：key = md5(方法签名 + 参数拼接)，参数为空或 null 时参数部分为空串。
 */
@ExtendWith(MockitoExtension.class)
class DefaultIdempotentKeyResolverTest {

    private static final String METHOD_SIGNATURE = "OrderService#create";

    @Mock
    private JoinPoint joinPoint;

    @Mock
    private MethodSignature signature;

    @Mock
    private Idempotent idempotent;

    private final DefaultIdempotentKeyResolver resolver = new DefaultIdempotentKeyResolver();

    @Test
    void resolver_joinsMethodNameAndArgsIntoMd5Key() {
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.toString()).thenReturn(METHOD_SIGNATURE);
        when(joinPoint.getArgs()).thenReturn(new Object[] {"a", 1});

        String key = resolver.resolver(joinPoint, idempotent);

        assertThat(key).isEqualTo(SecureUtil.md5(METHOD_SIGNATURE + "a,1")).hasSize(32);
    }

    @Test
    void resolver_nullArgsProduceStableKey() {
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.toString()).thenReturn(METHOD_SIGNATURE);
        when(joinPoint.getArgs()).thenReturn(null);

        String key = resolver.resolver(joinPoint, idempotent);

        assertThat(key).isEqualTo(SecureUtil.md5(METHOD_SIGNATURE)).hasSize(32);
    }
}
