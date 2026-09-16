package com.basicframework.module.infra.framework.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants;
import org.junit.jupiter.api.Test;
import org.quartz.SchedulerException;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;

/** {@link JobStartupRegistrar} 启动注册行为测试：注册、跳过与失败均不得阻断应用启动。 */
class JobStartupRegistrarTest {

    private final JobSchedulerSyncExecutor jobSchedulerSyncExecutor = mock(JobSchedulerSyncExecutor.class);
    private final JobStartupRegistrar registrar = new JobStartupRegistrar(jobSchedulerSyncExecutor);

    @Test
    void onApplicationEvent_registersAllJobsIntoQuartz() throws Exception {
        registrar.onApplicationEvent(mock(ApplicationReadyEvent.class));

        verify(jobSchedulerSyncExecutor).syncAllJobs();
    }

    @Test
    void onApplicationEvent_lockHeldByAnotherNode_skipsQuietly() throws Exception {
        doThrow(new ServiceException(GlobalErrorCodeConstants.LOCKED))
                .when(jobSchedulerSyncExecutor)
                .syncAllJobs();

        assertThatCode(() -> registrar.onApplicationEvent(mock(ApplicationReadyEvent.class)))
                .doesNotThrowAnyException();
    }

    @Test
    void onApplicationEvent_syncFailure_doesNotBlockStartup() throws Exception {
        doThrow(new SchedulerException("调度器写入失败"))
                .when(jobSchedulerSyncExecutor)
                .syncAllJobs();

        assertThatCode(() -> registrar.onApplicationEvent(mock(ApplicationReadyEvent.class)))
                .doesNotThrowAnyException();
    }

    @Test
    void onApplicationEvent_nonLockServiceFailure_isLoggedAsError() throws Exception {
        ServiceException failure = new ServiceException(GlobalErrorCodeConstants.ERROR_CONFIGURATION);
        doThrow(failure).when(jobSchedulerSyncExecutor).syncAllJobs();
        Logger logger = (Logger) LoggerFactory.getLogger(JobStartupRegistrar.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            registrar.onApplicationEvent(mock(ApplicationReadyEvent.class));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage())
                    .contains(ServiceException.class.getName())
                    .doesNotContain(failure.getMessage());
        });
    }
}
