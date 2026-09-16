package com.basicframework.framework.lock4j.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * 锁获取失败策略单元测试
 *
 * 契约：获取锁失败时抛出携带 {@link GlobalErrorCodeConstants#LOCKED} 错误码与非空文案的
 * {@link ServiceException}；兜底文案仅在 LOCKED.msg 为 null/blank 时启用（当前常量为非空，
 * 兜底分支为防御性死代码，未覆盖）。
 */
class DefaultLockFailureStrategyTest {

    private final DefaultLockFailureStrategy strategy = new DefaultLockFailureStrategy();

    @Test
    void onLockFailure_throwsServiceExceptionWithLockedCodeAndMessage() throws Exception {
        Method method = DefaultLockFailureStrategyTest.class.getDeclaredMethod("sampleTarget");

        assertThatThrownBy(() -> strategy.onLockFailure("lock:order:1", method, new Object[0]))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> {
                    ServiceException exception = (ServiceException) e;
                    assertThat(exception.getCode()).isEqualTo(GlobalErrorCodeConstants.LOCKED.getCode());
                    assertThat(exception.getMessage()).isNotBlank();
                });
    }

    @Test
    void onLockFailure_withNullMethod_throwsServiceException() {
        assertThatThrownBy(() -> strategy.onLockFailure("lock:order:1", null, new Object[0]))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> {
                    ServiceException exception = (ServiceException) e;
                    assertThat(exception.getCode()).isEqualTo(GlobalErrorCodeConstants.LOCKED.getCode());
                    assertThat(exception.getMessage()).isNotBlank();
                });
    }

    /** 仅作为反射取 Method 的样例方法 */
    @SuppressWarnings("unused")
    void sampleTarget() {
        // no-op
    }
}
