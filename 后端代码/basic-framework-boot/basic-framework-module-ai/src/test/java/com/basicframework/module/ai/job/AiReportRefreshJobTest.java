package com.basicframework.module.ai.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.mysql.report.AiReportRefreshMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.report.refresh.AiReportRefreshService;
import com.basicframework.module.ai.service.report.refresh.dto.AiReportRefreshRequestDTO;
import com.basicframework.module.ai.service.report.refresh.dto.AiReportRefreshResultDTO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** R06 刷新 Job：按节奏扫描到期报表、逐张刷新、单张失败不中断其余报表。 */
class AiReportRefreshJobTest {

    private final AiReportRefreshMapper refreshMapper = mock(AiReportRefreshMapper.class);

    private final AiReportRefreshService refreshService = mock(AiReportRefreshService.class);

    private final AiReportRefreshJob job = new AiReportRefreshJob(refreshMapper, refreshService, 1800, 50);

    private static AiReportRefreshResultDTO result(String status) {
        return new AiReportRefreshResultDTO().setStatus(status);
    }

    @Test
    void scansDueReportsAndCountsOutcomes() {
        when(refreshMapper.selectDueReportIds(1800, 50)).thenReturn(List.of(1L, 2L, 3L));
        when(refreshService.refresh(any(AiReportRefreshRequestDTO.class)))
                .thenReturn(result(AiReportRefreshResultDTO.STATUS_OK))
                .thenReturn(result(AiReportRefreshResultDTO.STATUS_UNCHANGED))
                .thenReturn(result(AiReportRefreshResultDTO.STATUS_FAILED));

        String summary = job.execute("");

        assertThat(summary).contains("扫描 3").contains("刷新 1").contains("未变化 1").contains("失败 1");
        // 作业没有会话身份：只按报表编号刷新，不指定任何身份
        ArgumentCaptor<AiReportRefreshRequestDTO> captor = ArgumentCaptor.forClass(AiReportRefreshRequestDTO.class);
        verify(refreshService, times(3)).refresh(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(AiReportRefreshRequestDTO::getReportId)
                .containsExactly(1L, 2L, 3L);
        assertThat(captor.getAllValues()).allSatisfy(request -> {
            assertThat(request.getRowScope()).isNull();
            assertThat(request.getCreatedByRun()).isNull();
        });
    }

    @Test
    void oneFailingReportDoesNotStopTheRest() {
        when(refreshMapper.selectDueReportIds(anyInt(), anyInt())).thenReturn(List.of(1L, 2L));
        when(refreshService.refresh(any(AiReportRefreshRequestDTO.class)))
                .thenThrow(new ServiceException(AiErrorCodeConstants.AI_REPORT_SCOPE_CHANGED))
                .thenReturn(result(AiReportRefreshResultDTO.STATUS_OK));

        String summary = job.execute("");

        assertThat(summary).contains("扫描 2").contains("刷新 1").contains("失败 1");
        verify(refreshService, times(2)).refresh(any(AiReportRefreshRequestDTO.class));
    }

    @Test
    void noDueReportIsANoOp() {
        when(refreshMapper.selectDueReportIds(anyInt(), anyInt())).thenReturn(List.of());

        String summary = job.execute("");

        assertThat(summary).contains("扫描 0");
        verify(refreshService, never()).refresh(any(AiReportRefreshRequestDTO.class));
    }
}
