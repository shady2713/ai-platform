package com.basicframework.module.system.controller.admin.captcha;

import static org.assertj.core.api.Assertions.assertThat;

import com.anji.captcha.model.vo.CaptchaVO;
import com.basicframework.framework.ratelimiter.core.annotation.RateLimiter;
import com.basicframework.framework.ratelimiter.core.keyresolver.impl.ClientIpRateLimiterKeyResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 公开验证码端点的限流契约。 */
class CaptchaRateLimiterContractTest {

    @Test
    void publicCaptchaEndpoints_areLimitedPerClientIp() throws NoSuchMethodException {
        assertClientIpRateLimit("get");
        assertClientIpRateLimit("check");
    }

    private static void assertClientIpRateLimit(String methodName) throws NoSuchMethodException {
        Method method = CaptchaController.class.getMethod(methodName, CaptchaVO.class, HttpServletRequest.class);
        RateLimiter rateLimiter = method.getAnnotation(RateLimiter.class);

        assertThat(rateLimiter).isNotNull();
        assertThat(rateLimiter.count()).isEqualTo(30);
        assertThat(rateLimiter.time()).isEqualTo(60);
        assertThat(rateLimiter.timeUnit()).isEqualTo(TimeUnit.SECONDS);
        assertThat(rateLimiter.keyResolver()).isEqualTo(ClientIpRateLimiterKeyResolver.class);
    }
}
