package com.basicframework.module.system.dal.mysql.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.query.QueryWrapperX;
import com.basicframework.framework.mybatis.core.util.JdbcUtils;
import com.basicframework.module.system.dal.dataobject.notify.NotifyMessageDO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class NotifyMessageMapperTest {

    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), NotifyMessageMapper.class.getName()),
                NotifyMessageDO.class);
    }

    @Test
    void adminPageBuildsEverySuppliedFilter() {
        NotifyMessageMapper mapper = mapperWithPageResult();
        LocalDateTime from = LocalDateTime.now().minusDays(1);
        LocalDateTime to = LocalDateTime.now();
        NotifyMessageQuery query = new NotifyMessageQuery(7L, 1, "WELCOME", 2, new LocalDateTime[] {from, to});

        PageResult<NotifyMessageDO> result = mapper.selectPage(new PageParam(), query);

        assertThat(result.getTotal()).isEqualTo(1L);
        LambdaQueryWrapper<NotifyMessageDO> wrapper = capturedPageWrapper(mapper);
        assertThat(wrapper.getSqlSegment())
                .contains("user_id", "user_type", "template_code", "template_type", "create_time", "id");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(7L, 1, "%WELCOME%", 2, from, to);
    }

    @Test
    void userPageAlwaysBindsUserIdAndUserType() {
        NotifyMessageMapper mapper = mapperWithPageResult();
        LocalDateTime from = LocalDateTime.now().minusHours(1);
        LocalDateTime to = LocalDateTime.now();

        mapper.selectPage(new PageParam(), false, new LocalDateTime[] {from, to}, 7L, 1);

        LambdaQueryWrapper<NotifyMessageDO> wrapper = capturedPageWrapper(mapper);
        assertThat(wrapper.getSqlSegment()).contains("read_status", "create_time", "user_id", "user_type", "id");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(false, from, to, 7L, 1);
    }

    @Test
    void updateSelectedMessagesCannotCrossTheUserBoundary() {
        NotifyMessageMapper mapper = mock(NotifyMessageMapper.class, CALLS_REAL_METHODS);
        doReturn(2).when(mapper).update(any(NotifyMessageDO.class), any(Wrapper.class));

        assertThat(mapper.updateListRead(List.of(31L, 32L), 7L, 1)).isEqualTo(2);

        ArgumentCaptor<NotifyMessageDO> updateCaptor = ArgumentCaptor.forClass(NotifyMessageDO.class);
        ArgumentCaptor<Wrapper<NotifyMessageDO>> wrapperCaptor = wrapperCaptor();
        verify(mapper).update(updateCaptor.capture(), wrapperCaptor.capture());
        assertThat(updateCaptor.getValue().getReadStatus()).isTrue();
        assertThat(updateCaptor.getValue().getReadTime()).isNotNull();
        LambdaQueryWrapper<NotifyMessageDO> wrapper = asLambdaWrapper(wrapperCaptor.getValue());
        assertThat(wrapper.getSqlSegment()).contains("id", "user_id", "user_type", "read_status");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(31L, 32L, 7L, 1, false);
    }

    @Test
    void updateAllMessagesStillBindsTheUserBoundary() {
        NotifyMessageMapper mapper = mock(NotifyMessageMapper.class, CALLS_REAL_METHODS);
        doReturn(3).when(mapper).update(any(NotifyMessageDO.class), any(Wrapper.class));

        assertThat(mapper.updateListRead(7L, 1)).isEqualTo(3);

        ArgumentCaptor<Wrapper<NotifyMessageDO>> wrapperCaptor = wrapperCaptor();
        verify(mapper).update(any(NotifyMessageDO.class), wrapperCaptor.capture());
        LambdaQueryWrapper<NotifyMessageDO> wrapper = asLambdaWrapper(wrapperCaptor.getValue());
        assertThat(wrapper.getSqlSegment()).contains("user_id", "user_type", "read_status");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(7L, 1, false);
    }

    @Test
    void unreadListAndCountBindIdentityAndLimitTheResult() {
        NotifyMessageMapper mapper = mock(NotifyMessageMapper.class, CALLS_REAL_METHODS);
        NotifyMessageDO message = NotifyMessageDO.builder().id(31L).build();
        doReturn(List.of(message)).when(mapper).selectList(any(Wrapper.class));
        doReturn(4L).when(mapper).selectCount(any(Wrapper.class));

        try (MockedStatic<JdbcUtils> jdbcUtils = mockStatic(JdbcUtils.class)) {
            jdbcUtils.when(JdbcUtils::getDbType).thenReturn(DbType.MYSQL);
            assertThat(mapper.selectUnreadListByUserIdAndUserType(7L, 1, 5)).containsExactly(message);
        }
        assertThat(mapper.selectUnreadCountByUserIdAndUserType(7L, 1)).isEqualTo(4L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<NotifyMessageDO>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(wrapperCaptor.capture());
        QueryWrapperX<NotifyMessageDO> listWrapper = (QueryWrapperX<NotifyMessageDO>) wrapperCaptor.getValue();
        assertThat(listWrapper.getSqlSegment()).contains("user_id", "user_type", "read_status", "id");
        assertThat(listWrapper.getParamNameValuePairs().values()).contains(7L, 1, false);
        assertThat(listWrapper.getSqlSegment()).endsWith("LIMIT 5");

        verify(mapper).selectCount(wrapperCaptor.capture());
        LambdaQueryWrapper<NotifyMessageDO> countWrapper = asLambdaWrapper(wrapperCaptor.getValue());
        assertThat(countWrapper.getSqlSegment()).contains("read_status", "user_id", "user_type");
        assertThat(countWrapper.getParamNameValuePairs().values()).contains(false, 7L, 1);
    }

    private static NotifyMessageMapper mapperWithPageResult() {
        NotifyMessageMapper mapper = mock(NotifyMessageMapper.class, CALLS_REAL_METHODS);
        doAnswer(invocation -> {
                    IPage<NotifyMessageDO> page = invocation.getArgument(0);
                    page.setRecords(List.of(NotifyMessageDO.builder().id(31L).build()));
                    page.setTotal(1L);
                    return page;
                })
                .when(mapper)
                .selectPage(any(IPage.class), any(Wrapper.class));
        return mapper;
    }

    private static LambdaQueryWrapper<NotifyMessageDO> capturedPageWrapper(NotifyMessageMapper mapper) {
        ArgumentCaptor<Wrapper<NotifyMessageDO>> wrapperCaptor = wrapperCaptor();
        verify(mapper).selectPage(any(IPage.class), wrapperCaptor.capture());
        return asLambdaWrapper(wrapperCaptor.getValue());
    }

    @SuppressWarnings("unchecked")
    private static LambdaQueryWrapper<NotifyMessageDO> asLambdaWrapper(Wrapper<NotifyMessageDO> wrapper) {
        return (LambdaQueryWrapper<NotifyMessageDO>) wrapper;
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Wrapper<NotifyMessageDO>> wrapperCaptor() {
        return ArgumentCaptor.forClass(Wrapper.class);
    }
}
