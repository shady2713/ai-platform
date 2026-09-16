package com.basicframework.module.system.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.system.service.retention.SystemDataRetentionService;
import com.basicframework.module.system.service.retention.SystemDataRetentionService.CleanupResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link SystemDataRetentionCleanJob} 单元测试
 *
 */
@ExtendWith(MockitoExtension.class)
class SystemDataRetentionCleanJobTest {

    @InjectMocks
    private SystemDataRetentionCleanJob job;

    @Mock
    private SystemDataRetentionService retentionService;

    @Test
    void execute_reportsCleanupSummaryFromService() {
        when(retentionService.cleanExpiredData()).thenReturn(new CleanupResult(10, 5, 3, 2, 1, 8));

        String summary = job.execute("");

        assertThat(summary)
                .contains("登录日志 10")
                .contains("操作日志 5")
                .contains("短信日志 3")
                .contains("短信验证码 2")
                .contains("已读站内信 1")
                .contains("过期会话 8");
        verify(retentionService).cleanExpiredData();
    }
}
