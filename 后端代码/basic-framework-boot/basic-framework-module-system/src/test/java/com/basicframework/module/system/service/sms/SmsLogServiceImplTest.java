package com.basicframework.module.system.service.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.system.dal.dataobject.sms.SmsLogDO;
import com.basicframework.module.system.dal.dataobject.sms.SmsTemplateDO;
import com.basicframework.module.system.dal.mysql.sms.SmsLogMapper;
import com.basicframework.module.system.enums.sms.SmsReceiveStatusEnum;
import com.basicframework.module.system.enums.sms.SmsSendStatusEnum;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SmsLogServiceImplTest {

    private final SmsLogMapper smsLogMapper = mock(SmsLogMapper.class);
    private final SmsLogServiceImpl service = new SmsLogServiceImpl(smsLogMapper);

    @Test
    void createSmsLog_persistsCompleteInitialRecord() {
        SmsTemplateDO template = template();
        when(smsLogMapper.insert(any(SmsLogDO.class))).thenAnswer(invocation -> {
            invocation.<SmsLogDO>getArgument(0).setId(101L);
            return 1;
        });

        Long logId =
                service.createSmsLog("13900000001", 8L, 1, true, template, "您的验证码为 123456", Map.of("code", "123456"));

        assertThat(logId).isEqualTo(101L);
        ArgumentCaptor<SmsLogDO> logCaptor = ArgumentCaptor.forClass(SmsLogDO.class);
        verify(smsLogMapper).insert(logCaptor.capture());
        SmsLogDO savedLog = logCaptor.getValue();
        assertThat(savedLog.getMobile()).isEqualTo("13900000001");
        assertThat(savedLog.getUserId()).isEqualTo(8L);
        assertThat(savedLog.getChannelId()).isEqualTo(template.getChannelId());
        assertThat(savedLog.getChannelCode()).isEqualTo(template.getChannelCode());
        assertThat(savedLog.getTemplateId()).isEqualTo(template.getId());
        assertThat(savedLog.getTemplateCode()).isEqualTo(template.getCode());
        assertThat(savedLog.getTemplateContent()).isEqualTo("您的验证码为 [REDACTED]");
        assertThat(savedLog.getTemplateParams()).containsEntry("code", "[REDACTED]");
        assertThat(savedLog.getSendStatus()).isEqualTo(SmsSendStatusEnum.INIT.getStatus());
        assertThat(savedLog.getReceiveStatus()).isEqualTo(SmsReceiveStatusEnum.INIT.getStatus());
    }

    @Test
    void createSmsLog_preservesNonVerificationTemplateContent() {
        SmsTemplateDO template = template();
        template.setCode("NOTICE");

        service.createSmsLog("13900000001", null, null, true, template, "活动码为 ABC", Map.of("code", "ABC"));

        ArgumentCaptor<SmsLogDO> logCaptor = ArgumentCaptor.forClass(SmsLogDO.class);
        verify(smsLogMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().getTemplateContent()).isEqualTo("活动码为 ABC");
        assertThat(logCaptor.getValue().getTemplateParams()).containsEntry("code", "ABC");
    }

    @Test
    void createSmsLog_whenSendingIsNotExplicitlyEnabled_recordsIgnoredStatus() {
        service.createSmsLog("13900000001", null, null, false, template(), "", Map.of());
        service.createSmsLog("13900000001", null, null, null, template(), "", Map.of());

        ArgumentCaptor<SmsLogDO> logCaptor = ArgumentCaptor.forClass(SmsLogDO.class);
        verify(smsLogMapper, times(2)).insert(logCaptor.capture());
        assertThat(logCaptor.getAllValues())
                .allSatisfy(log -> assertThat(log.getSendStatus()).isEqualTo(SmsSendStatusEnum.IGNORE.getStatus()));
    }

    @Test
    void updateSmsSendResult_mapsBothTerminalStatusesAndProviderFields() {
        service.updateSmsSendResult(10L, true, "OK", "accepted", "request-1", "serial-1");
        service.updateSmsSendResult(11L, false, "FAIL", "rejected", "request-2", "serial-2");
        service.updateSmsSendResult(12L, null, "MALFORMED", "missing success", "request-3", "serial-3");

        ArgumentCaptor<SmsLogDO> logCaptor = ArgumentCaptor.forClass(SmsLogDO.class);
        verify(smsLogMapper, times(3)).updateById(logCaptor.capture());
        SmsLogDO success = logCaptor.getAllValues().get(0);
        SmsLogDO failure = logCaptor.getAllValues().get(1);
        SmsLogDO malformed = logCaptor.getAllValues().get(2);
        assertThat(success.getId()).isEqualTo(10L);
        assertThat(success.getSendStatus()).isEqualTo(SmsSendStatusEnum.SUCCESS.getStatus());
        assertThat(success.getSendTime()).isNotNull();
        assertThat(success.getApiRequestId()).isEqualTo("request-1");
        assertThat(success.getApiSerialNo()).isEqualTo("serial-1");
        assertThat(failure.getId()).isEqualTo(11L);
        assertThat(failure.getSendStatus()).isEqualTo(SmsSendStatusEnum.FAILURE.getStatus());
        assertThat(failure.getApiSendCode()).isEqualTo("FAIL");
        assertThat(failure.getApiSendMsg()).isEqualTo("rejected");
        assertThat(malformed.getSendStatus()).isEqualTo(SmsSendStatusEnum.FAILURE.getStatus());
        assertThat(malformed.getApiSendCode()).isEqualTo("MALFORMED");
    }

    @Test
    void updateReceiveResultUsesProviderCorrelation() {
        LocalDateTime receiveTime = LocalDateTime.of(2026, 8, 30, 10, 0);
        when(smsLogMapper.updateReceiveResult(any(), eq(null), eq("aliyun"), eq("serial-1")))
                .thenReturn(1);

        boolean updated = service.updateSmsReceiveResult(
                new SmsReceiveResultCommand("aliyun", null, "serial-1", true, receiveTime, null, null));

        assertThat(updated).isTrue();
        ArgumentCaptor<SmsLogDO> logCaptor = ArgumentCaptor.forClass(SmsLogDO.class);
        verify(smsLogMapper).updateReceiveResult(logCaptor.capture(), eq(null), eq("aliyun"), eq("serial-1"));
        SmsLogDO updatedLog = logCaptor.getValue();
        assertThat(updatedLog.getReceiveStatus()).isEqualTo(SmsReceiveStatusEnum.SUCCESS.getStatus());
        assertThat(updatedLog.getReceiveTime()).isEqualTo(receiveTime);
    }

    @Test
    void updateReceiveResult_returnsFalseWhenProviderCorrelationDoesNotMatch() {
        LocalDateTime receiveTime = LocalDateTime.of(2026, 8, 30, 10, 0);
        when(smsLogMapper.updateReceiveResult(any(), eq(9L), eq("aliyun"), eq("serial-2")))
                .thenReturn(0);
        when(smsLogMapper.existsByProviderCorrelation(9L, "aliyun", "serial-2")).thenReturn(false);

        boolean updated = service.updateSmsReceiveResult(
                new SmsReceiveResultCommand("aliyun", 9L, "serial-2", false, receiveTime, "DELIVER_FAIL", "rejected"));

        assertThat(updated).isFalse();
        ArgumentCaptor<SmsLogDO> logCaptor = ArgumentCaptor.forClass(SmsLogDO.class);
        verify(smsLogMapper).updateReceiveResult(logCaptor.capture(), eq(9L), eq("aliyun"), eq("serial-2"));
        SmsLogDO updatedLog = logCaptor.getValue();
        assertThat(updatedLog.getReceiveStatus()).isEqualTo(SmsReceiveStatusEnum.FAILURE.getStatus());
        assertThat(updatedLog.getApiReceiveCode()).isEqualTo("DELIVER_FAIL");
        assertThat(updatedLog.getApiReceiveMsg()).isEqualTo("rejected");
    }

    @Test
    void updateReceiveResultTreatsDuplicateOrStaleReceiptAsHandled() {
        when(smsLogMapper.updateReceiveResult(any(), eq(null), eq("aliyun"), eq("serial-3")))
                .thenReturn(0);
        when(smsLogMapper.existsByProviderCorrelation(null, "aliyun", "serial-3"))
                .thenReturn(true);

        boolean handled = service.updateSmsReceiveResult(
                new SmsReceiveResultCommand("aliyun", null, "serial-3", false, null, "LATE", "late receipt"));

        assertThat(handled).isTrue();
        verify(smsLogMapper).existsByProviderCorrelation(null, "aliyun", "serial-3");
    }

    @Test
    void updateReceiveResultRejectsMissingCorrelation() {
        assertThatThrownBy(() -> service.updateSmsReceiveResult(
                        new SmsReceiveResultCommand("aliyun", null, " ", true, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateReceiveResultRejectsNullCommandAndMissingStatus() {
        assertThatThrownBy(() -> service.updateSmsReceiveResult(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateSmsReceiveResult(
                        new SmsReceiveResultCommand("aliyun", null, "serial-1", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static SmsTemplateDO template() {
        SmsTemplateDO template = new SmsTemplateDO();
        template.setId(20L);
        template.setChannelId(30L);
        template.setChannelCode("aliyun");
        template.setCode("admin-reset-password");
        template.setType(1);
        template.setApiTemplateId("1400000000");
        return template;
    }
}
