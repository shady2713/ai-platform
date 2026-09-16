package com.basicframework.module.system.service.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.system.dal.dataobject.notify.NotifyMessageDO;
import com.basicframework.module.system.dal.dataobject.notify.NotifyTemplateDO;
import com.basicframework.module.system.dal.mysql.notify.NotifyMessageMapper;
import com.basicframework.module.system.dal.mysql.notify.NotifyMessageQuery;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotifyMessageServiceImplTest {

    private NotifyMessageServiceImpl service;

    @Mock
    private NotifyMessageMapper mapper;

    @BeforeEach
    void setUp() {
        service = new NotifyMessageServiceImpl(mapper);
    }

    @Test
    void createNotifyMessage_copiesTheTemplateSnapshotAndReturnsTheGeneratedId() {
        NotifyTemplateDO template = NotifyTemplateDO.builder()
                .id(11L)
                .code("WELCOME")
                .type(2)
                .nickname("系统通知")
                .build();
        Map<String, Object> parameters = Map.of("name", "shady");
        when(mapper.insert(any(NotifyMessageDO.class))).thenAnswer(invocation -> {
            invocation.<NotifyMessageDO>getArgument(0).setId(31L);
            return 1;
        });

        Long id = service.createNotifyMessage(7L, 1, template, "欢迎 shady", parameters);

        assertThat(id).isEqualTo(31L);
        ArgumentCaptor<NotifyMessageDO> captor = ArgumentCaptor.forClass(NotifyMessageDO.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue())
                .returns(7L, NotifyMessageDO::getUserId)
                .returns(1, NotifyMessageDO::getUserType)
                .returns(11L, NotifyMessageDO::getTemplateId)
                .returns("WELCOME", NotifyMessageDO::getTemplateCode)
                .returns(2, NotifyMessageDO::getTemplateType)
                .returns("系统通知", NotifyMessageDO::getTemplateNickname)
                .returns("欢迎 shady", NotifyMessageDO::getTemplateContent)
                .returns(parameters, NotifyMessageDO::getTemplateParams)
                .returns(false, NotifyMessageDO::getReadStatus);
    }

    @Test
    void queryMethods_preserveUserScopeAndDelegateToTheMapper() {
        PageParam pageParam = new PageParam();
        LocalDateTime[] createTime = {LocalDateTime.now().minusDays(1), LocalDateTime.now()};
        NotifyMessageQuery query = new NotifyMessageQuery(7L, 1, "WELCOME", 2, createTime);
        NotifyMessageDO message = NotifyMessageDO.builder().id(31L).build();
        PageResult<NotifyMessageDO> adminPage = new PageResult<>(List.of(message), 1L);
        PageResult<NotifyMessageDO> userPage = new PageResult<>(List.of(message), 1L);
        when(mapper.selectPage(pageParam, query)).thenReturn(adminPage);
        when(mapper.selectPage(pageParam, false, createTime, 7L, 1)).thenReturn(userPage);
        when(mapper.selectById(31L)).thenReturn(message);
        when(mapper.selectUnreadListByUserIdAndUserType(7L, 1, 5)).thenReturn(List.of(message));
        when(mapper.selectUnreadCountByUserIdAndUserType(7L, 1)).thenReturn(1L);

        assertThat(service.getNotifyMessagePage(pageParam, query)).isSameAs(adminPage);
        assertThat(service.getMyNotifyMessagePage(pageParam, false, createTime, 7L, 1))
                .isSameAs(userPage);
        assertThat(service.getNotifyMessage(31L)).isSameAs(message);
        assertThat(service.getUnreadNotifyMessageList(7L, 1, 5)).containsExactly(message);
        assertThat(service.getUnreadNotifyMessageCount(7L, 1)).isEqualTo(1L);
    }

    @Test
    void readUpdates_preserveUserScopeAndReturnAffectedRows() {
        when(mapper.updateListRead(List.of(31L, 32L), 7L, 1)).thenReturn(2);
        when(mapper.updateListRead(7L, 1)).thenReturn(4);

        assertThat(service.updateNotifyMessageRead(List.of(31L, 32L), 7L, 1)).isEqualTo(2);
        assertThat(service.updateAllNotifyMessageRead(7L, 1)).isEqualTo(4);
    }
}
