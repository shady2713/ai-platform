package com.basicframework.framework.idempotent.core.keyresolver.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.framework.idempotent.core.annotation.Idempotent;
import java.lang.reflect.Method;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 表达式幂等 Key 解析器单元测试
 *
 * 契约：按 Spring EL {@link Idempotent#keyArg()} 求值，变量来自方法参数名。
 * 覆盖三类方法定位路径：接口声明（走 target 上重新查找）、类声明（直接用）、
 * target 缺失方法（抛 RuntimeException）。依赖 {@code -parameters} 编译参数。
 */
@ExtendWith(MockitoExtension.class)
class ExpressionIdempotentKeyResolverTest {

    @Mock
    private JoinPoint joinPoint;

    @Mock
    private MethodSignature signature;

    @Mock
    private Idempotent idempotent;

    private final ExpressionIdempotentKeyResolver resolver = new ExpressionIdempotentKeyResolver();

    @Test
    void resolver_interfaceDeclaredMethod_resolvesVariableFromTargetMethod() throws Exception {
        Method ifaceMethod = KeyArgInterface.class.getMethod("resolve", Long.class, String.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(ifaceMethod);
        when(signature.getName()).thenReturn("resolve");
        when(joinPoint.getTarget()).thenReturn(new KeyArgImpl());
        when(joinPoint.getArgs()).thenReturn(new Object[] {123L, "alice"});
        when(idempotent.keyArg()).thenReturn("#name");

        String key = resolver.resolver(joinPoint, idempotent);

        assertThat(key).isEqualTo("alice");
    }

    @Test
    void resolver_concreteDeclaredMethod_usesMethodDirectly() throws Exception {
        Method concreteMethod = KeyArgImpl.class.getMethod("greet", String.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(concreteMethod);
        when(joinPoint.getArgs()).thenReturn(new Object[] {"hello"});
        when(idempotent.keyArg()).thenReturn("#word");

        String key = resolver.resolver(joinPoint, idempotent);

        assertThat(key).isEqualTo("hello");
    }

    @Test
    void resolver_targetMissingMethod_throwsRuntimeException() throws Exception {
        Method ifaceMethod = KeyArgInterface.class.getMethod("resolve", Long.class, String.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(ifaceMethod);
        when(signature.getName()).thenReturn("resolve");
        when(joinPoint.getTarget()).thenReturn(mock(Runnable.class));

        assertThatThrownBy(() -> resolver.resolver(joinPoint, idempotent))
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(NoSuchMethodException.class);
    }

    /** 测试夹具接口，方法声明在接口上，走 target 重新查找分支 */
    interface KeyArgInterface {
        String resolve(Long userId, String name);
    }

    /** 测试夹具实现，兼具接口方法与独立的类声明方法，便于覆盖两类路径 */
    static final class KeyArgImpl implements KeyArgInterface {
        @Override
        public String resolve(Long userId, String name) {
            return "resolved";
        }

        public String greet(String word) {
            return "greeted";
        }
    }
}
