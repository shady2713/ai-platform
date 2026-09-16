package com.basicframework.framework.datapermission.core.aop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.basicframework.framework.datapermission.core.annotation.DataPermission;
import java.lang.reflect.Method;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DataPermissionAnnotationInterceptorTest {

    @Mock
    private MethodInvocation invocation;

    @AfterEach
    void clearContext() {
        DataPermissionContextHolder.clear();
    }

    @Test
    void methodAnnotation_isAvailableDuringInvocationAndRemovedAfterward() throws Throwable {
        DataPermissionAnnotationInterceptor interceptor = new DataPermissionAnnotationInterceptor();
        Method method = Fixture.class.getDeclaredMethod("disabled");
        Fixture target = new Fixture();
        when(invocation.getMethod()).thenReturn(method);
        when(invocation.getThis()).thenReturn(target);
        when(invocation.proceed()).thenAnswer(ignored -> {
            assertThat(DataPermissionContextHolder.get().enable()).isFalse();
            return "result";
        });

        assertThat(interceptor.invoke(invocation)).isEqualTo("result");
        assertThat(DataPermissionContextHolder.get()).isNull();
        assertThat(interceptor.getDataPermissionCache()).hasSize(1);
    }

    @Test
    void classAnnotation_isRemovedWhenInvocationFails() throws Throwable {
        DataPermissionAnnotationInterceptor interceptor = new DataPermissionAnnotationInterceptor();
        Method method = ClassAnnotatedFixture.class.getDeclaredMethod("run");
        when(invocation.getMethod()).thenReturn(method);
        when(invocation.getThis()).thenReturn(new ClassAnnotatedFixture());
        when(invocation.proceed()).thenThrow(new IllegalStateException("expected failure"));

        assertThatThrownBy(() -> interceptor.invoke(invocation))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("expected failure");
        assertThat(DataPermissionContextHolder.get()).isNull();
    }

    @Test
    void unannotatedMethod_isCachedWithoutChangingContext() throws Throwable {
        DataPermissionAnnotationInterceptor interceptor = new DataPermissionAnnotationInterceptor();
        Method method = Fixture.class.getDeclaredMethod("plain");
        when(invocation.getMethod()).thenReturn(method);
        when(invocation.getThis()).thenReturn(new Fixture());
        when(invocation.proceed()).thenReturn("first", "second");

        assertThat(interceptor.invoke(invocation)).isEqualTo("first");
        assertThat(interceptor.invoke(invocation)).isEqualTo("second");
        assertThat(DataPermissionContextHolder.get()).isNull();
        assertThat(interceptor.getDataPermissionCache())
                .containsValue(DataPermissionAnnotationInterceptor.DATA_PERMISSION_NULL);
    }

    private static class Fixture {

        @DataPermission(enable = false)
        void disabled() {}

        void plain() {}
    }

    @DataPermission
    private static class ClassAnnotatedFixture {

        void run() {}
    }
}
