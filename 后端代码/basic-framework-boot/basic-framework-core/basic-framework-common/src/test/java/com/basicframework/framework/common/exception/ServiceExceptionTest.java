package com.basicframework.framework.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ServiceExceptionTest {

    @Test
    void constructor_preservesErrorCodeAndRuntimeExceptionMessage() {
        ErrorCode errorCode = new ErrorCode(1_002_000_001, "账号已禁用");

        ServiceException exception = new ServiceException(errorCode);

        assertThat(exception.getCode()).isEqualTo(1_002_000_001);
        assertThat(exception).hasMessage("账号已禁用").hasNoCause();
        assertThat(exception.getPublicMessage()).isEqualTo("账号已禁用");
    }

    @Test
    void constructor_acceptsExplicitCodeAndMessage() {
        ServiceException exception = new ServiceException(400, "参数错误");

        assertThat(exception.getCode()).isEqualTo(400);
        assertThat(exception).hasMessage("参数错误");
    }
}
