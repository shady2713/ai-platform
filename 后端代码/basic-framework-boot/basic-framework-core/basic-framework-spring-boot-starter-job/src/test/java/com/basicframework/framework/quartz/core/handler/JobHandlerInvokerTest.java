package com.basicframework.framework.quartz.core.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.basicframework.framework.quartz.core.enums.JobDataKeyEnum;
import com.basicframework.framework.quartz.core.service.JobLogFrameworkService;
import java.time.LocalDateTime;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.impl.triggers.SimpleTriggerImpl;
import org.quartz.spi.TriggerFiredBundle;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.quartz.SpringBeanJobFactory;

class JobHandlerInvokerTest {

    private static final long JOB_ID = 7L;
    private static final long JOB_LOG_ID = 13L;
    private static final String HANDLER_NAME = "sampleJob";
    private static final String HANDLER_PARAM = "payload";

    private final ApplicationContext applicationContext = mock(ApplicationContext.class);
    private final JobLogFrameworkService jobLogFrameworkService = mock(JobLogFrameworkService.class);
    private final JobHandler jobHandler = mock(JobHandler.class);

    private JobHandlerInvoker invoker;

    @BeforeEach
    void setUp() {
        invoker = new JobHandlerInvoker(applicationContext, jobLogFrameworkService);
        when(applicationContext.getBean(HANDLER_NAME, JobHandler.class)).thenReturn(jobHandler);
        when(jobLogFrameworkService.createJobLog(
                        eq(JOB_ID), any(LocalDateTime.class), eq(HANDLER_NAME), eq(HANDLER_PARAM), anyInt()))
                .thenReturn(JOB_LOG_ID);
    }

    @Test
    void executeInternal_successPersistsObservableResult() throws Exception {
        when(jobHandler.execute(HANDLER_PARAM)).thenReturn("completed");

        invoker.executeInternal(executionContext(0, 0, 0));

        verify(jobLogFrameworkService)
                .updateJobLogResultAsync(eq(JOB_LOG_ID), any(LocalDateTime.class), anyInt(), eq(true), eq("completed"));
    }

    @Test
    void executeInternal_terminalFailurePersistsOnlyExceptionTypeAndStopsRetrying() throws Exception {
        IllegalStateException failure = new IllegalStateException("provider failed");
        when(jobHandler.execute(HANDLER_PARAM)).thenThrow(failure);

        assertThatThrownBy(() -> invoker.executeInternal(executionContext(0, 0, 0)))
                .isInstanceOf(JobExecutionException.class)
                .hasCause(failure)
                .satisfies(exception -> assertThat(((JobExecutionException) exception).refireImmediately())
                        .isFalse());
        verify(jobLogFrameworkService)
                .updateJobLogResultAsync(
                        eq(JOB_LOG_ID),
                        any(LocalDateTime.class),
                        anyInt(),
                        eq(false),
                        eq(IllegalStateException.class.getName()));
    }

    @Test
    void executeInternal_retryableFailureRequestsImmediateRefire() throws Exception {
        IllegalStateException failure = new IllegalStateException("transient failure");
        when(jobHandler.execute(HANDLER_PARAM)).thenThrow(failure);

        assertThatThrownBy(() -> invoker.executeInternal(executionContext(0, 1, 0)))
                .isInstanceOf(JobExecutionException.class)
                .hasCause(failure)
                .satisfies(exception -> assertThat(((JobExecutionException) exception).refireImmediately())
                        .isTrue());
    }

    @Test
    void executeInternal_neverConvertsJvmErrorsIntoRetryableJobFailures() throws Exception {
        AssertionError fatalError = new AssertionError("jvm invariant broken");
        when(jobHandler.execute(HANDLER_PARAM)).thenThrow(fatalError);

        assertThatThrownBy(() -> invoker.executeInternal(executionContext(0, 1, 0)))
                .isSameAs(fatalError);
        verify(jobLogFrameworkService, never()).updateJobLogResultAsync(any(), any(), anyInt(), anyBoolean(), any());
    }

    @Test
    void executeInternal_logUpdateFailureDoesNotLeakJobResult() throws Exception {
        String sensitiveResult = "private-job-result";
        String sensitiveFailure = "private-log-storage-detail";
        when(jobHandler.execute(HANDLER_PARAM)).thenReturn(sensitiveResult);
        doThrow(new IllegalStateException(sensitiveFailure))
                .when(jobLogFrameworkService)
                .updateJobLogResultAsync(
                        eq(JOB_LOG_ID), any(LocalDateTime.class), anyInt(), eq(true), eq(sensitiveResult));
        Logger logger = (Logger) LoggerFactory.getLogger(JobHandlerInvoker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            invoker.executeInternal(executionContext(0, 0, 0));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list)
                .isNotEmpty()
                .allMatch(event -> event.getThrowableProxy() == null)
                .allMatch(event -> !event.getFormattedMessage().contains(sensitiveResult))
                .allMatch(event -> !event.getFormattedMessage().contains(sensitiveFailure))
                .anyMatch(event -> event.getFormattedMessage().contains(IllegalStateException.class.getName()));
    }

    @Test
    void springBeanJobFactoryCreatesConstructorInjectedInvoker() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JobLogFrameworkService.class, () -> jobLogFrameworkService);
            context.refresh();
            ExposedSpringBeanJobFactory jobFactory = new ExposedSpringBeanJobFactory();
            jobFactory.setApplicationContext(context);
            JobDetail detail = JobBuilder.newJob(JobHandlerInvoker.class)
                    .withIdentity("constructor-job")
                    .build();
            SimpleTriggerImpl trigger = new SimpleTriggerImpl("constructor-trigger");
            Date now = new Date();
            TriggerFiredBundle bundle = new TriggerFiredBundle(detail, trigger, null, false, now, now, null, null);

            assertThat(jobFactory.create(bundle)).isInstanceOf(JobHandlerInvoker.class);
        }
    }

    private static JobExecutionContext executionContext(int refireCount, int retryCount, int retryInterval) {
        JobDataMap data = new JobDataMap();
        data.put(JobDataKeyEnum.JOB_ID.name(), JOB_ID);
        data.put(JobDataKeyEnum.JOB_HANDLER_NAME.name(), HANDLER_NAME);
        data.put(JobDataKeyEnum.JOB_HANDLER_PARAM.name(), HANDLER_PARAM);
        data.put(JobDataKeyEnum.JOB_RETRY_COUNT.name(), retryCount);
        data.put(JobDataKeyEnum.JOB_RETRY_INTERVAL.name(), retryInterval);
        JobExecutionContext context = mock(JobExecutionContext.class);
        when(context.getMergedJobDataMap()).thenReturn(data);
        when(context.getRefireCount()).thenReturn(refireCount);
        when(context.getJobDetail())
                .thenReturn(JobBuilder.newJob(JobHandlerInvoker.class)
                        .withIdentity("sample-invocation")
                        .build());
        return context;
    }

    private static final class ExposedSpringBeanJobFactory extends SpringBeanJobFactory {

        private Object create(TriggerFiredBundle bundle) throws Exception {
            return createJobInstance(bundle);
        }
    }
}
