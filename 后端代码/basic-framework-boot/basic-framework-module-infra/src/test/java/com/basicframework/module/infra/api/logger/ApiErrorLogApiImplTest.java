package com.basicframework.module.infra.api.logger;

import static org.mockito.Mockito.verify;

import com.basicframework.module.infra.api.logger.dto.ApiErrorLogCreateReqDTO;
import com.basicframework.module.infra.service.logger.ApiErrorLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ApiErrorLogApiImpl} 单元测试
 *
 */
@ExtendWith(MockitoExtension.class)
class ApiErrorLogApiImplTest {

    @InjectMocks
    private ApiErrorLogApiImpl apiErrorLogApi;

    @Mock
    private ApiErrorLogService apiErrorLogService;

    @Test
    void createApiErrorLog_delegatesToServiceWithSameDto() {
        ApiErrorLogCreateReqDTO createDTO = new ApiErrorLogCreateReqDTO();
        createDTO.setTraceId("trace-2");
        createDTO.setRequestUrl("/admin-api/infra/file/upload");

        apiErrorLogApi.createApiErrorLog(createDTO);

        verify(apiErrorLogService).createApiErrorLog(createDTO);
    }
}
