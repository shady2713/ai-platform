package com.basicframework.module.system.service.notify;

import static com.basicframework.module.system.enums.ErrorCodeConstants.NOTICE_NOT_EXISTS;
import static com.basicframework.module.system.enums.ErrorCodeConstants.NOTIFY_SEND_TEMPLATE_PARAM_MISS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.system.dal.dataobject.notify.NotifyTemplateDO;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotifySendServiceImplTest {

    private NotifySendServiceImpl service;

    @Mock
    private NotifyTemplateService notifyTemplateService;

    @Mock
    private NotifyMessageService notifyMessageService;

    @BeforeEach
    void setUp() {
        service = new NotifySendServiceImpl(notifyTemplateService, notifyMessageService);
    }

    @Test
    void sendSingleNotifyToAdmin_formatsAndPersistsEnabledTemplate() {
        NotifyTemplateDO template = enabledTemplate();
        Map<String, Object> params = Map.of("name", "shady");
        when(notifyTemplateService.getNotifyTemplateByCodeFromCache("WELCOME")).thenReturn(template);
        when(notifyTemplateService.formatNotifyTemplateContent(template.getContent(), params))
                .thenReturn("你好 shady");
        when(notifyMessageService.createNotifyMessage(7L, UserTypeEnum.ADMIN.getValue(), template, "你好 shady", params))
                .thenReturn(21L);

        Long messageId = service.sendSingleNotifyToAdmin(7L, "WELCOME", params);

        assertThat(messageId).isEqualTo(21L);
        verify(notifyMessageService)
                .createNotifyMessage(7L, UserTypeEnum.ADMIN.getValue(), template, "你好 shady", params);
    }

    @Test
    void sendSingleNotifyToMember_returnsNullForDisabledTemplate() {
        NotifyTemplateDO template = enabledTemplate();
        template.setStatus(CommonStatusEnum.DISABLE.getStatus());
        when(notifyTemplateService.getNotifyTemplateByCodeFromCache("WELCOME")).thenReturn(template);

        Long messageId = service.sendSingleNotifyToMember(8L, "WELCOME", Map.of("name", "shady"));

        assertThat(messageId).isNull();
        verify(notifyTemplateService, never()).formatNotifyTemplateContent(template.getContent(), Map.of());
        verifyNoInteractions(notifyMessageService);
    }

    @Test
    void sendSingleNotify_failsWhenTemplateDoesNotExist() {
        when(notifyTemplateService.getNotifyTemplateByCodeFromCache("MISSING")).thenReturn(null);

        assertThatThrownBy(() -> service.sendSingleNotify(1L, UserTypeEnum.ADMIN.getValue(), "MISSING", Map.of()))
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(NOTICE_NOT_EXISTS.getCode()));
    }

    @Test
    void sendSingleNotify_failsWhenRequiredTemplateParameterIsMissing() {
        NotifyTemplateDO template = enabledTemplate();
        when(notifyTemplateService.getNotifyTemplateByCodeFromCache("WELCOME")).thenReturn(template);

        assertThatThrownBy(() -> service.sendSingleNotify(1L, UserTypeEnum.ADMIN.getValue(), "WELCOME", Map.of()))
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(NOTIFY_SEND_TEMPLATE_PARAM_MISS.getCode()));
        verifyNoInteractions(notifyMessageService);
    }

    private static NotifyTemplateDO enabledTemplate() {
        return NotifyTemplateDO.builder()
                .id(3L)
                .code("WELCOME")
                .content("你好 {name}")
                .params(List.of("name"))
                .status(CommonStatusEnum.ENABLE.getStatus())
                .build();
    }
}
