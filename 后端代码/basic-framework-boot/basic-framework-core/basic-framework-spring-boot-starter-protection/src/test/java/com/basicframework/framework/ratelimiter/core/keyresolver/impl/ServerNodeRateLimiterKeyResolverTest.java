package com.basicframework.framework.ratelimiter.core.keyresolver.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.system.HostInfo;
import cn.hutool.system.SystemUtil;
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
 * Server 节点级限流 Key 解析器单元测试
 *
 * 契约：key = md5(方法签名 + serverNode)，serverNode = {@code address@pid}，请求参数不参与限流桶。
 * 主机信息与进程号取自 {@link SystemUtil} 静态上下文，用 mockStatic 隔离。
 */
@ExtendWith(MockitoExtension.class)
class ServerNodeRateLimiterKeyResolverTest {

    private static final String METHOD_SIGNATURE = "OrderService#create";

    @Mock
    private JoinPoint joinPoint;

    @Mock
    private MethodSignature signature;

    @Mock
    private RateLimiter rateLimiter;

    private MockedStatic<SystemUtil> utils;

    private final ServerNodeRateLimiterKeyResolver resolver = new ServerNodeRateLimiterKeyResolver();

    @AfterEach
    void tearDown() {
        if (utils != null) {
            utils.close();
        }
    }

    @Test
    void resolver_usesOnlyHostAddressAndPid() {
        HostInfo hostInfo = mock(HostInfo.class);
        when(hostInfo.getAddress()).thenReturn("10.1.2.3");
        utils = mockStatic(SystemUtil.class);
        utils.when(SystemUtil::getHostInfo).thenReturn(hostInfo);
        utils.when(SystemUtil::getCurrentPID).thenReturn(42L);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.toString()).thenReturn(METHOD_SIGNATURE);

        String serverNode = "10.1.2.3@42";
        String key = resolver.resolver(joinPoint, rateLimiter);

        assertThat(key).isEqualTo(SecureUtil.md5(METHOD_SIGNATURE + serverNode)).hasSize(32);
        verify(joinPoint, never()).getArgs();
    }
}
