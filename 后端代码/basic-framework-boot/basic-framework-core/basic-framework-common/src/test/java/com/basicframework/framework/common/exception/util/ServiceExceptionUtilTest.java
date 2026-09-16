package com.basicframework.framework.common.exception.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants;
import org.junit.jupiter.api.Test;

class ServiceExceptionUtilTest {

    private static final ErrorCode ERROR_CODE = new ErrorCode(1_000_000_001, "用户 {} 不存在");

    @Test
    void createsExceptionsFromErrorCodesAndParameters() {
        ServiceException plain = ServiceExceptionUtil.exception(ERROR_CODE);
        ServiceException formatted = ServiceExceptionUtil.exception(ERROR_CODE, "admin");
        ServiceException invalid = ServiceExceptionUtil.invalidParamException("字段 {} 非法", "name");

        assertThat(plain.getCode()).isEqualTo(ERROR_CODE.getCode());
        assertThat(plain.getMessage()).isEqualTo(ERROR_CODE.getMsg());
        assertThat(formatted.getMessage()).isEqualTo("用户 admin 不存在");
        assertThat(invalid.getCode()).isEqualTo(GlobalErrorCodeConstants.BAD_REQUEST.getCode());
        assertThat(invalid.getMessage()).isEqualTo("字段 name 非法");
    }

    @Test
    void formatsExactExcessAndMissingParametersPredictably() {
        assertThat(ServiceExceptionUtil.doFormat(1, "{}:{}", "left", "right")).isEqualTo("left:right");
        assertThat(ServiceExceptionUtil.doFormat(1, "fixed", "unused")).isEqualTo("fixed");
        assertThat(ServiceExceptionUtil.doFormat(1, "{} fixed", "used", "unused"))
                .isEqualTo("used fixed");
        assertThat(ServiceExceptionUtil.doFormat(1, "{}:{}", "only")).isEqualTo("only:{}");
    }
}
