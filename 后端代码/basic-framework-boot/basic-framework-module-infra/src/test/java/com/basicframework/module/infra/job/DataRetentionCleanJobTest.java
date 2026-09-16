package com.basicframework.module.infra.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.infra.framework.retention.config.InfraDataRetentionProperties;
import com.basicframework.module.infra.job.job.JobLogCleanJob;
import com.basicframework.module.infra.job.logger.AccessLogCleanJob;
import com.basicframework.module.infra.job.logger.ErrorLogCleanJob;
import com.basicframework.module.infra.service.job.JobLogService;
import com.basicframework.module.infra.service.logger.ApiAccessLogService;
import com.basicframework.module.infra.service.logger.ApiErrorLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DataRetentionCleanJobTest {

    @InjectMocks
    private AccessLogCleanJob accessLogCleanJob;

    @InjectMocks
    private ErrorLogCleanJob errorLogCleanJob;

    @InjectMocks
    private JobLogCleanJob jobLogCleanJob;

    @Mock
    private ApiAccessLogService apiAccessLogService;

    @Mock
    private ApiErrorLogService apiErrorLogService;

    @Mock
    private JobLogService jobLogService;

    @Mock
    private InfraDataRetentionProperties properties;

    @BeforeEach
    void setUp() {
        when(properties.getAccessLogDays()).thenReturn(14);
        when(properties.getErrorLogDays()).thenReturn(14);
        when(properties.getJobLogDays()).thenReturn(14);
        when(properties.getBatchSize()).thenReturn(100);
        when(properties.getMaxBatches()).thenReturn(100);
    }

    @Test
    void jobsDelegateValidatedRetentionSettingsAndReportCounts() {
        when(apiAccessLogService.cleanAccessLog(14, 100, 100)).thenReturn(3);
        when(apiErrorLogService.cleanErrorLog(14, 100, 100)).thenReturn(2);
        when(jobLogService.cleanJobLog(14, 100, 100)).thenReturn(1);

        assertThat(accessLogCleanJob.execute("")).contains("3 个");
        assertThat(errorLogCleanJob.execute("")).contains("2 个");
        assertThat(jobLogCleanJob.execute("")).contains("1 个");
        verify(apiAccessLogService).cleanAccessLog(14, 100, 100);
        verify(apiErrorLogService).cleanErrorLog(14, 100, 100);
        verify(jobLogService).cleanJobLog(14, 100, 100);
    }
}
