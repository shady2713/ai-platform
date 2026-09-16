package com.basicframework.module.infra.api.logger;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.infra.api.logger.dto.ApiAccessLogCreateReqDTO;
import com.basicframework.module.infra.api.logger.dto.ApiErrorLogCreateReqDTO;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LogCommonApiTest {

    @Test
    void shouldDelegateAsyncAccessLogCreation() {
        AtomicReference<ApiAccessLogCreateReqDTO> captured = new AtomicReference<>();
        ApiAccessLogCommonApi api = captured::set;
        ApiAccessLogCreateReqDTO request = new ApiAccessLogCreateReqDTO();

        api.createApiAccessLogAsync(request);

        assertThat(captured).hasValue(request);
    }

    @Test
    void shouldDelegateAsyncErrorLogCreation() {
        AtomicReference<ApiErrorLogCreateReqDTO> captured = new AtomicReference<>();
        ApiErrorLogCommonApi api = captured::set;
        ApiErrorLogCreateReqDTO request = new ApiErrorLogCreateReqDTO();

        api.createApiErrorLogAsync(request);

        assertThat(captured).hasValue(request);
    }
}
