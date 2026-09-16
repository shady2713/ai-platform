package com.basicframework.module.system.controller.admin.notify;

import static com.basicframework.module.system.enums.ErrorCodeConstants.NOTIFY_SEND_USER_TYPE_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.system.controller.admin.notify.vo.template.NotifyTemplatePageReqVO;
import com.basicframework.module.system.controller.admin.notify.vo.template.NotifyTemplateSaveReqVO;
import com.basicframework.module.system.controller.admin.notify.vo.template.NotifyTemplateSendReqVO;
import com.basicframework.module.system.dal.dataobject.notify.NotifyTemplateDO;
import com.basicframework.module.system.dal.mysql.notify.NotifyTemplateQuery;
import com.basicframework.module.system.enums.notify.NotifyTemplateTypeEnum;
import com.basicframework.module.system.service.notify.NotifySendService;
import com.basicframework.module.system.service.notify.NotifyTemplateService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotifyTemplateControllerTest {

    @InjectMocks
    private NotifyTemplateController controller;

    @Mock
    private NotifyTemplateService notifyTemplateService;

    @Mock
    private NotifySendService notifySendService;

    @Test
    void mutationsMapRequestsAndDelegateExactTargets() {
        NotifyTemplateSaveReqVO request = saveRequest();
        when(notifyTemplateService.createNotifyTemplate(any(NotifyTemplateDO.class)))
                .thenReturn(9L);

        assertThat(controller.createNotifyTemplate(request).getData()).isEqualTo(9L);
        assertThat(controller.updateNotifyTemplate(request).getData()).isTrue();
        assertThat(controller.deleteNotifyTemplate(7L).getData()).isTrue();
        assertThat(controller.deleteNotifyTemplateList(List.of(7L, 8L)).getData())
                .isTrue();

        verify(notifyTemplateService).updateNotifyTemplate(any(NotifyTemplateDO.class));
        verify(notifyTemplateService).deleteNotifyTemplate(7L);
        verify(notifyTemplateService).deleteNotifyTemplateList(List.of(7L, 8L));
    }

    @Test
    void getAndPageMapResultsWhilePreservingEveryFilter() {
        NotifyTemplateDO template = template();
        NotifyTemplatePageReqVO request = pageRequest();
        when(notifyTemplateService.getNotifyTemplate(7L)).thenReturn(template);
        when(notifyTemplateService.getNotifyTemplatePage(any(), any()))
                .thenReturn(new PageResult<>(List.of(template), 1L));

        assertThat(controller.getNotifyTemplate(7L).getData().getCode()).isEqualTo("WELCOME");
        assertThat(controller.getNotifyTemplatePage(request).getData().getTotal())
                .isEqualTo(1L);

        ArgumentCaptor<NotifyTemplateQuery> queryCaptor = ArgumentCaptor.forClass(NotifyTemplateQuery.class);
        verify(notifyTemplateService).getNotifyTemplatePage(any(), queryCaptor.capture());
        assertThat(queryCaptor.getValue())
                .extracting(
                        NotifyTemplateQuery::getCode,
                        NotifyTemplateQuery::getName,
                        NotifyTemplateQuery::getType,
                        NotifyTemplateQuery::getStatus,
                        NotifyTemplateQuery::getCreateTime)
                .containsExactly("WEL", "欢迎", 1, 0, request.getCreateTime());
    }

    @Test
    void sendNotify_routesMemberAndAdminExplicitly() {
        Map<String, Object> params = Map.of("name", "Alice");
        when(notifySendService.sendSingleNotifyToMember(1L, "WELCOME", params)).thenReturn(11L);
        when(notifySendService.sendSingleNotifyToAdmin(2L, "WELCOME", params)).thenReturn(12L);

        CommonResult<Long> memberResult = controller.sendNotify(request(1L, UserTypeEnum.MEMBER.getValue(), params));
        CommonResult<Long> adminResult = controller.sendNotify(request(2L, UserTypeEnum.ADMIN.getValue(), params));

        assertThat(memberResult.getData()).isEqualTo(11L);
        assertThat(adminResult.getData()).isEqualTo(12L);
        verify(notifySendService).sendSingleNotifyToMember(1L, "WELCOME", params);
        verify(notifySendService).sendSingleNotifyToAdmin(2L, "WELCOME", params);
    }

    @Test
    void sendNotify_rejectsUnknownUserTypeInsteadOfFallingBackToAdmin() {
        assertThatThrownBy(() -> controller.sendNotify(request(1L, 99, Map.of())))
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(NOTIFY_SEND_USER_TYPE_INVALID.getCode()));
    }

    private static NotifyTemplateSendReqVO request(Long userId, Integer userType, Map<String, Object> params) {
        NotifyTemplateSendReqVO request = new NotifyTemplateSendReqVO();
        request.setUserId(userId);
        request.setUserType(userType);
        request.setTemplateCode("WELCOME");
        request.setTemplateParams(params);
        return request;
    }

    private static NotifyTemplateSaveReqVO saveRequest() {
        NotifyTemplateSaveReqVO request = new NotifyTemplateSaveReqVO();
        request.setId(7L);
        request.setName("欢迎通知");
        request.setCode("WELCOME");
        request.setType(NotifyTemplateTypeEnum.NOTIFICATION_MESSAGE.getType());
        request.setNickname("系统");
        request.setContent("你好 {name}");
        request.setStatus(CommonStatusEnum.ENABLE.getStatus());
        return request;
    }

    private static NotifyTemplatePageReqVO pageRequest() {
        NotifyTemplatePageReqVO request = new NotifyTemplatePageReqVO();
        request.setCode("WEL");
        request.setName("欢迎");
        request.setType(NotifyTemplateTypeEnum.NOTIFICATION_MESSAGE.getType());
        request.setStatus(CommonStatusEnum.ENABLE.getStatus());
        request.setCreateTime(new LocalDateTime[] {LocalDateTime.now().minusDays(1), LocalDateTime.now()});
        return request;
    }

    private static NotifyTemplateDO template() {
        return new NotifyTemplateDO()
                .setId(7L)
                .setName("欢迎通知")
                .setCode("WELCOME")
                .setType(NotifyTemplateTypeEnum.NOTIFICATION_MESSAGE.getType())
                .setNickname("系统")
                .setContent("你好 {name}")
                .setStatus(CommonStatusEnum.ENABLE.getStatus());
    }
}
