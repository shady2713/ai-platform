package com.basicframework.module.infra.service.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.infra.dal.dataobject.job.JobLogDO;
import com.basicframework.module.infra.dal.mysql.job.JobLogMapper;
import com.basicframework.module.infra.dal.mysql.job.JobLogQuery;
import com.basicframework.module.infra.enums.job.JobLogStatusEnum;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
class JobLogServiceImplTest {

    private JobLogServiceImpl service;

    @Mock
    private JobLogMapper jobLogMapper;

    @BeforeEach
    void setUp() {
        service = new JobLogServiceImpl(jobLogMapper);
    }

    @Test
    void createJobLog_persistsRunningLogAndReturnsGeneratedId() {
        LocalDateTime beginTime = LocalDateTime.of(2026, 8, 28, 12, 0);
        when(jobLogMapper.insert(any(JobLogDO.class))).thenAnswer(invocation -> {
            JobLogDO jobLog = invocation.getArgument(0);
            jobLog.setId(42L);
            return 1;
        });

        Long id = service.createJobLog(7L, beginTime, "cleanupJob", "{}", 2);

        assertThat(id).isEqualTo(42L);
        ArgumentCaptor<JobLogDO> captor = ArgumentCaptor.forClass(JobLogDO.class);
        verify(jobLogMapper).insert(captor.capture());
        assertThat(captor.getValue())
                .extracting(
                        JobLogDO::getJobId,
                        JobLogDO::getBeginTime,
                        JobLogDO::getHandlerName,
                        JobLogDO::getHandlerParam,
                        JobLogDO::getExecuteIndex,
                        JobLogDO::getStatus)
                .containsExactly(7L, beginTime, "cleanupJob", "{}", 2, JobLogStatusEnum.RUNNING.getStatus());
    }

    @Test
    void updateJobLogResultAsync_persistsSuccessfulResult() {
        LocalDateTime endTime = LocalDateTime.of(2026, 8, 28, 12, 1);

        service.updateJobLogResultAsync(9L, endTime, 60, true, "done");

        ArgumentCaptor<JobLogDO> captor = ArgumentCaptor.forClass(JobLogDO.class);
        verify(jobLogMapper).updateById(captor.capture());
        assertThat(captor.getValue())
                .extracting(
                        JobLogDO::getId,
                        JobLogDO::getEndTime,
                        JobLogDO::getDuration,
                        JobLogDO::getStatus,
                        JobLogDO::getResult)
                .containsExactly(9L, endTime, 60, JobLogStatusEnum.SUCCESS.getStatus(), "done");
    }

    @Test
    void cleanJobLog_stopsAfterPartialBatchAndReturnsDeletedCount() {
        when(jobLogMapper.deleteByCreateTimeLt(any(LocalDateTime.class), any(Integer.class)))
                .thenReturn(10, 10, 3);

        Integer deleted = service.cleanJobLog(30, 10, 5);

        assertThat(deleted).isEqualTo(23);
        verify(jobLogMapper, times(3)).deleteByCreateTimeLt(any(LocalDateTime.class), any(Integer.class));
    }

    @Test
    void getMethods_delegateToMapper() {
        JobLogDO jobLog = JobLogDO.builder().id(3L).build();
        PageParam pageParam = new PageParam();
        JobLogQuery query = new JobLogQuery(null, null, null, null, null);
        PageResult<JobLogDO> page = new PageResult<>(List.of(jobLog), 1L);
        when(jobLogMapper.selectById(3L)).thenReturn(jobLog);
        when(jobLogMapper.selectPage(pageParam, query)).thenReturn(page);

        assertThat(service.getJobLog(3L)).isSameAs(jobLog);
        assertThat(service.getJobLogPage(pageParam, query)).isSameAs(page);
    }

    @Test
    void updateJobLogResultAsync_logsExceptionWithoutExecutionResult() {
        IllegalStateException failure = new IllegalStateException("database unavailable");
        doThrow(failure).when(jobLogMapper).updateById(any(JobLogDO.class));
        Logger logger = (Logger) LoggerFactory.getLogger(JobLogServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        boolean previousAdditive = logger.isAdditive();
        logger.setAdditive(false);
        logger.addAppender(appender);

        try {
            service.updateJobLogResultAsync(1L, LocalDateTime.now(), 10, false, "secret-token");
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(previousAdditive);
            appender.stop();
        }

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage())
                    .contains(IllegalStateException.class.getName())
                    .doesNotContain("secret-token", failure.getMessage());
            assertThat(event.getThrowableProxy()).isNull();
        });
    }
}
