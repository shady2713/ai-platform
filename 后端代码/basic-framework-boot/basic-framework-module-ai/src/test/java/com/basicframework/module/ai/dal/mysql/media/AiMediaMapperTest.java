package com.basicframework.module.ai.dal.mysql.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaAssetDO;
import com.basicframework.module.ai.dal.dataobject.media.AiMediaTaskDO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

/**
 * 媒体任务/产物 Mapper 的查询形状（不连数据库）：幂等定位必须带主体范围、分页必须带主体范围与可选过滤、
 * 领取必须只取待领取且到时的行并带 LIMIT 上界、过期租约计数只看 RUNNING 且已过期。
 *
 * <p>CAS 领取/续租/终态栅栏/取消/恢复的 SQL 由集成用例在真实 MySQL 上验证（{@code AiImageGenerationAcceptanceIT}）。
 */
class AiMediaMapperTest {

    private static final Long APPLICATION_ID = 11L;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 2, 3, 4, 5);

    private final AiMediaTaskMapper taskMapper = mock(AiMediaTaskMapper.class, Answers.CALLS_REAL_METHODS);

    private final AiMediaAssetMapper assetMapper = mock(AiMediaAssetMapper.class, Answers.CALLS_REAL_METHODS);

    /** Lambda 包装器需要实体元数据缓存（正常由 MyBatis 运行时初始化）。 */
    @BeforeAll
    static void initTableMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AiMediaTaskDO.class);
        TableInfoHelper.initTableInfo(assistant, AiMediaAssetDO.class);
    }

    @Test
    void requestKeyLookupIsScopedToApplicationSubjectAndKey() {
        doReturn(new AiMediaTaskDO().setId(91L)).when(taskMapper).selectOne(any());

        assertThat(taskMapper
                        .selectByRequestKey(APPLICATION_ID, "USER", "alice", "img-1")
                        .getId())
                .isEqualTo(91L);

        Wrapper<AiMediaTaskDO> wrapper = captureSelectOne();
        assertThat(wrapper.getSqlSegment())
                .containsIgnoringCase("application_id")
                .containsIgnoringCase("subject_type")
                .containsIgnoringCase("external_user_id")
                .containsIgnoringCase("request_key");
        assertThat(paramValues(wrapper).values()).contains(APPLICATION_ID, "USER", "alice", "img-1");
    }

    @Test
    void taskPageAlwaysCarriesSubjectScopeAndOnlyPresentFilters() {
        doAnswer(invocation -> {
                    IPage<AiMediaTaskDO> page = invocation.getArgument(0);
                    page.setRecords(List.of(new AiMediaTaskDO().setId(7L)));
                    page.setTotal(1L);
                    return page;
                })
                .when(taskMapper)
                .selectPage(any(IPage.class), any());

        PageResult<AiMediaTaskDO> filtered = taskMapper.selectPage(
                new PageParam().setPageNo(1).setPageSize(5), APPLICATION_ID, "USER", "alice", "IMAGE", "QUEUED");
        PageResult<AiMediaTaskDO> unfiltered = taskMapper.selectPage(
                new PageParam().setPageNo(1).setPageSize(5), APPLICATION_ID, "USER", "alice", null, null);

        assertThat(filtered.getTotal()).isEqualTo(1L);
        assertThat(filtered.getList()).extracting(AiMediaTaskDO::getId).containsExactly(7L);
        assertThat(unfiltered.getList()).hasSize(1);

        ArgumentCaptor<Wrapper<AiMediaTaskDO>> wrappers = wrapperCaptor();
        verify(taskMapper, times(2)).selectPage(any(IPage.class), wrappers.capture());
        Wrapper<AiMediaTaskDO> filteredWrapper = wrappers.getAllValues().get(0);
        assertThat(filteredWrapper.getSqlSegment())
                .containsIgnoringCase("application_id")
                .containsIgnoringCase("subject_type")
                .containsIgnoringCase("external_user_id")
                .containsIgnoringCase("media_kind")
                .containsIgnoringCase("status");
        assertThat(paramValues(filteredWrapper).values()).contains(APPLICATION_ID, "USER", "alice", "IMAGE", "QUEUED");
        assertThat(wrappers.getAllValues().get(1).getSqlSegment())
                .doesNotContainIgnoringCase("media_kind")
                .doesNotContainIgnoringCase("status");
    }

    @Test
    void claimableQueryTakesOnlyDueQueuedRowsWithBoundedLimit() {
        doReturn(List.of(new AiMediaTaskDO().setId(1L))).when(taskMapper).selectList(any());

        assertThat(taskMapper.selectClaimable(NOW, 7)).hasSize(1);
        assertThat(taskMapper.selectClaimable(NOW, 0)).hasSize(1);

        ArgumentCaptor<Wrapper<AiMediaTaskDO>> wrappers = wrapperCaptor();
        verify(taskMapper, times(2)).selectList(wrappers.capture());
        Wrapper<AiMediaTaskDO> bounded = wrappers.getAllValues().get(0);
        assertThat(bounded.getSqlSegment())
                .containsIgnoringCase("status")
                .containsIgnoringCase("next_attempt_time")
                .containsIgnoringCase("ORDER BY")
                .contains("LIMIT 7");
        assertThat(paramValues(bounded).values()).contains(AiMediaTaskDO.STATUS_QUEUED, NOW);
        assertThat(wrappers.getAllValues().get(1).getSqlSegment())
                .as("LIMIT 至少为 1，避免取空批")
                .contains("LIMIT 1");
    }

    @Test
    void expiredLeaseCountOnlyLooksAtRunningRowsPastTheirLease() {
        doReturn(3L).when(taskMapper).selectCount(any());

        assertThat(taskMapper.countExpired(NOW)).isEqualTo(3L);

        ArgumentCaptor<Wrapper<AiMediaTaskDO>> captor = wrapperCaptor();
        verify(taskMapper).selectCount(captor.capture());
        Wrapper<AiMediaTaskDO> wrapper = captor.getValue();
        assertThat(wrapper.getSqlSegment()).containsIgnoringCase("status").containsIgnoringCase("lease_expires_time");
        assertThat(paramValues(wrapper).values()).contains(AiMediaTaskDO.STATUS_RUNNING, NOW);
    }

    @Test
    void assetQueriesAreScopedToTheTaskAndOrderedByOrdinal() {
        doReturn(List.of(new AiMediaAssetDO().setId(1L).setOrdinal(1)))
                .when(assetMapper)
                .selectList(any());
        doReturn(2L).when(assetMapper).selectCount(any());

        assertThat(assetMapper.selectByTask(88L)).hasSize(1);
        assertThat(assetMapper.countByTask(88L)).isEqualTo(2L);

        ArgumentCaptor<Wrapper<AiMediaAssetDO>> wrappers = wrapperCaptor();
        verify(assetMapper).selectList(wrappers.capture());
        verify(assetMapper).selectCount(wrappers.capture());
        Wrapper<AiMediaAssetDO> ordered = wrappers.getAllValues().get(0);
        assertThat(ordered.getSqlSegment())
                .containsIgnoringCase("task_id")
                .containsIgnoringCase("ORDER BY")
                .containsIgnoringCase("ordinal");
        assertThat(paramValues(ordered).values()).contains(88L);
        assertThat(wrappers.getAllValues().get(1).getSqlSegment()).containsIgnoringCase("task_id");
    }

    @SuppressWarnings("unchecked")
    private Wrapper<AiMediaTaskDO> captureSelectOne() {
        ArgumentCaptor<Wrapper<AiMediaTaskDO>> captor = wrapperCaptor();
        verify(taskMapper).selectOne(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> ArgumentCaptor<Wrapper<T>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    private static Map<String, Object> paramValues(Wrapper<?> wrapper) {
        return ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
    }
}
