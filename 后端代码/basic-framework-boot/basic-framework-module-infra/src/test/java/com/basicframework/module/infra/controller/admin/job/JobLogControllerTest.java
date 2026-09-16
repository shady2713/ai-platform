package com.basicframework.module.infra.controller.admin.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.framework.excel.core.util.ExcelUtils;
import com.basicframework.module.infra.controller.admin.job.vo.log.JobLogPageReqVO;
import com.basicframework.module.infra.controller.admin.job.vo.log.JobLogRespVO;
import com.basicframework.module.infra.dal.dataobject.job.JobLogDO;
import com.basicframework.module.infra.dal.mysql.job.JobLogQuery;
import com.basicframework.module.infra.service.job.JobLogService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletResponse;

class JobLogControllerTest {

    private final JobLogService jobLogService = mock(JobLogService.class);
    private final JobLogController controller = new JobLogController(jobLogService);

    @Test
    void getAndPageReturnMappedDataAndPreserveEveryFilter() {
        JobLogDO log = log();
        JobLogPageReqVO request = pageRequest();
        when(jobLogService.getJobLog(11L)).thenReturn(log);
        when(jobLogService.getJobLogPage(any(), any())).thenReturn(new PageResult<>(List.of(log), 1L));

        JobLogRespVO detail = controller.getJobLog(11L).getData();
        PageResult<JobLogRespVO> page = controller.getJobLogPage(request).getData();

        assertThat(detail.getJobId()).isEqualTo(7L);
        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getList()).extracting(JobLogRespVO::getId).containsExactly(11L);
        ArgumentCaptor<JobLogQuery> queryCaptor = ArgumentCaptor.forClass(JobLogQuery.class);
        verify(jobLogService).getJobLogPage(same(request), queryCaptor.capture());
        assertQueryMatchesRequest(queryCaptor.getValue(), request);
    }

    @Test
    void exportAppliesHardLimitAndWritesThePublishedSpreadsheet() throws Exception {
        JobLogPageReqVO request = pageRequest();
        JobLogDO log = log();
        List<JobLogRespVO> expectedRows = BeanUtils.toBean(List.of(log), JobLogRespVO.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(jobLogService.getJobLogPage(any(), any())).thenReturn(new PageResult<>(List.of(log), 1L));

        try (MockedStatic<ExcelUtils> excelUtils = mockStatic(ExcelUtils.class)) {
            controller.export(response, request);

            excelUtils.verify(() -> ExcelUtils.write(response, "任务日志.xls", "数据", JobLogRespVO.class, expectedRows));
        }

        assertThat(request.getPageSize()).isEqualTo(PageParam.EXPORT_MAX_PAGE_SIZE);
        ArgumentCaptor<JobLogQuery> queryCaptor = ArgumentCaptor.forClass(JobLogQuery.class);
        verify(jobLogService).getJobLogPage(same(request), queryCaptor.capture());
        assertQueryMatchesRequest(queryCaptor.getValue(), request);
    }

    private static JobLogDO log() {
        return new JobLogDO()
                .setId(11L)
                .setJobId(7L)
                .setHandlerName("cleanupJob")
                .setStatus(1);
    }

    private static JobLogPageReqVO pageRequest() {
        JobLogPageReqVO request = new JobLogPageReqVO();
        request.setJobId(7L);
        request.setHandlerName("cleanupJob");
        request.setBeginTime(LocalDateTime.now().minusHours(1));
        request.setEndTime(LocalDateTime.now());
        request.setStatus(1);
        return request;
    }

    private static void assertQueryMatchesRequest(JobLogQuery query, JobLogPageReqVO request) {
        assertThat(query)
                .extracting(
                        JobLogQuery::getJobId,
                        JobLogQuery::getHandlerName,
                        JobLogQuery::getBeginTime,
                        JobLogQuery::getEndTime,
                        JobLogQuery::getStatus)
                .containsExactly(7L, "cleanupJob", request.getBeginTime(), request.getEndTime(), 1);
    }
}
