package com.basicframework.module.system.service.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.system.dal.mysql.logger.LoginLogMapper;
import com.basicframework.module.system.dal.mysql.logger.OperateLogMapper;
import com.basicframework.module.system.dal.mysql.notify.NotifyMessageMapper;
import com.basicframework.module.system.dal.mysql.session.UserSessionMapper;
import com.basicframework.module.system.dal.mysql.sms.SmsCodeMapper;
import com.basicframework.module.system.dal.mysql.sms.SmsLogMapper;
import com.basicframework.module.system.framework.retention.config.SystemDataRetentionProperties;
import com.basicframework.module.system.service.retention.SystemDataRetentionService.CleanupResult;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SystemDataRetentionServiceTest {

    @InjectMocks
    private SystemDataRetentionService service;

    @Mock
    private LoginLogMapper loginLogMapper;

    @Mock
    private OperateLogMapper operateLogMapper;

    @Mock
    private SmsLogMapper smsLogMapper;

    @Mock
    private SmsCodeMapper smsCodeMapper;

    @Mock
    private NotifyMessageMapper notifyMessageMapper;

    @Mock
    private UserSessionMapper userSessionMapper;

    @Mock
    private SystemDataRetentionProperties properties;

    @BeforeEach
    void setUp() {
        when(properties.getLoginLogDays()).thenReturn(180);
        when(properties.getOperateLogDays()).thenReturn(180);
        when(properties.getSmsLogDays()).thenReturn(30);
        when(properties.getSmsCodeDays()).thenReturn(7);
        when(properties.getReadNotifyMessageDays()).thenReturn(90);
        when(properties.getBatchSize()).thenReturn(500);
        when(properties.getMaxBatches()).thenReturn(20);
    }

    @Test
    void cleanExpiredData_deletesEachCategoryInBoundedBatches() {
        when(loginLogMapper.deleteByCreateTimeLt(any(), eq(500))).thenReturn(500, 12);
        when(operateLogMapper.deleteByCreateTimeLt(any(), eq(500))).thenReturn(3);
        when(smsLogMapper.deleteByCreateTimeLt(any(), eq(500))).thenReturn(0);
        when(smsCodeMapper.deleteByCreateTimeLt(any(), eq(500))).thenReturn(4);
        when(notifyMessageMapper.deleteReadByCreateTimeLt(any(), eq(500))).thenReturn(7);
        when(userSessionMapper.deleteExpired(any(), eq(500))).thenReturn(2);

        CleanupResult result = service.cleanExpiredData();

        assertThat(result).isEqualTo(new CleanupResult(512, 3, 0, 4, 7, 2));
        assertThat(result.summary()).isEqualTo("登录日志 512，操作日志 3，短信日志 0，短信验证码 4，已读站内信 7，过期会话 2");
        verify(loginLogMapper, times(2)).deleteByCreateTimeLt(any(LocalDateTime.class), eq(500));
        verify(operateLogMapper).deleteByCreateTimeLt(any(LocalDateTime.class), eq(500));
        verify(smsLogMapper).deleteByCreateTimeLt(any(LocalDateTime.class), eq(500));
        verify(smsCodeMapper).deleteByCreateTimeLt(any(LocalDateTime.class), eq(500));
        verify(notifyMessageMapper).deleteReadByCreateTimeLt(any(LocalDateTime.class), eq(500));
        verify(userSessionMapper).deleteExpired(any(LocalDateTime.class), eq(500));
    }

    @Test
    void cleanExpiredData_stopsAtConfiguredBatchLimit() {
        when(properties.getMaxBatches()).thenReturn(2);
        when(loginLogMapper.deleteByCreateTimeLt(any(), eq(500))).thenReturn(500);
        when(operateLogMapper.deleteByCreateTimeLt(any(), eq(500))).thenReturn(0);
        when(smsLogMapper.deleteByCreateTimeLt(any(), eq(500))).thenReturn(0);
        when(notifyMessageMapper.deleteReadByCreateTimeLt(any(), eq(500))).thenReturn(0);
        when(userSessionMapper.deleteExpired(any(), eq(500))).thenReturn(0);

        CleanupResult result = service.cleanExpiredData();

        assertThat(result.loginLogs()).isEqualTo(1000);
        verify(loginLogMapper, times(2)).deleteByCreateTimeLt(any(LocalDateTime.class), eq(500));
    }
}
