package com.basicframework.module.infra.service.logger;

import static com.basicframework.module.infra.dal.dataobject.logger.ApiAccessLogDO.REQUEST_PARAMS_MAX_LENGTH;
import static com.basicframework.module.infra.dal.dataobject.logger.ApiAccessLogDO.RESULT_MSG_MAX_LENGTH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.infra.api.logger.dto.ApiAccessLogCreateReqDTO;
import com.basicframework.module.infra.dal.dataobject.logger.ApiAccessLogDO;
import com.basicframework.module.infra.dal.mysql.logger.ApiAccessLogMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * API 访问日志 Service 单元测试
 *
 * 覆盖日志写入的两处长度截断（请求参数/响应消息，与 DO 常量一致）
 * 与清理的分批删除语义（不足一批即停）。
 */
@ExtendWith(MockitoExtension.class)
class ApiAccessLogServiceImplTest {

    @InjectMocks
    private ApiAccessLogServiceImpl apiAccessLogService;

    @Mock
    private ApiAccessLogMapper apiAccessLogMapper;

    @Test
    void create_truncatesOverlongFieldsToDoConstants() {
        ApiAccessLogCreateReqDTO dto = new ApiAccessLogCreateReqDTO();
        dto.setRequestParams("x".repeat(REQUEST_PARAMS_MAX_LENGTH + 100));
        dto.setResultMsg("y".repeat(RESULT_MSG_MAX_LENGTH + 100));

        apiAccessLogService.createApiAccessLog(dto);

        ArgumentCaptor<ApiAccessLogDO> captor = ArgumentCaptor.forClass(ApiAccessLogDO.class);
        verify(apiAccessLogMapper).insert(captor.capture());
        ApiAccessLogDO saved = captor.getValue();
        assertThat(saved.getRequestParams()).hasSize(REQUEST_PARAMS_MAX_LENGTH);
        assertThat(saved.getResultMsg()).hasSize(RESULT_MSG_MAX_LENGTH);
        // hutool maxLength 截断会补 "...", 断言尾部省略符
        assertThat(saved.getRequestParams()).endsWith("...");
    }

    @Test
    void create_keepsShortValuesAsIs() {
        ApiAccessLogCreateReqDTO dto = new ApiAccessLogCreateReqDTO();
        dto.setRequestParams("short");
        dto.setResultMsg("ok");

        apiAccessLogService.createApiAccessLog(dto);

        ArgumentCaptor<ApiAccessLogDO> captor = ArgumentCaptor.forClass(ApiAccessLogDO.class);
        verify(apiAccessLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getRequestParams()).isEqualTo("short");
        assertThat(captor.getValue().getResultMsg()).isEqualTo("ok");
    }

    @Test
    void create_handlesNullFields() {
        ApiAccessLogCreateReqDTO dto = new ApiAccessLogCreateReqDTO();
        dto.setRequestParams(null);
        dto.setResultMsg(null);

        apiAccessLogService.createApiAccessLog(dto);

        verify(apiAccessLogMapper).insert(any(ApiAccessLogDO.class));
    }

    @Test
    void clean_deletesInBatchesUntilShortfall() {
        int deleteLimit = 300;
        when(apiAccessLogMapper.deleteByCreateTimeLt(any(LocalDateTime.class), eq(deleteLimit)))
                .thenReturn(deleteLimit, deleteLimit, 200);

        int deleted = apiAccessLogService.cleanAccessLog(30, deleteLimit, 10);

        // 两批整批（300+300）后第三批 200 < limit，停止
        assertThat(deleted).isEqualTo(800);
        verify(apiAccessLogMapper, org.mockito.Mockito.times(3))
                .deleteByCreateTimeLt(any(LocalDateTime.class), eq(deleteLimit));
    }

    @Test
    void clean_stopsImmediatelyWhenNoRecords() {
        when(apiAccessLogMapper.deleteByCreateTimeLt(any(LocalDateTime.class), eq(300)))
                .thenReturn(0);

        int deleted = apiAccessLogService.cleanAccessLog(30, 300, 10);

        assertThat(deleted).isZero();
        verify(apiAccessLogMapper, org.mockito.Mockito.times(1))
                .deleteByCreateTimeLt(any(LocalDateTime.class), eq(300));
    }

    @Test
    void clean_stopsAtConfiguredBatchLimit() {
        when(apiAccessLogMapper.deleteByCreateTimeLt(any(LocalDateTime.class), eq(300)))
                .thenReturn(300);

        int deleted = apiAccessLogService.cleanAccessLog(30, 300, 2);

        assertThat(deleted).isEqualTo(600);
        verify(apiAccessLogMapper, org.mockito.Mockito.times(2))
                .deleteByCreateTimeLt(any(LocalDateTime.class), eq(300));
    }
}
