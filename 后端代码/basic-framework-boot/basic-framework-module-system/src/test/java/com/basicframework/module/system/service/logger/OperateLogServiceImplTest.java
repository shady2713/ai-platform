package com.basicframework.module.system.service.logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.system.api.logger.dto.OperateLogCreateReqDTO;
import com.basicframework.module.system.dal.dataobject.logger.OperateLogDO;
import com.basicframework.module.system.dal.mysql.logger.OperateLogMapper;
import com.basicframework.module.system.dal.mysql.logger.OperateLogQuery;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link OperateLogServiceImpl} 单元测试
 *
 */
@ExtendWith(MockitoExtension.class)
class OperateLogServiceImplTest {

    @InjectMocks
    private OperateLogServiceImpl operateLogService;

    @Mock
    private OperateLogMapper operateLogMapper;

    @Test
    void createOperateLog_mapsDtoFieldsAndInserts() {
        OperateLogCreateReqDTO reqDTO = new OperateLogCreateReqDTO();
        reqDTO.setTraceId("trace-1");
        reqDTO.setUserId(1L);
        reqDTO.setUserType(2);
        reqDTO.setType("system:user");
        reqDTO.setSubType("create");
        reqDTO.setBizId(10L);
        reqDTO.setAction("创建用户");
        reqDTO.setRequestMethod("POST");
        reqDTO.setRequestUrl("/admin-api/system/user/create");

        operateLogService.createOperateLog(reqDTO);

        ArgumentCaptor<OperateLogDO> captor = ArgumentCaptor.forClass(OperateLogDO.class);
        verify(operateLogMapper).insert(captor.capture());
        OperateLogDO saved = captor.getValue();
        assertThat(saved.getTraceId()).isEqualTo("trace-1");
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getUserType()).isEqualTo(2);
        assertThat(saved.getType()).isEqualTo("system:user");
        assertThat(saved.getSubType()).isEqualTo("create");
        assertThat(saved.getBizId()).isEqualTo(10L);
        assertThat(saved.getAction()).isEqualTo("创建用户");
        assertThat(saved.getRequestMethod()).isEqualTo("POST");
        assertThat(saved.getRequestUrl()).isEqualTo("/admin-api/system/user/create");
    }

    @Test
    void getOperateLog_delegatesToMapper() {
        OperateLogDO operateLog = new OperateLogDO();
        operateLog.setId(7L);
        when(operateLogMapper.selectById(7L)).thenReturn(operateLog);

        assertThat(operateLogService.getOperateLog(7L)).isSameAs(operateLog);
    }

    @Test
    void getOperateLogPage_delegatesToMapper() {
        PageResult<OperateLogDO> pageResult = new PageResult<>(List.of(new OperateLogDO()), 1L);
        OperateLogQuery query = new OperateLogQuery(1L, 10L, "system:user", "create", null, null);
        when(operateLogMapper.selectPage(any(PageParam.class), any(OperateLogQuery.class)))
                .thenReturn(pageResult);

        assertThat(operateLogService.getOperateLogPage(new PageParam(), query)).isSameAs(pageResult);
    }
}
