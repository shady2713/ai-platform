package com.basicframework.module.infra.framework.job;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.basicframework.module.infra.service.job.JobService;
import org.junit.jupiter.api.Test;
import org.quartz.SchedulerException;

/** {@link JobSchedulerSyncExecutor} 全量同步委托测试。 */
class JobSchedulerSyncExecutorTest {

    private final JobService jobService = mock(JobService.class);
    private final JobSchedulerSyncExecutor executor = new JobSchedulerSyncExecutor(jobService);

    @Test
    void syncAllJobs_delegatesToJobServiceSyncJob() throws Exception {
        executor.syncAllJobs();

        verify(jobService).syncJob();
    }

    @Test
    void syncAllJobs_propagatesSchedulerException() throws Exception {
        doThrow(new SchedulerException("quartz 不可用")).when(jobService).syncJob();

        org.assertj.core.api.Assertions.assertThatThrownBy(executor::syncAllJobs)
                .isInstanceOf(SchedulerException.class);
    }
}
