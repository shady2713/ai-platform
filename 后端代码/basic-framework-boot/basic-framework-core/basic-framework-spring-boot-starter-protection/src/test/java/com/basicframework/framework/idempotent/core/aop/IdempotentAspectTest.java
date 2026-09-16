package com.basicframework.framework.idempotent.core.aop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants;
import com.basicframework.framework.idempotent.core.annotation.Idempotent;
import com.basicframework.framework.idempotent.core.keyresolver.IdempotentKeyResolver;
import com.basicframework.framework.idempotent.core.redis.IdempotentRedisDAO;
import java.lang.reflect.Method;
import java.util.List;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.Test;

class IdempotentAspectTest {

    private final IdempotentRedisDAO redisDAO = mock(IdempotentRedisDAO.class);
    private final ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
    private final IdempotentAspect aspect = new IdempotentAspect(List.of(new FixtureKeyResolver()), redisDAO);

    @Test
    void aroundPointCut_firstRequest_executesBusinessLogic() throws Throwable {
        Idempotent annotation = annotation("protectedOperation");
        when(redisDAO.setIfAbsent("request-key", 2, annotation.timeUnit())).thenReturn(true);
        when(joinPoint.proceed()).thenReturn("done");

        assertThat(aspect.aroundPointCut(joinPoint, annotation)).isEqualTo("done");

        verify(joinPoint).proceed();
        verify(redisDAO, never()).delete("request-key");
    }

    @Test
    void aroundPointCut_duplicateRequest_failsWithStableBusinessError() throws Throwable {
        Idempotent annotation = annotation("protectedOperation");
        Signature signature = mock(Signature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.toShortString()).thenReturn("Fixture.protectedOperation()");

        assertThatThrownBy(() -> aspect.aroundPointCut(joinPoint, annotation))
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> {
                    ServiceException exception = (ServiceException) error;
                    assertThat(exception.getCode()).isEqualTo(GlobalErrorCodeConstants.REPEATED_REQUESTS.getCode());
                    assertThat(exception.getMessage()).isEqualTo("请求处理中");
                });
        verify(joinPoint, never()).proceed();
    }

    @Test
    void aroundPointCut_businessFailure_releasesKeyWhenConfigured() throws Throwable {
        Idempotent annotation = annotation("protectedOperation");
        RuntimeException failure = new RuntimeException("business failed");
        when(redisDAO.setIfAbsent("request-key", 2, annotation.timeUnit())).thenReturn(true);
        when(joinPoint.proceed()).thenThrow(failure);

        assertThatThrownBy(() -> aspect.aroundPointCut(joinPoint, annotation)).isSameAs(failure);
        verify(redisDAO).delete("request-key");
    }

    @Test
    void aroundPointCut_businessFailure_keepsKeyWhenConfigured() throws Throwable {
        Idempotent annotation = annotation("keepKeyOnFailure");
        when(redisDAO.setIfAbsent("request-key", 1, annotation.timeUnit())).thenReturn(true);
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("business failed"));

        assertThatThrownBy(() -> aspect.aroundPointCut(joinPoint, annotation))
                .isInstanceOf(IllegalStateException.class);
        verify(redisDAO, never()).delete("request-key");
    }

    @Test
    void aroundPointCut_missingResolver_failsBeforeRedisAccess() throws Exception {
        IdempotentAspect emptyAspect = new IdempotentAspect(List.of(), redisDAO);

        assertThatThrownBy(() -> emptyAspect.aroundPointCut(joinPoint, annotation("protectedOperation")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IdempotentKeyResolver");
    }

    private static Idempotent annotation(String methodName) throws Exception {
        Method method = Fixture.class.getDeclaredMethod(methodName);
        return method.getAnnotation(Idempotent.class);
    }

    static final class FixtureKeyResolver implements IdempotentKeyResolver {
        @Override
        public String resolver(JoinPoint joinPoint, Idempotent idempotent) {
            return "request-key";
        }
    }

    static final class Fixture {
        @Idempotent(timeout = 2, message = "请求处理中", keyResolver = FixtureKeyResolver.class)
        void protectedOperation() {}

        @Idempotent(deleteKeyWhenException = false, keyResolver = FixtureKeyResolver.class)
        void keepKeyOnFailure() {}
    }
}
