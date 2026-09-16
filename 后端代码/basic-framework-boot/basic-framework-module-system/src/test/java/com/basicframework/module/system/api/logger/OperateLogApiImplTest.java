package com.basicframework.module.system.api.logger;

import static org.mockito.Mockito.verify;

import com.basicframework.module.system.api.logger.dto.OperateLogCreateReqDTO;
import com.basicframework.module.system.service.logger.OperateLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link OperateLogApiImpl} 单元测试
 *
 */
@ExtendWith(MockitoExtension.class)
class OperateLogApiImplTest {

    @InjectMocks
    private OperateLogApiImpl operateLogApi;

    @Mock
    private OperateLogService operateLogService;

    @Test
    void createOperateLog_delegatesToServiceWithSameDto() {
        OperateLogCreateReqDTO createReqDTO = new OperateLogCreateReqDTO();
        createReqDTO.setTraceId("trace-3");
        createReqDTO.setBizId(10L);

        operateLogApi.createOperateLog(createReqDTO);

        verify(operateLogService).createOperateLog(createReqDTO);
    }
}
