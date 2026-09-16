package com.basicframework.module.infra.dal.mysql.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.infra.dal.dataobject.file.FileDO;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FileMapperTest {

    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), FileMapper.class.getName()), FileDO.class);
    }

    @Test
    void pageAlwaysExcludesDeletedAndIncompleteFilesWhilePreservingFilters() {
        FileMapper mapper = mapperWithPageResult();
        LocalDateTime from = LocalDateTime.now().minusDays(1);
        LocalDateTime to = LocalDateTime.now();

        PageResult<FileDO> result =
                mapper.selectPage(new PageParam(), "avatars", "image/png", new LocalDateTime[] {from, to});

        assertThat(result.getTotal()).isEqualTo(1L);
        ArgumentCaptor<Wrapper<FileDO>> captor = wrapperCaptor();
        verify(mapper).selectPage(any(IPage.class), captor.capture());
        LambdaQueryWrapper<FileDO> wrapper = asLambdaWrapper(captor.getValue());
        assertThat(wrapper.getSqlSegment())
                .contains("path", "type", "create_time", "delete_status", "upload_status", "id");
        assertThat(parameterValues(wrapper))
                .contains(
                        "%avatars%",
                        "%image/png%", from, to, FileMapper.DELETE_STATUS_ACTIVE, FileMapper.UPLOAD_STATUS_COMPLETE);
    }

    @Test
    void activeAndUploadLookupsKeepLifecycleAndIdentityBoundaries() {
        FileMapper mapper = mock(FileMapper.class, CALLS_REAL_METHODS);
        FileDO file = new FileDO().setId(31L);
        doReturn(file).when(mapper).selectOne(any(Wrapper.class));

        assertThat(mapper.selectActiveById(31L)).isSameAs(file);
        assertThat(mapper.selectActiveByConfigIdAndPath(3L, "avatars/a.png")).isSameAs(file);
        assertThat(mapper.selectClaimedUpload("token-hash")).isSameAs(file);
        assertThat(mapper.selectCompletedUpload("token-hash", 7L, 2)).isSameAs(file);
        assertThat(mapper.selectValidatingUploadForUpdate(31L)).isSameAs(file);

        ArgumentCaptor<Wrapper<FileDO>> captor = wrapperCaptor();
        verify(mapper, times(5)).selectOne(captor.capture());
        List<LambdaQueryWrapper<FileDO>> wrappers = captor.getAllValues().stream()
                .map(FileMapperTest::asLambdaWrapper)
                .toList();
        assertThat(parameterValues(wrappers.get(0)))
                .contains(31L, FileMapper.DELETE_STATUS_ACTIVE, FileMapper.UPLOAD_STATUS_COMPLETE);
        assertThat(parameterValues(wrappers.get(1)))
                .contains(3L, "avatars/a.png", FileMapper.DELETE_STATUS_ACTIVE, FileMapper.UPLOAD_STATUS_COMPLETE);
        assertThat(parameterValues(wrappers.get(2)))
                .contains("token-hash", FileMapper.UPLOAD_STATUS_VALIDATING, FileMapper.DELETE_STATUS_ACTIVE);
        assertThat(parameterValues(wrappers.get(3)))
                .contains("token-hash", FileMapper.UPLOAD_STATUS_COMPLETE, 7L, 2, FileMapper.DELETE_STATUS_ACTIVE);
        assertThat(parameterValues(wrappers.get(4)))
                .contains(31L, FileMapper.UPLOAD_STATUS_VALIDATING, FileMapper.DELETE_STATUS_ACTIVE);
        assertThat(wrappers.get(4).getSqlSegment()).endsWith("FOR UPDATE");
    }

    @Test
    void pendingDeletionOrdersRetriesDeterministicallyAndAppliesTheConfiguredLimit() {
        FileMapper mapper = mock(FileMapper.class, CALLS_REAL_METHODS);
        LocalDateTime now = LocalDateTime.now();
        doReturn(List.of()).when(mapper).selectList(any(Wrapper.class));

        assertThat(mapper.selectPendingDeletion(now, 100)).isEmpty();

        ArgumentCaptor<Wrapper<FileDO>> captor = wrapperCaptor();
        verify(mapper).selectList(captor.capture());
        LambdaQueryWrapper<FileDO> wrapper = asLambdaWrapper(captor.getValue());
        assertThat(wrapper.getSqlSegment())
                .contains("delete_status", "delete_next_retry_time", "id")
                .endsWith("LIMIT 100");
        assertThat(parameterValues(wrapper)).contains(FileMapper.DELETE_STATUS_PENDING, now);
    }

    @Test
    void countByConfigIdBindsOnlyTheRequestedConfiguration() {
        FileMapper mapper = mock(FileMapper.class, CALLS_REAL_METHODS);
        doReturn(4L).when(mapper).selectCount(any(Wrapper.class));

        assertThat(mapper.selectCountByConfigId(3L)).isEqualTo(4L);

        ArgumentCaptor<Wrapper<FileDO>> captor = wrapperCaptor();
        verify(mapper).selectCount(captor.capture());
        LambdaQueryWrapper<FileDO> wrapper = asLambdaWrapper(captor.getValue());
        assertThat(wrapper.getSqlSegment()).contains("config_id");
        assertThat(parameterValues(wrapper)).containsExactly(3L);
    }

    private static FileMapper mapperWithPageResult() {
        FileMapper mapper = mock(FileMapper.class, CALLS_REAL_METHODS);
        doAnswer(invocation -> {
                    IPage<FileDO> page = invocation.getArgument(0);
                    page.setRecords(List.of(new FileDO().setId(31L)));
                    page.setTotal(1L);
                    return page;
                })
                .when(mapper)
                .selectPage(any(IPage.class), any(Wrapper.class));
        return mapper;
    }

    @SuppressWarnings("unchecked")
    private static LambdaQueryWrapper<FileDO> asLambdaWrapper(Wrapper<FileDO> wrapper) {
        return (LambdaQueryWrapper<FileDO>) wrapper;
    }

    private static Collection<Object> parameterValues(LambdaQueryWrapper<FileDO> wrapper) {
        wrapper.getSqlSegment();
        return wrapper.getParamNameValuePairs().values();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Wrapper<FileDO>> wrapperCaptor() {
        return ArgumentCaptor.forClass(Wrapper.class);
    }
}
