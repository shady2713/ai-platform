package com.basicframework.framework.quartz.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.framework.quartz.core.scheduler.SchedulerManager;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.Scheduler;

class BasicFrameworkQuartzAutoConfigurationTest {

    private final BasicFrameworkQuartzAutoConfiguration autoConfiguration = new BasicFrameworkQuartzAutoConfiguration();

    @Test
    void schedulerManager_usesQuartzWhenAvailableAndFailsLoudlyWhenDisabled() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.checkExists(new JobKey("job"))).thenReturn(true);

        SchedulerManager enabledManager = autoConfiguration.schedulerManager(Optional.of(scheduler));
        SchedulerManager disabledManager = autoConfiguration.schedulerManager(Optional.empty());

        assertThat(enabledManager.jobExists("job")).isTrue();
        assertThatThrownBy(() -> disabledManager.jobExists("job")).hasMessageContaining("定时任务已禁用");
    }
}
