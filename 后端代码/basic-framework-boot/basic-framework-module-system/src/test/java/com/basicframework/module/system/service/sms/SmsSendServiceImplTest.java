package com.basicframework.module.system.service.sms;

import static com.basicframework.module.system.enums.ErrorCodeConstants.SMS_SEND_MOBILE_TEMPLATE_PARAM_MISS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.core.KeyValue;
import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.system.dal.dataobject.sms.SmsChannelDO;
import com.basicframework.module.system.dal.dataobject.sms.SmsTemplateDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.framework.sms.core.client.SmsClient;
import com.basicframework.module.system.framework.sms.core.client.dto.SmsReceiveRespDTO;
import com.basicframework.module.system.mq.message.sms.SmsSendMessage;
import com.basicframework.module.system.mq.producer.sms.SmsProducer;
import com.basicframework.module.system.service.user.AdminUserService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SmsSendServiceImplTest {

    @InjectMocks
    private SmsSendServiceImpl service;

    @Mock
    private AdminUserService adminUserService;

    @Mock
    private SmsChannelService smsChannelService;

    @Mock
    private SmsTemplateService smsTemplateService;

    @Mock
    private SmsLogService smsLogService;

    @Mock
    private SmsProducer smsProducer;

    @Test
    void sendSingleSmsToAdmin_resolvesMobileAndPublishesEnabledTemplate() {
        SmsTemplateDO template = enabledTemplate();
        SmsChannelDO channel = enabledChannel();
        Map<String, Object> params = Map.of("code", "1234");
        when(adminUserService.getUser(7L)).thenReturn(new AdminUserDO().setMobile("13800138000"));
        when(smsTemplateService.getSmsTemplateByCodeFromCache("LOGIN")).thenReturn(template);
        when(smsChannelService.getSmsChannel(3L)).thenReturn(channel);
        when(smsTemplateService.formatSmsTemplateContent("验证码 {code}", params)).thenReturn("验证码 1234");
        when(smsLogService.createSmsLog(
                        "13800138000", 7L, UserTypeEnum.ADMIN.getValue(), true, template, "验证码 1234", params))
                .thenReturn(11L);

        assertThat(service.sendSingleSmsToAdmin(null, 7L, "LOGIN", params)).isEqualTo(11L);

        verify(smsProducer)
                .sendSmsSendMessage(
                        11L, "13800138000", 3L, "provider-template", List.of(new KeyValue<>("code", "1234")));
    }

    @Test
    void sendSingleSms_recordsDisabledChannelWithoutPublishing() {
        SmsTemplateDO template = enabledTemplate();
        SmsChannelDO channel = enabledChannel().setStatus(CommonStatusEnum.DISABLE.getStatus());
        Map<String, Object> params = Map.of("code", "1234");
        when(smsTemplateService.getSmsTemplateByCodeFromCache("LOGIN")).thenReturn(template);
        when(smsChannelService.getSmsChannel(3L)).thenReturn(channel);
        when(smsTemplateService.formatSmsTemplateContent("验证码 {code}", params)).thenReturn("验证码 1234");
        when(smsLogService.createSmsLog(
                        "13800138000", 7L, UserTypeEnum.ADMIN.getValue(), false, template, "验证码 1234", params))
                .thenReturn(12L);

        assertThat(service.sendSingleSms("13800138000", 7L, UserTypeEnum.ADMIN.getValue(), "LOGIN", params))
                .isEqualTo(12L);

        verifyNoInteractions(smsProducer);
    }

    @Test
    void sendSingleSms_rejectsMissingTemplateParameterBeforeWritingLog() {
        SmsTemplateDO template = enabledTemplate();
        when(smsTemplateService.getSmsTemplateByCodeFromCache("LOGIN")).thenReturn(template);
        when(smsChannelService.getSmsChannel(3L)).thenReturn(enabledChannel());

        ServiceException exception = assertThrows(
                ServiceException.class,
                () -> service.sendSingleSms("13800138000", 7L, UserTypeEnum.ADMIN.getValue(), "LOGIN", Map.of()));

        assertThat(exception.getCode()).isEqualTo(SMS_SEND_MOBILE_TEMPLATE_PARAM_MISS.getCode());
        verify(smsLogService, never())
                .createSmsLog(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void doSendSms_recordsProviderFailureWithoutLeakingItToTheConsumer() throws Throwable {
        SmsClient client = org.mockito.Mockito.mock(SmsClient.class);
        SmsSendMessage message = new SmsSendMessage()
                .setLogId(13L)
                .setMobile("13800138000")
                .setChannelId(3L)
                .setApiTemplateId("provider-template")
                .setTemplateParams(List.of(new KeyValue<>("code", "1234")));
        when(smsChannelService.getSmsClient(3L)).thenReturn(client);
        when(client.sendSms(13L, "13800138000", "provider-template", message.getTemplateParams()))
                .thenThrow(new IllegalStateException("provider unavailable"));

        service.doSendSms(message);

        verify(smsLogService)
                .updateSmsSendResult(13L, false, "EXCEPTION", IllegalStateException.class.getName(), null, null);
    }

    @Test
    void doSendSms_neverConvertsJvmErrorsIntoProviderFailures() throws Exception {
        SmsClient client = org.mockito.Mockito.mock(SmsClient.class);
        SmsSendMessage message = new SmsSendMessage()
                .setLogId(13L)
                .setMobile("13800138000")
                .setChannelId(3L)
                .setApiTemplateId("provider-template")
                .setTemplateParams(List.of(new KeyValue<>("code", "1234")));
        AssertionError fatalError = new AssertionError("jvm invariant broken");
        when(smsChannelService.getSmsClient(3L)).thenReturn(client);
        when(client.sendSms(13L, "13800138000", "provider-template", message.getTemplateParams()))
                .thenThrow(fatalError);

        assertThatThrownBy(() -> service.doSendSms(message)).isSameAs(fatalError);
        verifyNoInteractions(smsLogService);
    }

    @Test
    void receiveSmsStatus_forwardsEveryParsedReceipt() throws Throwable {
        SmsClient client = org.mockito.Mockito.mock(SmsClient.class);
        LocalDateTime receiveTime = LocalDateTime.of(2026, 8, 30, 1, 0);
        SmsReceiveRespDTO receipt = new SmsReceiveRespDTO();
        receipt.setLogId(14L);
        receipt.setSerialNo("serial-1");
        receipt.setSuccess(true);
        receipt.setReceiveTime(receiveTime);
        receipt.setErrorCode(null);
        receipt.setErrorMsg(null);
        when(smsChannelService.getSmsClient("aliyun")).thenReturn(client);
        when(client.parseSmsReceiveStatus("receipt-body")).thenReturn(List.of(receipt));
        when(smsLogService.updateSmsReceiveResult(any(SmsReceiveResultCommand.class)))
                .thenReturn(true);

        service.receiveSmsStatus("aliyun", "receipt-body");

        verify(smsLogService)
                .updateSmsReceiveResult(
                        new SmsReceiveResultCommand("aliyun", 14L, "serial-1", true, receiveTime, null, null));
    }

    @Test
    void receiveSmsStatusRejectsUnmatchedReceipt() throws Throwable {
        SmsClient client = org.mockito.Mockito.mock(SmsClient.class);
        SmsReceiveRespDTO receipt = new SmsReceiveRespDTO();
        receipt.setSerialNo("unknown-serial");
        receipt.setSuccess(true);
        when(smsChannelService.getSmsClient("aliyun")).thenReturn(client);
        when(client.parseSmsReceiveStatus("receipt-body")).thenReturn(List.of(receipt));
        when(smsLogService.updateSmsReceiveResult(any(SmsReceiveResultCommand.class)))
                .thenReturn(false);

        assertThatThrownBy(() -> service.receiveSmsStatus("aliyun", "receipt-body"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("短信回执无法匹配已发送日志");
    }

    private static SmsTemplateDO enabledTemplate() {
        return new SmsTemplateDO()
                .setStatus(CommonStatusEnum.ENABLE.getStatus())
                .setContent("验证码 {code}")
                .setParams(List.of("code"))
                .setChannelId(3L)
                .setApiTemplateId("provider-template");
    }

    private static SmsChannelDO enabledChannel() {
        return new SmsChannelDO().setId(3L).setStatus(CommonStatusEnum.ENABLE.getStatus());
    }
}
