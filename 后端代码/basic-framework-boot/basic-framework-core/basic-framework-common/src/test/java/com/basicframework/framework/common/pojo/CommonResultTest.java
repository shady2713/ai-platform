package com.basicframework.framework.common.pojo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants;
import org.junit.jupiter.api.Test;

class CommonResultTest {

    private static final ErrorCode ERROR_CODE = new ErrorCode(1_000_000_002, "资源 {} 不存在");

    @Test
    void returnsSuccessfulDataAndChecksStatus() {
        CommonResult<String> result = CommonResult.success("data");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isError()).isFalse();
        assertThat(result.getCheckedData()).isEqualTo("data");
        assertThat(CommonResult.isSuccess(result.getCode())).isTrue();
        assertThat(result.getMsg()).isEmpty();
    }

    @Test
    void buildsErrorsFromAllSupportedSources() {
        CommonResult<Object> formatted = CommonResult.error(ERROR_CODE, "file");
        CommonResult<Object> copied = CommonResult.error(formatted);
        CommonResult<Object> fromException = CommonResult.error(new ServiceException(42, "failed"));

        assertThat(formatted.getCode()).isEqualTo(ERROR_CODE.getCode());
        assertThat(formatted.getMsg()).isEqualTo("资源 file 不存在");
        assertThat(copied.getCode()).isEqualTo(formatted.getCode());
        assertThat(copied.getMsg()).isEqualTo(formatted.getMsg());
        assertThat(fromException.getCode()).isEqualTo(42);
        assertThat(fromException.getMsg()).isEqualTo("failed");
        assertThat(CommonResult.error(ERROR_CODE).getMsg()).isEqualTo(ERROR_CODE.getMsg());
    }

    @Test
    void rejectsSuccessCodeForErrorsAndThrowsBusinessExceptionOnCheck() {
        assertThatThrownBy(() -> CommonResult.error(GlobalErrorCodeConstants.SUCCESS.getCode(), "invalid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommonResult.error(GlobalErrorCodeConstants.SUCCESS))
                .isInstanceOf(IllegalArgumentException.class);

        CommonResult<Object> error = CommonResult.error(42, "failed");
        assertThat(error.isError()).isTrue();
        assertThatThrownBy(error::checkError).isInstanceOfSatisfying(ServiceException.class, exception -> {
            assertThat(exception.getCode()).isEqualTo(42);
            assertThat(exception.getMessage()).isEqualTo("failed");
        });
    }
}
