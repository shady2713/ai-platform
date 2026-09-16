package com.basicframework.module.system.controller.admin.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.security.core.util.SecurityFrameworkUtils;
import com.basicframework.module.system.controller.admin.notify.vo.message.NotifyMessageMyPageReqVO;
import com.basicframework.module.system.controller.admin.notify.vo.message.NotifyMessagePageReqVO;
import com.basicframework.module.system.controller.admin.notify.vo.message.NotifyMessageRespVO;
import com.basicframework.module.system.dal.dataobject.notify.NotifyMessageDO;
import com.basicframework.module.system.dal.mysql.notify.NotifyMessageQuery;
import com.basicframework.module.system.service.notify.NotifyMessageService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class NotifyMessageControllerTest {

    private static final Long USER_ID = 7L;
    private static final Integer USER_TYPE = UserTypeEnum.ADMIN.getValue();

    private final NotifyMessageService messageService = mock(NotifyMessageService.class);
    private final NotifyMessageController controller = new NotifyMessageController(messageService);

    @Test
    void administrativeQueriesMapResultsAndPreserveEveryFilter() {
        NotifyMessageDO message = message();
        NotifyMessagePageReqVO request = adminPageRequest();
        when(messageService.getNotifyMessage(31L)).thenReturn(message);
        when(messageService.getNotifyMessagePage(any(), any())).thenReturn(new PageResult<>(List.of(message), 1L));

        NotifyMessageRespVO detail = controller.getNotifyMessage(31L).getData();
        PageResult<NotifyMessageRespVO> page =
                controller.getNotifyMessagePage(request).getData();

        assertThat(detail.getTemplateCode()).isEqualTo("WELCOME");
        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getList()).extracting(NotifyMessageRespVO::getId).containsExactly(31L);
        ArgumentCaptor<NotifyMessageQuery> queryCaptor = ArgumentCaptor.forClass(NotifyMessageQuery.class);
        verify(messageService).getNotifyMessagePage(same(request), queryCaptor.capture());
        assertThat(queryCaptor.getValue())
                .extracting(
                        NotifyMessageQuery::getUserId,
                        NotifyMessageQuery::getUserType,
                        NotifyMessageQuery::getTemplateCode,
                        NotifyMessageQuery::getTemplateType)
                .containsExactly(USER_ID, USER_TYPE, "WELCOME", 2);
        assertThat(queryCaptor.getValue().getCreateTime()).containsExactly(request.getCreateTime());
    }

    @Test
    void personalOperationsAlwaysBindTheCurrentAdminIdentity() {
        NotifyMessageMyPageReqVO request = myPageRequest();
        NotifyMessageDO message = message();
        when(messageService.getMyNotifyMessagePage(any(), any(), any(), any(), any()))
                .thenReturn(new PageResult<>(List.of(message), 1L));
        when(messageService.getUnreadNotifyMessageList(USER_ID, USER_TYPE, 5)).thenReturn(List.of(message));
        when(messageService.getUnreadNotifyMessageCount(USER_ID, USER_TYPE)).thenReturn(3L);

        try (MockedStatic<SecurityFrameworkUtils> securityUtils = mockStatic(SecurityFrameworkUtils.class)) {
            securityUtils.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(USER_ID);

            assertThat(controller.getMyNotifyMessagePage(request).getData().getTotal())
                    .isEqualTo(1L);
            assertThat(controller.updateNotifyMessageRead(List.of(31L, 32L)).getData())
                    .isTrue();
            assertThat(controller.updateAllNotifyMessageRead().getData()).isTrue();
            assertThat(controller.getUnreadNotifyMessageList(5).getData())
                    .extracting(NotifyMessageRespVO::getId)
                    .containsExactly(31L);
            assertThat(controller.getUnreadNotifyMessageCount().getData()).isEqualTo(3L);
        }

        verify(messageService)
                .getMyNotifyMessagePage(
                        same(request),
                        same(request.getReadStatus()),
                        same(request.getCreateTime()),
                        same(USER_ID),
                        same(USER_TYPE));
        verify(messageService).updateNotifyMessageRead(List.of(31L, 32L), USER_ID, USER_TYPE);
        verify(messageService).updateAllNotifyMessageRead(USER_ID, USER_TYPE);
        verify(messageService).getUnreadNotifyMessageList(USER_ID, USER_TYPE, 5);
        verify(messageService).getUnreadNotifyMessageCount(USER_ID, USER_TYPE);
    }

    private static NotifyMessageDO message() {
        return NotifyMessageDO.builder()
                .id(31L)
                .userId(USER_ID)
                .userType(USER_TYPE)
                .templateCode("WELCOME")
                .templateType(2)
                .build();
    }

    private static NotifyMessagePageReqVO adminPageRequest() {
        NotifyMessagePageReqVO request = new NotifyMessagePageReqVO();
        request.setUserId(USER_ID);
        request.setUserType(USER_TYPE);
        request.setTemplateCode("WELCOME");
        request.setTemplateType(2);
        request.setCreateTime(new LocalDateTime[] {LocalDateTime.now().minusDays(1), LocalDateTime.now()});
        return request;
    }

    private static NotifyMessageMyPageReqVO myPageRequest() {
        NotifyMessageMyPageReqVO request = new NotifyMessageMyPageReqVO();
        request.setReadStatus(false);
        request.setCreateTime(new LocalDateTime[] {LocalDateTime.now().minusHours(1), LocalDateTime.now()});
        return request;
    }
}
