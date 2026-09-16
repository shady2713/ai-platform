package com.basicframework.module.system.service.notify;

import static com.basicframework.module.system.enums.ErrorCodeConstants.NOTIFY_TEMPLATE_CODE_DUPLICATE;
import static com.basicframework.module.system.enums.ErrorCodeConstants.NOTIFY_TEMPLATE_NOT_EXISTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.system.dal.dataobject.notify.NotifyTemplateDO;
import com.basicframework.module.system.dal.mysql.notify.NotifyTemplateMapper;
import com.basicframework.module.system.dal.mysql.notify.NotifyTemplateQuery;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotifyTemplateServiceImplTest {

    private NotifyTemplateServiceImpl service;

    @Mock
    private NotifyTemplateMapper notifyTemplateMapper;

    @BeforeEach
    void setUp() {
        service = new NotifyTemplateServiceImpl(notifyTemplateMapper);
    }

    @Test
    void createNotifyTemplate_parsesParametersAndReturnsGeneratedId() {
        NotifyTemplateDO template = NotifyTemplateDO.builder()
                .code("WELCOME")
                .content("你好 {name}，编号 {code}")
                .build();
        when(notifyTemplateMapper.insert(any(NotifyTemplateDO.class))).thenAnswer(invocation -> {
            NotifyTemplateDO inserted = invocation.getArgument(0);
            inserted.setId(31L);
            return 1;
        });

        Long id = service.createNotifyTemplate(template);

        assertThat(id).isEqualTo(31L);
        assertThat(template.getParams()).containsExactly("name", "code");
        verify(notifyTemplateMapper).insert(template);
    }

    @Test
    void createNotifyTemplate_failsForDuplicateCode() {
        NotifyTemplateDO template =
                NotifyTemplateDO.builder().code("WELCOME").content("你好").build();
        when(notifyTemplateMapper.selectByCode("WELCOME"))
                .thenReturn(NotifyTemplateDO.builder().id(9L).code("WELCOME").build());

        assertThatThrownBy(() -> service.createNotifyTemplate(template))
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(NOTIFY_TEMPLATE_CODE_DUPLICATE.getCode()));
    }

    @Test
    void updateNotifyTemplate_validatesAndRefreshesParameters() {
        NotifyTemplateDO update = NotifyTemplateDO.builder()
                .id(5L)
                .code("UPDATED")
                .content("状态 {status}")
                .build();
        when(notifyTemplateMapper.selectById(5L))
                .thenReturn(NotifyTemplateDO.builder().id(5L).code("OLD").build());
        when(notifyTemplateMapper.selectByCode("UPDATED"))
                .thenReturn(NotifyTemplateDO.builder().id(5L).code("UPDATED").build());

        service.updateNotifyTemplate(update);

        assertThat(update.getParams()).containsExactly("status");
        verify(notifyTemplateMapper).updateById(update);
    }

    @Test
    void updateNotifyTemplate_failsWhenCodeBelongsToAnotherTemplate() {
        NotifyTemplateDO update = NotifyTemplateDO.builder()
                .id(5L)
                .code("DUPLICATE")
                .content("状态 {status}")
                .build();
        when(notifyTemplateMapper.selectById(5L))
                .thenReturn(NotifyTemplateDO.builder().id(5L).code("OLD").build());
        when(notifyTemplateMapper.selectByCode("DUPLICATE"))
                .thenReturn(NotifyTemplateDO.builder().id(6L).code("DUPLICATE").build());

        assertThatThrownBy(() -> service.updateNotifyTemplate(update))
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(NOTIFY_TEMPLATE_CODE_DUPLICATE.getCode()));
    }

    @Test
    void deleteNotifyTemplate_deletesExistingTemplate() {
        when(notifyTemplateMapper.selectById(5L))
                .thenReturn(NotifyTemplateDO.builder().id(5L).build());

        service.deleteNotifyTemplate(5L);

        verify(notifyTemplateMapper).deleteById(5L);
    }

    @Test
    void deleteNotifyTemplate_failsWhenTemplateDoesNotExist() {
        when(notifyTemplateMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.deleteNotifyTemplate(99L))
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(NOTIFY_TEMPLATE_NOT_EXISTS.getCode()));
    }

    @Test
    void deleteNotifyTemplateList_delegatesToMapper() {
        List<Long> ids = List.of(1L, 2L);

        service.deleteNotifyTemplateList(ids);

        verify(notifyTemplateMapper).deleteByIds(ids);
    }

    @Test
    void queryMethods_delegateToMapper() {
        NotifyTemplateDO template =
                NotifyTemplateDO.builder().id(5L).code("WELCOME").build();
        PageParam pageParam = new PageParam();
        NotifyTemplateQuery query = new NotifyTemplateQuery(null, null, null, null, null);
        PageResult<NotifyTemplateDO> page = new PageResult<>(List.of(template), 1L);
        when(notifyTemplateMapper.selectById(5L)).thenReturn(template);
        when(notifyTemplateMapper.selectByCode("WELCOME")).thenReturn(template);
        when(notifyTemplateMapper.selectPage(pageParam, query)).thenReturn(page);

        assertThat(service.getNotifyTemplate(5L)).isSameAs(template);
        assertThat(service.getNotifyTemplateByCodeFromCache("WELCOME")).isSameAs(template);
        assertThat(service.getNotifyTemplatePage(pageParam, query)).isSameAs(page);
    }

    @Test
    void formatNotifyTemplateContent_replacesNamedParameters() {
        assertThat(service.formatNotifyTemplateContent("你好 {name}", java.util.Map.of("name", "shady")))
                .isEqualTo("你好 shady");
    }
}
