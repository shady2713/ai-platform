package com.basicframework.module.system.service.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.system.dal.dataobject.notice.NoticeDO;
import com.basicframework.module.system.dal.dataobject.notify.NotifyMessageDO;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.dal.mysql.notice.NoticeMapper;
import com.basicframework.module.system.dal.mysql.notify.NotifyMessageMapper;
import com.basicframework.module.system.service.user.AdminUserService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NoticeServiceImplTest {

    @Mock
    private NoticeMapper noticeMapper;

    @Mock
    private NotifyMessageMapper notifyMessageMapper;

    @Mock
    private AdminUserService adminUserService;

    private NoticeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NoticeServiceImpl(noticeMapper, notifyMessageMapper, adminUserService);
    }

    @Test
    void pushNotice_buildsUnreadAdminMessagesForEveryEnabledUser() {
        NoticeDO notice = notice(8L, "系统维护", "<b>今晚升级</b>");
        when(noticeMapper.selectById(8L)).thenReturn(notice);
        when(adminUserService.getUserListByStatus(CommonStatusEnum.ENABLE.getStatus()))
                .thenReturn(List.of(
                        AdminUserDO.builder().id(1L).build(),
                        AdminUserDO.builder().id(2L).build()));

        service.pushNotice(8L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NotifyMessageDO>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(notifyMessageMapper).insertBatch(messagesCaptor.capture());
        assertThat(messagesCaptor.getValue()).hasSize(2).allSatisfy(message -> {
            assertThat(message.getUserType()).isEqualTo(UserTypeEnum.ADMIN.getValue());
            assertThat(message.getTemplateId()).isEqualTo(8L);
            assertThat(message.getTemplateCode()).isEqualTo("SYSTEM_NOTICE_8");
            assertThat(message.getTemplateContent()).isEqualTo("系统维护\n今晚升级");
            assertThat(message.getTemplateParams())
                    .containsEntry("noticeId", 8L)
                    .containsEntry("title", "系统维护");
            assertThat(message.getReadStatus()).isFalse();
        });
        assertThat(messagesCaptor.getValue())
                .extracting(NotifyMessageDO::getUserId)
                .containsExactly(1L, 2L);
    }

    @Test
    void pushNotice_skipsBatchWriteWhenNoEnabledUserExists() {
        when(noticeMapper.selectById(8L)).thenReturn(notice(8L, "系统维护", null));
        when(adminUserService.getUserListByStatus(CommonStatusEnum.ENABLE.getStatus()))
                .thenReturn(List.of());

        service.pushNotice(8L);

        verify(notifyMessageMapper, never()).insertBatch(anyList());
    }

    @Test
    void updateNotice_rejectsMissingNoticeBeforeWriting() {
        NoticeDO update = notice(99L, "不存在", "content");
        when(noticeMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.updateNotice(update)).isInstanceOf(ServiceException.class);
        verify(noticeMapper, never()).updateById(update);
    }

    private static NoticeDO notice(Long id, String title, String content) {
        NoticeDO notice = new NoticeDO();
        notice.setId(id);
        notice.setTitle(title);
        notice.setContent(content);
        notice.setType(1);
        return notice;
    }
}
