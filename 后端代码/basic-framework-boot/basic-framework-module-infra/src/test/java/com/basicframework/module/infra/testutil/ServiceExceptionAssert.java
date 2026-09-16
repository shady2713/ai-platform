package com.basicframework.module.infra.testutil;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;

/**
 * 服务层测试共享断言：钉住业务异常的错误码契约。
 */
public final class ServiceExceptionAssert {

    private ServiceExceptionAssert() {}

    /**
     * 断言动作抛出指定错误码的业务异常。
     *
     * @param code 期望的业务错误码
     * @param action 触发异常的动作
     */
    public static void assertServiceException(Integer code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(code);
    }

    /**
     * 断言动作抛出指定错误码对象的业务异常。
     *
     * @param errorCode 期望的错误码对象
     * @param action 触发异常的动作
     */
    public static void assertServiceException(ErrorCode errorCode, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(errorCode.getCode());
    }
}
