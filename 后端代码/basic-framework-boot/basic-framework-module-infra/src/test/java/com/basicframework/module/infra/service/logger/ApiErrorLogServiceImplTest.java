package com.basicframework.module.infra.service.logger;

import static com.basicframework.module.infra.dal.dataobject.logger.ApiErrorLogDO.REQUEST_PARAMS_MAX_LENGTH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.infra.api.logger.dto.ApiErrorLogCreateReqDTO;
import com.basicframework.module.infra.dal.dataobject.logger.ApiErrorLogDO;
import com.basicframework.module.infra.dal.mysql.logger.ApiErrorLogMapper;
import com.basicframework.module.infra.enums.logger.ApiErrorLogProcessStatusEnum;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApiErrorLogServiceImplTest {

    @InjectMocks
    private ApiErrorLogServiceImpl service;

    @Mock
    private ApiErrorLogMapper mapper;

    @Test
    void createApiErrorLog_truncatesRequestAndSetsInitialStatus() {
        ApiErrorLogCreateReqDTO request = new ApiErrorLogCreateReqDTO();
        request.setTraceId("trace-1");
        request.setRequestUrl("/admin-api/example");
        request.setExceptionName("IllegalStateException");
        request.setRequestParams("x".repeat(REQUEST_PARAMS_MAX_LENGTH + 100));

        service.createApiErrorLog(request);

        ArgumentCaptor<ApiErrorLogDO> captor = ArgumentCaptor.forClass(ApiErrorLogDO.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getRequestParams())
                .hasSize(REQUEST_PARAMS_MAX_LENGTH)
                .endsWith("...");
        assertThat(captor.getValue().getProcessStatus()).isEqualTo(ApiErrorLogProcessStatusEnum.INIT.getStatus());
    }

    @Test
    void createApiErrorLog_doesNotBreakRequestWhenPersistenceFails() {
        ApiErrorLogCreateReqDTO request = new ApiErrorLogCreateReqDTO();
        request.setTraceId("trace-2");
        request.setRequestUrl("/admin-api/example");
        request.setExceptionName("IllegalArgumentException");
        when(mapper.insert(any(ApiErrorLogDO.class))).thenThrow(new IllegalStateException("database unavailable"));

        assertThatCode(() -> service.createApiErrorLog(request)).doesNotThrowAnyException();
    }

    @Test
    void cleanErrorLog_deletesUntilTheFirstPartialBatch() {
        when(mapper.deleteByCreateTimeLt(any(LocalDateTime.class), eq(300))).thenReturn(300, 300, 80);

        assertThat(service.cleanErrorLog(30, 300, 10)).isEqualTo(680);
        verify(mapper, times(3)).deleteByCreateTimeLt(any(LocalDateTime.class), eq(300));
    }

    @Test
    void cleanErrorLog_honorsTheMaximumBatchCount() {
        when(mapper.deleteByCreateTimeLt(any(LocalDateTime.class), eq(200))).thenReturn(200);

        assertThat(service.cleanErrorLog(7, 200, 2)).isEqualTo(400);
        verify(mapper, times(2)).deleteByCreateTimeLt(any(LocalDateTime.class), eq(200));
    }
}
