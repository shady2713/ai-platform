package com.basicframework.module.system.service.sms;

import static com.basicframework.module.system.testutil.ServiceExceptionAssert.assertServiceException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.system.dal.dataobject.sms.SmsChannelDO;
import com.basicframework.module.system.dal.dataobject.sms.SmsTemplateDO;
import com.basicframework.module.system.dal.mysql.sms.SmsChannelMapper;
import com.basicframework.module.system.dal.mysql.sms.SmsTemplateMapper;
import com.basicframework.module.system.enums.ErrorCodeConstants;
import com.basicframework.module.system.framework.sms.core.client.SmsClient;
import com.basicframework.module.system.framework.sms.core.client.dto.SmsTemplateRespDTO;
import com.basicframework.module.system.framework.sms.core.enums.SmsTemplateAuditStatusEnum;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class SmsTemplateServiceImplTest {

    @InjectMocks
    private SmsTemplateServiceImpl service;

    @Mock
    private SmsTemplateMapper smsTemplateMapper;

    @Mock
    private SmsChannelMapper smsChannelMapper;

    @Mock
    private SmsChannelService smsChannelService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private SmsClient smsClient;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void executeTransactionCallbacksImmediately() {
        lenient()
                .when(transactionTemplate.execute(any(TransactionCallback.class)))
                .thenAnswer(invocation -> {
                    TransactionCallback<Object> callback = invocation.getArgument(0);
                    return callback.doInTransaction(mock(TransactionStatus.class));
                });
        lenient()
                .doAnswer(invocation -> {
                    Consumer<TransactionStatus> callback = invocation.getArgument(0);
                    callback.accept(mock(TransactionStatus.class));
                    return null;
                })
                .when(transactionTemplate)
                .executeWithoutResult(any());
    }

    @Test
    void createSmsTemplate_validatesRemoteOutsideTransactionThenLocksChannelForWrite() throws Throwable {
        SmsChannelDO channel = channel("api-key");
        SmsTemplateDO template = template(null);
        stubSuccessfulRemoteValidation(channel);
        when(smsChannelMapper.selectByIdForShare(1L)).thenReturn(channel("api-key"));
        when(smsTemplateMapper.insert(template)).thenAnswer(invocation -> {
            template.setId(9L);
            return 1;
        });

        assertThat(service.createSmsTemplate(template)).isEqualTo(9L);

        InOrder inOrder = inOrder(smsChannelService, smsClient, smsChannelMapper, smsTemplateMapper);
        inOrder.verify(smsChannelService).getSmsChannel(1L);
        inOrder.verify(smsChannelService).createTransientSmsClient(channel);
        inOrder.verify(smsClient).getSmsTemplate("api-template");
        inOrder.verify(smsChannelMapper).selectByIdForShare(1L);
        inOrder.verify(smsTemplateMapper).insert(template);
        assertThat(template.getChannelCode()).isEqualTo("aliyun");
    }

    @Test
    void createSmsTemplate_rejectsChannelChangedDuringRemoteValidation() throws Throwable {
        SmsChannelDO channel = channel("old-key");
        SmsTemplateDO template = template(null);
        stubSuccessfulRemoteValidation(channel);
        when(smsChannelMapper.selectByIdForShare(1L)).thenReturn(channel("new-key"));

        assertServiceException(ErrorCodeConstants.SMS_CHANNEL_CHANGED, () -> service.createSmsTemplate(template));

        verify(smsTemplateMapper, never()).insert(any(SmsTemplateDO.class));
    }

    @Test
    void updateSmsTemplate_locksTemplateThenChannelBeforeWrite() throws Throwable {
        SmsChannelDO channel = channel("api-key");
        SmsTemplateDO template = template(8L);
        stubSuccessfulRemoteValidation(channel);
        when(smsTemplateMapper.selectByIdForUpdate(8L)).thenReturn(template(8L));
        when(smsChannelMapper.selectByIdForShare(1L)).thenReturn(channel("api-key"));

        service.updateSmsTemplate(template);

        InOrder inOrder = inOrder(smsTemplateMapper, smsChannelMapper);
        inOrder.verify(smsTemplateMapper).selectByIdForUpdate(8L);
        inOrder.verify(smsChannelMapper).selectByIdForShare(1L);
        inOrder.verify(smsTemplateMapper).updateById(template);
    }

    @Test
    void deleteSmsTemplateList_locksDistinctTemplatesInStableOrder() {
        when(smsTemplateMapper.selectByIdForUpdate(1L)).thenReturn(template(1L));
        when(smsTemplateMapper.selectByIdForUpdate(2L)).thenReturn(template(2L));

        service.deleteSmsTemplateList(List.of(2L, 1L, 2L));

        InOrder inOrder = inOrder(smsTemplateMapper);
        inOrder.verify(smsTemplateMapper).selectByIdForUpdate(1L);
        inOrder.verify(smsTemplateMapper).selectByIdForUpdate(2L);
        inOrder.verify(smsTemplateMapper).deleteByIds(List.of(1L, 2L));
    }

    @Test
    void validateApiTemplate_neverConvertsJvmErrorsIntoBusinessFailures() throws Exception {
        SmsChannelDO channel = channel("api-key");
        AssertionError fatalError = new AssertionError("jvm invariant broken");
        when(smsChannelService.createTransientSmsClient(channel)).thenReturn(smsClient);
        when(smsClient.getSmsTemplate("api-template")).thenThrow(fatalError);

        assertThatThrownBy(() -> service.validateApiTemplate(channel, "api-template"))
                .isSameAs(fatalError);
    }

    @Test
    void validateApiTemplate_doesNotExposeProviderFailureMessage() throws Exception {
        SmsChannelDO channel = channel("api-key");
        String providerSecret = "provider-secret-token";
        when(smsChannelService.createTransientSmsClient(channel)).thenReturn(smsClient);
        when(smsClient.getSmsTemplate("api-template")).thenThrow(new IllegalStateException(providerSecret));

        assertThatThrownBy(() -> service.validateApiTemplate(channel, "api-template"))
                .isInstanceOf(ServiceException.class)
                .hasMessage(ErrorCodeConstants.SMS_TEMPLATE_API_ERROR.getMsg())
                .hasMessageNotContaining(providerSecret);
    }

    private void stubSuccessfulRemoteValidation(SmsChannelDO channel) throws Throwable {
        when(smsChannelService.getSmsChannel(1L)).thenReturn(channel);
        when(smsChannelService.createTransientSmsClient(channel)).thenReturn(smsClient);
        when(smsClient.getSmsTemplate("api-template"))
                .thenReturn(new SmsTemplateRespDTO().setAuditStatus(SmsTemplateAuditStatusEnum.SUCCESS.getStatus()));
    }

    private static SmsChannelDO channel(String apiKeyCiphertext) {
        return new SmsChannelDO()
                .setId(1L)
                .setCode("aliyun")
                .setStatus(CommonStatusEnum.ENABLE.getStatus())
                .setApiKeyCiphertext(apiKeyCiphertext)
                .setApiSecretCiphertext("encrypted-secret");
    }

    private static SmsTemplateDO template(Long id) {
        return new SmsTemplateDO()
                .setId(id)
                .setChannelId(1L)
                .setCode("login")
                .setContent("验证码 {code}")
                .setApiTemplateId("api-template");
    }
}
