package com.basicframework.framework.ratelimiter.core.aop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants;
import com.basicframework.framework.ratelimiter.core.annotation.RateLimiter;
import com.basicframework.framework.ratelimiter.core.keyresolver.RateLimiterKeyResolver;
import com.basicframework.framework.ratelimiter.core.redis.RateLimiterRedisDAO;
import java.lang.reflect.Method;
import java.util.List;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.Test;

class RateLimiterAspectTest {

    private final RateLimiterRedisDAO redisDAO = mock(RateLimiterRedisDAO.class);
    private final JoinPoint joinPoint = mock(JoinPoint.class);
    private final RateLimiterAspect aspect = new RateLimiterAspect(List.of(new FixtureKeyResolver()), redisDAO);

    @Test
    void beforePointCut_availablePermit_allowsRequest() throws Exception {
        RateLimiter annotation = annotation("defaultMessage");
        when(redisDAO.tryAcquire("rate-key", 3, 500, annotation.timeUnit())).thenReturn(true);

        assertThatCode(() -> aspect.beforePointCut(joinPoint, annotation)).doesNotThrowAnyException();
    }

    @Test
    void beforePointCut_exhaustedPermit_usesDefaultMessageWhenBlank() throws Exception {
        assertRateLimited(annotation("defaultMessage"), GlobalErrorCodeConstants.TOO_MANY_REQUESTS.getMsg());
    }

    @Test
    void beforePointCut_exhaustedPermit_preservesCustomMessage() throws Exception {
        assertRateLimited(annotation("customMessage"), "操作太快");
    }

    @Test
    void beforePointCut_missingResolver_failsBeforeRedisAccess() throws Exception {
        RateLimiterAspect emptyAspect = new RateLimiterAspect(List.of(), redisDAO);

        assertThatThrownBy(() -> emptyAspect.beforePointCut(joinPoint, annotation("defaultMessage")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RateLimiterKeyResolver");
        verify(redisDAO, never())
                .tryAcquire("rate-key", 3, 500, annotation("defaultMessage").timeUnit());
    }

    private void assertRateLimited(RateLimiter annotation, String expectedMessage) {
        Signature signature = mock(Signature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.toShortString()).thenReturn("Fixture.rateLimited()");

        assertThatThrownBy(() -> aspect.beforePointCut(joinPoint, annotation))
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> {
                    ServiceException exception = (ServiceException) error;
                    assertThat(exception.getCode()).isEqualTo(GlobalErrorCodeConstants.TOO_MANY_REQUESTS.getCode());
                    assertThat(exception.getMessage()).isEqualTo(expectedMessage);
                });
    }

    private static RateLimiter annotation(String methodName) throws Exception {
        Method method = Fixture.class.getDeclaredMethod(methodName);
        return method.getAnnotation(RateLimiter.class);
    }

    static final class FixtureKeyResolver implements RateLimiterKeyResolver {
        @Override
        public String resolver(JoinPoint joinPoint, RateLimiter rateLimiter) {
            return "rate-key";
        }
    }

    static final class Fixture {
        @RateLimiter(
                count = 3,
                time = 500,
                timeUnit = java.util.concurrent.TimeUnit.MILLISECONDS,
                keyResolver = FixtureKeyResolver.class)
        void defaultMessage() {}

        @RateLimiter(message = "操作太快", keyResolver = FixtureKeyResolver.class)
        void customMessage() {}
    }
}
