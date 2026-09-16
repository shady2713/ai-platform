package com.basicframework.module.infra.service.job;

import static com.basicframework.module.infra.enums.ErrorCodeConstants.JOB_HANDLER_BEAN_NOT_EXISTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.hutool.extra.spring.SpringUtil;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.quartz.core.handler.JobHandler;
import com.basicframework.framework.quartz.core.scheduler.SchedulerManager;
import com.basicframework.module.infra.dal.dataobject.job.JobDO;
import com.basicframework.module.infra.dal.mysql.job.JobMapper;
import com.basicframework.module.infra.enums.job.JobStatusEnum;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JobServiceImplTest {

    @InjectMocks
    private JobServiceImpl jobService;

    @Mock
    private JobMapper jobMapper;

    @Mock
    private SchedulerManager schedulerManager;

    @Test
    void enableJob_repairsMissingQuartzRecordBeforeResume() throws Exception {
        JobDO job = job(25L, "accessLogCleanJob", JobStatusEnum.STOP.getStatus());
        when(jobMapper.selectById(job.getId())).thenReturn(job);
        when(schedulerManager.jobExists(job.getHandlerName())).thenReturn(false);

        jobService.updateJobStatus(job.getId(), JobStatusEnum.NORMAL.getStatus());

        verify(schedulerManager).addJob(25L, "accessLogCleanJob", "", "0 0 0 * * ?", 3, 0);
        verify(schedulerManager).resumeJob("accessLogCleanJob");
    }

    @Test
    void syncJob_updatesExistingAndCreatesMissingWithoutDeleteWindow() throws Exception {
        JobDO normal = job(25L, "accessLogCleanJob", JobStatusEnum.NORMAL.getStatus());
        JobDO stopped = job(26L, "errorLogCleanJob", JobStatusEnum.STOP.getStatus());
        when(jobMapper.selectList()).thenReturn(List.of(normal, stopped));
        when(schedulerManager.jobExists(normal.getHandlerName())).thenReturn(true);
        when(schedulerManager.jobExists(stopped.getHandlerName())).thenReturn(false);

        jobService.syncJob();

        verify(schedulerManager).updateJob("accessLogCleanJob", "", "0 0 0 * * ?", 3, 0);
        verify(schedulerManager).resumeJob("accessLogCleanJob");
        verify(schedulerManager).addJob(26L, "errorLogCleanJob", "", "0 0 0 * * ?", 3, 0);
        verify(schedulerManager).pauseJob("errorLogCleanJob");
        verify(schedulerManager, never()).deleteJob("accessLogCleanJob");
        verify(schedulerManager, never()).deleteJob("errorLogCleanJob");
    }

    @Test
    void updateJob_repairsMissingQuartzRecord() throws Exception {
        JobDO current = job(25L, "accessLogCleanJob", JobStatusEnum.NORMAL.getStatus());
        JobDO update = job(25L, "accessLogCleanJob", JobStatusEnum.NORMAL.getStatus());
        update.setCronExpression("0 30 0 * * ?");
        when(jobMapper.selectById(current.getId())).thenReturn(current);
        when(schedulerManager.jobExists(current.getHandlerName())).thenReturn(false);

        try (MockedStatic<SpringUtil> springUtil = mockStatic(SpringUtil.class)) {
            springUtil.when(() -> SpringUtil.getBean(current.getHandlerName())).thenReturn(mock(JobHandler.class));

            jobService.updateJob(update);
        }

        verify(jobMapper).updateById(update);
        verify(schedulerManager).addJob(25L, "accessLogCleanJob", "", "0 30 0 * * ?", 3, 0);
        verify(schedulerManager, never()).updateJob("accessLogCleanJob", "", "0 30 0 * * ?", 3, 0);
    }

    @Test
    void updateJob_rebuildsQuartzKeyWhenHandlerChanges() throws Exception {
        JobDO current = job(25L, "accessLogCleanJob", JobStatusEnum.NORMAL.getStatus());
        JobDO update = job(25L, "renamedCleanJob", JobStatusEnum.NORMAL.getStatus());
        when(jobMapper.selectById(current.getId())).thenReturn(current);
        when(jobMapper.selectByHandlerName(update.getHandlerName())).thenReturn(null);

        try (MockedStatic<SpringUtil> springUtil = mockStatic(SpringUtil.class)) {
            springUtil.when(() -> SpringUtil.getBean(update.getHandlerName())).thenReturn(mock(JobHandler.class));

            jobService.updateJob(update);
        }

        verify(schedulerManager).deleteJob("accessLogCleanJob");
        verify(schedulerManager).addJob(25L, "renamedCleanJob", "", "0 0 0 * * ?", 3, 0);
    }

    @Test
    void updateJob_rejectsANullHandlerInsteadOfRelyingOnDisabledAssertions() {
        JobDO current = job(25L, "accessLogCleanJob", JobStatusEnum.NORMAL.getStatus());
        JobDO update = job(25L, "accessLogCleanJob", JobStatusEnum.NORMAL.getStatus());
        when(jobMapper.selectById(current.getId())).thenReturn(current);

        try (MockedStatic<SpringUtil> springUtil = mockStatic(SpringUtil.class)) {
            springUtil.when(() -> SpringUtil.getBean(current.getHandlerName())).thenReturn(null);

            assertThatThrownBy(() -> jobService.updateJob(update))
                    .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                            .isEqualTo(JOB_HANDLER_BEAN_NOT_EXISTS.getCode()));
        }

        verify(jobMapper, never()).updateById(update);
    }

    private static JobDO job(Long id, String handlerName, Integer status) {
        return JobDO.builder()
                .id(id)
                .handlerName(handlerName)
                .handlerParam("")
                .cronExpression("0 0 0 * * ?")
                .retryCount(3)
                .retryInterval(0)
                .status(status)
                .build();
    }
}
