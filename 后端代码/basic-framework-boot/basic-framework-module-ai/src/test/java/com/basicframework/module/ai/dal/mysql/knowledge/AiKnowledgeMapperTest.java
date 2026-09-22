package com.basicframework.module.ai.dal.mysql.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeChunkDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentVersionDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeIndexGenerationDO;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

/**
 * K02 Mapper 查询形状：过滤列、排序、绑定参数与 CAS 语义（不连数据库）。
 *
 * <p>真实 SQL 语义由 `AiKnowledgePersistenceIT` 在真实 MySQL 上验证；这里保证"取值走绑定参数、
 * 过滤列与排序固定"这两条与安全相关的形状不被改写。
 */
class AiKnowledgeMapperTest {

    private final AiKnowledgeBaseMapper baseMapper = mock(AiKnowledgeBaseMapper.class, Answers.CALLS_REAL_METHODS);

    private final AiKnowledgeDocumentMapper documentMapper =
            mock(AiKnowledgeDocumentMapper.class, Answers.CALLS_REAL_METHODS);

    private final AiKnowledgeDocumentVersionMapper versionMapper =
            mock(AiKnowledgeDocumentVersionMapper.class, Answers.CALLS_REAL_METHODS);

    private final AiKnowledgeChunkMapper chunkMapper = mock(AiKnowledgeChunkMapper.class, Answers.CALLS_REAL_METHODS);

    private final AiKnowledgeIndexGenerationMapper generationMapper =
            mock(AiKnowledgeIndexGenerationMapper.class, Answers.CALLS_REAL_METHODS);

