package com.basicframework.framework.quartz.core.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.quartz.core.enums.JobDataKeyEnum;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.CronTrigger;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;

@ExtendWith(MockitoExtension.class)
class SchedulerManagerTest {

    private static final String HANDLER_NAME = "sampleJob";

    @Mock
    private Scheduler scheduler;

    private SchedulerManager schedulerManager;

    @BeforeEach
    void setUp() {
        schedulerManager = new SchedulerManager(scheduler);
    }

    @Test
    void addJob_persistsWithoutRecoveryAndSkipsMissedCronFirings() throws Exception {
        when(scheduler.scheduleJob(any(JobDetail.class), any(Trigger.class))).thenReturn(new Date());

        schedulerManager.addJob(1L, HANDLER_NAME, "param", "0 0 0 * * ?", 2, 1000);

        ArgumentCaptor<JobDetail> jobCaptor = ArgumentCaptor.forClass(JobDetail.class);
        ArgumentCaptor<Trigger> triggerCaptor = ArgumentCaptor.forClass(Trigger.class);
        verify(scheduler).scheduleJob(jobCaptor.capture(), triggerCaptor.capture());

        JobDetail jobDetail = jobCaptor.getValue();
        assertThat(jobDetail.requestsRecovery()).isFalse();
        assertThat(jobDetail.isConcurrentExecutionDisallowed()).isTrue();
        assertThat(jobDetail.getJobDataMap().getLong(JobDataKeyEnum.JOB_ID.name()))
                .isEqualTo(1L);

        CronTrigger trigger = (CronTrigger) triggerCaptor.getValue();
        assertThat(trigger.getMisfireInstruction()).isEqualTo(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);
        assertThat(trigger.getJobDataMap().getString(JobDataKeyEnum.JOB_HANDLER_PARAM.name()))
                .isEqualTo("param");
    }

    @Test
    void jobExists_delegatesToQuartz() throws Exception {
        when(scheduler.checkExists(new JobKey(HANDLER_NAME))).thenReturn(true);

        assertThat(schedulerManager.jobExists(HANDLER_NAME)).isTrue();
    }

    @Test
    void updateJob_replacesTriggerWithConfiguredRetryDataAndMisfirePolicy() throws Exception {
        when(scheduler.rescheduleJob(any(), any(Trigger.class))).thenReturn(new Date());

        schedulerManager.updateJob(HANDLER_NAME, "updated", "0 5 0 * * ?", 3, 2000);

        ArgumentCaptor<Trigger> triggerCaptor = ArgumentCaptor.forClass(Trigger.class);
        verify(scheduler).rescheduleJob(eq(new org.quartz.TriggerKey(HANDLER_NAME)), triggerCaptor.capture());
        CronTrigger trigger = (CronTrigger) triggerCaptor.getValue();
        assertThat(trigger.getJobDataMap().getString(JobDataKeyEnum.JOB_HANDLER_PARAM.name()))
                .isEqualTo("updated");
        assertThat(trigger.getJobDataMap().getInt(JobDataKeyEnum.JOB_RETRY_COUNT.name()))
                .isEqualTo(3);
        assertThat(trigger.getJobDataMap().getInt(JobDataKeyEnum.JOB_RETRY_INTERVAL.name()))
                .isEqualTo(2000);
        assertThat(trigger.getMisfireInstruction()).isEqualTo(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);
    }

    @Test
    void deletePauseAndResumeJob_useMatchingQuartzKeysInLifecycleOrder() throws Exception {
        schedulerManager.deleteJob(HANDLER_NAME);
        schedulerManager.pauseJob(HANDLER_NAME);
        schedulerManager.resumeJob(HANDLER_NAME);

        InOrder deletionOrder = inOrder(scheduler);
        deletionOrder.verify(scheduler).pauseTrigger(new org.quartz.TriggerKey(HANDLER_NAME));
        deletionOrder.verify(scheduler).unscheduleJob(new org.quartz.TriggerKey(HANDLER_NAME));
        deletionOrder.verify(scheduler).deleteJob(new JobKey(HANDLER_NAME));
        verify(scheduler).pauseJob(new JobKey(HANDLER_NAME));
        verify(scheduler).resumeJob(new JobKey(HANDLER_NAME));
        verify(scheduler).resumeTrigger(new org.quartz.TriggerKey(HANDLER_NAME));
    }

    @Test
    void triggerJob_includesEveryHandlerInputInQuartzData() throws Exception {
        schedulerManager.triggerJob(2L, HANDLER_NAME, "manual");

        ArgumentCaptor<org.quartz.JobDataMap> dataCaptor = ArgumentCaptor.forClass(org.quartz.JobDataMap.class);
        verify(scheduler).triggerJob(eq(new JobKey(HANDLER_NAME)), dataCaptor.capture());
        assertThat(dataCaptor.getValue().getLong(JobDataKeyEnum.JOB_ID.name())).isEqualTo(2L);
        assertThat(dataCaptor.getValue().getString(JobDataKeyEnum.JOB_HANDLER_NAME.name()))
                .isEqualTo(HANDLER_NAME);
        assertThat(dataCaptor.getValue().getString(JobDataKeyEnum.JOB_HANDLER_PARAM.name()))
                .isEqualTo("manual");
    }

    @Test
    void missingScheduler_failsLoudlyInsteadOfSilentlyDroppingJobs() {
        SchedulerManager disabledSchedulerManager = new SchedulerManager(null);

        assertThatThrownBy(() -> disabledSchedulerManager.jobExists(HANDLER_NAME))
                .hasMessageContaining("定时任务已禁用");
    }
}
