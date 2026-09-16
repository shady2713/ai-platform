package com.basicframework.module.infra.api.logger;

import static org.mockito.Mockito.verify;

import com.basicframework.module.infra.api.logger.dto.ApiAccessLogCreateReqDTO;
import com.basicframework.module.infra.service.logger.ApiAccessLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ApiAccessLogApiImpl} 单元测试
 *
 */
@ExtendWith(MockitoExtension.class)
class ApiAccessLogApiImplTest {

    @InjectMocks
    private ApiAccessLogApiImpl apiAccessLogApi;

    @Mock
    private ApiAccessLogService apiAccessLogService;

    @Test
    void createApiAccessLog_delegatesToServiceWithSameDto() {
        ApiAccessLogCreateReqDTO createDTO = new ApiAccessLogCreateReqDTO();
        createDTO.setTraceId("trace-1");
        createDTO.setRequestUrl("/admin-api/infra/file/upload");

        apiAccessLogApi.createApiAccessLog(createDTO);

        verify(apiAccessLogService).createApiAccessLog(createDTO);
    }
}