    /** Lambda 包装器需要实体元数据缓存（正常由 MyBatis 运行时初始化）。 */
    @BeforeAll
    static void initTableMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        for (Class<?> entity : List.of(
                AiKnowledgeBaseDO.class,
                AiKnowledgeDocumentDO.class,
                AiKnowledgeDocumentVersionDO.class,
                AiKnowledgeChunkDO.class,
                AiKnowledgeIndexGenerationDO.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), entity);
        }
    }

    private static Map<String, Object> paramValues(Wrapper<?> wrapper) {
        return ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
    }

    @Test
    void baseMapperFiltersSortsAndBindsValues() {
        doReturn(new AiKnowledgeBaseDO()).when(baseMapper).selectOne(any());
        doAnswer(invocation -> {
                    IPage<AiKnowledgeBaseDO> page = invocation.getArgument(0);
                    page.setRecords(List.of(new AiKnowledgeBaseDO().setId(61L)));
                    page.setTotal(1L);
                    return page;
                })
                .when(baseMapper)
                .selectPage(any(IPage.class), any());
        doReturn(List.of(new AiKnowledgeBaseDO().setId(61L))).when(baseMapper).selectList(any());
        doReturn(1).when(baseMapper).update(any(), any());

        assertThat(baseMapper.selectByCode("handbook")).isNotNull();
        PageResult<AiKnowledgeBaseDO> page = baseMapper.selectPage(new PageParam(), "SHARED", 9L, "ENABLED");
        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(baseMapper.selectByOwnerApplication(9L)).hasSize(1);
        assertThat(baseMapper.updateWithVersion(new AiKnowledgeBaseDO().setId(61L), 3))
                .isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AiKnowledgeBaseDO>> selectOne = ArgumentCaptor.forClass(Wrapper.class);
        verify(baseMapper).selectOne(selectOne.capture());
        assertThat(selectOne.getValue().getSqlSegment()).containsIgnoringCase("code");
        assertThat(paramValues(selectOne.getValue()).values()).contains("handbook");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AiKnowledgeBaseDO>> selectPage = ArgumentCaptor.forClass(Wrapper.class);
        verify(baseMapper).selectPage(any(IPage.class), selectPage.capture());
        String pageSql = selectPage.getValue().getSqlSegment();
        assertThat(pageSql).containsIgnoringCase("visibility");
        assertThat(pageSql).containsIgnoringCase("owner_application_id");
        assertThat(pageSql).containsIgnoringCase("status");
        assertThat(pageSql).containsIgnoringCase("ORDER BY id DESC");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AiKnowledgeBaseDO>> cas = ArgumentCaptor.forClass(Wrapper.class);
        verify(baseMapper).update(any(), cas.capture());
        assertThat(cas.getValue().getSqlSegment()).containsIgnoringCase("id").containsIgnoringCase("version");
    }

    @Test
    void documentMapperScopesByBaseAndSourceKey() {
        doReturn(new AiKnowledgeDocumentDO()).when(documentMapper).selectOne(any());
        doAnswer(invocation -> {
                    IPage<AiKnowledgeDocumentDO> page = invocation.getArgument(0);
                    page.setRecords(List.of());
                    page.setTotal(0L);
                    return page;
                })
                .when(documentMapper)
                .selectPage(any(IPage.class), any());
        doReturn(List.of(new AiKnowledgeDocumentDO())).when(documentMapper).selectList(any());
        doReturn(1).when(documentMapper).update(any(), any());

        assertThat(documentMapper.selectBySourceKey(61L, "handbook/v1.pdf")).isNotNull();
        assertThat(documentMapper.selectPage(new PageParam(), 61L, "READY").getTotal())
                .isZero();
        assertThat(documentMapper.selectByKnowledgeBase(61L)).hasSize(1);
        assertThat(documentMapper.updateWithVersion(new AiKnowledgeDocumentDO().setId(71L), 4))
                .isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AiKnowledgeDocumentDO>> selectOne = ArgumentCaptor.forClass(Wrapper.class);
        verify(documentMapper).selectOne(selectOne.capture());
        assertThat(selectOne.getValue().getSqlSegment())
                .containsIgnoringCase("knowledge_base_id")
                .containsIgnoringCase("source_key");
        assertThat(paramValues(selectOne.getValue()).values()).contains(61L, "handbook/v1.pdf");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AiKnowledgeDocumentDO>> cas = ArgumentCaptor.forClass(Wrapper.class);
        verify(documentMapper).update(any(), cas.capture());
        // MyBatis-Plus 在生成 SQL 片段时才填充绑定参数表，因此先断言片段再断言取值
        assertThat(cas.getValue().getSqlSegment()).containsIgnoringCase("id").containsIgnoringCase("version");
        assertThat(paramValues(cas.getValue()).values()).contains(71L, 4);
    }

    @Test
    void versionMapperOrdersByVersionNoAndScopesByGeneration() {
        doReturn(new AiKnowledgeDocumentVersionDO()).when(versionMapper).selectOne(any());
        doReturn(List.of(new AiKnowledgeDocumentVersionDO().setVersionNo(2)))
                .when(versionMapper)
                .selectList(any());
        doAnswer(invocation -> {
                    IPage<AiKnowledgeDocumentVersionDO> page = invocation.getArgument(0);
                    page.setRecords(List.of());
                    page.setTotal(0L);
                    return page;
                })
                .when(versionMapper)
                .selectPage(any(IPage.class), any());
        doReturn(1).when(versionMapper).update(any(), any());

        assertThat(versionMapper.selectByVersionNo(71L, 2)).isNotNull();
        assertThat(versionMapper.selectByDocument(71L)).hasSize(1);
        assertThat(versionMapper.selectPage(new PageParam(), 71L).getTotal()).isZero();
        assertThat(versionMapper.selectByGeneration(61L, 3)).hasSize(1);
        assertThat(versionMapper.updateWithVersion(new AiKnowledgeDocumentVersionDO().setId(81L), 5))
                .isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AiKnowledgeDocumentVersionDO>> list = ArgumentCaptor.forClass(Wrapper.class);
        verify(versionMapper, org.mockito.Mockito.atLeastOnce()).selectList(list.capture());
        assertThat(list.getAllValues().stream()
                        .map(wrapper -> wrapper.getSqlSegment())
                        .toList())
                .anySatisfy(sql -> assertThat(sql).containsIgnoringCase("version_no DESC"))
                .anySatisfy(sql -> assertThat(sql).containsIgnoringCase("index_generation"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AiKnowledgeDocumentVersionDO>> cas = ArgumentCaptor.forClass(Wrapper.class);
        verify(versionMapper).update(any(), cas.capture());
        assertThat(cas.getValue().getSqlSegment()).containsIgnoringCase("id").containsIgnoringCase("version");
        assertThat(paramValues(cas.getValue()).values()).contains(81L, 5);
    }

    @Test
    void chunkMapperOrdersByIndexAndDeletesByScope() {
        doReturn(List.of(new AiKnowledgeChunkDO().setChunkIndex(0)))
                .when(chunkMapper)
                .selectList(any());
        doReturn(3L).when(chunkMapper).selectCount(any());
        doReturn(2).when(chunkMapper).deleteByVersion(81L);
        doReturn(1).when(chunkMapper).deleteByGeneration(61L, 3);
        doReturn(4L).when(chunkMapper).countDistinctVersions(61L, 3);

        assertThat(chunkMapper.selectByVersion(81L)).hasSize(1);
        assertThat(chunkMapper.countByVersion(81L)).isEqualTo(3);
        assertThat(chunkMapper.countByGeneration(61L, 3)).isEqualTo(3);
        assertThat(chunkMapper.deleteByVersion(81L)).isEqualTo(2);
        assertThat(chunkMapper.deleteByGeneration(61L, 3)).isEqualTo(1);
        assertThat(chunkMapper.countDistinctVersions(61L, 3)).isEqualTo(4);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AiKnowledgeChunkDO>> list = ArgumentCaptor.forClass(Wrapper.class);
        verify(chunkMapper).selectList(list.capture());
        assertThat(list.getValue().getSqlSegment())
                .containsIgnoringCase("document_version_id")
                .containsIgnoringCase("ORDER BY chunk_index ASC");
        assertThat(paramValues(list.getValue()).values()).contains(81L);
    }

    @Test
    void generationMapperScopesByBaseAndStatus() {
        doReturn(new AiKnowledgeIndexGenerationDO()).when(generationMapper).selectOne(any());
        doReturn(List.of(new AiKnowledgeIndexGenerationDO().setGenerationNo(2)))
                .when(generationMapper)
                .selectList(any());
        doReturn(1).when(generationMapper).update(any(), any());

        assertThat(generationMapper.selectByGenerationNo(61L, 2)).isNotNull();
        assertThat(generationMapper.selectActive(61L)).isNotNull();
        assertThat(generationMapper.selectBuilding(61L)).isNotNull();
        assertThat(generationMapper.selectByKnowledgeBase(61L)).hasSize(1);
        assertThat(generationMapper.updateWithVersion(new AiKnowledgeIndexGenerationDO().setId(102L), 6))
                .isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AiKnowledgeIndexGenerationDO>> list = ArgumentCaptor.forClass(Wrapper.class);
        verify(generationMapper, org.mockito.Mockito.atLeastOnce()).selectList(list.capture());
        assertThat(list.getAllValues().get(0).getSqlSegment()).containsIgnoringCase("generation_no DESC");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AiKnowledgeIndexGenerationDO>> cas = ArgumentCaptor.forClass(Wrapper.class);
        verify(generationMapper).update(any(), cas.capture());
        assertThat(cas.getValue().getSqlSegment()).containsIgnoringCase("id").containsIgnoringCase("version");
        assertThat(paramValues(cas.getValue()).values()).contains(102L, 6);
    }
}
