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
import com.basicframework.module.infra.controller.admin.job.vo.job.JobPageReqVO;
import com.basicframework.module.infra.controller.admin.job.vo.job.JobRespVO;
import com.basicframework.module.infra.controller.admin.job.vo.job.JobSaveReqVO;
import com.basicframework.module.infra.dal.dataobject.job.JobDO;
import com.basicframework.module.infra.service.job.JobService;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;

class JobControllerTest {

    private final JobService jobService = mock(JobService.class);
    private final JobController controller = new JobController(jobService);

    @Test
    void privilegedMutationsMapRequestsAndDelegateExactTargets() throws Exception {
        JobSaveReqVO request = saveRequest();
        when(jobService.createJob(any(JobDO.class))).thenReturn(9L);

        assertThat(controller.createJob(request).getData()).isEqualTo(9L);
        assertThat(controller.updateJob(request).getData()).isTrue();
        assertThat(controller.updateJobStatus(7L, 1).getData()).isTrue();
        assertThat(controller.deleteJob(7L).getData()).isTrue();
        assertThat(controller.deleteJobList(List.of(7L, 8L)).getData()).isTrue();
        assertThat(controller.triggerJob(7L).getData()).isTrue();
        assertThat(controller.syncJob().getData()).isTrue();

        ArgumentCaptor<JobDO> jobCaptor = ArgumentCaptor.forClass(JobDO.class);
        verify(jobService).createJob(jobCaptor.capture());
        assertThat(jobCaptor.getValue())
                .extracting(JobDO::getId, JobDO::getName, JobDO::getHandlerName, JobDO::getCronExpression)
                .containsExactly(7L, "清理任务", "cleanupJob", "0 0/5 * * * ?");
        verify(jobService).updateJob(any(JobDO.class));
        verify(jobService).updateJobStatus(7L, 1);
        verify(jobService).deleteJob(7L);
        verify(jobService).deleteJobList(List.of(7L, 8L));
        verify(jobService).triggerJob(7L);
        verify(jobService).syncJob();
    }

    @Test
    void getAndPageReturnMappedDataAndPreserveFilters() {
        JobDO job = job();
        JobPageReqVO request = pageRequest();
        when(jobService.getJob(7L)).thenReturn(job);
        when(jobService.getJobPage(any(), any(), any(), any())).thenReturn(new PageResult<>(List.of(job), 1L));

        JobRespVO detail = controller.getJob(7L).getData();
        PageResult<JobRespVO> page = controller.getJobPage(request).getData();

        assertThat(detail.getName()).isEqualTo("清理任务");
        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getList()).extracting(JobRespVO::getId).containsExactly(7L);
        verify(jobService)
                .getJobPage(
                        same(request),
                        same(request.getName()),
                        same(request.getStatus()),
                        same(request.getHandlerName()));
    }

    @Test
    void exportAppliesHardLimitAndWritesThePublishedSpreadsheet() throws Exception {
        JobPageReqVO request = pageRequest();
        JobDO job = job();
        List<JobRespVO> expectedRows = BeanUtils.toBean(List.of(job), JobRespVO.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(jobService.getJobPage(any(), any(), any(), any())).thenReturn(new PageResult<>(List.of(job), 1L));

        try (MockedStatic<ExcelUtils> excelUtils = mockStatic(ExcelUtils.class)) {
            controller.export(response, request);

            excelUtils.verify(() -> ExcelUtils.write(response, "定时任务.xls", "数据", JobRespVO.class, expectedRows));
        }

        assertThat(request.getPageSize()).isEqualTo(PageParam.EXPORT_MAX_PAGE_SIZE);
        verify(jobService)
                .getJobPage(
                        same(request),
                        same(request.getName()),
                        same(request.getStatus()),
                        same(request.getHandlerName()));
    }

    @Test
    void nextTimesReturnEmptyForMissingJobsAndOrderedResultsForExistingJobs() {
        when(jobService.getJob(404L)).thenReturn(null);
        when(jobService.getJob(7L)).thenReturn(job());

        List<LocalDateTime> missing = controller.getJobNextTimes(404L, 2).getData();
        List<LocalDateTime> existing = controller.getJobNextTimes(7L, 2).getData();

        assertThat(missing).isEmpty();
        assertThat(existing).hasSize(2).isSorted();
    }

    @Test
    void nextTimesEndpointUsesTheCanonicalKebabCaseRoute() throws Exception {
        Method method = JobController.class.getMethod("getJobNextTimes", Long.class, Integer.class);

        assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly("/next-times");
    }

    private static JobSaveReqVO saveRequest() {
        JobSaveReqVO request = new JobSaveReqVO();
        request.setId(7L);
        request.setName("清理任务");
        request.setHandlerName("cleanupJob");
        request.setHandlerParam("scope=expired");
        request.setCronExpression("0 0/5 * * * ?");
        request.setRetryCount(3);
        request.setRetryInterval(1000);
        request.setMonitorTimeout(60000);
        return request;
    }

    private static JobDO job() {
        return new JobDO()
                .setId(7L)
                .setName("清理任务")
                .setHandlerName("cleanupJob")
                .setCronExpression("0 0/5 * * * ?");
    }

    private static JobPageReqVO pageRequest() {
        JobPageReqVO request = new JobPageReqVO();
        request.setName("清理");
        request.setStatus(1);
        request.setHandlerName("cleanupJob");
        return request;
    }
}
